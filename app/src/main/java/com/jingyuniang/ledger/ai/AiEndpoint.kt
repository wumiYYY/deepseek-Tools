package com.jingyuniang.ledger.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AiConnectionConfig(val baseUrl: String, val model: String)

/** 默认使用公司 AI；已保存的接口配置保持不变。 */
object AiEndpoint {
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEEPSEEK_MODEL = "deepseek-chat"
    const val SOUTHCHIP_BASE_URL = "https://litellm.southchips.net"
    const val SOUTHCHIP_MODEL = "sc-deepseek-flash"
    const val DEFAULT_BASE_URL = SOUTHCHIP_BASE_URL
    const val DEFAULT_MODEL = SOUTHCHIP_MODEL

    fun resolveSavedConfig(baseUrl: String?, model: String?, hasApiKey: Boolean): AiConnectionConfig {
        if (!baseUrl.isNullOrBlank()) {
            val fallbackModel = if (baseUrl.toHttpUrlOrNull()?.host == "api.deepseek.com") DEEPSEEK_MODEL else DEFAULT_MODEL
            return AiConnectionConfig(baseUrl, model?.takeIf { it.isNotBlank() } ?: fallbackModel)
        }
        // 旧版只支持官方接口：已经填写的 Key 继续发往原服务，避免随默认值变化误发。
        if (hasApiKey) return AiConnectionConfig(DEEPSEEK_BASE_URL, model?.takeIf { it.isNotBlank() } ?: DEEPSEEK_MODEL)
        return AiConnectionConfig(DEFAULT_BASE_URL, DEFAULT_MODEL)
    }

    fun sameService(first: String, second: String): Boolean =
        runCatching { chatUrl(first) == chatUrl(second) }.getOrDefault(false)

    fun normalizeBaseUrl(value: String): String {
        val input = value.trim().trimEnd('/').removeSuffix("/chat/completions")
        val url = requireNotNull(input.toHttpUrlOrNull()) { "请输入有效的接口地址（以 https:// 或 http:// 开头）" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "接口地址不能包含账号、密码、查询参数或片段"
        }
        return url.toString().trimEnd('/')
    }

    fun chatUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        val url = requireNotNull(normalized.toHttpUrlOrNull())
        val prefix = if (url.encodedPath == "/" && url.host != "api.deepseek.com") "/v1" else ""
        return "$normalized$prefix/chat/completions"
    }
}
