package com.jingyuniang.ledger.util

import android.util.Log

/** 统一日志（方便你在手机上看 logcat 排查抓取问题） */
object AppLog {
    private const val TAG = "WhaleMoney"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
