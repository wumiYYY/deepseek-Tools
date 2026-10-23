package com.jingyuniang.ledger.capture

/** 判断一条通知 / 一段页面文字值不值得交给解析引擎 */
object CaptureFilters {

    /** 已知的支付类应用包名 */
    val paymentPackages = setOf(
        "com.tencent.mm",                       // 微信
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
        "com.jd.jrapp",                         // 京东金融
        "com.sankuai.meituan",                  // 美团
        "com.sdu.didi.psnger",                  // 滴滴
        "com.taobao.taobao",                    // 淘宝
        "com.xunmeng.pinduoduo",                // 拼多多
        "com.ss.android.ugc.aweme",             // 抖音
        "com.ecny.noemie.pay"                   // 数字人民币
    )

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
    private val PAY_SOURCE_MARKERS = listOf(
        "微信支付", "QQ钱包", "QQ支付", "财付通", "支付宝", "云闪付", "银联",
        "钱包", "银行", "信用卡", "储蓄卡", "动账", "交易提醒", "支付通知", "账单"
    )

    /** 转账 / 红包这类「聊天里的钱卡片」：标题是对方昵称，但正文一定是这几种格式之一 */
    private val TRANSFER_MARKERS = listOf(
        "[转账]", "［转账］", "转账给你", "向你转账", "给你转账", "请收款", "已收款",
        "收款到账", "你收到一笔", "[微信红包]", "［微信红包］", "[红包]", "领取了你的红包",
        "转账已被接收", "待确认收款"
    )

    /** 银行短信的典型句式：出现这些词说明是「被转发进聊天群的银行文本」，绝不能记账 */
    private val BANK_TEXT_MARKERS = listOf(
        "尾号", "账户", "余额", "信用卡", "储蓄卡", "动账", "交易单号",
        "人民币", "入账", "扣款", "消费人民币", "可用额度"
    )

    /** 营销/广告词：银行 App 天天推「4元水电券」「立减8元」，绝不能算支出 */
    private val AD_MARKERS = listOf(
        "券", "劵", "立减", "满减", "满赠", "优惠", "特惠", "福利", "领取", "免费领",
        "抽奖", "活动", "限时", "到手价", "红包雨", "积分兑换", "权益", "专享", "专属",
        "分期", "提额", "额度提升", "推荐", "最高可", "最高立减", "先领", "抢购",
        "会员日", "补贴", "抵用", "抵扣", "礼包", "秒杀", "申请办卡"
    )

    /** 真正的动账证据：有这些词才可能是账单，没有就是广告 */
    private val PAY_EVIDENCE = listOf(
        "消费", "支出", "扣款", "支付成功", "已付款", "已扣", "存入",
        "转入", "转账", "还款", "退款", "提现", "工资", "利息", "结息", "缴费成功"
    )

    fun isSmsApp(pkg: String): Boolean = smsPackages.any { pkg.contains(it, true) } ||
        pkg.contains("mms", true) || pkg.contains("sms", true)

    fun isChatApp(pkg: String): Boolean = chatPackages.contains(pkg) ||
        pkg.contains("mobileqq", true) || pkg.contains("tim", true) || pkg.contains("wework", true)

    fun isPaymentApp(pkg: String): Boolean {
        if (isChatApp(pkg)) return false
        return paymentPackages.contains(pkg) ||
            pkg.contains("bank", true) || pkg.contains("alipay", true) || pkg.contains("wallet", true) ||
            pkg.contains("icbc", true) || pkg.contains("cmb", true) || pkg.contains("ccb", true) ||
            pkg.contains("pay", true)
    }

    /**
     * 各家的提示词都不一样，这里尽量放宽：
     * 微信「支付成功 / 已支付」、支付宝「你已成功付款 / 付款成功」、银行「消费 / 扣款 / 入账」。
     * 放宽的代价是可能多抓几条无关通知，但它们置信度低，只会进「待确认」，不会错记账。
     */
    private val STRONG_HINTS = listOf(
        "支付成功", "付款成功", "已成功付款", "成功付款", "已支付", "支付", "付款",
        "消费", "扣款", "支出", "入账", "到账", "转账", "收款", "退款",
        "交易金额", "实付", "缴费", "还款", "提现"
    )

    /**
     * @return true 表示值得解析
     * @param title 通知标题：聊天类 App 靠它区分「群消息」和「支付通知」
     */
    fun shouldRead(pkg: String, text: String, title: String = ""): Boolean {
        if (text.length < 6) return false
        if (ParseEngine.isJunk(text)) return false
        val hasMoney = text.contains('元') || text.contains('¥') || text.contains('￥')

        // 【广告闸门】营销词 + 无动账证据 → 直接丢掉。
        // 招商银行推的「4元水电券」就是这种，不能被当成 4 元支出。
        val adLike = AD_MARKERS.any { text.contains(it) || title.contains(it) }
        val payEvidence = PAY_EVIDENCE.any { text.contains(it) }
        if (adLike && !payEvidence) return false

        // 短信：银行动账短信基本都带金额
        if (isSmsApp(pkg)) return hasMoney || STRONG_HINTS.any { text.contains(it) }

        // 聊天类（微信/QQ/钉钉/飞书…）分两种情况：
        // ① 标题是「微信支付 / QQ钱包 / 招商银行」→ 真支付通知
        // ② 标题是人名/群名，但正文是「[转账]100.00元」「[微信红包]」这类钱卡片 → 也可信
        // ③ 标题是人名/群名，正文却是银行短信句式 → 是群里转发的文本，必须丢弃
        if (isChatApp(pkg)) {
            val titleOk = PAY_SOURCE_MARKERS.any { title.contains(it) }
            if (titleOk) return hasMoney && STRONG_HINTS.any { text.contains(it) }
            val transferLike = TRANSFER_MARKERS.any { text.contains(it) }
            val bankLike = BANK_TEXT_MARKERS.any { text.contains(it) }
            return transferLike && !bankLike
        }

        if (isPaymentApp(pkg)) return true
        return hasMoney && STRONG_HINTS.any { text.contains(it) }
    }

    /** 微信聊天里「我转你 100」这类也会冒出金额，做一层收紧 */
    fun wechatLikelyPayment(text: String): Boolean {
        if (!text.contains('元') && !text.contains('¥') && !text.contains('￥')) return false
        return text.contains("微信支付") || text.contains("支付成功") || text.contains("付款成功") ||
            text.contains("已支付") || text.contains("收款到账") || text.contains("转账") ||
            text.contains("零钱") || text.contains("支付凭证")
    }
}
