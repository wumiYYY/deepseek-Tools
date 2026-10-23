package com.jingyuniang.ledger.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.launch

/** 银行短信抓取：信用卡 / 储蓄卡扣款短信是最可靠的一路来源 */
class SmsCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?: return
        if (messages.isEmpty()) return
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress ?: ""
        if (body.isBlank()) return
        AppLog.d("收到短信: $sender ${body.take(60)}")

        val pending = goAsync()
        Services.appScope.launch {
            try {
                // 短信没有包名，标题放发信号码，来源名用「银行短信」便于在列表里辨识
                CapturePipeline.process(TxnSource.SMS, null, "银行短信", sender, body)
            } catch (t: Throwable) {
                AppLog.e("短信抓取失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
