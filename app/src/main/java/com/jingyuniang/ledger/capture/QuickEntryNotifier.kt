package com.jingyuniang.ledger.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jingyuniang.ledger.MainActivity
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.WhaleApp

/** 通知栏常驻「记一笔」：下拉通知栏一秒记账，弥补漏抓的那几笔 */
object QuickEntryNotifier {

    private const val ID = 7300
    const val ACTION_ADD = "com.jingyuniang.ledger.QUICK_ADD"
    const val ACTION_VOICE = "com.jingyuniang.ledger.QUICK_VOICE"

    fun show(ctx: Context) {
        val settings = Services.settings.value
        val builder = NotificationCompat.Builder(ctx, WhaleApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle("🐳 鲸鱼娘金库")
            .setContentText("点「记一笔」马上记，或点「打字」写一句")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open(ctx, null, 71))
            .addAction(0, "＋ 记一笔", open(ctx, ACTION_ADD, 72))
            .addAction(0, "⌨️ 打字", open(ctx, ACTION_VOICE, 73))

        if (settings.autoBook) {
            builder.setSubText("自动记账运行中")
        }
        runCatching {
            if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                NotificationManagerCompat.from(ctx).notify(ID, builder.build())
            }
        }
    }

    fun hide(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
    }

    private fun open(ctx: Context, action: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (action != null) this.action = action
        }
        return PendingIntent.getActivity(
            ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
