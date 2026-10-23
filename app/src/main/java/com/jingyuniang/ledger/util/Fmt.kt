package com.jingyuniang.ledger.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 金额与数字格式化 */
object Fmt {
    /** 分 → "1,234.56"（不带符号） */
    fun amount(cents: Long): String {
        val neg = cents < 0
        val v = abs(cents)
        val yuan = v / 100
        val fen = v % 100
        val sb = StringBuilder()
        val s = yuan.toString()
        var count = 0
        for (i in s.indices.reversed()) {
            sb.append(s[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        val intPart = sb.reverse().toString()
        val out = if (fen == 0L) intPart else "$intPart.${fen.toString().padStart(2, '0')}"
        return if (neg) "-$out" else out
    }

    /** 分 → "¥1,234.56" */
    fun money(cents: Long): String = (if (cents < 0) "-¥" else "¥") + amount(abs(cents))

    /** 分 → 带正负号 "＋1,234.56" / "－1,234.56" */
    fun signed(cents: Long, income: Boolean): String = (if (income) "＋" else "－") + amount(abs(cents))

    /** "12.5" / "12.50" → 1250 */
    fun toCents(input: String): Long {
        val t = input.replace(",", "").replace("¥", "").replace("￥", "").trim()
        if (t.isEmpty() || t == ".") return 0
        val parts = t.split(".")
        val yuan = parts[0].ifEmpty { "0" }.filter { it.isDigit() }.toLongOrNull() ?: 0
        val fen = when {
            parts.size == 1 -> 0L
            parts[1].isEmpty() -> 0L
            parts[1].length == 1 -> (parts[1].filter { it.isDigit() }.toLongOrNull() ?: 0) * 10
            else -> parts[1].take(2).filter { it.isDigit() }.toLongOrNull() ?: 0
        }
        return yuan * 100 + fen
    }

    /** 分 → 输入框用字符串 "12.50" */
    fun inputText(cents: Long): String {
        if (cents == 0L) return ""
        return if (cents % 100 == 0L) (cents / 100).toString()
        else String.format(Locale.CHINA, "%.2f", cents / 100.0)
    }
}

/** 时间工具（全部走系统时区，只用 API 26 就有的 java.time 接口） */
object Dates {
    private val ZONE: ZoneId get() = ZoneId.systemDefault()
    private val MD = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val YMD = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val YM = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    private val HMS = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 注意：不能用 LocalDate.ofInstant —— 它是 API 33 才加的 */
    private fun dateOf(ts: Long): LocalDate =
        Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate()

    fun startOfDay(ts: Long): Long = dateOf(ts).atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun endOfDay(ts: Long): Long = startOfDay(ts) + 86400_000L

    fun startOfMonth(ts: Long): Long {
        val d = dateOf(ts)
        return LocalDate.of(d.year, d.monthValue, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    fun startOfNextMonth(ts: Long): Long {
        val d = dateOf(ts)
        val next = if (d.monthValue == 12) LocalDate.of(d.year + 1, 1, 1) else LocalDate.of(d.year, d.monthValue + 1, 1)
        return next.atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    fun addMonths(ts: Long, delta: Int): Long {
        val d = dateOf(ts)
        val base = if (d.dayOfMonth > 1) LocalDate.of(d.year, d.monthValue, 1) else d
        return base.plusMonths(delta.toLong()).atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    fun addDays(ts: Long, delta: Int): Long = startOfDay(ts) + delta * 86400_000L

    fun startOfYear(ts: Long): Long {
        val d = dateOf(ts)
        return LocalDate.of(d.year, 1, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    fun startOfWeek(ts: Long): Long {
        val d = dateOf(ts)
        return d.minusDays((d.dayOfWeek.value - 1).toLong()).atStartOfDay(ZONE).toInstant().toEpochMilli()
    }

    fun month(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(YM)
    fun md(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(MD)
    fun ymd(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(YMD)
    fun hm(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(HMS)
    fun weekday(ts: Long): String {
        val d = dateOf(ts)
        return WEEK[d.dayOfWeek.value - 1]
    }

    fun year(ts: Long): Int = dateOf(ts).year
    fun monthOfYear(ts: Long): Int = dateOf(ts).monthValue
    fun dayOfMonth(ts: Long): Int = dateOf(ts).dayOfMonth
    fun isToday(ts: Long): Boolean = startOfDay(ts) == startOfDay(System.currentTimeMillis())
    fun isSameMonth(a: Long, b: Long): Boolean = startOfMonth(a) == startOfMonth(b)
    fun daysInMonth(ts: Long): Int = dateOf(ts).lengthOfMonth()

    /** 友好时间：今天 / 昨天 / M月d日 */
    fun friendlyDay(ts: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val d = startOfDay(ts)
        return when (d) {
            today -> "今天"
            today - 86400_000L -> "昨天"
            today - 2 * 86400_000L -> "前天"
            else -> md(ts)
        }
    }

    /** 构造本地时间戳 */
    fun of(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli()
}
