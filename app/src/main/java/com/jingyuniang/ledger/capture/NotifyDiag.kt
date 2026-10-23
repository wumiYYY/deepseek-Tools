package com.jingyuniang.ledger.capture

import androidx.compose.runtime.mutableStateListOf

/** 一条「收到过的通知」记录，用于在 App 内诊断「为什么没抓到」 */
data class NotifyEvent(
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val verdict: String
)

/**
 * 通知体检用的内存环形队列（最近 40 条，进程重启即清空）。
 * 只记录「支付类应用 + 通知里带金额」的通知，不落库、不上传。
 */
object NotifyDiag {

    private val _events = mutableStateListOf<NotifyEvent>()
    val events: List<NotifyEvent> get() = _events

    fun add(e: NotifyEvent) {
        _events.add(0, e)
        while (_events.size > 40) _events.removeAt(_events.lastIndex)
    }

    fun clear() = _events.clear()

    fun last(pkg: String, since: Long): NotifyEvent? =
        _events.firstOrNull { it.pkg == pkg && it.time >= since }
}
