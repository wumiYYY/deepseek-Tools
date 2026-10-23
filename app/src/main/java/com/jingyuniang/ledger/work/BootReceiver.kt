package com.jingyuniang.ledger.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jingyuniang.ledger.Services

/** 开机 / 升级后把定时任务和悬浮球恢复起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Services.init(context)
        WorkScheduler.scheduleAll(context)
        if (Services.settings.value.floatingBall && Settings.canDrawOverlays(context)) {
            WorkScheduler.startFloatingBall(context)
        }
    }
}
