package com.jingyuniang.ledger.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val RELEASE_CERT_SHA256 = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"
private const val MAX_APK_BYTES = 128L * 1024 * 1024

data class UpdateRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: HttpUrl,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String
)

object UpdateManifest {
    fun parse(json: String, manifestUrl: HttpUrl): UpdateRelease {
        val obj = JSONObject(json)
        val version = obj.getString("versionName")
        require(version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,2}\\.[0-9]{1,2}"))) { "更新版本信息无效" }
        val parts = version.split('.').map(String::toInt)
        val code = obj.getInt("versionCode")
        require(code > 0 && code == parts[0] * 10000 + parts[1] * 100 + parts[2]) { "更新版本号不匹配" }
        require(obj.optString("channel", "stable") == "stable") { "更新渠道不匹配" }
        require(obj.getString("signatureSha256").lowercase() == RELEASE_CERT_SHA256) { "更新包签名信息不匹配" }
        val hash = obj.getString("sha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "更新文件校验信息无效" }
        val size = obj.getLong("sizeBytes")
        require(size in 1..MAX_APK_BYTES) { "更新文件大小无效" }
        // 只允许本站版本目录，防止凭证被转发到外站或任意路径。
        val relative = obj.getString("apk")
        require(relative == "$version/jingyuniang-v$version.apk") { "更新下载地址无效" }
        val apkUrl = requireNotNull(manifestUrl.resolve(relative)) { "更新下载地址无效" }
        return UpdateRelease(code, version, apkUrl, hash, size, obj.optString("notes").take(4000))
    }
}

class UpdateClient(
    manifestUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    private val manifestUrl = manifestUrl.toHttpUrl().also {
        require(it.scheme == "https" || (it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "更新服务必须使用 HTTPS"
        }
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }

    private fun request(url: HttpUrl): Request = Request.Builder().url(url)
        .header("X-Whale-Update-Token", token)
        .header("Cache-Control", "no-cache")
        .build()

    suspend fun check(): UpdateRelease = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "此安装包尚未配置更新服务，请联系发布者" }
        http.newBuilder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .build().newCall(request(manifestUrl)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("检查更新失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("更新服务器未返回内容")
                val bytes = body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    val output = ByteArrayOutputStream()
                    while (output.size() <= 64 * 1024) {
                        val n = input.read(buffer, 0, minOf(buffer.size, 64 * 1024 + 1 - output.size()))
                        if (n == -1) break
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                require(bytes.size <= 64 * 1024) { "更新信息过大" }
                currentCoroutineContext().ensureActive()
                UpdateManifest.parse(bytes.toString(Charsets.UTF_8), manifestUrl)
            }
    }

    suspend fun download(release: UpdateRelease, directory: File, onProgress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            // 即使调用方传入对象，也必须遵守与清单一致的下载范围。
            require(release.apkUrl == manifestUrl.resolve("${release.versionName}/jingyuniang-v${release.versionName}.apk"))
            require(release.sizeBytes in 1..MAX_APK_BYTES)
            require(token.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
            val target = File(directory, "jingyuniang-v${release.versionName}.apk")
            val partial = File(directory, target.name + ".part")
            try {
                http.newBuilder().followRedirects(false).followSslRedirects(false).build()
                    .newCall(request(release.apkUrl)).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("下载更新失败（HTTP ${response.code}）")
                        val body = response.body ?: throw IOException("更新文件为空")
                        require(body.contentLength() == -1L || body.contentLength() == release.sizeBytes) { "更新文件大小与发布信息不符" }
                        val hash = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        partial.outputStream().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    total += n
                                    require(total <= release.sizeBytes) { "更新文件超出预期大小" }
                                    output.write(buffer, 0, n)
                                    hash.update(buffer, 0, n)
                                    onProgress(total)
                                }
                            }
                        }
                        require(total == release.sizeBytes) { "下载未完成，请重试" }
                        require(hash.digest().hex() == release.sha256) { "更新文件校验失败，请重新下载" }
                    }
                currentCoroutineContext().ensureActive()
                check(!target.exists() || target.delete()) { "无法替换更新缓存" }
                check(partial.renameTo(target)) { "无法保存更新文件" }
                target
            } finally {
                partial.delete()
            }
        }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
