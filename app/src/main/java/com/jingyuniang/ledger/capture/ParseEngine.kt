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

    /** 关键词命不中、AI 也问不出来时落到的分类名（由 CapturePipeline 按需创建） */
    const val UNCERTAIN_CATEGORY = "待分类"

    /** 垃圾消息关键词：命中直接丢弃 */
    private val JUNK = listOf(
        "验证码", "登录", "注册", "密码", "抽奖", "优惠券到账", "红包封面", "确认收货",
        "发货", "物流", "快递已签收", "为你推荐", "活动", "恭喜获得", "恭喜您", "中奖", // 注意：
        // · 「领取」不能放这里 —— 「[转账] 领取了你的红包」会被误杀（真机/矩阵测试抓到）。
        //   它在 AD_MARKERS 里还有，营销推送照样被广告门拦。
        // · JUNK 是「一眼假」的绝对垃圾，判定必须保守，否则真实交易会被吞。
        "券", "劵", "立减", "满减", "优惠", "抽奖", "权益", "提额", "积分", "广告",
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
        "退款", "退货", "返现", "入账", "向您转账", "转账给您", "到账", "收入", "收款成功", "收到", "工资", "薪资", "请收款", "已收款", "转账给你", "向你转账", "收到一笔",
        "报销", "转入", "存入", "红包收入", "利息", "分红", "收益", "奖励金",
        // 收红包/收转账（v1.4.5）：原来只认「红包收入」这种书面词，真机上「[微信红包] 恭喜发财」被当成支出
        "微信红包", "收到红包", "红包到账", "恭喜发财"
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

        // 5) 分类：商户记忆（**只认用户亲手教过的**）→ 分类关键词 → 都猜不出就留 0，
        //    由上层记成「待分类」—— 猜不出来要让人看见，不要伪装成「其他」（v1.4.2 的教训）。
        var categoryId = 0L
        // 商户不可信（App 名 / 包名 / 银行名）时**不查记忆**：
        // 早年把「cmb.pb」「招商银行」这类键学进过 merchant_rule，一查就会把整批账单判成同一个类。
        val merchantForRule = if (isUsableMerchant(merchant, appName)) merchant else ""
        matchMerchantRule(merchantForRule, merchantRules)?.let { v ->
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
    // ⚠️ 注意 `(?:,\d{3})+` 是「+」不是「*」：
    // 如果写成 `*`，正则引擎会先用 `\d{1,3}` 贪婪吃掉前 3 位（"1000.00" → "100"），
    // 于是 1000 元被解析成 100 元（真机矩阵测试抓到的 bug）。
    private val AMOUNT_REGEX = Regex("""(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?""")

    /** 金额前面的「付费动词」：命中说明这个数字是这笔交易的主角 */
    private val STRONG_BEFORE = listOf(
        "消费", "支出", "支付", "付款", "扣款", "交易金额", "金额", "实付",
        "转账", "入账", "到账", "收入", "退款", "收款", "缴费", "存入", "转出", "转入",
        // 银行短信里的常见动词（漏一个，那条短信的金额就可能抓成 0）
        "取现", "取款", "年费", "保费", "手续费", "申购", "赎回", "扣收", "扣除", "收取",
        "还款", "工资", "报销", "赞赏", "退回", "待缴", "应缴", "支出金额"
    )
    private val CURRENCY_BEFORE = listOf("人民币", "¥", "￥")
    private val NEG_BEFORE = listOf(
        "余额", "额度", "可用", "剩余", "积分", "优惠", "立减", "抵扣",
        "满减", "折扣", "减免", "券", "限额", "冻结",
        // 编号类：这些词后面的长数字是单号，不是金额
        "保单号", "订单号", "流水号", "交易单号", "编号", "凭证号", "工号", "卡号"
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
            // 「余额/额度」降权：但「余额宝」「余额+」这类产品名不算（否则转入余额宝的金额永远抓不到）
            val beforeForNeg = before.replace("余额宝", "").replace("余额+", "")
            if (NEG_BEFORE.any { beforeForNeg.contains(it) }) score -= 20
            // 卡尾号降权：只在这个数字**紧邻**「尾号」时才降权（数字本身就是卡号），
            // 不能因为「尾号8888账户取现 500.00」里窗口含「尾号」就把 500 也降权
            val beforeTrimmed = before.trimEnd()
            val tailAdjacent = beforeTrimmed.endsWith("尾号") || beforeTrimmed.endsWith("尾") ||
                Regex("""(?:尾号|卡号|账户|账号)[:：]?\s*$""").containsMatchIn(beforeTrimmed)
            if (tailAdjacent) score -= 6
            if (before.any { it == ':' || it == '：' || it == '-' || it == '/' } ||
                before.contains("年") || before.contains("月") || before.contains("日")
            ) score -= 3
            // 字母+数字的编号（保单号 PA123456 / 订单 SF12345678）：紧邻 ASCII 字母的数字不是金额。
            // 注意不能用 isLetter()：中文字符也算 letter，会把所有中文短信的金额全跳过。
            val prev = if (start > 0) norm[start - 1] else ' '
            if (prev in 'A'..'Z' || prev in 'a'..'z') return@forEach
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
        // 强信号优先：渠道名里的「支付」（如"微信支付收款到账"）不该把收入判成支出
        val incStrong = listOf(
            "收款到账", "到账", "收到", "入账", "退款", "工资", "报销", "转入", "存入", "收益", "返现", "退回"
        ).count { text.contains(it) }
        val expStrong = listOf(
            "支付成功", "已支付", "付款成功", "已付款", "付款", "消费", "扣款", "支出", "缴费", "转出", "提现"
        ).count { text.contains(it) }
        if (incStrong > 0 && expStrong == 0) return TxnType.INCOME
        if (expStrong > 0 && incStrong == 0) return TxnType.EXPENSE
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
        // ⚠️ v1.4.4：招行手机银行的包名是 `cmb.pb`，原来只认 `cmbchina` 认不出来。
        // 后果很严重：招行短信「在【财付通-微信支付-微信零钱充值账户】发生快捷支付扣款」
        // 因为包里出现「微信」被匹配成**微信账户**，于是充值被记成「微信支出」——微信余额反而变少。
        pkg.contains("unionpay") || pkg.contains("bank") || pkg.contains("icbc") ||
            pkg.contains("ccb") || pkg.contains("abchina") || pkg.contains("boc") ||
            pkg.contains("cmb") || pkg.contains("spdb") || pkg.contains("citic") ||
            pkg.contains("psbc") || pkg.contains("cebbank") || pkg.contains("cib") -> AccountType.BANK
        else -> null
    }

    /**
     * 资金转移的「另一方」账户（v1.4.4）。
     *
     * 场景：银行卡 → 微信零钱（充值）、微信零钱 → 银行卡（提现）。这种是**双边转移**，
     * 只扣来源会让目标账户永远不增加，用户看到的就是「钱凭空少了一笔」。
     *
     * 从正文里认「另一方」：优先账户名原样出现，其次关键词出现；认不出来返回 null
     * （宁可只记单边，也不猜一个错账户去加钱）。
     */
    fun matchTransferTarget(text: String, accounts: List<Account>, sourceId: Long): Account? {
        var best: Account? = null
        var bestScore = 0
        accounts.filter { it.id != sourceId && it.id > 0 }.forEach { acc ->
            var score = 0
            if (acc.name.length >= 2 && text.contains(acc.name)) score += 10
            acc.keywords.split(",", "，").map { it.trim() }
                .filter { it.length >= 2 && !it.equals("银行", true) && !it.equals("钱包", true) }
                .forEach { kw ->
                    if (text.contains(kw, true)) score += 5
                }
            if (score > bestScore) {
                bestScore = score
                best = acc
            }
        }
        return if (bestScore >= 5) best else null
    }

    // ---------- 商户 ----------
    //
    // v1.4.2 修的两个真 bug（用真机数据定位的）：
    //  1. 招行这类「您账户3979于10月27日13:20在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款」
    //     原来只认「商户:xxx」标签 → 抽不到 → 退成 App 名「招商银行」；
    //  2. 退成 App 名以后还被当成真商户**学进 merchant_rule**，于是之后所有招行短信
    //     都被强制归到第一个撞对的分类（滴滴出行 → 餐饮）。见 isUsableMerchant + CapturePipeline.book。
    private val MERCHANT_PATTERNS = listOf(
        // 银行/支付通道最爱用的全角方括号：【财付通-微信支付-瑞幸咖啡】
        Regex("""【([^】]{2,30})】"""),
        Regex("""(?:付款方|收款方|商户名称|商户|商家)[:：]\s*(.{1,24})"""),
        Regex("""向(.{1,24}?)(?:付款|支付|转账)"""),
        Regex("""(?:在|于)(.{1,24}?)(?:消费|支付|购买)"""),
        Regex("""(?:付款给|转账给|收款人)[:：]?\s*(.{1,20}?)(?:[,,。\s]|$)"""),
        Regex("""(?:交易对方|对方)[:：]\s*(.{1,24})"""),
        Regex("""支付成功[-—\s]*(.{1,20}?)(?:[,,。\s]|$)""")
    )

    /** 「财付通-微信支付-瑞幸咖啡」里的渠道前缀，剥掉才是真商户 */
    private val CHANNEL_PREFIXES = listOf(
        "财付通-微信支付-", "财付通（微信支付）-", "财付通-", "微信支付-", "支付宝-",
        "云闪付-", "银联-", "快捷支付-", "京东支付-", "美团支付-", "抖音支付-"
    )

    /**
     * 这些**不是商户**，是 App / 渠道 / 银行本身。它们一旦被当成商户学进记忆，
     * 就会把该来源的所有账单锁死到同一个分类（v1.4.1 真机事故的根因）。
     */
    private val NOT_A_MERCHANT = listOf(
        "银行", "信用卡", "储蓄卡", "借记卡", "花呗", "白条", "余额宝", "零钱",
        "微信支付", "微信", "支付宝", "云闪付", "财付通", "银联", "快捷支付", "支付平台"
    )

    /** 全角/半角括号里的分店名：`兰溪手擀面（盛夏路店）` → `兰溪手擀面` */
    private val BRANCH_SUFFIX = Regex("""[（(][^）)]{0,20}店[）)]""")

    fun extractMerchant(text: String, fallback: String): String {
        for (p in MERCHANT_PATTERNS) {
            val raw = p.find(text)?.groupValues?.getOrNull(1) ?: continue
            val v = normalizeMerchant(raw)
            if (v.isEmpty() || v.length < 2) continue
            if (v.containsAny(PAY_CTX)) continue
            if (looksLikeNoise(v)) continue
            if (!isUsableMerchant(v, fallback)) continue
            return v
        }
        // 「微信支付收款0.01元(朋友到店)」这类写法，退化取括号前的说明
        val paren = Regex("""[(（](.{2,20})[)）]""").find(text)?.groupValues?.getOrNull(1)?.let { normalizeMerchant(it) }
        if (!paren.isNullOrBlank() && !paren.containsAny(PAY_CTX) && !looksLikeNoise(paren) &&
            isUsableMerchant(paren, fallback)
        ) return paren
        return fallback
    }

    /** 剥渠道前缀、去分店括号、去首尾标点 */
    fun normalizeMerchant(raw: String): String {
        var s = raw.trim().trim('的', ' ', ',', '.', ':', '，', '。', '、', '；', ';', '-', '【', '】')
        var changed = true
        while (changed) {
            changed = false
            for (p in CHANNEL_PREFIXES) {
                if (s.startsWith(p)) {
                    s = s.removePrefix(p).trim().trim('-', ':', '，', ' ')
                    changed = true
                }
            }
        }
        s = BRANCH_SUFFIX.replace(s, "").trim()
        return s
    }

    /**
     * 这个「商户」能不能拿去学习/展示为商户？
     * 纯 App 名、纯银行/渠道名、纯分店名（盛夏路店）一律不算 —— 它们学进去就是灾难。
     */
    fun isUsableMerchant(merchant: String, appName: String = ""): Boolean {
        val m = merchant.trim()
        if (m.length < 2) return false
        if (m.count { it.isDigit() } >= 3) return false
        if (looksLikeNoise(m)) return false
        if (appName.isNotBlank() && (m == appName || m.contains(appName) || appName.contains(m))) return false
        // 包名 / 域名这类纯 ASCII 带点的串不是商户。
        // 真机踩过：模拟/兜底时 merchant 会等于包名「cmb.pb」，而它早年又被学进过记忆，
        // 于是所有「抽不到商户」的短信都被强行判成那条记忆里的分类（餐饮）。
        if (m.contains('.') && m.none { it.code > 0x2E80 }) return false
        val norm = normalizeMerchant(m)
        // 用 contains 而不是 ==：真机上写进去的是「招商银行」「财付通-微信支付」这种带前缀的串
        if (NOT_A_MERCHANT.any { norm.contains(it) }) return false
        // 「盛夏路店」这种只有门店名的串，学进去等于把一条街的所有店并成一个分类。
        // 注意别误伤「携程酒店」「如家酒店」这类真商户 —— 只有带路/街/号/区/镇/城的地名才算分店名。
        if (norm.endsWith("店") && norm.length <= 6 &&
            norm.any { it == '路' || it == '街' || it == '号' || it == '区' || it == '镇' || it == '城' }
        ) return false
        return true
    }

    /**
     * 商户记忆匹配（只认用户亲手教过的商户名）。
     * 从「双向 contains」改成「完全相等 > 前缀/包含（规则名 ≥3 字）」：
     * 双向匹配会让一条 `微信` 的记忆吃掉所有微信账单，这是 v1.4.1 串味的直接原因。
     */
    fun matchMerchantRule(
        merchant: String,
        rules: Map<String, Pair<Long, Long>>
    ): Pair<Long, Long>? {
        val m = merchant.trim()
        if (m.length < 2 || rules.isEmpty()) return null
        // 老版本（v1.4.1 及以前）把「招商银行」「盛夏路店」这类垃圾键学进过 merchant_rule。
        // 这里直接把不合格的键过滤掉 —— 等于**自动中和历史污染**，不需要删数据库。
        val usable = rules.filterKeys { isUsableMerchant(it) }
        if (usable.isEmpty()) return null
        usable[m]?.let { return it }
        return usable.entries
            .filter { it.key.length >= 3 && m.contains(it.key, ignoreCase = true) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /** 日期、时间、卡号、金额这类「看起来是商户其实不是」的串要排掉 */
    private fun looksLikeNoise(v: String): Boolean {
        if (v.any { it == '年' || it == '月' || it == '日' || it == '时' || it == '号' }) return true
        if (v.count { it.isDigit() } >= 3) return true
        if (v.all { it.isDigit() || it == '-' || it == ':' || it == '.' || it == ' ' }) return true
        return false
    }

    // ---------- 分类 ----------
    /**
     * 分类关键词匹配。
     *
     * 打分**按词长加权**（长词更具体）：
     * - 商户命中：5 + 词长（「美团外卖」比「美团」更像答案）
     * - 正文命中：2 + 词长/2
     * - 分类名原样出现：+3
     *
     * 同分时比「命中的最长词」，再同分才按分类排序取先者 —— 避免短词把长词挤掉。
     * 全都命中不了返回 0（上层落「待分类」），**不要**退回「其他」。
     */
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
        var bestLen = 0
        pool.forEach { c ->
            var score = 0
            var longest = 0
            c.keywords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { kw ->
                val w = kw.length.coerceIn(2, 6)
                var hit = false
                if (merchant.contains(kw, true)) {
                    score += 5 + w
                    hit = true
                }
                if (text.contains(kw, true)) {
                    score += 2 + w / 2
                    hit = true
                }
                if (hit && kw.length > longest) longest = kw.length
            }
            if (c.name.isNotBlank() && text.contains(c.name)) {
                score += 3
                if (c.name.length > longest) longest = c.name.length
            }
            if (score > bestScore || (score == bestScore && score > 0 && longest > bestLen)) {
                bestScore = score
                bestId = c.id
                bestLen = longest
            }
        }
        if (bestId > 0L) return bestId
        // ⚠️ 返回 0 = 「真的猜不出来」。不要退回「其他」：
        // 那会让「没分类」伪装成「其他」，统计里看着很笼统，用户也没法知道哪些是猜的。
        // 上层 CapturePipeline 会把 0 落成一个独立的「待分类」分类。
        return 0L
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

    /** 规则表只读视图，只给测试/文档用（遍历覆盖） */
    internal object Tables {
        val junk: List<String> get() = JUNK
        val specUnits: List<String> get() = SPEC_UNIT_AFTER
        val incomeWords: List<String> get() = INCOME_WORDS
        val expenseWords: List<String> get() = EXPENSE_WORDS
        val strongPay: List<String> get() = STRONG_PAY
        val negBefore: List<String> get() = NEG_BEFORE
        val strongBefore: List<String> get() = STRONG_BEFORE
        val negAfter: List<String> get() = NEG_AFTER
        val payCtx: List<String> get() = PAY_CTX
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
