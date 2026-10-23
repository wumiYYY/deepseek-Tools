package com.jingyuniang.ledger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.jingyuniang.ledger.capture.CaptureNotificationListener
import com.jingyuniang.ledger.service.FloatingBallService

/** 各项系统权限的检查与跳转，全部集中在这里，方便「权限向导」页面统一调用 */
object Permissions {

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(ctx.packageName) }
    }

    fun openNotificationListenerSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    }

    fun openAccessibilitySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun hasPostNotification(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasReceiveSms(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun openOverlaySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 打开某个应用的通知设置（用来排查「微信通知被关了所以抓不到」） */
    fun openAppNotificationSettings(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        runCatching {
            ctx.startActivity(
                Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("android.provider.extra.APP_PACKAGE", pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun startFloatingBall(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java)) }
    fun stopFloatingBall(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, FloatingBallService::class.java)) }
}
