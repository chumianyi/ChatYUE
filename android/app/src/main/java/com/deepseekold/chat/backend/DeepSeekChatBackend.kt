package com.deepseekold.chat.backend

import okhttp3.Call
import okhttp3.Callback as OkHttpCallback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 固定接口 / 固定密钥 / 固定模型 的流式聊天后端。
 *
 * 与上游版本的区别：
 * - 接口地址与密钥来自 [FixedEndpoint]，不再由调用方提供，App 内也没有任何可修改入口；
 * - 模型固定为 `deepseek-ai/DeepSeek-R1`，始终返回 `reasoning_content` 思考过程；
 * - 不再发送 `thinking` / `reasoning_effort` 之类可切换参数（仅思考模式，无可切换项）。
 */
class DeepSeekChatBackend(
    private val client: OkHttpClient = defaultClient(),
    private val callbackExecutor: Executor = MainThreadExecutor,
    private val endpoint: String = FixedEndpoint.CHAT_COMPLETIONS_URL,
    private val firstEventTimeoutMillis: Long = FIRST_EVENT_TIMEOUT_MILLIS,
) : ChatBackend {

    override fun sendMessage(
        request: ChatRequest,
        callback: ChatBackend.Callback,
    ): CancellableRequest {
        val terminal = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val firstEventReceived = AtomicBoolean(false)
        val thinking = StringBuilder()
        val content = StringBuilder()
        var finishReason: BackendFinishReason? = null

        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${FixedEndpoint.API_KEY}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(createRequestBody(request))
            .build()

        val call = client.newCall(httpRequest)
        val firstEventTimeout = scheduleFirstEventTimeout(
            call = call,
            terminal = terminal,
            canceled = canceled,
            firstEventReceived = firstEventReceived,
            callback = callback,
        )
        call.enqueue(
            object : OkHttpCallback {
                override fun onFailure(call: Call, e: IOException) {
                    firstEventTimeout?.cancel(false)
                    if (canceled.get()) return
                    dispatchFailure(
                        terminal,
                        callback,
                        BackendError(
                            message = e.message ?: "网络请求失败。",
                            type = BackendErrorType.NETWORK,
                            retryable = true,
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            firstEventTimeout?.cancel(false)
                            dispatchFailure(
                                terminal,
                                callback,
                                parseHttpError(it.code, it.body?.string().orEmpty()),
                            )
                            return
                        }

                        val source = it.body?.source()
                        if (source == null) {
                            firstEventTimeout?.cancel(false)
                            dispatchFailure(
                                terminal,
                                callback,
                                BackendError(
                                    message = "服务端返回了空响应。",
                                    type = BackendErrorType.PROTOCOL,
                                    retryable = true,
                                ),
                            )
                            return
                        }

                        try {
                            readSseEvents(source) { data ->
                                if (canceled.get() || terminal.get()) return@readSseEvents
                                if (firstEventReceived.compareAndSet(false, true)) {
                                    firstEventTimeout?.cancel(false)
                                }
                                if (data == DONE_EVENT) {
                                    complete(
                                        terminal = terminal,
                                        callback = callback,
                                        content = content.toString(),
                                        thinking = thinking.toString(),
                                        finishReason = finishReason ?: BackendFinishReason.STOP,
                                    )
                                    return@readSseEvents
                                }

                                val event = JSONObject(data)
                                val choice = event.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?: return@readSseEvents
                                val delta = choice.optJSONObject("delta")
                                    ?: JSONObject()
                                val thinkingDelta = delta.optStringOrNull("reasoning_content")
                                val textDelta = delta.optStringOrNull("content")

                                if (!thinkingDelta.isNullOrEmpty()) {
                                    thinking.append(thinkingDelta)
                                    dispatchDelta {
                                        callback.onThinkingDelta(thinkingDelta)
                                    }
                                }
                                if (!textDelta.isNullOrEmpty()) {
                                    content.append(textDelta)
                                    dispatchDelta {
                                        callback.onTextDelta(textDelta)
                                    }
                                }

                                val reason = choice.optStringOrNull("finish_reason")
                                if (!reason.isNullOrEmpty()) {
                                    finishReason = when (reason) {
                                        "stop" -> BackendFinishReason.STOP
                                        "length" -> BackendFinishReason.LENGTH
                                        "content_filter" -> BackendFinishReason.CONTENT_FILTER
                                        else -> {
                                            dispatchFailure(
                                                terminal,
                                                callback,
                                                BackendError(
                                                    message = "服务端返回了不支持的结束原因：$reason",
                                                    code = reason,
                                                    type = BackendErrorType.PROTOCOL,
                                                ),
                                            )
                                            return@readSseEvents
                                        }
                                    }
                                }
                            }

                            if (!terminal.get() && !canceled.get()) {
                                firstEventTimeout?.cancel(false)
                                if (finishReason != null) {
                                    complete(
                                        terminal = terminal,
                                        callback = callback,
                                        content = content.toString(),
                                        thinking = thinking.toString(),
                                        finishReason = finishReason ?: BackendFinishReason.STOP,
                                    )
                                } else {
                                    dispatchFailure(
                                        terminal,
                                        callback,
                                        BackendError(
                                            message = "流式响应提前结束，未收到完成标记。",
                                            type = BackendErrorType.PROTOCOL,
                                            retryable = true,
                                        ),
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            firstEventTimeout?.cancel(false)
                            if (!canceled.get()) {
                                dispatchFailure(
                                    terminal,
                                    callback,
                                    BackendError(
                                        message = e.message ?: "无法解析服务端响应。",
                                        type = BackendErrorType.PROTOCOL,
                                        retryable = true,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        )

        return CancellableRequest {
            if (canceled.compareAndSet(false, true)) {
                firstEventTimeout?.cancel(false)
                call.cancel()
            }
        }
    }

    private fun scheduleFirstEventTimeout(
        call: Call,
        terminal: AtomicBoolean,
        canceled: AtomicBoolean,
        firstEventReceived: AtomicBoolean,
        callback: ChatBackend.Callback,
    ): ScheduledFuture<*>? {
        if (firstEventTimeoutMillis <= 0L) return null
        return TimeoutScheduler.schedule(
            timeout@{
                if (canceled.get() ||
                    terminal.get() ||
                    !firstEventReceived.compareAndSet(false, true)
                ) {
                    return@timeout
                }
                dispatchFailure(
                    terminal,
                    callback,
                    BackendError(
                        message = "连接服务器超时，请检查网络连接后重试。",
                        type = BackendErrorType.NETWORK,
                        retryable = true,
                    ),
                )
                call.cancel()
            },
            firstEventTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun createRequestBody(request: ChatRequest) =
        JSONObject().apply {
            put("model", FixedEndpoint.MODEL)
            put("stream", true)
            put(
                "messages",
                request.messages.map { message ->
                    JSONObject()
                        .put("role", message.role.name.lowercase())
                        .put("content", message.content)
                }.let { values ->
                    org.json.JSONArray(values)
                },
            )
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun dispatchDelta(action: () -> Unit) {
        callbackExecutor.execute(action)
    }

    private fun complete(
        terminal: AtomicBoolean,
        callback: ChatBackend.Callback,
        content: String,
        thinking: String,
        finishReason: BackendFinishReason,
    ) {
        if (!terminal.compareAndSet(false, true)) return
        callbackExecutor.execute {
            callback.onCompleted(
                BackendAssistantMessage(
                    content = content,
                    thinking = thinking.ifEmpty { null },
                    finishReason = finishReason,
                ),
            )
        }
    }

    private fun dispatchFailure(
        terminal: AtomicBoolean,
        callback: ChatBackend.Callback,
        error: BackendError,
    ) {
        if (!terminal.compareAndSet(false, true)) return
        callbackExecutor.execute { callback.onFailure(error) }
    }

    private fun readSseEvents(
        source: okio.BufferedSource,
        onEvent: (String) -> Unit,
    ) {
        val dataLines = mutableListOf<String>()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    onEvent(dataLines.joinToString("\n"))
                    dataLines.clear()
                }
                continue
            }
            if (line.startsWith(":")) continue

            val separator = line.indexOf(':')
            val field = if (separator >= 0) line.substring(0, separator) else line
            if (field != "data") continue
            val valueStart = if (separator >= 0 && line.getOrNull(separator + 1) == ' ') {
                separator + 2
            } else {
                separator + 1
            }
            dataLines += line.substring(valueStart)
        }
        if (dataLines.isNotEmpty()) onEvent(dataLines.joinToString("\n"))
    }

    private fun parseHttpError(statusCode: Int, body: String): BackendError {
        val parsed = runCatching {
            JSONObject(body).optJSONObject("error")
        }.getOrNull()
        val message = parsed?.optStringOrNull("message")
            ?: "请求失败（HTTP $statusCode）。"
        val code = parsed?.optStringOrNull("code")
        return when (statusCode) {
            401, 403 -> BackendError(
                message = message,
                code = code,
                type = BackendErrorType.AUTHENTICATION,
            )
            429 -> BackendError(
                message = message,
                code = code,
                type = BackendErrorType.RATE_LIMIT,
                retryable = true,
            )
            in 500..599 -> BackendError(
                message = message,
                code = code,
                type = BackendErrorType.SERVER,
                retryable = true,
            )
            else -> BackendError(message = message, code = code)
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val DONE_EVENT = "[DONE]"
        private const val FIRST_EVENT_TIMEOUT_MILLIS = 30_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MainThreadExecutor = Executor { runnable ->
            android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)
        }
        private val TimeoutScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "deepseek-first-event-timeout").apply {
                isDaemon = true
            }
        }

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
