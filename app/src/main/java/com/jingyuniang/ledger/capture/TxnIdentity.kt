package com.jingyuniang.ledger.capture

/**
 * 交易身份（Transaction Identity）—— 用来判断「两条通知是不是同一笔钱」。
 *
 * 为什么需要：单靠「同金额 + 时间窗」去重有两个坑
 * - 同一个渠道连着两笔同金额（连买两杯 25 元）→ 被误判成重复，**漏记**
 * - 同一笔钱被微信通知 + 银行短信各报一次 → 靠金额窗口勉强能合，但窗口一放宽就误杀
 *
 * 业界做法（`bank-sms-parser` 的生产建议）：用 **参考号 / 卡尾号 + 金额 + 时间桶** 构成身份，
 * 有参考号时优先用参考号（唯一），没参考号时退化成「卡尾号 + 金额 + 5 分钟桶」。
 *
 * 纯函数，可直接单测。
 */
object TxnIdentity {

    /** 时间桶大小：5 分钟内视为同一时刻（覆盖「通知 + 短信」先后到达的时间差） */
    const val BUCKET_MS = 300_000L

    /** 「尾号8888」「*8888」「…8888」 */
    private val TAIL_KEYWORD = Regex("""(?:尾号|卡号后四位|末四位)[:：]?\s*(\d{4})""")

    /** 招行风格：「您账户3979于10月26日…」——四位数字紧跟「于/在」 */
    private val TAIL_BEFORE_DATE = Regex("""(?:账户|卡)\s*(\d{4})\s*(?:于|在)""")

    /** 掩码卡号：****8888 / ＊＊8888 / …8888 */
    private val TAIL_MASKED = Regex("""(?:[*＊]{2,}|…|\.{2,})\s*(\d{4})""")

    /** 交易单号 / 流水号 / 订单号 / 参考号（最可靠，直接当身份） */
    private val REF_NO = Regex(
        """(?:交易单号|交易流水号|流水号|订单号|参考号|凭证号|交易参考号)[:：]?\s*([A-Za-z0-9]{5,})"""
    )

    /** 取卡尾号（4 位数字）；没有则空串 */
    fun cardTail(text: String): String =
        TAIL_KEYWORD.find(text)?.groupValues?.get(1)
            ?: TAIL_BEFORE_DATE.find(text)?.groupValues?.get(1)
            ?: TAIL_MASKED.find(text)?.groupValues?.get(1)
            ?: ""

    /** 取交易参考号；没有则空串 */
    fun refNo(text: String): String = REF_NO.find(text)?.groupValues?.get(1) ?: ""

    /** 时间分桶 */
    fun bucket(timeMs: Long, windowMs: Long = BUCKET_MS): Long = timeMs / windowMs

    /**
     * 生成交易身份：
     * 1. 有参考号 → `ref:<参考号>`（跨渠道、跨时间都算同一笔）
     * 2. 有卡尾号 → `card:<尾号>|<金额>|<时间桶>`
     * 3. 都没有 → `app:<包名>|<金额>|<时间桶>`
     */
    fun of(pkg: String?, text: String, amountCents: Long, timeMs: Long): String {
        refNo(text).takeIf { it.isNotBlank() }?.let { return "ref:$it" }
        val tail = cardTail(text)
        val b = bucket(timeMs)
        return if (tail.isNotBlank()) "card:$tail|$amountCents|$b"
        else "app:${pkg?.takeIf { it.isNotBlank() } ?: "unknown"}|$amountCents|$b"
    }

    /** 日志/自检用的可读描述 */
    fun describe(id: String): String = when {
        id.startsWith("ref:") -> "参考号 ${id.removePrefix("ref:")}"
        id.startsWith("card:") -> {
            val p = id.removePrefix("card:").split("|")
            "卡尾号 ${p.getOrNull(0)} · 金额 ${p.getOrNull(1)} 分 · 桶 ${p.getOrNull(2)}"
        }
        id.startsWith("app:") -> {
            val p = id.removePrefix("app:").split("|")
            "应用 ${p.getOrNull(0)} · 金额 ${p.getOrNull(1)} 分 · 桶 ${p.getOrNull(2)}"
        }
        else -> id
    }
}
