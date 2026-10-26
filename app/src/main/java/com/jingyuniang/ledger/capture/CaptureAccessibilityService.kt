package com.jingyuniang.ledger.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.launch

/**
 * 自动记账增强（默认关闭）：只在**支付结果页**补漏，不做「整屏抓账单」。
 *
 * 历史教训（真机反馈：滑动微信/支付宝账单页也会被抓）：
 * 旧实现监听 `TYPE_WINDOW_CONTENT_CHANGED`，而列表滚动会持续产生这个事件；
 * 再加上「支付类 App 无条件放行」+「取整窗前 40 个文本节点」，
 * 一屏账单明细就是一堆「支付 25.00」，于是每滑一屏就可能多出一条账。
 *
 * 现在的策略（宁可漏，不可错）：
 * 1) 只处理 `TYPE_WINDOW_STATE_CHANGED`（页面切换），彻底忽略内容变化/滚动；`n *    并且只对支付通道类 App 生效（抖音/淘宝等刷内容时根本不读）；
 * 2) 窗口文本必须命中「支付成功 / 付款成功 / 交易成功…」这类**结果页文案**；
 * 3) 文本长度 > 260 字直接放弃（结果页都很短，账单列表页都很长）；
 * 4) 同 App 冷却 10 秒 + 文本 hash 去重；
 * 5) 这条来源的置信度在流水线里被封顶，**永远不会自动入账**，只会进「待确认」。
 */
class CaptureAccessibilityService : AccessibilityService() {

    private var lastHash = 0
    private val lastByPkg = HashMap<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 只认页面切换；滚动/内容更新一律不看（这就是「滑账单也抓」的根因）
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 应用内开关可以一键关掉，省电（系统里仍开着也不会再读屏）
        if (!Services.settings.value.captureAccess) return
        val pkg = e.packageName?.toString() ?: return
        // 只对「支付通道」（微信/支付宝/银行/云闪付）读屏；电商、短视频一律不读
        if (!CaptureFilters.isPayRail(pkg)) return

        val now = System.currentTimeMillis()
        val last = lastByPkg[pkg] ?: 0L
        if (now - last < 10_000L) return          // 同一 App 10 秒内只处理一次

        val text = runCatching { collect(rootInActiveWindow) }.getOrDefault("")
        if (text.length < 8) return
        if (!CaptureFilters.isPaymentResultPage(text)) return   // 不是结果页 → 直接放弃
        if (!CaptureFilters.shouldRead(pkg, text)) return
        val hash = text.hashCode()
        if (hash == lastHash) return
        lastHash = hash
        lastByPkg[pkg] = now

        AppLog.d("读屏命中结果页 pkg=$pkg len=${text.length}")
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
