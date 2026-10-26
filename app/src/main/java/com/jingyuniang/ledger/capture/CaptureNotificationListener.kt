package com.jingyuniang.ledger.capture

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.launch

/**
 * 自动记账主力：监听所有通知，只挑支付类应用的「支付成功」类通知交给解析引擎。
 * 用户需在系统设置里授予「通知使用权」。
 */
class CaptureNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i("通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLog.w("通知监听断开，尝试重新绑定")
        runCatching { requestRebind(android.content.ComponentName(this, CaptureNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        runCatching { handle(item) }.onFailure { AppLog.e("通知解析异常", it) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val n: Notification = sbn.notification ?: return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ").orEmpty()
        val body = big.ifBlank { text }
        val all = listOf(title, sub, body, lines).filter { it.isNotBlank() }.joinToString(" ")
        if (all.isBlank()) return

        val appName = appLabel(pkg)
        val source = if (CaptureFilters.isSmsApp(pkg)) TxnSource.SMS else TxnSource.NOTIFY
        // 通知渠道是判断「营销推送 vs 交易提醒」的强信号：银行 App 的广告几乎都在单独渠道里
        val channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { n.channelId }.getOrNull()
        } else null
        val channelName = channelId?.let { id ->
            runCatching {
                getSystemService(android.app.NotificationManager::class.java)
                    ?.getNotificationChannel(id)?.name?.toString()
            }.getOrNull()
        }

        // 只把「支付类应用」的通知放进体检面板，避免把聊天/广告都记下来
        if (CaptureFilters.isPaymentApp(pkg) || CaptureFilters.isSmsApp(pkg)) {
            val readable = CaptureFilters.shouldRead(pkg, all, title, channelId, channelName)
            val wechatOk = pkg != "com.tencent.mm" || CaptureFilters.wechatLikelyPayment(all)
            val chatRejected = CaptureFilters.isChatApp(pkg) && title.isNotEmpty() &&
                !listOf("微信支付", "QQ钱包", "QQ支付", "财付通", "支付宝", "云闪付", "银联", "钱包", "银行", "信用卡", "动账", "交易提醒", "账单").any { title.contains(it) }
            val verdict = when {
                !CaptureFilters.isPaymentApp(pkg) && !CaptureFilters.isSmsApp(pkg) -> "非支付类应用"
                CaptureFilters.isMarketingChannel(channelId, channelName) -> "营销通知渠道，已忽略"
                CaptureFilters.isNonTxnNotice(all) -> "账单/额度/营销提醒（非动账），已忽略"
                CaptureFilters.looksLikeStatementList(all) -> "账单列表页（多个金额），已忽略"
                !readable -> "未含支付关键词，已忽略"
                !wechatOk -> "微信聊天消息（非支付），已忽略"
                chatRejected -> "聊天群消息（非支付），已忽略"
                else -> "已交给解析引擎"
            }
            NotifyDiag.add(
                NotifyEvent(
                    pkg = pkg,
                    app = appName.ifBlank { pkg },
                    title = title.take(40),
                    text = all.take(180),
                    time = System.currentTimeMillis(),
                    verdict = verdict
                )
            )
        }

        Services.appScope.launch {
            CapturePipeline.process(source, pkg, appName, title, all)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被清除不做处理：抓取记录已经落库
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(ctx.packageName)
        }
    }
}
