package com.jingyuniang.ledger.util

import android.util.Log

/**
 * 统一日志（方便你在手机上看 logcat 排查抓取问题）。
 *
 * 每个方法都包了 runCatching：**JVM 单元测试里没有 android.util.Log 的实现**，
 * 直接调用会抛 `Method w in android.util.Log not mocked`，把业务逻辑的测试一起带崩。
 */
object AppLog {
    private const val TAG = "WhaleMoney"
    fun d(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }

    fun i(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    fun w(msg: String, t: Throwable? = null) {
        runCatching { Log.w(TAG, msg, t) }
    }

    fun e(msg: String, t: Throwable? = null) {
        runCatching { Log.e(TAG, msg, t) }
    }
}
