package com.jingyuniang.ledger.capture

import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Fmt

/**
 * 解析引擎：把「一条通知 / 一条短信 / 一段页面文字」变成可记账的结构化数据。
 *
 * 设计原则（和钱迹、一木记账一致）：
 * 1) 金额识别优先看「消费 / 支出 / 付款 / 支付」等付费动词附近的数字，避开「余额 / 额度 / 优惠」；
 * 2) 方向（收入/支出）靠关键词判定，拿不准就按支出并降置信度；
 * 3) 账户靠「包名 + 关键词 + 卡号尾号」匹配；
 * 4) 分类靠「商户记忆 → 分类关键词 → 规则表」三级命中；
 * 5) 置信度不足的一律进「待确认」，绝不硬塞错误账目。
 */
object ParseEngine {

    data class Result(
        val amountCents: Long,
        val direction: TxnType,
        val merchant: String,
        val accountId: Long,
        val accountName: String,
        val categoryId: Long,
        val confidence: Int,
        val note: String,
        val matchedRule: String = ""
    )

    /** 垃圾消息关键词：命中直接丢弃 */
    private val JUNK = listOf(
        "验证码", "登录", "注册", "密码", "抽奖", "优惠券到账", "红包封面", "确认收货",
        "发货", "物流", "快递已签收", "为你推荐", "活动", "恭喜获得", "恭喜您", "中奖", "券", "劵", "立减", "满减", "优惠", "领取", "抽奖", "权益", "提额", "积分", "广告",
        "账单已出", "还款提醒", "到期提醒", "即将到期", "额度提升", "邀请", "关注",
        "更新", "版本", "清理", "内存", "安全提醒", "异地登录", "失败", "未支付", "待支付",
        "取消订单", "退款申请", "审核中", "处理中"
    )

    private val STRONG_PAY = listOf(
        "支付成功", "付款成功", "支付了", "付款", "已支付", "支付", "消费", "支出", "扣款",
        "交易金额", "转账", "转出", "支出金额", "实付", "已扣款", "交易成功", "缴费成功",
        "收款", "入账", "到账", "转入", "存入", "收入", "退款", "工资", "报销"
    )

    private val INCOME_WORDS = listOf(
        "退款", "退货", "返现", "入账", "到账", "收入", "收款成功", "收到", "工资", "薪资", "请收款", "已收款", "转账给你", "向你转账", "收到一笔",
        "报销", "转入", "存入", "红包收入", "利息", "分红", "收益", "奖励金"
    )

    private val EXPENSE_WORDS = listOf(
        "支出", "消费", "付款", "支付", "扣款", "转出", "购买", "实付", "已支付", "缴费"
    )

    private val NEGATIVE_CONTEXT = listOf("余额", "额度", "可用", "剩余", "积分", "优惠", "立减", "抵扣", "满减", "折扣", "减免", "红包", "券", "限额")

    private val CN_NUM = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 中文金额：「十块」「二十五元」「一百五十块钱」「十二块五」 */
    private val CN_AMOUNT = Regex(
        """([零〇一二两三四五六七八九十百千万]+)\s*(?:元钱|块钱|元|块|圆|钱)([零〇一二两三四五六七八九])?"""
    )

    /**
     * 主入口
     * @param pkg 来源应用包名（短信为 null）
     * @param appName 来源应用名
     * @param text 通知正文 / 短信正文 / 页面文字（已拼接标题）
     */
    fun parse(
        pkg: String?,
        appName: String,
        text: String,
        accounts: List<Account>,
        categories: List<Category>,
        rules: List<AutoRule>,
        merchantRules: Map<String, Pair<Long, Long>> = emptyMap()
    ): Result? {
        val raw = text.replace('\u00A0', ' ').trim()
        if (raw.length < 4) return null
        if (isJunk(raw)) return null

        // 0) 自定义规则优先（可强制方向 / 分类 / 账户）
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.pkg.isNotBlank() && pkg != null && rule.pkg != pkg) continue
            val kws = rule.keyword.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            if (kws.isNotEmpty() && kws.none { raw.contains(it, true) }) continue
            val amount = if (rule.regex.isNotBlank()) {
                runCatching { Regex(rule.regex).find(raw)?.groupValues?.getOrNull(1) }
                    .getOrNull()?.let { Fmt.toCents(it) } ?: 0L
            } else 0L
            if (amount > 0 || kws.isNotEmpty() || rule.pkg.isNotBlank()) {
                val finalAmount = if (amount > 0) amount else extractAmount(raw)
                if (finalAmount <= 0) continue
                return Result(
                    amountCents = finalAmount,
                    direction = rule.direction,
                    merchant = extractMerchant(raw, appName),
                    accountId = rule.accountId,
                    accountName = accounts.firstOrNull { it.id == rule.accountId }?.name ?: "",
                    categoryId = rule.categoryId,
                    confidence = if (amount > 0) 96 else 88,
                    note = raw.take(120),
                    matchedRule = rule.name
                )
            }
        }

        // 1) 金额
        val amount = extractAmount(raw)
        if (amount <= 0L) return null
        if (amount > 100_000_000L) return null // 超过 100 万，多半误识别

        // 2) 方向
        val direction = detectDirection(raw)

        // 3) 账户
        val account = matchAccount(pkg, raw, accounts)
        var confidence = 40
        if (direction != TxnType.TRANSFER) confidence += 20
        if (account != null) confidence += 15
        else confidence -= 10

        // 4) 商户
        val merchant = extractMerchant(raw, appName)
        if (merchant.isNotBlank() && merchant != appName) confidence += 10

        // 5) 分类
        var categoryId = 0L
        merchantRules.entries.firstOrNull { merchant.isNotBlank() && (merchant.contains(it.key, true) || it.key.contains(merchant, true)) }
            ?.let { (_, v) ->
                categoryId = v.first
                confidence += 10
            }
        if (categoryId == 0L) {
            categoryId = matchCategory(raw, merchant, direction, categories)
            if (categoryId > 0) confidence += 10
        }

        // 6) 强支付动词再加分
        if (STRONG_PAY.any { raw.contains(it) }) confidence += 5

        return Result(
            amountCents = amount,
            direction = direction,
            merchant = merchant,
            accountId = account?.id ?: 0L,
            accountName = account?.name ?: "",
            categoryId = categoryId,
            confidence = confidence.coerceIn(0, 99),
            note = raw.take(120)
        )
    }

    // ---------- 垃圾过滤 ----------
    fun isJunk(text: String): Boolean = JUNK.any { text.contains(it) }

    // ---------- 金额 ----------
    private val AMOUNT_REGEX = Regex("""(\d{1,3}(?:,\d{3})*|\d+)(?:\.(\d{1,2}))?""")

    /** 金额前面的「付费动词」：命中说明这个数字是这笔交易的主角 */
    private val STRONG_BEFORE = listOf(
        "消费", "支出", "支付", "付款", "扣款", "交易金额", "金额", "实付",
        "转账", "入账", "到账", "收入", "退款", "收款", "缴费", "存入", "转出", "转入"
    )
    private val CURRENCY_BEFORE = listOf("人民币", "¥", "￥")
    private val NEG_BEFORE = listOf(
        "余额", "额度", "可用", "剩余", "积分", "优惠", "立减", "抵扣",
        "满减", "折扣", "减免", "券", "限额", "冻结"
    )
    private val NEG_AFTER = listOf("余额", "额度", "可用", "剩余", "积分")

    /**
     * 数字后面紧跟这些单位 → 是商品规格/材质，不是金额。
     * 真机踩过：淘宝推送「中国地质大学欧泊吊坠 925银精工镶嵌」被记成 925 元支出。
     */
    private val SPEC_UNIT_AFTER = listOf(
        "银", "金", "k金", "K金", "克拉", "克", "mg", "毫升", "英寸", "寸",
        "mah", "mAh", "毫安", "gb", "GB", "tb", "TB", "mb", "MB", "hz", "Hz",
        "像素", "万像素", "℃", "°c", "cm", "mm", "ml", "mL", "kg", "公斤", "厘米", "毫米",
        "号", "码", "件", "只", "支", "瓶", "袋", "盒", "套", "枚", "条", "片", "粒"
    )

    private data class Cand(val cents: Long, val score: Int, val pos: Int)

    fun extractAmount(text: String): Long {
        val norm = normalize(text)
        val cands = ArrayList<Cand>()
        AMOUNT_REGEX.findAll(norm).forEach { m ->
            val intPart = m.groupValues[1].replace(",", "")
            val dec = m.groupValues[2]
            if (intPart.length > 8) return@forEach
            val base = intPart.toLongOrNull() ?: return@forEach
            var cents = base * 100
            if (dec.isNotEmpty()) cents += dec.padEnd(2, '0').take(2).toLongOrNull() ?: 0L
            if (cents <= 0L) return@forEach

            val start = m.range.first
            val end = m.range.last + 1
            val before = norm.substring(maxOf(0, start - 18), start).takeLast(12)
            val after = norm.substring(end, minOf(norm.length, end + 10)).take(8)

            var score = 0
            if (STRONG_BEFORE.any { before.contains(it) }) score += 8
            if (CURRENCY_BEFORE.any { before.contains(it) }) score += 4
            val afterTrim = after.trimStart()
            if (afterTrim.startsWith("元") || afterTrim.startsWith("块")) score += 5
            else if (after.contains("元") || after.contains("块")) score += 2
            // 规格/材质单位：商品标题里的「925银」「18K金」「5000mAh」「6.7英寸」不是金额
            if (SPEC_UNIT_AFTER.any { afterTrim.startsWith(it) }) score -= 30
            if (NEG_BEFORE.any { before.contains(it) }) score -= 20
            if (before.contains("尾号") || before.endsWith("尾")) score -= 6
            if (before.any { it == ':' || it == '：' || it == '-' || it == '/' } ||
                before.contains("年") || before.contains("月") || before.contains("日")
            ) score -= 3
            cands.add(Cand(cents, score, start))
        }
        // 没有阿拉伯数字时兜底认中文金额（「十块钱」「二十五元」）
        if (cands.isEmpty()) return parseChineseAmount(norm)
        // 分数最高的胜出；同分取位置靠前的（通常金额在余额前面）
        val best = cands.maxWithOrNull(compareBy({ it.score }, { -it.pos })) ?: return 0L
        if (best.score >= 0) return best.cents
        // 全是负分（多半只提到了余额/额度）：宁可不识别，也不记错账
        return 0L
    }

    private val PAY_CTX = listOf(
        "消费", "支出", "支付", "付款", "扣款", "交易金额", "金额", "实付", "合计",
        "转账", "收款", "入账", "到账", "转入", "转出", "存入", "收入", "退款"
    )

    private fun String.containsAny(list: List<String>): Boolean = list.any { this.contains(it) }

    private fun normalize(s: String): String = s
        .replace('：', ':')
        .replace('，', ',')
        .replace('。', '.')
        .replace('（', '(')
        .replace('）', ')')

    // ---------- 方向 ----------
    fun detectDirection(text: String): TxnType {
        val inc = INCOME_WORDS.count { text.contains(it) }
        val exp = EXPENSE_WORDS.count { text.contains(it) }
        return when {
            inc > exp -> TxnType.INCOME
            exp > inc -> TxnType.EXPENSE
            inc > 0 && exp == 0 -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
    }

    // ---------- 账户 ----------
    fun matchAccount(pkg: String?, text: String, accounts: List<Account>): Account? {
        // 1) 包名对应的账户类型
        if (pkg != null) {
            val type = pkgToType(pkg)
            if (type != null) {
                accounts.firstOrNull { it.type == type }?.let { return it }
            }
        }
        // 2) 关键词
        var best: Account? = null
        var bestScore = 0
        accounts.forEach { acc ->
            var score = 0
            acc.keywords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { kw ->
                if (text.contains(kw, true)) score += 3
            }
            if (acc.cardTail.isNotBlank() && (text.contains("尾号${acc.cardTail}") ||
                        text.contains("*${acc.cardTail}") || text.contains("…${acc.cardTail}"))
            ) score += 8
            if (acc.name.isNotBlank() && text.contains(acc.name)) score += 4
            if (score > bestScore) {
                bestScore = score
                best = acc
            }
        }
        return if (bestScore >= 3) best else null
    }

    fun pkgToType(pkg: String): AccountType? = when {
        pkg == "com.tencent.mm" -> AccountType.WECHAT
        pkg.contains("alipay") || pkg.contains("Alipay") -> AccountType.ALIPAY
        pkg.contains("unionpay") || pkg.contains("bank") || pkg.contains("icbc") ||
            pkg.contains("ccb") || pkg.contains("abchina") || pkg.contains("boc") ||
            pkg.contains("cmbchina") || pkg.contains("spdb") || pkg.contains("citic") -> AccountType.BANK
        else -> null
    }

    // ---------- 商户 ----------
    private val MERCHANT_PATTERNS = listOf(
        Regex("""向(.{1,24}?)(?:付款|支付|转账)"""),
        Regex("""(?:付款方|收款方|商户名称|商户|商家)[:：]\s*(.{1,24})"""),
        Regex("""(?:在|于)(.{1,24}?)(?:消费|支付|购买)"""),
        Regex("""(?:付款给|转账给|收款人)[:：]?\s*(.{1,20}?)(?:[,,。\\s]|$)"""),
        Regex("""(?:交易对方|对方)[:：]\s*(.{1,24})"""),
        Regex("""支付成功[-—\s]*(.{1,20}?)(?:[,,。\\s]|$)""")
    )

    fun extractMerchant(text: String, fallback: String): String {
        for (p in MERCHANT_PATTERNS) {
            val m = p.find(text)
            val v = m?.groupValues?.getOrNull(1)?.trim()?.trim('的', ' ', ',', '.', ':', '，', '。') ?: continue
            if (v.isEmpty() || v.length < 2) continue
            if (v.containsAny(PAY_CTX)) continue
            if (looksLikeNoise(v)) continue
            return v
        }
        // 「微信支付收款0.01元(朋友到店)」这类写法，退化取括号前的说明
        val paren = Regex("""[(（](.{2,20})[)）]""").find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!paren.isNullOrBlank() && !paren.containsAny(PAY_CTX) && !looksLikeNoise(paren)) return paren
        return fallback
    }

    /** 日期、时间、卡号、金额这类「看起来是商户其实不是」的串要排掉 */
    private fun looksLikeNoise(v: String): Boolean {
        if (v.any { it == '年' || it == '月' || it == '日' || it == '时' || it == '号' }) return true
        if (v.count { it.isDigit() } >= 3) return true
        if (v.all { it.isDigit() || it == '-' || it == ':' || it == '.' || it == ' ' }) return true
        return false
    }

    // ---------- 分类 ----------
    fun matchCategory(
        text: String,
        merchant: String,
        direction: TxnType,
        categories: List<Category>
    ): Long {
        val type = if (direction == TxnType.INCOME) TxnType.INCOME else TxnType.EXPENSE
        val pool = categories.filter { it.type == type && it.keywords.isNotBlank() }
        var bestId = 0L
        var bestScore = 0
        pool.forEach { c ->
            var score = 0
            c.keywords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { kw ->
                if (merchant.contains(kw, true)) score += 5
                if (text.contains(kw, true)) score += 2
            }
            if (c.name.isNotBlank() && text.contains(c.name)) score += 3
            if (score > bestScore) {
                bestScore = score
                bestId = c.id
            }
        }
        if (bestId > 0L) return bestId
        return categories.firstOrNull { it.type == type && it.name == "其他" }?.id ?: 0L
    }

    /**
     * 中文金额 → 分。识别「十块」「二十五元」「一百五十块钱」「十二块五」这类口语写法。
     * 只认带「元 / 块 / 圆 / 角 / 毛」单位的中文数字，避免把「十分钟」「三月」当成金额。
     */
    fun parseChineseAmount(text: String): Long {
        val m = CN_AMOUNT.find(text) ?: return 0L
        val yuan = cnNumberToLong(m.groupValues[1])
        if (yuan <= 0L) return 0L
        var cents = yuan * 100
        // 「十二块五」= 12.5 元：单位后面紧跟的一位数字按「角」算
        val tail = m.groupValues[2]
        if (tail.isNotEmpty()) cents += (CN_NUM[tail[0]] ?: 0) * 10L
        return cents
    }

    /** 「二十五」→25，「一百五十」→150，「一千零五」→1005 */
    private fun cnNumberToLong(s: String): Long {
        var total = 0L
        var section = 0L
        var num = 0L
        for (ch in s) {
            when {
                CN_NUM.containsKey(ch) -> num = CN_NUM.getValue(ch).toLong()
                ch == '十' -> { section += (if (num == 0L) 1L else num) * 10L; num = 0L }
                ch == '百' -> { section += (if (num == 0L) 1L else num) * 100L; num = 0L }
                ch == '千' -> { section += (if (num == 0L) 1L else num) * 1000L; num = 0L }
                ch == '万' -> { total += (section + num) * 10000L; section = 0L; num = 0L }
            }
        }
        return total + section + num
    }
}
