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
        // 原始响应文本（截断保存），用于服务端未按 SSE 规范返回时的兜底解析与报错诊断。
        val rawBody = StringBuilder()
        var eventCount = 0
        var streamErrorMessage: String? = null
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
                        val contentType = it.body?.contentType()?.toString().orEmpty()
                        val httpStatus = it.code
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
                            readSseEvents(source, rawBody) { data ->
                                if (canceled.get() || terminal.get()) return@readSseEvents
                                eventCount++
                                if (firstEventReceived.compareAndSet(false, true)) {
                                    firstEventTimeout?.cancel(false)
                                }
                                val payload = data.trim()
                                if (payload == DONE_EVENT) {
                                    complete(
                                        terminal = terminal,
                                        callback = callback,
                                        content = content.toString(),
                                        thinking = thinking.toString(),
                                        finishReason = finishReason ?: BackendFinishReason.STOP,
                                    )
                                    return@readSseEvents
                                }

                                val event = runCatching { JSONObject(payload) }.getOrNull()
                                    ?: return@readSseEvents
                                // 有些网关会在流中直接下发 error 对象。
                                event.optJSONObject("error")?.let { error ->
                                    streamErrorMessage = error.optString("message")
                                        .ifBlank { error.toString() }
                                    return@readSseEvents
                                }
                                val choice = event.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?: return@readSseEvents
                                val delta = choice.optJSONObject("delta")
                                    ?: JSONObject()
                                val thinkingDelta = delta.optStringOrNull("reasoning_content")
                                    ?: choice.optStringOrNull("reasoning_content")
                                val textDelta = delta.optStringOrNull("content")
                                    ?: choice.optStringOrNull("content")

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
                                        "stop", "eos", "end_turn" -> BackendFinishReason.STOP
                                        "length", "max_tokens" -> BackendFinishReason.LENGTH
                                        "content_filter" -> BackendFinishReason.CONTENT_FILTER
                                        // 未知的结束原因不再视为错误，按正常结束处理。
                                        else -> BackendFinishReason.STOP
                                    }
                                }
                            }

                            if (!terminal.get() && !canceled.get()) {
                                firstEventTimeout?.cancel(false)
                                when {
                                    // 情况一：完全没有解析到 SSE 事件，尝试按非流式 JSON 解析。
                                    eventCount == 0 -> {
                                        if (!consumePlainBody(rawBody.toString(), terminal, callback)) {
                                            dispatchFailure(
                                                terminal,
                                                callback,
                                                BackendError(
                                                    message = "服务端没有返回任何内容。" +
                                                        httpDetail(httpStatus, contentType) +
                                                        rawBodySnippet(rawBody.toString()),
                                                    type = BackendErrorType.PROTOCOL,
                                                    retryable = true,
                                                ),
                                            )
                                        }
                                    }

                                    // 情况二：流中直接下发过 error。
                                    streamErrorMessage != null &&
                                        content.isEmpty() && thinking.isEmpty() -> {
                                        dispatchFailure(
                                            terminal,
                                            callback,
                                            BackendError(
                                                message = streamErrorMessage.orEmpty(),
                                                type = BackendErrorType.SERVER,
                                                retryable = true,
                                            ),
                                        )
                                    }

                                    // 情况三：收到内容，但服务端没有下发结束标记。
                                    // 很多网关（尤其是自建代理）收流时不会发送 finish_reason 或 [DONE]，
                                    // 此时已拿到的内容就是完整回答，按正常结束处理。
                                    content.isNotEmpty() || thinking.isNotEmpty() -> {
                                        complete(
                                            terminal = terminal,
                                            callback = callback,
                                            content = content.toString(),
                                            thinking = thinking.toString(),
                                            finishReason = finishReason ?: BackendFinishReason.STOP,
                                        )
                                    }

                                    else -> {
                                        dispatchFailure(
                                            terminal,
                                            callback,
                                            BackendError(
                                                message = "服务端没有返回可显示的内容。" +
                                                    httpDetail(httpStatus, contentType) +
                                                    rawBodySnippet(rawBody.toString()),
                                                type = BackendErrorType.PROTOCOL,
                                                retryable = true,
                                            ),
                                        )
                                    }
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
        rawSink: StringBuilder,
        onEvent: (String) -> Unit,
    ) {
        val dataLines = mutableListOf<String>()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (rawSink.length < RAW_BODY_LIMIT) {
                rawSink.append(line).append('\n')
            }
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

    /**
     * 兜底：服务端忽略 `stream: true`，直接返回了一个普通的 Chat Completions JSON。
     * 解析成功返回 true。
     */
    private fun consumePlainBody(
        text: String,
        terminal: AtomicBoolean,
        callback: ChatBackend.Callback,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        val json = runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
            ?: return false

        json.optJSONObject("error")?.let { error ->
            dispatchFailure(
                terminal,
                callback,
                BackendError(
                    message = error.optStringOrNull("message") ?: error.toString(),
                    code = error.optStringOrNull("code"),
                    type = BackendErrorType.SERVER,
                ),
            )
            return true
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
        val message = choice.optJSONObject("message")
        val plainContent = message?.optStringOrNull("content")
            ?: choice.optStringOrNull("text")
            ?: choice.optStringOrNull("content")
        val plainThinking = message?.optStringOrNull("reasoning_content")
        if (plainContent.isNullOrEmpty() && plainThinking.isNullOrEmpty()) return false

        complete(
            terminal = terminal,
            callback = callback,
            content = plainContent.orEmpty(),
            thinking = plainThinking.orEmpty(),
            finishReason = BackendFinishReason.STOP,
        )
        return true
    }

    /** 出错时附带一小段服务端原文，便于定位问题，同时避免刷屏。 */
    private fun rawBodySnippet(raw: String): String {
        val snippet = raw.trim().replace(Regex("\\s+"), " ")
        if (snippet.isEmpty()) return ""
        return "\n服务端返回：${snippet.take(SNIPPET_LENGTH)}"
    }

    private fun httpDetail(status: Int, contentType: String): String {
        val type = contentType.ifEmpty { "无" }
        return "\nHTTP 状态：$status，Content-Type：$type"
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
        private const val FIRST_EVENT_TIMEOUT_MILLIS = 60_000L
        private const val RAW_BODY_LIMIT = 64 * 1024
        private const val SNIPPET_LENGTH = 200
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
