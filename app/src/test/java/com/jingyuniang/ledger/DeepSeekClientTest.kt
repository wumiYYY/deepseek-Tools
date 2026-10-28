package com.jingyuniang.ledger

import com.jingyuniang.ledger.ai.AiEndpoint
import com.jingyuniang.ledger.ai.DeepSeekClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekClientTest {
    private val server = MockWebServer()

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun client(key: String = "test-only-key") = DeepSeekClient(
        key, AiEndpoint.SOUTHCHIP_MODEL, server.url("/").toString()
    )

    @Test
    fun `company model and bearer key reach configured server with JSON mode`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"amount\":12}"}}]}"""))
        val result = client().chatResult("只输出 JSON", "测试十二元", jsonMode = true)
        assertEquals("{\"amount\":12}", result.getOrThrow())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer test-only-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("sc-deepseek-flash", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("测试十二元", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun `normal chat omits JSON mode`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"连接成功"}}]}"""))
        assertEquals("连接成功", client().chat("问候", "你好"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertFalse(JSONObject(request.body.readUtf8()).has("response_format"))
    }

    @Test
    fun `failed auth reports status without exposing gateway body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private gateway details test-only-key"))
        val error = client().chatResult("测试", "测试").exceptionOrNull()!!
        assertTrue(error.message!!.contains("401"))
        assertFalse(error.message!!.contains("test-only-key"))
        assertFalse(error.message!!.contains("private gateway"))
    }

    @Test
    fun `unknown model and rate limit remain failures`() = runBlocking {
        for (code in listOf(404, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue(client().chatResult("测试", "测试").exceptionOrNull()!!.message!!.contains(code.toString()))
        }
    }

    @Test
    fun `empty completion is not a successful connection`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        assertTrue(client().chatResult("测试", "测试").isFailure)
    }

    @Test
    fun `invalid response does not crash normal AI fallback path`() = runBlocking {
        server.enqueue(MockResponse().setBody("not JSON"))
        assertNull(client().chat("测试", "测试"))
    }

    @Test
    fun `missing key makes no network request`() = runBlocking {
        assertTrue(client("").chatResult("测试", "测试").isFailure)
        assertEquals(0, server.requestCount)
    }
}
