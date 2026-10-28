package com.jingyuniang.ledger.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.update.ApkInstaller
import com.jingyuniang.ledger.update.AppUpdateViewModel
import com.jingyuniang.ledger.update.UpdatePhase
import java.util.Locale

@Composable
fun AppUpdateDialog(vm: AppUpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPermission by rememberSaveable { mutableStateOf(false) }

    fun install() {
        runCatching { context.startActivity(ApkInstaller.intent(context, vm.readyApk())) }
            .onSuccess { vm.dismiss() }
            .onFailure { vm.installError("无法打开安装器，请返回后重试：${it.message.orEmpty().take(100)}") }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingPermission) {
            pendingPermission = false
            if (context.packageManager.canRequestPackageInstalls()) install()
            else vm.installError("请允许「安装未知应用」，然后点击「安装更新」。")
        }
    }
    LaunchedEffect(Unit) { vm.check(manual = false) }
    if (!state.visible) return

    val release = state.release
    val busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING
    AlertDialog(
        onDismissRequest = vm::dismiss,
        title = { Text(if (release == null) "检查更新" else "发现新版本 v${release.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (state.phase) {
                    UpdatePhase.CHECKING -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("正在检查新版本…")
                    }
                    UpdatePhase.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { if (release == null) 0f else (state.downloaded.toFloat() / release.sizeBytes).coerceIn(0f, 1f) })
                        Spacer(Modifier.height(10.dp))
                        Text("正在下载 ${megabytes(state.downloaded)} / ${megabytes(release?.sizeBytes ?: 0)}")
                    }
                    else -> {
                        if (release != null) {
                            Text("安装包 ${megabytes(release.sizeBytes)}")
                            if (release.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(release.notes)
                            }
                        }
                        if (state.message.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.message)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!busy) TextButton(onClick = {
                when (state.phase) {
                    UpdatePhase.AVAILABLE -> vm.download()
                    UpdatePhase.READY -> {
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else runCatching {
                            pendingPermission = true
                            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                        }.onFailure {
                            pendingPermission = false
                            vm.installError("无法打开安装权限设置，请在系统设置中允许本应用安装更新。")
                        }
                    }
                    UpdatePhase.ERROR -> if (release == null) vm.check() else vm.download()
                    else -> vm.dismiss()
                }
            }) {
                Text(when (state.phase) {
                    UpdatePhase.AVAILABLE -> "下载并安装"
                    UpdatePhase.READY -> "安装更新"
                    UpdatePhase.ERROR -> if (release == null) "重试" else "重新下载"
                    else -> "知道了"
                })
            }
        },
        dismissButton = {
            if (busy || release != null) TextButton(onClick = vm::dismiss) {
                Text(if (state.phase == UpdatePhase.DOWNLOADING) "后台下载" else "稍后")
            }
        }
    )
}

private fun megabytes(bytes: Long): String = String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
