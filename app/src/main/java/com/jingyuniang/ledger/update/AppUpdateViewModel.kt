package com.jingyuniang.ledger.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyuniang.ledger.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, ERROR }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val visible: Boolean = false,
    val release: UpdateRelease? = null,
    val downloaded: Long = 0,
    val message: String = ""
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_TOKEN)
    private val prefs = application.getSharedPreferences("whale_updates", 0)
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var apk: File? = null

    fun check(manual: Boolean = true) {
        if (operation?.isActive == true || (_state.value.phase == UpdatePhase.READY && apk?.isFile == true)) {
            if (manual) _state.value = _state.value.copy(visible = true)
            return
        }
        val elapsed = System.currentTimeMillis() - prefs.getLong("last_success", 0)
        if (!manual && elapsed in 0 until 24 * 60 * 60 * 1000L) return
        _state.value = UpdateUiState(UpdatePhase.CHECKING, visible = manual)
        operation = viewModelScope.launch {
            try {
                val release = client.check()
                prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                _state.value = UpdateUiState(
                    if (newer) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                    visible = _state.value.visible || newer,
                    release = if (newer) release else null,
                    message = if (newer) "" else "当前已是最新版本 v${BuildConfig.VERSION_NAME}"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = UpdateUiState(UpdatePhase.ERROR, _state.value.visible, message = errorMessage(e))
            }
        }
    }

    fun download() {
        if (operation?.isActive == true) return
        val release = _state.value.release ?: return
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, message = "")
        operation = viewModelScope.launch {
            var downloaded: File? = null
            try {
                val file = client.download(release, File(getApplication<Application>().cacheDir, "updates")) { bytes ->
                    _state.value = _state.value.copy(downloaded = bytes)
                }
                downloaded = file
                withContext(Dispatchers.IO) { ApkInstaller.verify(getApplication(), file, release) }
                apk = file
                _state.value = _state.value.copy(phase = UpdatePhase.READY, message = "下载完成，可以安装更新。账本数据将保留。")
            } catch (e: Exception) {
                downloaded?.delete()
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = errorMessage(e))
            }
        }
    }

    fun readyApk(): File = requireNotNull(apk?.takeIf { it.isFile }) { "更新缓存已清理，请重新下载" }
    fun dismiss() { _state.value = _state.value.copy(visible = false) }
    fun installError(message: String) { _state.value = _state.value.copy(visible = true, message = message) }

    private fun errorMessage(error: Exception): String = when (error) {
        is javax.net.ssl.SSLException -> "更新服务器证书验证失败，请稍后重试"
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接更新服务器，请检查网络后重试"
        is java.net.SocketTimeoutException -> "更新请求超时，请重试"
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "更新信息校验失败"
        else -> "更新请求失败，请稍后重试"
    }
}
