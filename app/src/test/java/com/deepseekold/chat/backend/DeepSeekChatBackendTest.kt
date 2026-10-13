package com.deepseekold.chat.backend

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class DeepSeekChatBackendTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fixedEndpointIsHardcodedAndLocked() {
        assertEquals(
            "http://103.236.99.177:24512/deepseekold/v1/chat/completions",
            FixedEndpoint.CHAT_COMPLETIONS_URL,
        )
        assertEquals("sk-123456789012345678901234567890", FixedEndpoint.API_KEY)
        assertEquals("deepseek-ai/DeepSeek-R1", FixedEndpoint.MODEL)
    }

    @Test
    fun streamSeparatesThinkingAndAnswerDeltas() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning_content":"先"}}]}

                    data: {"choices":[{"delta":{"reasoning_content":"想"}}]}

                    data: {"choices":[{"delta":{"content":"答"}}]}

                    data: {"choices":[{"delta":{"content":"案"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "问题")),
            ),
            callback,
        )

        assertTrue(callback.completed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("先", "想"), callback.thinkingDeltas)
        assertEquals(listOf("答", "案"), callback.textDeltas)
        assertEquals("答案", callback.result?.content)
        assertEquals("先想", callback.result?.thinking)
        assertEquals(BackendFinishReason.STOP, callback.result?.finishReason)
    }

    @Test
    fun requestUsesFixedModelKeyAndHistory() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(
                    BackendMessage(BackendRole.SYSTEM, "压缩摘要"),
                    BackendMessage(BackendRole.USER, "第一问"),
                    BackendMessage(BackendRole.ASSISTANT, "第一答"),
                    BackendMessage(BackendRole.USER, "第二问"),
                ),
            ),
            callback,
        )

        assertTrue(callback.completed.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(
            "Bearer ${FixedEndpoint.API_KEY}",
            request?.getHeader("Authorization"),
        )
        val body = JSONObject(request?.body?.readUtf8().orEmpty())
        assertEquals("deepseek-ai/DeepSeek-R1", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(4, body.getJSONArray("messages").length())
        assertEquals(
            "system",
            body.getJSONArray("messages").getJSONObject(0).getString("role"),
        )
    }

    @Test
    fun streamWithoutFinishMarkerStillCompletes() {
        // 自建代理常见的收流方式：只有 delta，没有 finish_reason，也没有 [DONE]。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"reasoning_content":"想"}}]}

                    data: {"choices":[{"delta":{"content":"完整"}}]}

                    data: {"choices":[{"delta":{"content":"回答"}}]}

                    """.trimIndent(),
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "问题")),
            ),
            callback,
        )

        assertTrue(callback.completed.await(5, TimeUnit.SECONDS))
        assertEquals("完整回答", callback.result?.content)
        assertEquals("想", callback.result?.thinking)
        assertEquals(BackendFinishReason.STOP, callback.result?.finishReason)
        assertTrue(callback.error == null)
    }

    @Test
    fun plainJsonResponseIsAcceptedAsFallback() {
        // 服务端忽略了 stream: true，直接返回普通 JSON。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant",""" +
                        """"reasoning_content":"思考","content":"非流式回答"}}]}""",
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "问题")),
            ),
            callback,
        )

        assertTrue(callback.completed.await(5, TimeUnit.SECONDS))
        assertEquals("非流式回答", callback.result?.content)
        assertEquals("思考", callback.result?.thinking)
    }

    @Test
    fun streamedErrorMessageIsSurfaced() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"error\":{\"message\":\"upstream timeout\"}}\n\n",
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "问题")),
            ),
            callback,
        )

        assertTrue(callback.failed.await(5, TimeUnit.SECONDS))
        assertEquals("upstream timeout", callback.error?.message)
    }

    @Test
    fun unknownFinishReasonIsTreatedAsStop() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}," +
                        "\"finish_reason\":\"some_new_reason\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "问题")),
            ),
            callback,
        )

        assertTrue(callback.completed.await(5, TimeUnit.SECONDS))
        assertEquals("ok", callback.result?.content)
        assertEquals(BackendFinishReason.STOP, callback.result?.finishReason)
    }

    @Test
    fun authenticationFailureIsMapped() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"invalid key","code":"invalid_api_key"}}"""),
        )

        val callback = RecordingCallback()
        createBackend().sendMessage(ChatRequest("1", emptyList()), callback)

        assertTrue(callback.failed.await(5, TimeUnit.SECONDS))
        assertEquals(BackendErrorType.AUTHENTICATION, callback.error?.type)
        assertEquals("invalid_api_key", callback.error?.code)
    }

    @Test
    fun failsWhenNoSseEventArrivesBeforeTimeout() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )

        val callback = RecordingCallback()
        createBackend(firstEventTimeoutMillis = 100).sendMessage(
            ChatRequest(
                conversationId = "1",
                messages = listOf(BackendMessage(BackendRole.USER, "hello")),
            ),
            callback,
        )

        assertTrue(callback.failed.await(2, TimeUnit.SECONDS))
        assertEquals(BackendErrorType.NETWORK, callback.error?.type)
        assertTrue(callback.error?.retryable == true)
    }

    private fun createBackend(
        firstEventTimeoutMillis: Long = 30_000,
    ): DeepSeekChatBackend {
        return DeepSeekChatBackend(
            client = OkHttpClient(),
            callbackExecutor = Executor { it.run() },
            endpoint = server.url("/chat/completions").toString(),
            firstEventTimeoutMillis = firstEventTimeoutMillis,
        )
    }

    private class RecordingCallback : ChatBackend.Callback {
        val thinkingDeltas = CopyOnWriteArrayList<String>()
        val textDeltas = CopyOnWriteArrayList<String>()
        val completed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var result: BackendAssistantMessage? = null
        var error: BackendError? = null

        override fun onThinkingDelta(text: String) {
            thinkingDeltas += text
        }

        override fun onTextDelta(text: String) {
            textDeltas += text
        }

        override fun onCompleted(message: BackendAssistantMessage) {
            result = message
            completed.countDown()
        }

        override fun onFailure(error: BackendError) {
            this.error = error
            failed.countDown()
        }
    }
}
