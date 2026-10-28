package com.jingyuniang.ledger

import com.jingyuniang.ledger.update.RELEASE_CERT_SHA256
import com.jingyuniang.ledger.update.UpdateClient
import com.jingyuniang.ledger.update.UpdateManifest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.net.InetAddress

class UpdateClientTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val bytes = "test APK content".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()
    private val client get() = UpdateClient(url("/whale-vault/update.json").toString(), "test-update-token")

    @Before fun start() { server.start(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0) }
    @After fun stop() { server.shutdown() }

    private fun manifest() = JSONObject().apply {
        put("versionCode", 10408)
        put("versionName", "1.4.8")
        put("apk", "1.4.8/jingyuniang-v1.4.8.apk")
        put("sha256", sha)
        put("sizeBytes", bytes.size)
        put("signatureSha256", RELEASE_CERT_SHA256)
        put("notes", "增加在线更新\n保留账本")
    }

    private fun release(json: JSONObject = manifest()) = UpdateManifest.parse(json.toString(), url("/whale-vault/update.json"))

    @Test fun `manifest uses same server and preserves release notes`() {
        val item = release()
        assertEquals(url("/whale-vault/1.4.8/jingyuniang-v1.4.8.apk"), item.apkUrl)
        assertEquals(10408, item.versionCode)
        assertEquals("增加在线更新\n保留账本", item.notes)
    }

    @Test fun `untrusted metadata and external or traversing URLs are rejected`() {
        for ((key, value) in listOf(
            "apk" to "https://evil.example/app.apk", "apk" to "../app.apk",
            "apk" to "//evil.example/app.apk", "signatureSha256" to "0".repeat(64),
            "sha256" to "invalid", "versionCode" to 10407, "sizeBytes" to 0,
            "sizeBytes" to 200_000_000, "channel" to "beta", "versionName" to "../1.4.8"
        )) {
            assertThrows("Invalid $key=$value", IllegalArgumentException::class.java) { release(manifest().put(key, value)) }
        }
    }

    @Test fun `production update URLs require HTTPS`() {
        assertThrows(IllegalArgumentException::class.java) { UpdateClient("http://161.118.233.45/whale-vault/update.json", "token") }
    }

    @Test fun `check sends access header and disables cached responses`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest().toString()))
        assertEquals(10408, client.check().versionCode)
        val request = server.takeRequest()
        assertEquals("/whale-vault/update.json", request.path)
        assertEquals("test-update-token", request.getHeader("X-Whale-Update-Token"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
    }

    @Test fun `redirect and error pages never become updates`() = runBlocking {
        for (response in listOf(
            MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/update.json"),
            MockResponse().setResponseCode(403), MockResponse().setResponseCode(500),
            MockResponse().setBody("<html>not a manifest</html>"), MockResponse().setBody(" ".repeat(65537))
        )) {
            server.enqueue(response)
            assertTrue(runCatching { client.check() }.isFailure)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun `download validates content and reports progress`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        var downloaded = 0L
        val file = client.download(release(), temp.root) { downloaded = it }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), downloaded)
        assertFalse(temp.root.listFiles()!!.any { it.name.endsWith(".part") })
        assertEquals("test-update-token", server.takeRequest().getHeader("X-Whale-Update-Token"))
    }

    @Test fun `corrupted downloads are rejected and partial files removed`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(bytes.size))))
        assertTrue(runCatching { client.download(release(), temp.root) {} }.isFailure)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `oversized and truncated downloads are rejected`() = runBlocking {
        for (length in listOf(bytes.size - 1, bytes.size + 1)) {
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(length)), 4))
            assertTrue(runCatching { client.download(release(), temp.root) {} }.isFailure)
            assertTrue(temp.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun `download rejects redirects and forged destinations`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/app.apk"))
        assertTrue(runCatching { client.download(release(), temp.root) {} }.isFailure)
        val foreign = release().copy(apkUrl = url("/other.apk"))
        assertTrue(runCatching { client.download(foreign, temp.root) {} }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `missing update credential does not send a request`() = runBlocking {
        assertTrue(runCatching { UpdateClient(url("/update.json").toString(), "").check() }.isFailure)
        assertEquals(0, server.requestCount)
    }
}
