package com.jingyuniang.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jingyuniang.ledger.MainActivity
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面小组件：一眼看到本月支出，点一下就能记一笔。
 * 用传统 RemoteViews 实现，兼容所有启动器。
 */
private const val ACTION_TOGGLE = "com.jingyuniang.ledger.WIDGET_TOGGLE_CAPTURE"

class WhaleWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            Services.init(context)
            val result = goAsync()
            Services.appScope.launch {
                runCatching {
                    val prefs = com.jingyuniang.ledger.data.Prefs(context)
                    val now = Services.settings.value.captureMaster
                    prefs.setCaptureMaster(!now)
                }
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, WhaleWidgetReceiver::class.java))
                val views = runCatching { build(context) }.getOrElse { emptyViews(context) }
                withContext(Dispatchers.Main) {
                    ids.forEach { id -> runCatching { mgr.updateAppWidget(id, views) } }
                    runCatching { result.finish() }
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Services.init(context)
        val result = goAsync()
        Services.appScope.launch {
            val views = runCatching { build(context) }.getOrElse { emptyViews(context) }
            withContext(Dispatchers.Main) {
                appWidgetIds.forEach { id ->
                    runCatching { appWidgetManager.updateAppWidget(id, views) }
                }
                runCatching { result.finish() }
            }
        }
    }

    private suspend fun build(context: Context): RemoteViews {
        val repo = Services.repo
        val ledgerId = Services.settings.value.ledgerId
        val now = System.currentTimeMillis()
        val monthStart = Dates.startOfMonth(now)
        val monthEnd = Dates.startOfNextMonth(now)
        val todayStart = Dates.startOfDay(now)
        val expense = repo.txnDao.sumRange(ledgerId, TxnType.EXPENSE, monthStart, monthEnd)
        val income = repo.txnDao.sumRange(ledgerId, TxnType.INCOME, monthStart, monthEnd)
        val today = repo.txnDao.sumRange(ledgerId, TxnType.EXPENSE, todayStart, todayStart + 86_400_000L)

        val views = RemoteViews(context.packageName, R.layout.widget_whale)
        views.setTextViewText(R.id.widget_expense, "本月支出 ¥${Fmt.amount(expense)}")
        views.setTextViewText(R.id.widget_income, "本月收入 ¥${Fmt.amount(income)}")
        views.setTextViewText(R.id.widget_today, "今日 ¥${Fmt.amount(today)}")
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context, null, 1001))
        views.setOnClickPendingIntent(
            R.id.widget_add,
            openIntent(context, "com.jingyuniang.ledger.QUICK_ADD", 1002)
        )
        // 🔁 抓取开关：一键暂停/恢复自动记账（不用进 App）
        val on = Services.settings.value.captureMaster
        views.setTextViewText(R.id.widget_voice, if (on) "🔁 抓取：开" else "⏸ 抓取：已停")
        views.setOnClickPendingIntent(
            R.id.widget_voice,
            android.app.PendingIntent.getBroadcast(
                context,
                1003,
                Intent(context, WhaleWidgetReceiver::class.java).setAction(ACTION_TOGGLE),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        )
        return views
    }

    private fun emptyViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_whale)
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context, null, 1001))
        views.setOnClickPendingIntent(R.id.widget_add, openIntent(context, "com.jingyuniang.ledger.QUICK_ADD", 1002))
        views.setOnClickPendingIntent(R.id.widget_voice, openIntent(context, "com.jingyuniang.ledger.QUICK_VOICE", 1003))
        return views
    }

    private fun openIntent(context: Context, action: String?, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, WhaleWidgetReceiver::class.java))
                if (ids.isNotEmpty()) {
                    val i = Intent(context, WhaleWidgetReceiver::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(i)
                }
            }
        }
    }
}
