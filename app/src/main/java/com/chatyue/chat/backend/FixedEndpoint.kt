package com.chatyue.chat.backend

/**
 * 全局固定配置。
 *
 * 需求要求：接口地址与密钥**强制固定**，App 内不提供查看入口，也不允许用户修改。
 * 因此这些常量只在此处定义一次，UI 层不会读取或展示它们的原始值。
 */
object FixedEndpoint {
    /** 固定接口根路径（v1）。 */
    const val BASE_URL = "http://103.236.99.177:24512/deepseekold/v1"

    /** 固定的 Chat Completions 接口地址，不可修改。 */
    const val CHAT_COMPLETIONS_URL = "$BASE_URL/chat/completions"

    /** 固定模型清单接口，2.0 起由服务端固化提供。 */
    const val MODELS_URL = "$BASE_URL/models"

    /** 固定的鉴权密钥，不在界面上展示。 */
    const val API_KEY = "sk-123456789012345678901234567890"
}
