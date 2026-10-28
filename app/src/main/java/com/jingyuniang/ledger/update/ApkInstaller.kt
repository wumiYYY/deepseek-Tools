package com.jingyuniang.ledger.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.jingyuniang.ledger.BuildConfig
import java.io.File
import java.security.MessageDigest

object ApkInstaller {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "无法读取更新安装包" }
        require(archive.packageName == context.packageName) { "更新包与当前应用不一致，请先安装正式版" }
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(version == release.versionCode.toLong() && archive.versionName == release.versionName) { "安装包版本与发布信息不符" }
        require(version > BuildConfig.VERSION_CODE) { "安装包不是更新版本" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val signatures = certificates(archive)
        require(signatures == setOf(RELEASE_CERT_SHA256) && signatures == certificates(installed)) { "安装包签名与原版不一致，已拒绝安装" }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    fun intent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
