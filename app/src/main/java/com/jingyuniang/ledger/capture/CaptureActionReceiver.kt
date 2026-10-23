package com.jingyuniang.ledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.launch

/** 通知栏「确认入账 / 忽略 / 撤销」按钮的接收器 */
class CaptureActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(CaptureNotifier.EXTRA_CAPTURE_ID, 0L)
        val action = intent.action ?: return
        if (id <= 0L) return
        val pending = goAsync()
        Services.appScope.launch {
            try {
                when (action) {
                    CaptureNotifier.ACTION_CONFIRM -> CapturePipeline.confirm(id)
                    CaptureNotifier.ACTION_IGNORE -> CapturePipeline.ignore(id)
                    CaptureNotifier.ACTION_UNDO -> CapturePipeline.undo(id)
                    else -> Unit
                }
            } catch (t: Throwable) {
                AppLog.e("处理抓取动作失败 $action", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
