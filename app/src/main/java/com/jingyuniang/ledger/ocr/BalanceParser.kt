package com.jingyuniang.ledger.ocr

import com.jingyuniang.ledger.data.AccountType

/**
 * 从「微信 / 支付宝 / 银行 App 的余额截图」OCR 文本里抽出**余额**。
 *
 * 为什么不复用 [com.jingyuniang.ledger.capture.ParseEngine.extractAmount]：
 * 那个函数是给「一条交易通知」用的，它的规则是**故意避开**「余额」旁边的数字
 * （怕把余额当成消费金额）。余额截图正好相反——要的恰恰是「余额」那一行的数字。
 *
 * 策略（纯 JVM，可单测）：
 * 1) 按行看，先给每行判标签权重：可用余额 > 账户余额 > 余额 > 零钱 > 总资产/总金额 > 余额宝/零钱通；
 *    带「收益 / 限额 / 积分 / 红包 / 券」等噪声词的行直接跳过；
 * 2) 数字可能和标签同一行，也可能在下面 1-2 行（OCR 常把标签和数值拆开）；
 * 3) 同一行有多个数字时取最大的那个（余额一般就是这行最大的金额）；
 * 4) 百分比、年份、日期、卡号这类数字要排掉。
 */
object BalanceParser {

    data class Hit(val cents: Long, val label: String)

    /** 金额：允许千分位、两位小数 */
    private val MONEY = Regex("""\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

    /** 标签优先级（数值越大越可信）；顺序也很重要，先匹配到的更具体 */
    private val LABELS = listOf(
        "可用余额" to 100,
        "账户余额" to 98,
        "当前余额" to 97,
        "余额" to 90,
        "零钱" to 85,
        "总资产" to 40,
        "总金额" to 38
    )

    /** 这些词出现的行不可能是「余额本身」 */
    private val NOISE = listOf(
        "收益", "利息", "累计", "昨日", "年化", "万份", "限额", "上限", "积分",
        "红包", "券", "优惠", "满减", "预计", "变动", "较上", "借呗", "花呗可用",
        "还款", "账单", "待还", "额度"
    )

    fun parse(raw: String?): Hit? {
        if (raw.isNullOrBlank()) return null
        val text = raw
            .replace('，', ',')
            .replace('．', '.')
            .replace('\u00A0', ' ')
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var best: Hit? = null
        var bestScore = 0
        lines.forEachIndexed { idx, line ->
            if (line.any { it == '%' }) return@forEachIndexed
            val label = labelOf(line) ?: return@forEachIndexed
            if (NOISE.any { line.contains(it) }) return@forEachIndexed

            var cents = moneyIn(line)
            if (cents <= 0L) {
                // 标签与数值被 OCR 拆成两行：往下找 1-2 行
                for (step in 1..2) {
                    val next = lines.getOrNull(idx + step) ?: break
                    if (NOISE.any { next.contains(it) }) break
                    val v = moneyIn(next)
                    if (v > 0L) {
                        cents = v
                        break
                    }
                }
            }
            if (cents <= 0L) return@forEachIndexed
            // 同分时让靠后的行胜出：OCR 里「余额宝」这类理财标题会排在真正的「余额」前面
            if (label.second >= bestScore) {
                bestScore = label.second
                best = Hit(cents, label.first)
            }
        }
        return best
    }

    /**
     * 猜这张截图是哪个 App / 哪种账户的余额页。
     *
     * 为什么需要：OCR 只能给出数字，账户却要人来选。默认选「微信」时，
     * 拍的是支付宝余额就会被写进微信账户——余额直接错，而且很难发现。
     * 这里按截图里出现的品牌词打分，猜不出来就返回 null（让界面提示用户自己选）。
     */
    fun guessAccountType(raw: String?): AccountType? {
        if (raw.isNullOrBlank()) return null
        val t = fixOcrChars(raw)
        val score = HashMap<AccountType, Int>()
        fun add(type: AccountType, w: Int) { score[type] = (score[type] ?: 0) + w }

        // 权重分配要点：钱包首页几乎都会列「银行卡」入口，所以「银行卡」只能算弱信号，
        // 「零钱 / 余额宝」才是这个钱包 App 的强信号（真机踩过：微信钱包截图被猜成了储蓄卡）
        if (t.contains("零钱")) add(AccountType.WECHAT, 5)
        if (t.contains("微信")) add(AccountType.WECHAT, 4)
        if (t.contains("余额宝")) add(AccountType.ALIPAY, 4)
        if (t.contains("支付宝")) add(AccountType.ALIPAY, 4)
        if (t.contains("花呗")) add(AccountType.ALIPAY, 2)
        if (t.contains("信用卡")) add(AccountType.CREDIT, 4)
        if (t.contains("储蓄卡") || t.contains("借记卡") || t.contains("银行卡") || t.contains("尾号")) {
            add(AccountType.BANK, 2)
        }
        if (t.contains("现金")) add(AccountType.CASH, 3)

        val best = score.entries.maxByOrNull { it.value } ?: return null
        if (best.value < 2) return null
        // 同分时按「越像钱包 App 越优先」的顺序取，别让「银行卡」这种通用词抢走
        val priority = listOf(AccountType.WECHAT, AccountType.ALIPAY, AccountType.CREDIT, AccountType.CASH, AccountType.BANK)
        return priority.firstOrNull { score[it] == best.value } ?: best.key
    }

    /** 「余额宝」「零钱通」是理财，不是可用余额：单独降权，且不让它们抢「余额/零钱」的标签 */
    private fun labelOf(line: String): Pair<String, Int>? {
        val s = fixOcrChars(line)
        if (s.contains("余额宝")) return "余额宝" to 30
        if (s.contains("零钱通")) return "零钱通" to 28
        for ((k, w) in LABELS) {
            if (s.contains(k)) return k to w
        }
        return null
    }

    /**
     * OCR 常见错字修正（真机实测：ML Kit 会把「零钱」读成「零线」、「余额」读成「佘额」，
     * 还会混出繁体「餘額」「總金額」）。只用于判标签，不碰数字。
     */
    private fun fixOcrChars(s: String): String = s
        .replace('线', '钱')
        .replace('綫', '钱')
        .replace('銭', '钱')
        .replace('錢', '钱')
        .replace('佘', '余')
        .replace('赊', '余')
        .replace('餘', '余')
        .replace('颉', '额')
        .replace('額', '额')
        .replace('總', '总')
        .replace('寶', '宝')
        .replace('銀', '银')
        .replace('錢', '钱')

    /** 取这一行里最大的一笔金额（分）；没有则 0 */
    private fun moneyIn(line: String): Long {
        var max = 0L
        MONEY.findAll(line).forEach { m ->
            val start = m.range.first
            val end = m.range.last + 1
            val before = line.substring(maxOf(0, start - 1), start)
            val after = line.substring(end, minOf(line.length, end + 1))
            // 排掉日期 / 时间 / 百分比 / 年份 / 卡号尾号
            if (before.any { it == '-' || it == ':' || it == '/' || it == '#' }) return@forEach
            if (after.any { it == '%' || it == '年' || it == '月' || it == '日' || it == ':' }) return@forEach
            if (line.length > end && line.substring(end).startsWith("年")) return@forEach
            val raw = m.value.replace(",", "")
            val cents = toCents(raw)
            if (cents > max) max = cents
        }
        return max
    }

    private fun toCents(v: String): Long {
        val parts = v.split('.')
        val yuan = parts[0].toLongOrNull() ?: return 0L
        var fen = 0L
        if (parts.size > 1) {
            fen = parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        }
        return yuan * 100 + fen
    }
}
