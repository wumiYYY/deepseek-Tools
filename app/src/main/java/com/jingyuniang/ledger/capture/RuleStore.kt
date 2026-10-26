package com.jingyuniang.ledger.capture

import android.content.Context
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 规则库（**可编辑**）。
 *
 * 三级来源，从高到低：
 * 1. `filesDir/capture_rules.json` —— 用户自己的规则（App 内「导出规则」后改文件，重开 App 生效）
 * 2. `assets/capture_rules.json`   —— 随 App 发布的默认规则
 * 3. 代码里的 [DEFAULTS]            —— 连 assets 都读不到时的兜底（保证 App 永远能用）
 *
 * 任何一级解析失败都会**静默降级到下一级**并记日志 —— 改坏一个逗号不该让 App 抓不到账。
 *
 * 用户改词的入口：设置 → 数据导入导出 → 「导出识别规则」，
 * 手机上下载目录会得到 `capture_rules.json`，改完放回 App 私有目录（App 内提供 import 入���）。
 */
object RuleStore {

    /** 一份完整规则 */
    data class Rules(
        val version: Int = 1,
        val source: String = "内置",
        val adMarkers: List<String> = emptyList(),
        val nonTxnMarkers: List<String> = emptyList(),
        val fundTransferMarkers: List<String> = emptyList(),
        val strongPayTemplate: List<String> = emptyList(),
        val txnEvidence: List<String> = emptyList(),
        val payEvidence: List<String> = emptyList(),
        val paySourceMarkers: List<String> = emptyList(),
        val transferMarkers: List<String> = emptyList(),
        val bankTextMarkers: List<String> = emptyList(),
        val strongHints: List<String> = emptyList(),
        val marketingChannels: List<String> = emptyList(),
        val payRailPackages: List<String> = emptyList(),
        val commercePackages: List<String> = emptyList(),
        val contentPackages: List<String> = emptyList(),
        val junk: List<String> = emptyList(),
        val specUnits: List<String> = emptyList(),
        val incomeWords: List<String> = emptyList(),
        val expenseWords: List<String> = emptyList()
    ) {
        /** 规则总条数（设置页展示用） */
        val totalEntries: Int get() = adMarkers.size + nonTxnMarkers.size + fundTransferMarkers.size +
            strongPayTemplate.size + txnEvidence.size + payEvidence.size + paySourceMarkers.size +
            transferMarkers.size + bankTextMarkers.size + strongHints.size + marketingChannels.size +
            payRailPackages.size + commercePackages.size + contentPackages.size +
            junk.size + specUnits.size + incomeWords.size + expenseWords.size

        /**
         * 自检：每条规则都不能为空、不能是空串。
         * 用户可能改坏文件，这里在加载时就发现问题并降级。
         */
        fun validate(): String? {
            val tables = listOf(
                "adMarkers" to adMarkers, "nonTxnMarkers" to nonTxnMarkers,
                "fundTransferMarkers" to fundTransferMarkers, "strongPayTemplate" to strongPayTemplate,
                "txnEvidence" to txnEvidence, "payEvidence" to payEvidence,
                "paySourceMarkers" to paySourceMarkers, "transferMarkers" to transferMarkers,
                "bankTextMarkers" to bankTextMarkers, "strongHints" to strongHints,
                "marketingChannels" to marketingChannels, "payRailPackages" to payRailPackages,
                "commercePackages" to commercePackages, "contentPackages" to contentPackages,
                "junk" to junk, "specUnits" to specUnits,
                "incomeWords" to incomeWords, "expenseWords" to expenseWords
            )
            tables.forEach { (name, list) ->
                if (list.isEmpty()) return "规则表 $name 为空"
                list.firstOrNull { it.isBlank() }?.let { return "规则表 $name 里有空词条" }
            }
            // 关键表要有底线数量，避免用户误删到只剩一条还「以为生效了」
            if (adMarkers.size < 10) return "营销词太少（${adMarkers.size} 条），疑似文件被截断"
            if (nonTxnMarkers.size < 10) return "非动账模板太少（${nonTxnMarkers.size} 条），疑似文件被截断"
            if (junk.size < 10) return "垃圾词太少（${junk.size} 条），疑似文件被截断"
            return null
        }
    }

    @Volatile
    private var cached: Rules? = null

    /** 当前生效规则（懒加载，只解析一次） */
    val current: Rules
        get() = cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

    /** 用户规则文件路径 */
    fun userFile(context: Context = Services.appContext): File =
        File(context.filesDir, "capture_rules.json")

    fun reload(): Rules = synchronized(this) {
        cached = load()
        cached!!
    }

    private fun load(): Rules {
        val ctx = runCatching { Services.appContext }.getOrNull()
        // 1) 用户文件
        if (ctx != null) {
            val uf = userFile(ctx)
            if (uf.exists()) {
                parseOrNull(uf.readText(), "用户文件（${uf.name}）")?.let {
                    AppLog.i("规则库：使用用户文件，共 ${it.totalEntries} 条")
                    return it
                }
                AppLog.w("用户规则文件解析失败，回退到内置规则")
            }
        }
        // 2) assets
        if (ctx != null) {
            runCatching {
                ctx.assets.open("capture_rules.json").bufferedReader().use { it.readText() }
            }.getOrNull()?.let { text ->
                parseOrNull(text, "App 内置")?.let {
                    AppLog.i("规则库：使用内置 assets，共 ${it.totalEntries} 条")
                    return it
                }
                AppLog.w("assets 规则解析失败，回退到代码默认值")
            }
        }
        // 3) 代码默认
        AppLog.w("规则库：使用代码内默认值")
        return DEFAULTS
    }

    internal fun parseOrNull(text: String, source: String): Rules? = runCatching {
        val o = JSONObject(text)
        fun arr(name: String): List<String> =
            o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

        val r = Rules(
            version = o.optInt("version", 1),
            source = source,
            adMarkers = arr("adMarkers"),
            nonTxnMarkers = arr("nonTxnMarkers"),
            fundTransferMarkers = arr("fundTransferMarkers"),
            strongPayTemplate = arr("strongPayTemplate"),
            txnEvidence = arr("txnEvidence"),
            payEvidence = arr("payEvidence"),
            paySourceMarkers = arr("paySourceMarkers"),
            transferMarkers = arr("transferMarkers"),
            bankTextMarkers = arr("bankTextMarkers"),
            strongHints = arr("strongHints"),
            marketingChannels = arr("marketingChannels"),
            payRailPackages = arr("payRailPackages"),
            commercePackages = arr("commercePackages"),
            contentPackages = arr("contentPackages"),
            junk = arr("junk"),
            specUnits = arr("specUnits"),
            incomeWords = arr("incomeWords"),
            expenseWords = arr("expenseWords")
        )
        val err = r.validate()
        if (err != null) {
            AppLog.w("规则文件校验失败（$source）：$err")
            return null
        }
        r
    }.onFailure { AppLog.e("规则文件解析异常（$source）", it) }.getOrNull()

    /** 把规则导出成 JSON 字符串（给「导出识别规则」用） */
    fun exportJson(rules: Rules = current): String {
        fun arr(l: List<String>) = JSONArray().also { a -> l.forEach { a.put(it) } }
        return JSONObject().apply {
            put("version", rules.version)
            put("updatedAt", "2026-10-26")
            put("_comment", "改完放回 App 私有目录的 capture_rules.json（或用 App 内「导入识别规则」），重开 App 生效")
            put("adMarkers", arr(rules.adMarkers))
            put("nonTxnMarkers", arr(rules.nonTxnMarkers))
            put("fundTransferMarkers", arr(rules.fundTransferMarkers))
            put("strongPayTemplate", arr(rules.strongPayTemplate))
            put("txnEvidence", arr(rules.txnEvidence))
            put("payEvidence", arr(rules.payEvidence))
            put("paySourceMarkers", arr(rules.paySourceMarkers))
            put("transferMarkers", arr(rules.transferMarkers))
            put("bankTextMarkers", arr(rules.bankTextMarkers))
            put("strongHints", arr(rules.strongHints))
            put("marketingChannels", arr(rules.marketingChannels))
            put("payRailPackages", arr(rules.payRailPackages))
            put("commercePackages", arr(rules.commercePackages))
            put("contentPackages", arr(rules.contentPackages))
            put("junk", arr(rules.junk))
            put("specUnits", arr(rules.specUnits))
            put("incomeWords", arr(rules.incomeWords))
            put("expenseWords", arr(rules.expenseWords))
        }.toString(2)
    }

    /**
     * 导入用户规则文件（写入私有目录并立即生效）。
     * @return null 表示成功；否则是失败原因
     */
    fun importFrom(context: Context, bytes: ByteArray): String? {
        val text = String(bytes, Charsets.UTF_8)
        val parsed = parseOrNull(text, "导入") ?: return "文件内容不是有效的规则（JSON 格式或词条有问题）"
        return runCatching {
            userFile(context).writeText(text)
            cached = parsed
            AppLog.i("规则导入成功，共 ${parsed.totalEntries} 条")
            null
        }.getOrElse { "写入失败：${it.message}" }
    }

    /** 删除用户规则，回到内置 */
    fun resetToBuiltIn(context: Context): Boolean = runCatching {
        userFile(context).delete()
        reload()
        true
    }.getOrDefault(false)

    // ---------------- 单张表的读写（「说人话版规则」用来原地加词） ----------------

    /** 18 张表的 JSON 键名，顺序与设置页展示一致 */
    val TABLE_KEYS: List<String> = listOf(
        "adMarkers", "nonTxnMarkers", "fundTransferMarkers", "strongPayTemplate",
        "txnEvidence", "payEvidence", "paySourceMarkers", "transferMarkers",
        "bankTextMarkers", "strongHints", "marketingChannels", "payRailPackages",
        "commercePackages", "contentPackages", "junk", "specUnits",
        "incomeWords", "expenseWords"
    )

    /** 某张表当前有多少条词（UI 展示用；键名不认识返回 0） */
    fun countOf(key: String, rules: Rules = current): Int = rules.table(key)?.size ?: 0

    /**
     * 往规则里加词并**立即生效**（写用户规则文件 + 更新缓存）。
     * @return null = 成功；否则是失败原因
     */
    fun addWords(context: Context, tableKey: String, words: List<String>): String? {
        val base = current
        val next = base.appendWords(tableKey, words) ?: return "没有这张规则表：$tableKey"
        if (next === base) return null // 词已经在表里，算成功
        next.validate()?.let { return "改完的规则没通过自检：$it" }
        return runCatching {
            userFile(context).writeText(exportJson(next))
            cached = next
            AppLog.i("规则加词：$tableKey +${words.joinToString("/")}（共 ${next.totalEntries} 条）")
            null
        }.getOrElse { "写入失败：${it.message}" }
    }

    /** 从规则里删词并立即生效。@return null = 成功 */
    fun removeWord(context: Context, tableKey: String, word: String): String? {
        val base = current
        val next = base.removeWord(tableKey, word) ?: return "没有这张规则表：$tableKey"
        if (next === base) return null
        next.validate()?.let { return "改完的规则没通过自检：$it" }
        return runCatching {
            userFile(context).writeText(exportJson(next))
            cached = next
            AppLog.i("规则删词：$tableKey -$word（共 ${next.totalEntries} 条）")
            null
        }.getOrElse { "写入失败：${it.message}" }
    }

    // ---------------- 代码内兜底（与 assets 同内容；assets 正常时用不到） ----------------
    val DEFAULTS: Rules = Rules(
        source = "代码默认",
        adMarkers = listOf(
            "券", "劵", "立减", "满减", "满赠", "优惠", "特惠", "福利", "领取", "免费领",
            "抽奖", "活动", "限时", "到手价", "红包雨", "积分兑换", "权益", "专享", "专属",
            "分期", "提额", "额度提升", "推荐", "最高可", "最高立减", "先领", "抢购",
            "会员日", "补贴", "抵用", "抵扣", "礼包", "秒杀", "申请办卡",
            "包邮", "直降", "上新", "直播间", "加购", "收藏", "关注我", "爆款", "清仓", "百亿补贴"
        ),
        nonTxnMarkers = listOf(
            "本期账单", "账单已出", "账单日", "账单金额", "应还金额", "应还", "待还", "最低还款",
            "还款提醒", "即将到期", "到期提醒", "逾期", "最后还款日",
            "额度调整", "额度提醒", "额度已", "提额", "预授信", "可借", "可贷", "免息",
            "优惠券", "立减", "满减", "活动", "抽奖", "签到", "积分", "权益", "福利",
            "推荐", "开通", "申请", "邀请", "回馈", "首绑", "办卡", "加油券", "立享",
            "限时", "特惠", "领券", "领取", "秒杀", "抢购", "升级有礼",
            "包邮", "直降", "上新", "直播间", "猜你喜欢", "为你推荐", "浏览", "收藏", "加购",
            "订单已发货", "已发货", "待收货", "物流",
            "小荷包", "支付红包", "消费红包", "立减金", "返现红包", "红包雨",
            "账单分期", "分期成功", "转为分期", "已分期",
            "将于", "自动扣款", "自动续费", "即将扣款", "扣款提醒", "还款日将至", "待缴", "待缴费用",
            "可办理", "贷款额度", "最高可贷", "申请贷款", "授信", "预审批", "免息期"
        ),
        fundTransferMarkers = listOf(
            "信用卡还款", "还款成功", "还款入账", "已还款", "自动还款", "还信用卡",
            "还款金额", "本期还款", "账单还款",
            "零钱提现", "提现到银行卡", "提现到卡", "向银行卡转账", "转到银行卡", "转存",
            "转入余额宝", "余额宝转出",
            "ATM取现", "ATM取款", "取现", "现金提取",
            "申购", "赎回", "买入基金", "基金买入", "理财购买", "购买理财", "定投扣款"
        ),
        strongPayTemplate = listOf(
            "支付成功", "付款成功", "支付完成", "付款完成", "交易成功", "交易已完成",
            "已支付", "已付款", "已扣费", "扣费成功", "已扣款", "扣款成功", "订单支付成功",
            "退款到账", "已退款", "收款到账", "付款给", "你已成功付款"
        ),
        txnEvidence = listOf(
            "尾号", "账户", "卡号", "交易单号", "流水号", "订单号", "商户", "收款方", "付款方", "商家", "实付", "订单",
            "交易时间", "可用额度", "账户余额", "当前余额", "余额为", "余额为人民币", "余额",
            "实时", "已入账", "已到账"
        ),
        payEvidence = listOf(
            "消费", "支出", "扣款", "支付成功", "已付款", "已扣", "存入",
            "转入", "转账", "还款", "退款", "提现", "工资", "利息", "结息", "缴费成功"
        ),
        paySourceMarkers = listOf(
            "微信支付", "QQ钱包", "QQ支付", "财付通", "支付宝", "云闪付", "银联",
            "钱包", "银行", "信用卡", "储蓄卡", "动账", "交易提醒", "支付通知", "账单"
        ),
        transferMarkers = listOf(
            "[转账]", "［转账］", "转账给你", "向你转账", "给你转账", "请收款", "已收款",
            "收款到账", "你收到一笔", "[微信红包]", "［微信红包］", "[红包]", "领取了你的红包",
            "转账已被接收", "待确认收款", "已接收你的转账", "接收你的转账", "已接收转账"
        ),
        bankTextMarkers = listOf(
            "尾号", "账户", "余额", "信用卡", "储蓄卡", "动账", "交易单号",
            "人民币", "入账", "扣款", "消费人民币", "可用额度"
        ),
        strongHints = listOf(
            "支付成功", "付款成功", "已成功付款", "成功付款", "已支付", "支付", "付款",
            "消费", "扣款", "扣费", "已扣", "支出", "入账", "到账", "转账", "收款", "退款",
            "交易金额", "实付", "缴费", "还款", "提现", "支付给", "付款给",
            "转入", "存入", "扣除", "收取", "扣收", "申购", "赎回", "赞赏", "退回", "取现"
        ),
        marketingChannels = listOf(
            "营销", "活动", "推荐", "推广", "优惠", "福利", "权益", "通知消息", "消息通知",
            "market", "promo", "advert", "offer", "news"
        ),
        payRailPackages = listOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay", "com.unionpay.tsmservice",
            "com.icbc", "com.icbc.im", "com.chinamworld.main", "com.chinamworld.bocmbci",
            "com.android.bankabc", "cmb.pb", "com.bankcomm.Bankcomm", "com.yitong.mbank.psbc",
            "com.spdb.mobilebank", "com.ecitic.bank.mobile", "com.cmbc.cc.mbank",
            "com.cebbank.mobile.cemb", "com.pingan.paces.ccms", "com.cib.xyk",
            "com.jd.jrapp", "com.ecny.noemie.pay"
        ),
        commercePackages = listOf(
            "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall", "com.jd.jrapp",
            "com.xunmeng.pinduoduo", "com.taobao.idlefish", "com.sankuai.meituan",
            "com.sankuai.meituan.takeoutnew", "me.ele", "com.sdu.didi.psnger", "com.sdu.didi.gsui",
            "com.shizhuang.duapp", "com.vipshop.app", "com.taobao.litetao", "com.jd.jm",
            "com.achievo.vipshop", "com.zhenai.dating"
        ),
        contentPackages = listOf(
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.smile.gifmaker",
            "com.kuaishou.nebula", "com.xingin.xhs", "com.sina.weibo", "tv.danmaku.bili",
            "com.bilibili.app.in", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone",
            "com.google.android.youtube", "com.dragon.read", "com.ss.android.article.news",
            "com.zhihu.android"
        ),
        junk = listOf(
            "验证码", "登录", "注册", "密码", "抽奖", "优惠券到账", "红包封面", "确认收货",
            "发货", "物流", "快递已签收", "为你推荐", "活动", "恭喜获得", "恭喜您", "中奖",
            "券", "劵", "立减", "满减", "优惠", "抽奖", "权益", "提额", "积分", "广告",
            "账单已出", "还款提醒", "到期提醒", "即将到期", "额度提升", "邀请", "关注",
            "更新", "版本", "清理", "内存", "安全提醒", "异地登录", "失败", "未支付", "待支付",
            "取消订单", "退款申请", "审核中", "处理中"
        ),
        specUnits = listOf(
            "银", "金", "k金", "K金", "克拉", "克", "mg", "毫升", "英寸", "寸",
            "mah", "mAh", "毫安", "gb", "GB", "tb", "TB", "mb", "MB", "hz", "Hz",
            "像素", "万像素", "℃", "°c", "cm", "mm", "ml", "mL", "kg", "公斤", "厘米", "毫米",
            "号", "码", "件", "只", "支", "瓶", "袋", "盒", "套", "枚", "条", "片", "粒"
        ),
        incomeWords = listOf(
            "退款", "退货", "返现", "入账", "向您转账", "转账给您", "到账", "收入", "收款成功",
            "收到", "工资", "薪资", "请收款", "已收款", "转入", "存入", "报销", "利息",
            "结息", "收益", "分红", "中奖", "红包", "返利"
        ),
        expenseWords = listOf(
            "支出", "消费", "付款", "支付", "扣款", "购买", "实付", "已支付", "缴费", "转出"
        )
    )
}

// ---------------- 单张表的读写（放在 object 外：UI 与测试都不用写 with(RuleStore){…}） ----------------
//
// 「说人话的规则」靠这几个函数原地加/删一个词，而不是让用户去改 490 行 JSON。
// 全部是纯函数（不碰文件、不碰 Context），写盘在 RuleStore.addWords / removeWord 里做。

/** 取某张表的词表；键名不认识返回 null（不抛异常，UI 层要能容错） */
fun RuleStore.Rules.table(key: String): List<String>? = when (key) {
    "adMarkers" -> adMarkers
    "nonTxnMarkers" -> nonTxnMarkers
    "fundTransferMarkers" -> fundTransferMarkers
    "strongPayTemplate" -> strongPayTemplate
    "txnEvidence" -> txnEvidence
    "payEvidence" -> payEvidence
    "paySourceMarkers" -> paySourceMarkers
    "transferMarkers" -> transferMarkers
    "bankTextMarkers" -> bankTextMarkers
    "strongHints" -> strongHints
    "marketingChannels" -> marketingChannels
    "payRailPackages" -> payRailPackages
    "commercePackages" -> commercePackages
    "contentPackages" -> contentPackages
    "junk" -> junk
    "specUnits" -> specUnits
    "incomeWords" -> incomeWords
    "expenseWords" -> expenseWords
    else -> null
}

/** 换掉某张表的词表；键名不认识返回 null */
fun RuleStore.Rules.withTable(key: String, values: List<String>): RuleStore.Rules? = when (key) {
    "adMarkers" -> copy(adMarkers = values)
    "nonTxnMarkers" -> copy(nonTxnMarkers = values)
    "fundTransferMarkers" -> copy(fundTransferMarkers = values)
    "strongPayTemplate" -> copy(strongPayTemplate = values)
    "txnEvidence" -> copy(txnEvidence = values)
    "payEvidence" -> copy(payEvidence = values)
    "paySourceMarkers" -> copy(paySourceMarkers = values)
    "transferMarkers" -> copy(transferMarkers = values)
    "bankTextMarkers" -> copy(bankTextMarkers = values)
    "strongHints" -> copy(strongHints = values)
    "marketingChannels" -> copy(marketingChannels = values)
    "payRailPackages" -> copy(payRailPackages = values)
    "commercePackages" -> copy(commercePackages = values)
    "contentPackages" -> copy(contentPackages = values)
    "junk" -> copy(junk = values)
    "specUnits" -> copy(specUnits = values)
    "incomeWords" -> copy(incomeWords = values)
    "expenseWords" -> copy(expenseWords = values)
    else -> null
}

/**
 * 往某张表里追加词（去空白、去重、保持原顺序）。
 * 全是重复词时返回 [this]（调用方用 `===` 判断"什么都没变"）；键名错返回 null。
 */
fun RuleStore.Rules.appendWords(key: String, words: List<String>): RuleStore.Rules? {
    val cur = table(key) ?: return null
    val add = words.map { it.trim() }.filter { it.isNotBlank() }
    if (add.isEmpty()) return this
    val merged = LinkedHashSet(cur)
    val before = merged.size
    add.forEach { merged.add(it) }
    if (merged.size == before) return this
    return withTable(key, merged.toList())
}

/** 从某张表里删掉一个词（用户加错了要能撤回）；词不在表里返回 [this] */
fun RuleStore.Rules.removeWord(key: String, word: String): RuleStore.Rules? {
    val cur = table(key) ?: return null
    val next = cur.filter { it != word }
    if (next.size == cur.size) return this
    return withTable(key, next)
}
