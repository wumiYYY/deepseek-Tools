package com.jingyuniang.ledger

import com.jingyuniang.ledger.ai.AiEndpoint
import com.jingyuniang.ledger.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEndpointTest {
    @Test
    fun `new settings default to company address and model`() {
        val settings = Settings()
        assertEquals("https://litellm.southchips.net", settings.apiBase)
        assertEquals("sc-deepseek-flash", settings.model)
        val saved = AiEndpoint.resolveSavedConfig(null, null, false)
        assertEquals(settings.apiBase, saved.baseUrl)
        assertEquals(settings.model, saved.model)
    }

    @Test
    fun `unconfigured legacy installation gets company defaults`() {
        val config = AiEndpoint.resolveSavedConfig(null, "deepseek-chat", false)
        assertEquals(AiEndpoint.SOUTHCHIP_BASE_URL, config.baseUrl)
        assertEquals(AiEndpoint.SOUTHCHIP_MODEL, config.model)
    }

    @Test
    fun `configured legacy official key stays with its original service`() {
        val config = AiEndpoint.resolveSavedConfig(null, "deepseek-reasoner", true)
        assertEquals(AiEndpoint.DEEPSEEK_BASE_URL, config.baseUrl)
        assertEquals("deepseek-reasoner", config.model)
    }

    @Test
    fun `explicit saved custom service survives upgrade`() {
        val config = AiEndpoint.resolveSavedConfig("https://example.com/proxy/v1", "custom-model", true)
        assertEquals("https://example.com/proxy/v1", config.baseUrl)
        assertEquals("custom-model", config.model)
    }

    @Test
    fun `company root and v1 both select company preset`() {
        assertTrue(AiEndpoint.sameService(AiEndpoint.SOUTHCHIP_BASE_URL, "https://litellm.southchips.net/v1/"))
        assertFalse(AiEndpoint.sameService(AiEndpoint.SOUTHCHIP_BASE_URL, AiEndpoint.DEEPSEEK_BASE_URL))
        assertFalse(AiEndpoint.sameService("invalid address", AiEndpoint.SOUTHCHIP_BASE_URL))
    }

    @Test
    fun `saved company root keeps user facing address`() {
        assertEquals("https://litellm.southchips.net", AiEndpoint.normalizeBaseUrl(" https://litellm.southchips.net/ "))
    }

    @Test
    fun `legacy DeepSeek endpoint stays compatible`() {
        assertEquals("https://api.deepseek.com/chat/completions", AiEndpoint.chatUrl(AiEndpoint.DEEPSEEK_BASE_URL))
    }

    @Test
    fun `company root and v1 and full endpoint resolve to same URL`() {
        for (input in listOf(
            "https://litellm.southchips.net",
            " https://litellm.southchips.net/ ",
            "https://litellm.southchips.net/v1",
            "https://litellm.southchips.net/v1/",
            "https://litellm.southchips.net/v1/chat/completions/"
        )) {
            assertEquals("https://litellm.southchips.net/v1/chat/completions", AiEndpoint.chatUrl(input))
        }
    }

    @Test
    fun `custom proxy prefix is preserved`() {
        assertEquals("https://example.com/proxy/v1/chat/completions", AiEndpoint.chatUrl("https://example.com/proxy/v1/"))
    }

    @Test
    fun `invalid URLs and URL credentials are rejected`() {
        for (input in listOf("", "litellm.southchips.net", "ftp://example.com", "https://user:pass@example.com",
            "https://example.com?key=secret", "https://example.com#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { AiEndpoint.normalizeBaseUrl(input) }
        }
    }
}
