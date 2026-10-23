package com.jingyuniang.ledger.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.launch

/**
 * 自动记账增强（默认关闭）：读支付成功页面的文字。
 * 只在「支付类应用」窗口里工作，且做了去抖与节点数量上限，尽量不影响性能。
 */
class CaptureAccessibilityService : AccessibilityService() {

    private var lastHash = 0
    private var lastTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 应用内开关可以一键关掉，省电（系统里仍开着也不会再读屏）
        if (!Services.settings.value.captureAccess) return
        val pkg = e.packageName?.toString() ?: return
        if (!CaptureFilters.isPaymentApp(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastTime < 1200L) return

        val text = runCatching { collect(rootInActiveWindow) }.getOrDefault("")
        if (text.length < 8) return
        if (!CaptureFilters.shouldRead(pkg, text)) return
        val hash = text.hashCode()
        if (hash == lastHash) return
        lastHash = hash
        lastTime = now

        Services.appScope.launch {
            CapturePipeline.process(TxnSource.ACCESS, pkg, appLabel(pkg), "", text.take(600))
        }
    }

    private fun collect(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count > 40 || depth > 6) return
            node.text?.toString()?.let { if (it.isNotBlank()) { sb.append(it).append(' '); count++ } }
            node.contentDescription?.toString()?.let {
                if (it.isNotBlank()) { sb.append(it).append(' '); count++ }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (count > 40) break
            }
        }
        walk(root, 0)
        return sb.toString().trim()
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    override fun onInterrupt() {
        AppLog.w("无障碍服务被中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i("无障碍抓取服务已连接")
    }
}
