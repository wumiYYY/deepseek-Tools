package com.jingyuniang.ledger.capture

/**
 * 判断一条通知 / 一段页面文字值不值得交给解析引擎。
 *
 * ## 为什么要区分 App 类型（真机反馈驱动）
 * 早期实现把所有「可能付钱」的 App 塞进一个白名单，然后对白名单里的 App 无条件放行
 * （`if (isPaymentApp(pkg)) return true`）。结果：
 * - 淘宝的商品推荐「925银精炼镶嵌」被抠成 925 元；
 * - 抖音/快手刷视频时的「9.9元包邮」「直播间3元券」被当成支出，待确认成堆；
 * - 银行 App 的「本期账单 1234.56 元」也进账。
 *
 * 现在按**通知的性质**分三类，各用不同门槛：
 *
 * | 类型 | 例子 | 通知里「钱」的占比 | 门槛 |
 * |---|---|---|---|
 * | 支付通道 `PAY_RAIL` | 微信支付、支付宝、云闪付、各行 App | 高 | 有金额 + 支付动词 + 结构证据（余额/单号/商户/尾号） |
 * | 电商/本地生活 `COMMERCE` | 淘宝、京东、拼多多、美团、饿了么、滴滴 | 低（多数是营销） | 必须命中**强支付模板**（支付成功/已付款/订单支付成功…） |
 * | 内容/短视频 `CONTENT` | 抖音、快手、小红书、微博、B站、爱奇艺、优酷 | 极低（几乎全是内容推荐） | 同上，且默认不读屏 |
 * | 其他 | — | — | 金额 + 支付动词 |
 *
 * 另外还有四道闸门：渠道过滤（营销渠道直接丢）、非动账模板（账单/额度/营销）、
 * 账单列表页（≥3 笔金额）、聊天类转发识别。
 */
object CaptureFilters {

    /** 真正的「钱的路由」：微信支付 / 支付宝 / 云闪付 / 银行 App / 数字人民币 */
    val PAY_RAIL_PACKAGES = setOf(
        "com.tencent.mm",                       // 微信（走聊天类特殊判定）
        "com.eg.android.AlipayGphone",          // 支付宝
        "com.unionpay",                         // 云闪付
        "com.unionpay.tsmservice",              // 云闪付组件
        "com.icbc", "com.icbc.im",              // 工行
        "com.chinamworld.main",                 // 建行
        "com.chinamworld.bocmbci",              // 中行
        "com.android.bankabc",                  // 农行
        "cmb.pb",                               // 招行
        "com.bankcomm.Bankcomm",                // 交行
        "com.yitong.mbank.psbc",                // 邮储
        "com.spdb.mobilebank",                  // 浦发
        "com.ecitic.bank.mobile",               // 中信
        "com.cmbc.cc.mbank",                    // 民生
        "com.cebbank.mobile.cemb",              // 光大
        "com.pingan.paces.ccms",                // 平安
        "com.cib.xyk",                          // 兴业
        "com.jd.jrapp",                         // 京东金融（钱包）
        "com.ecny.noemie.pay"                   // 数字人民币
    )

    /**
     * 电商 / 本地生活：这些 App 的通知绝大多数是营销推送，
     * 只有「支付成功 / 已付款 / 订单支付成功」这类模板才可能是真动账。
     */
    val COMMERCE_PACKAGES = setOf(
        "com.taobao.taobao", "com.tmall.wireless",          // 淘宝 / 天猫
        "com.jingdong.app.mall", "com.jd.jrapp",            // 京东
        "com.xunmeng.pinduoduo",                            // 拼多多
        "com.taobao.idlefish",                              // 闲鱼
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", // 美团
        "me.ele",                                           // 饿了么
        "com.sdu.didi.psnger", "com.sdu.didi.gsui",         // 滴滴
        "com.shizhuang.duapp", "com.vipshop.app",           // 得物 / 唯品会
        "com.taobao.litetao", "com.jd.jm",                  // 淘特 / 京喜
        "com.achievo.vipshop", "com.zhenai.dating"          // 兼容部分渠道包名（保守）
    )

    /**
     * 内容 / 短视频 / 长视频 / 社区：刷这些 App 时通知基本全是内容推荐，
     * **默认不抓**（只有命中强支付模板才放行），并且**不做读屏**。
     */
    val CONTENT_PACKAGES = setOf(
        "com.ss.android.ugc.aweme",             // 抖音
        "com.ss.android.ugc.aweme.lite",        // 抖音极速版
        "com.smile.gifmaker", "com.kuaishou.nebula", // 快手 / 极速版
        "com.xingin.xhs",                       // 小红书
        "com.sina.weibo",                       // 微博
        "tv.danmaku.bili", "com.bilibili.app.in", // B 站
        "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone", // 长视频
        "com.google.android.youtube",           // YouTube
        "com.dragon.read", "com.ss.android.article.news", // 番茄小说 / 今日头条
        "com.zhihu.android"                     // 知乎
    )

    /** 兼容旧调用：曾经「可能付钱」的 App 合集 = 支付通道 + 电商 */
    val paymentPackages: Set<String> get() = PAY_RAIL_PACKAGES + COMMERCE_PACKAGES

    /** 短信类应用 */
    private val smsPackages = listOf(
        "com.android.mms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.miui.smsextra",
        "com.huawei.message", "com.android.messaging", "com.coloros.mms", "com.oneplus.mms"
    )

    /**
     * 聊天类应用：这些 App 的通知绝大部分是「聊天消息」，
     * 群里别人贴一段银行短信文本，会被误当成真实扣款 —— 必须区别对待。
     */
    private val chatPackages = setOf(
        "com.tencent.mm",              // 微信
        "com.tencent.mobileqq",        // QQ
        "com.tencent.qqlite",          // QQ 轻聊
        "com.tencent.tim",             // TIM
        "com.tencent.wework",          // 企业微信
        "com.alibaba.android.rimet",   // 钉钉
        "com.ss.android.lark",         // 飞书
        "org.telegram.messenger",
        "com.whatsapp",
        "com.facebook.orca",
        "com.tencent.androidqqmail"    // QQ 邮箱
    )

    /** 真正的支付通知，标题一定是这些「发送方」，而不是群名/人名 */
    private val PAY_SOURCE_MARKERS: List<String> get() = RuleStore.current.paySourceMarkers
    /** 转账 / 红包这类「聊天里的钱卡片」：标题是对方昵称，但正文一定是这几种格式之一 */
    private val TRANSFER_MARKERS: List<String> get() = RuleStore.current.transferMarkers
    /** 银行短信的典型句式：出现这些词说明是「被转发进聊天群的银行文本」，绝不能记账 */
    private val BANK_TEXT_MARKERS: List<String> get() = RuleStore.current.bankTextMarkers
    /** 营销/广告词：银行 App 天天推「4元水电券」「立减8元」，绝不能算支出 */
    private val AD_MARKERS: List<String> get() = RuleStore.current.adMarkers
    /**
     * 「不是动账」的模板词：这些通知**一定带金额**，但它们是账单/提醒/营销，不是刚发生的交易。
     * 这是「银行推送一堆待确认」的主要来源，命中即丢。
     */
    private val NON_TXN_MARKERS: List<String> get() = RuleStore.current.nonTxnMarkers
    /**
     * 信用卡还款类：这是**资金转移**（储蓄卡 → 信用卡），不是消费。
     *
     * 为什么不直接丢掉：
     * - 记成「支出」会让本月支出虚增（钱只是从自己的一个口袋挪到另一个口袋）；
     * - 完全不记，用户又看不到「我还了 1000」这件事；
     * - 所以正确做法是记成 `TxnType.TRANSFER`：不计入收支/预算，但保留流水、且能正确影响两个账户的余额。
     *
     * 没有信用卡账户时（用户还没建信用卡），没法组成「A → B」，此时丢弃并说明原因。
     */
    private val REPAYMENT_MARKERS = listOf(
        "信用卡还款", "还款成功", "还款入账", "已还款", "自动还款", "还信用卡",
        "还款金额", "本期还款", "账单还款"
    )

    /**
     * 资金转移类（不计收支）：信用卡还款、零钱提现到卡、转账到银行卡。
     * 这些「钱只是从自己一个口袋挪到另一个口袋」，记成支出会虚增、记成收入会虚增收入。
     */
    private val FUND_TRANSFER_MARKERS = REPAYMENT_MARKERS + listOf(
        "零钱提现", "提现到银行卡", "提现到卡", "向银行卡转账", "转到银行卡", "转存", "转入余额宝", "余额宝转出",
        // ATM 取现：银行 → 现金，净资产不变
        "ATM取现", "ATM取款", "取现", "现金提取",
        // 理财申购/赎回：现金 ↔ 理财，净资产不变
        "申购", "赎回", "买入基金", "基金买入", "理财购买", "购买理财", "定投扣款"
    )

    /** 是不是信用卡还款（资金转移，不是消费） */
    fun isRepayment(text: String): Boolean = REPAYMENT_MARKERS.any { text.contains(it) }

    /** 退款类关键词（用于 P1-5：退款应冲掉原支出，而不是新记一笔收入） */
    private val REFUND_MARKERS = listOf(
        "退款", "退货退款", "已退款", "退款到账", "退回", "原路退回", "冲正", "撤销交易"
    )

    /** 是不是一笔退款 */
    fun isRefund(text: String): Boolean = REFUND_MARKERS.any { text.contains(it) }

    /** 是不是资金转移（还款 / 提现到卡 / 转存）——应记成 TRANSFER */
    fun isFundTransfer(text: String): Boolean = FUND_TRANSFER_MARKERS.any { text.contains(it) }

    /** 真正的动账证据：有这些词才可能是账单，没有就是广告 */
    private val PAY_EVIDENCE: List<String> get() = RuleStore.current.payEvidence
    /**
     * 强支付模板：**电商/内容类 App 只有命中这些**才可能是真动账。
     * 注意不含单独的「支付」「付款」（"支持花呗付款"这种营销会误命中）。
     */
    private val STRICT_PAY_TEMPLATE: List<String> get() = RuleStore.current.strongPayTemplate
    /**
     * 结构证据：证明「这是一笔刚发生的交易」而不是账单/营销。
     * 至少要有其中一项，否则金额再大也只当可疑（进待确认，不自动入账）。
     */
    private val TXN_EVIDENCE: List<String> get() = RuleStore.current.txnEvidence
    /** 支付结果页的强文案（读屏只在被这些词命中时才认） */
    private val RESULT_PAGE_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "已支付", "已付款", "付款完成", "支付完成",
        "收款到账", "到账成功", "交易已完成", "支付凭证"
    )

    /** 营销类通知渠道名（银行 App 的营销推送几乎都单独占一个渠道） */
    private val MARKETING_CHANNELS: List<String> get() = RuleStore.current.marketingChannels
    /** 金额出现次数的粗计数（带单位/币符），用来识别「账单列表页」 */
    private val AMOUNT_WITH_UNIT = Regex("""(?:[¥￥]\s*\d[\d,]*(?:\.\d{1,2})?)|(?:\d[\d,]*(?:\.\d{1,2})?\s*[元块])""")

    fun isSmsApp(pkg: String): Boolean = smsPackages.any { pkg.contains(it, true) } ||
        pkg.contains("mms", true) || pkg.contains("sms", true)

    fun isChatApp(pkg: String): Boolean = chatPackages.contains(pkg) ||
        pkg.contains("mobileqq", true) || pkg.contains("tim", true) || pkg.contains("wework", true)

    /** 支付通道：微信/支付宝/云闪付/银行 App（注意微信仍是聊天类，走特殊判定） */
    fun isPayRail(pkg: String): Boolean {
        if (isChatApp(pkg)) return false
        if (PAY_RAIL_PACKAGES.contains(pkg)) return true
        val p = pkg.lowercase()
        return p.contains("bank") || p.contains("alipay") || p.contains("unionpay") ||
            p.contains("icbc") || p.contains("cmb") || p.contains("ccb") || p.contains("boc") ||
            p.contains("abchina") || p.contains("spdb") || p.contains("citic") ||
            p.contains("cmbc") || p.contains("cebbank") || p.contains("psbc") ||
            p.contains("bankcomm") || p.contains("wallet")
    }

    fun isCommerceApp(pkg: String): Boolean =
        COMMERCE_PACKAGES.contains(pkg) ||
            pkg.contains("taobao", true) || pkg.contains("tmall", true) ||
            pkg.contains("jingdong", true) || pkg.contains("pinduoduo", true) ||
            pkg.contains("meituan", true) || pkg.contains("didi", true)

    fun isContentApp(pkg: String): Boolean =
        CONTENT_PACKAGES.contains(pkg) ||
            pkg.contains("aweme", true) || pkg.contains("kuaishou", true) ||
            pkg.contains("gifmaker", true) || pkg.contains("xhs", true) ||
            pkg.contains("weibo", true) || pkg.contains("bili", true)

    /** 支付类 App（旧语义：支付通道 + 电商），保留给统计/诊断用 */
    fun isPaymentApp(pkg: String): Boolean = isPayRail(pkg) || isCommerceApp(pkg)

    /** 值得在「通知体检」里展示的 App（含内容类，方便排查「为什么这条没抓」） */
    fun isWatchedApp(pkg: String): Boolean =
        isPayRail(pkg) || isCommerceApp(pkg) || isContentApp(pkg) || isSmsApp(pkg)

    /**
     * 各家的提示词都不一样，这里尽量放宽：
     * 微信「支付成功 / 已支付」、支付宝「你已成功付款 / 付款成功」、银行「消费 / 扣款 / 入账」。
     * 放宽的代价是可能多抓几条无关通知，但它们置信度低，只会进「待确认」，不会错记账。
     */
    private val STRONG_HINTS: List<String> get() = RuleStore.current.strongHints
    /** 文本里「带单位的金额」出现几次（列表页一般 ≥3） */
    fun amountCount(text: String): Int = AMOUNT_WITH_UNIT.findAll(text).count()

    /** 账单列表页 / 明细页：一屏里出现多笔金额，绝不是「一条交易通知」 */
    fun looksLikeStatementList(text: String): Boolean = amountCount(text) >= 3

    /** 文本是否命中「强支付模板」（支付成功/已付款/已扣费…） */
    fun isStrongPayTemplate(text: String): Boolean = STRICT_PAY_TEMPLATE.any { text.contains(it) }

    /** 通知标题是否是支付发送方（「微信支付」「支付宝」「招商银行」…） */
    fun isPaySourceTitle(title: String): Boolean =
        title.isNotBlank() && PAY_SOURCE_MARKERS.any { title.contains(it) }

    /** 有结构证据？没有就说明只是「一句带金额的话」，最多进待确认 */
    fun hasTxnEvidence(text: String): Boolean = TXN_EVIDENCE.any { text.contains(it) }

    /** 命中「非动账模板」（账单/额度/营销） */
    fun isNonTxnNotice(text: String): Boolean = NON_TXN_MARKERS.any { text.contains(it) }

    /** 营销/推广渠道 */
    fun isMarketingChannel(channelId: String?, channelName: String?): Boolean {
        val s = listOfNotNull(channelId, channelName).joinToString(" ").lowercase()
        if (s.isBlank()) return false
        return MARKETING_CHANNELS.any { s.contains(it.lowercase()) }
    }

    /** 是否是「支付结果页」文案（读屏只信这个） */
    fun isPaymentResultPage(text: String): Boolean =
        text.length <= 260 && RESULT_PAGE_MARKERS.any { text.contains(it) }

    /**
     * @return true 表示值得解析
     * @param title 通知标题：聊天类 App 靠它区分「群消息」和「支付通知」
     * @param channelId / channelName 通知渠道（银行 App 的营销推送单独占渠道，是强信号）
     */
    fun shouldRead(
        pkg: String,
        text: String,
        title: String = "",
        channelId: String? = null,
        channelName: String? = null
    ): Boolean = judge(pkg, text, title, channelId, channelName).ok

    /** 判定结果 + 一句人话原因（给「抓取记录」列表展示，解决「为什么这条没抓」） */
    data class Verdict(val ok: Boolean, val reason: String)

    fun judge(
        pkg: String,
        text: String,
        title: String = "",
        channelId: String? = null,
        channelName: String? = null
    ): Verdict {
        if (text.length < 6) return Verdict(false, "内容太短，不像交易通知")
        if (ParseEngine.isJunk(text)) return Verdict(false, "验证码/物流/活动等无关通知")

        // 【渠道闸门】营销渠道直接丢
        if (isMarketingChannel(channelId, channelName)) return Verdict(false, "营销/推广类通知渠道")

        // 短信来源放宽：银行动账短信常省略「元」（"金额为500.00，余额为8000.00"），
        // 只要出现数字就当有金额（短信本身最可靠，且前面已过垃圾词/广告/非动账三道门）
        val hasDigits = text.any { it.isDigit() }
        val hasMoney = text.contains('元') || text.contains('¥') || text.contains('￥') ||
            text.contains("人民币") || (isSmsApp(pkg) && hasDigits)
        // 红包/转账卡片可能只有「[微信红包]」没有金额：放行给解析引擎，解析不出金额自然不会记账
        val transferCard = isChatApp(pkg) && TRANSFER_MARKERS.any { text.contains(it) }
        if (!hasMoney && !transferCard) return Verdict(false, "没有金额信息")

        // 【聊天里的钱卡片】必须排在广告门之前：
        // 「[转账] 领取了你的红包」里的「领取」是广告词表里的词，但这条是真红包
        if (isChatApp(pkg) && TRANSFER_MARKERS.any { text.contains(it) } &&
            !BANK_TEXT_MARKERS.any { text.contains(it) }
        ) return Verdict(true, "聊天里的转账/红包卡片")

        // 【广告闸门】营销词 + 无动账证据 → 丢掉
        val adLike = AD_MARKERS.any { text.contains(it) || title.contains(it) }
        val payEvidence = PAY_EVIDENCE.any { text.contains(it) }
        if (adLike && !payEvidence) return Verdict(false, "营销广告（命中「${AD_MARKERS.firstOrNull { text.contains(it) || title.contains(it) }}」）")

        // 【资金转移】放行，由 pipeline 记成 TRANSFER（不计收支）
        if (isFundTransfer(text)) return Verdict(hasMoney, "资金转移（还款/提现/理财），记成转账不计收支")


        // 【非动账模板】账单提醒 / 额度调整 / 营销：即使带「消费」「还款」也不记账
        if (isNonTxnNotice(text)) return Verdict(false, "账单/额度/营销类提醒，不是刚发生的交易（命中「${NON_TXN_MARKERS.firstOrNull { text.contains(it) }}」）")

        // 【列表页闸门】滑动账单页会在一次事件里塞进好几笔金额 → 整段丢弃
        if (looksLikeStatementList(text)) return Verdict(false, "账单列表页（出现 ${amountCount(text)} 笔金额），不是单条交易")

        // 短信：银行动账短信基本都带金额，最可靠，放行
        if (isSmsApp(pkg)) return Verdict(true, "银行/服务短信")

        // 聊天类（微信/QQ/钉钉/飞书…）：
        // ① 标题是「微信支付 / QQ钱包 / 招商银行」→ 真支付通知
        // ② 标题是人名/群名，正文是「[转账]100.00元」这类钱卡片 → 也可信
        // ③ 标题是人名/群名，正文却是银行短信句式 → 群里转发的文本，必须丢弃
        if (isChatApp(pkg)) {
            val titleOk = PAY_SOURCE_MARKERS.any { title.contains(it) }
            if (titleOk) return if (STRONG_HINTS.any { text.contains(it) }) Verdict(true, "支付类通知（发送方是「$title」）") else Verdict(false, "支付发送方的非动账消息")
            val bankLike = BANK_TEXT_MARKERS.any { text.contains(it) }
            if (TRANSFER_MARKERS.any { text.contains(it) } && !bankLike) return Verdict(true, "聊天里的转账/红包卡片")
            // 少数 ROM 会把「微信支付」这个发送方塞进正文、标题留空：
            // 文本本身是支付结果模板 + 不像群里的银行短信 → 放行（仍会进待确认，不会自动入账）
            return if (STRICT_PAY_TEMPLATE.any { text.contains(it) } && !bankLike) Verdict(true, "支付结果文案") else Verdict(false, "聊天消息（非支付）")
        }

        // 【内容/短视频/电商】刷视频时的「9.9元包邮」「直播间3元券」绝不能进账：
        // 只认「支付成功 / 已付款 / 订单支付成功」这类强模板
        if (isContentApp(pkg) || isCommerceApp(pkg)) {
            return if (STRICT_PAY_TEMPLATE.any { text.contains(it) }) Verdict(true, "App 内的支付成功通知") else Verdict(false, "内容/电商 App 的推送，非支付结果")
        }

        // 支付通道（银行 / 云闪付等）：必须有支付动词
        if (isPayRail(pkg)) {
            return if (STRONG_HINTS.any { text.contains(it) }) Verdict(true, "支付/银行类通知") else Verdict(false, "支付类 App 的非动账消息")
        }

        return if (hasMoney && STRONG_HINTS.any { text.contains(it) }) Verdict(true, "含金额与支付动词") else Verdict(false, "无可识别的支付信息")
    }

    /**
     * 规则表只读视图 —— **只给测试和文档用**。
     * 测试会遍历这里的每一条并断言其行为，所以删/改任何一条规则都会立刻让测试失败（防止悄悄放松规则）。
     */
    internal object Tables {
        val adMarkers: List<String> get() = AD_MARKERS
        val nonTxnMarkers: List<String> get() = NON_TXN_MARKERS
        val repaymentMarkers: List<String> get() = REPAYMENT_MARKERS
        val fundTransferMarkers: List<String> get() = FUND_TRANSFER_MARKERS
        val strongPayTemplate: List<String> get() = STRICT_PAY_TEMPLATE
        val txnEvidence: List<String> get() = TXN_EVIDENCE
        val payEvidence: List<String> get() = PAY_EVIDENCE
        val paySourceMarkers: List<String> get() = PAY_SOURCE_MARKERS
        val transferMarkers: List<String> get() = TRANSFER_MARKERS
        val bankTextMarkers: List<String> get() = BANK_TEXT_MARKERS
        val strongHints: List<String> get() = STRONG_HINTS
        val marketingChannels: List<String> get() = MARKETING_CHANNELS
        val payRailPackages: Set<String> get() = PAY_RAIL_PACKAGES
        val commercePackages: Set<String> get() = COMMERCE_PACKAGES
        val contentPackages: Set<String> get() = CONTENT_PACKAGES
        val refundMarkers: List<String> get() = REFUND_MARKERS
    }

    /**
     * 这条文本指向哪个第三方支付渠道（用于「微信付款但实际扣银行卡」时合并两条通知）。
     * 银行动账短信/推送里通常写明「财付通-微信支付-商户」「支付宝-商户」，这是最可靠的跨渠道线索。
     */
    fun payChannelOf(text: String): String? {
        // 注意顺序：先判断「微信支付/财付通」，再判断「支付宝」（两者不会同时出现）
        if (text.contains("财付通") || text.contains("微信支付") || text.contains("微信-")) return "wechat"
        if (text.contains("支付宝")) return "alipay"
        if (text.contains("云闪付") || text.contains("银联")) return "unionpay"
        if (text.contains("京东支付")) return "jd"
        return null
    }

    /** 该渠道对应的 App 包名（用来找「同渠道 App 是否已经报过同一笔」） */
    fun payChannelPackage(channel: String): String? = when (channel) {
        "wechat" -> "com.tencent.mm"
        "alipay" -> "com.eg.android.AlipayGphone"
        "unionpay" -> "com.unionpay"
        "jd" -> "com.jd.jrapp"
        else -> null
    }

    /** 微信聊天里「我转你 100」这类也会冒出金额，做一层收紧 */
    fun wechatLikelyPayment(text: String): Boolean {
        if (!text.contains('元') && !text.contains('¥') && !text.contains('￥')) return false
        return text.contains("微信支付") || text.contains("支付成功") || text.contains("付款成功") ||
            text.contains("已支付") || text.contains("收款到账") || text.contains("转账") ||
            text.contains("零钱") || text.contains("支付凭证")
    }
}
