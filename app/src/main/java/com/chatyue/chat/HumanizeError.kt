package com.chatyue.chat

/**
 * 把接口返回的技术性报错翻译成人能看懂的一句话。
 *
 * 只在能识别时替换，识别不了仍保留原文，避免丢失排障信息。
 */
object HumanizeError {

    fun humanize(message: String): String {
        val head = message.lineSequence().firstOrNull().orEmpty().trim()
        if (head.isEmpty()) return message
        return friendlyFor(head) ?: message
    }

    private fun friendlyFor(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("balance is insufficient") ||
                lower.contains("insufficient balance") ||
                lower.contains("insufficient_quota") ||
                lower.contains("quota") && lower.contains("exceed") ->
                "接口账号余额不足，需要先为上游接口充值。"

            lower.contains("invalid api key") ||
                lower.contains("incorrect api key") ||
                lower.contains("unauthorized") ||
                lower.contains("invalid_api_key") ->
                "接口密钥无效或已失效。"

            lower.contains("rate limit") ||
                lower.contains("too many requests") ||
                lower.contains("429") ->
                "请求过于频繁，请稍后再试。"

            lower.contains("model not found") ||
                lower.contains("no such model") ||
                lower.contains("does not exist") ->
                "接口未提供该模型。"

            lower.contains("timeout") || lower.contains("timed out") ->
                "请求超时，请检查网络后重试。"

            lower.contains("content_filter") || lower.contains("content filter") ->
                "内容被安全策略拦截，无法继续。"

            lower.contains("connection") && lower.contains("refused") ->
                "无法连接接口服务，请确认服务已启动。"

            lower.contains("unable to resolve host") ||
                lower.contains("no address associated") ||
                lower.contains("unknownhost") ->
                "无法解析接口域名，请检查网络。"

            lower.contains("cleartext") ->
                "当前系统禁止明文 HTTP 访问，请检查网络安全配置。"

            else -> null
        }
    }
}
