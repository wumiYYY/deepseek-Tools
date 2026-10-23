package com.jingyuniang.ledger.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jingyuniang.ledger.MainActivity
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.WhaleApp
import com.jingyuniang.ledger.data.Capture
import com.jingyuniang.ledger.data.CaptureStatus
import com.jingyuniang.ledger.util.Fmt

/** 抓取结果的通知：待确认 / 已自动入账（可撤销） */
object CaptureNotifier {

    const val ACTION_CONFIRM = "com.jingyuniang.ledger.capture.CONFIRM"
    const val ACTION_IGNORE = "com.jingyuniang.ledger.capture.IGNORE"
    const val ACTION_UNDO = "com.jingyuniang.ledger.capture.UNDO"
    const val ACTION_OPEN = "com.jingyuniang.ledger.capture.OPEN"
    const val EXTRA_CAPTURE_ID = "capture_id"

    private fun ctx(): Context = com.jingyuniang.ledger.Services.appContext

    private fun mgr(): NotificationManagerCompat = NotificationManagerCompat.from(ctx())

    private fun action(capture: Capture, actionName: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx(), CaptureActionReceiver::class.java).apply {
            action = actionName
            data = Uri.parse("whale://capture/${capture.id}/$requestCode")
            putExtra(EXTRA_CAPTURE_ID, capture.id)
        }
        return PendingIntent.getBroadcast(
            ctx(), requestCode + capture.id.toInt() * 10,
            i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openIntent(capture: Capture): PendingIntent {
        val i = Intent(ctx(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("whale://open/captures/${capture.id}")
            putExtra("screen", "captures")
        }
        return PendingIntent.getActivity(
            ctx(), 9000 + capture.id.toInt(),
            i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun sourceName(capture: Capture): String = when (capture.source) {
        com.jingyuniang.ledger.data.TxnSource.NOTIFY -> if (capture.appName.isBlank()) "通知抓取" else capture.appName
        com.jingyuniang.ledger.data.TxnSource.SMS -> "银行短信"
        com.jingyuniang.ledger.data.TxnSource.ACCESS -> "支付页面"
        else -> "自动记账"
    }

    fun notifyPending(capture: Capture, categoryName: String, accountName: String) {
        if (!mgr().areNotificationsEnabled()) return
        val income = capture.direction == com.jingyuniang.ledger.data.TxnType.INCOME
        val sign = if (income) "＋" else "－"
        val title = "🐳 抓到一笔${if (income) "收入" else "支出"} $sign${Fmt.amount(capture.amountCents)}"
        val body = buildString {
            append(categoryName)
            if (capture.merchant.isNotBlank() && capture.merchant != capture.appName) append(" · ").append(capture.merchant)
            if (accountName.isNotBlank()) append(" · ").append(accountName)
            append(" · 来自").append(sourceName(capture))
            append("（置信度 ").append(capture.confidence).append("%）")
        }
        val n = NotificationCompat.Builder(ctx(), WhaleApp.CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + "\n点「确认入账」完成记账，或「忽略」丢弃。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent(capture))
            .addAction(0, "确认入账", action(capture, ACTION_CONFIRM, 1))
            .addAction(0, "忽略", action(capture, ACTION_IGNORE, 2))
            .build()
        runCatching { mgr().notify(captureId(capture), n) }
    }

    fun notifyAutoBooked(capture: Capture, categoryName: String, accountName: String) {
        if (!mgr().areNotificationsEnabled()) return
        val body = buildString {
            append(categoryName)
            if (capture.merchant.isNotBlank() && capture.merchant != capture.appName) append(" · ").append(capture.merchant)
            if (accountName.isNotBlank()) append(" · ").append(accountName)
            append(" · ").append(sourceName(capture))
        }
        val n = NotificationCompat.Builder(ctx(), WhaleApp.CH_AUTO)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle("✅ 已自动记账 ¥${Fmt.amount(capture.amountCents)}")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openIntent(capture))
            .addAction(0, "撤销", action(capture, ACTION_UNDO, 3))
            .build()
        runCatching { mgr().notify(captureId(capture), n) }
    }

    fun cancel(capture: Capture) {
        runCatching { mgr().cancel(captureId(capture)) }
    }

    private fun captureId(capture: Capture): Int = 20_000 + (capture.id % 100_000).toInt()

    /** 通用提醒（记账提醒 / 预算超支） */
    fun notifyReminder(id: Int, title: String, body: String) {
        if (!mgr().areNotificationsEnabled()) return
        val i = Intent(ctx(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            ctx(), id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx(), WhaleApp.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { mgr().notify(id, n) }
    }

    fun createServiceChannelIfNeeded(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(WhaleApp.CH_SERVICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WhaleApp.CH_SERVICE, "悬浮球 / 常驻服务",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    fun isPending(capture: Capture): Boolean = capture.status == CaptureStatus.PENDING
}
