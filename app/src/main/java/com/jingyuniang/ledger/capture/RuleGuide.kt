package com.jingyuniang.ledger.capture

/**
 * 规则的「人话索引」（Backlog P1）。
 *
 * 490 条规则堆在 JSON 里，普通用户不可能读懂 `nonTxnMarkers` 是什么，
 * 更不知道「淘宝老推送」该动哪张表。这里把常见抱怨翻译成「该动哪张表 + 加哪几个词」，
 * 用户只要说出症状（甚至只打一个「淘宝」），就能一键加词，立即生效。
 *
 * 两条硬约束（都由 `RuleGuideTest` 守着）：
 * 1. 每条建议的词都必须**真的能落到某张已存在的表**里，且加完规则仍能通过 `RuleStore.Rules.validate()`；
 * 2. 建议词不允许是「宽泛词」——历史上 `junk` 里塞「领取」直接误杀真实红包（见 HANDOFF §6.9），
 *    所以这里每条词都 ≥2 个字且尽量贴合具体句式。
 */
data class RuleTableInfo(
    val key: String,
    val title: String,
    /** 这张表在抓取流程里干什么用 */
    val purpose: String,
    /** 乱加词的后果，提醒用户别乱来 */
    val caution: String
)

/**
 * 一条「症状 → 改法」。
 *
 * [tableKey] 为空串表示**加词改不了**（属于设计取舍或已知边界），只解释不改规则。
 */
data class RuleTip(
    val symptom: String,
    val fix: String,
    val tableKey: String,
    val words: List<String>,
    val tags: List<String>
)

object RuleGuide {

    /** 18 张表的说明（顺序与 [RuleStore.TABLE_KEYS] 一致） */
    val tables: List<RuleTableInfo> = listOf(
        RuleTableInfo(
            "adMarkers", "营销词", "通知里出现这些词，就当成广告，整条不记账。",
            "词太宽会误杀真实支付（比如只写「券」没问题，写「优惠」就可能挡住「优惠后实付」）"
        ),
        RuleTableInfo(
            "nonTxnMarkers", "非动账模板", "账单、还款提醒、额度推销这类「看起来有钱其实没动钱」的模板。",
            "别塞真实消费里会出现的词，否则真账被静默丢掉"
        ),
        RuleTableInfo(
            "fundTransferMarkers", "资金转移词", "还款 / 提现 / 申购赎回这类「左口袋到右口袋」，记为转账，不计收支。",
            "把真消费写进来会让日常开销凭空消失"
        ),
        RuleTableInfo(
            "strongPayTemplate", "强支付模板", "「支付成功」这种一句话就够定性的句式，电商/内容类 App 要靠它才敢记账。",
            "写得太泛等于给营销推送开后门"
        ),
        RuleTableInfo(
            "txnEvidence", "交易证据词", "尾号 / 单号 / 余额 / 商户这类证据，决定置信度能不能过 80 分自动入账。",
            "加证据词只会让入账更积极，加错不会丢账但可能多记"
        ),
        RuleTableInfo(
            "payEvidence", "动账动词", "消费 / 扣款 / 转入这类动词，判断「这是不是一笔动账」。",
            "加得太宽会把聊天内容当动账"
        ),
        RuleTableInfo(
            "paySourceMarkers", "支付渠道词", "微信 / 支付宝 / 银联 / 数字人民币…用来判来源和跨渠道去重。",
            "直接影响「合并支付会不会双记」，改动要谨慎"
        ),
        RuleTableInfo(
            "transferMarkers", "转账口语词", "微信聊天里「转账给你 / 请收款 / 红包」这些口语。",
            "和收入词重叠没关系，但别把普通聊天句子写进来"
        ),
        RuleTableInfo(
            "bankTextMarkers", "银行短信词", "银行短信的特征词，命中就会被当成最可靠的一类来源。",
            "短信是最信的来源，加错词会让营销短信被当真账"
        ),
        RuleTableInfo(
            "strongHints", "强提示词", "出现基本就是动账的动词集合，用来算基础置信度。",
            "宽泛词会拉高垃圾通知的分数"
        ),
        RuleTableInfo(
            "marketingChannels", "营销渠道词", "通知的「渠道名」里带这些词，整类不读（比如「优惠活动」渠道）。",
            "只匹配渠道名，不会误伤正文"
        ),
        RuleTableInfo(
            "payRailPackages", "支付通道包名", "微信 / 支付宝 / 各大银行 App 的包名，属于最宽松的来源分级。",
            "包名写错等于没加，可以先用「抓取诊断」看真实包名"
        ),
        RuleTableInfo(
            "commercePackages", "电商包名", "淘宝 / 京东 / 美团这类，只认强支付模板，宁漏不错。",
            "加进来不是「一定会记」，而是「必须有支付成功那类句式才记」"
        ),
        RuleTableInfo(
            "contentPackages", "内容包名", "抖音 / 快手 / 小红书 / B 站，同电商且不读屏。",
            "读屏永远不自动入账，加了也只能抓通知"
        ),
        RuleTableInfo(
            "junk", "垃圾词", "出现就直接丢弃整条，是最狠的一张表。",
            "最危险的表：历史上塞「领取」误杀过真实红包（HANDOFF §6.9）。只放具体句式，别放单词根"
        ),
        RuleTableInfo(
            "specUnits", "规格单位", "「925 银」「5G 手机」「2 件」里的数字不是金额，靠这张表排除。",
            "单字单位（如「匹」）容易撞正常词汇，慎加"
        ),
        RuleTableInfo(
            "incomeWords", "收入词", "判断这笔是收入（退款、工资、红包、收益）用的词。",
            "收入方向错会直接把账算反"
        ),
        RuleTableInfo(
            "expenseWords", "支出词", "判断这笔是支出（消费、付款、代扣）用的词。",
            "同上，方向错了比漏记更难发现"
        )
    )

    /** 常见抱怨 → 改法。前 6 条是最高频的（电商推送、账单、额度、自动续费、退款、双记） */
    val tips: List<RuleTip> = listOf(
        RuleTip(
            "淘宝 / 京东的「券后价 9.9」被记成一笔支出",
            "这些是营销词，往「营销词」里加，通知里出现就直接不记账。",
            "adMarkers", listOf("券后价", "预估到手", "店铺券", "满减券"),
            listOf("淘宝", "京东", "拼多多", "推送", "广告", "优惠券", "券后", "满减", "电商")
        ),
        RuleTip(
            "抖音 / 快手直播间的「拍下减」「直播间价」很烦",
            "同一张营销词表，加上直播特有的句式即可。",
            "adMarkers", listOf("直播间价", "拍下减", "限时秒杀"),
            listOf("抖音", "快手", "直播", "带货", "小红书", "视频号", "推送")
        ),
        RuleTip(
            "信用卡「本期应还」被记成一笔消费",
            "账单只是提醒，不是动钱。往「非动账模板」里加。",
            "nonTxnMarkers", listOf("本期应还", "最低还款额", "账单总金额"),
            listOf("信用卡", "账单", "还款", "银行", "应还", "花呗", "白条")
        ),
        RuleTip(
            "「预审批额度」「可借额度」这种推销被记成收入",
            "额度推销不是钱。往「非动账模板」里加。",
            "nonTxnMarkers", listOf("预审批额度", "可借额度", "额度提升至"),
            listOf("额度", "提额", "贷款", "授信", "推销", "借钱")
        ),
        RuleTip(
            "会员「将于 X 日自动扣费」的预告被当成已扣",
            "预告不是动账。往「非动账模板」里加。",
            "nonTxnMarkers", listOf("扣费提醒", "续费提醒"),
            listOf("自动续费", "会员", "扣费", "提醒", "预告", "订阅")
        ),
        RuleTip(
            "快递「派送中」「已签收」被记成花了钱",
            "物流状态不是消费。往「非动账模板」里加。",
            "nonTxnMarkers", listOf("派送中", "已签收", "待评价"),
            listOf("快递", "物流", "签收", "发货", "收货", "订单")
        ),
        RuleTip(
            "花呗 / 白条还款记成了一笔支出",
            "还款是资金转移，不计收支。往「资金转移词」里加。",
            "fundTransferMarkers", listOf("花呗还款", "白条还款"),
            listOf("花呗", "白条", "还款", "信用卡", "资金转移")
        ),
        RuleTip(
            "买基金 / 理财被记成消费",
            "申购赎回也是资金转移。往「资金转移词」里加。",
            "fundTransferMarkers", listOf("买入成功", "份额确认", "转出到银行卡"),
            listOf("基金", "理财", "申购", "赎回", "定投", "投资")
        ),
        RuleTip(
            "银行说「现金支取」「无卡取款」，被记成消费",
            "取现是自己取自己的钱。往「资金转移词」里加。",
            "fundTransferMarkers", listOf("现金支取", "无卡取款"),
            listOf("ATM", "取现", "取款", "现金", "银行")
        ),
        RuleTip(
            "银行短信只写「支付方式」「交易类型」，没抓到",
            "这是证据词，加上以后这类短信的置信度才够自动入账。",
            "txnEvidence", listOf("支付方式", "交易类型", "记账日"),
            listOf("证据", "置信度", "银行短信", "没抓到", "漏抓")
        ),
        RuleTip(
            "「话费充值」「水电缴纳」被漏记",
            "往「动账动词」里补动词。",
            "payEvidence", listOf("充值", "缴纳", "划扣"),
            listOf("缴费", "话费", "水电", "充值", "漏抓", "动账")
        ),
        RuleTip(
            "用「数字人民币 / 美团支付 / 抖音支付」付款没识别出来",
            "往「支付渠道词」里补渠道名。",
            "paySourceMarkers", listOf("数字人民币", "美团支付", "抖音支付"),
            listOf("渠道", "来源", "数字人民币", "美团", "抖音", "银联")
        ),
        RuleTip(
            "微信里「请确认收款」的转账没记上",
            "这是转账口语，往「转账口语词」里加。",
            "transferMarkers", listOf("请确认收款", "待你收款"),
            listOf("微信", "转账", "收款", "红包", "漏抓")
        ),
        RuleTip(
            "某家银行的短信老是被漏掉",
            "往「银行短信词」里补这家的固定说法。",
            "bankTextMarkers", listOf("交易类型", "记账日", "对方账户"),
            listOf("银行短信", "尾号", "动账", "短信", "漏抓", "用卡")
        ),
        RuleTip(
            "电商 / 抖音里确实付了钱，但没记上",
            "它们只认「强支付模板」——把这家 App 的付款句式补进来。",
            "strongPayTemplate", listOf("已成功支付", "付款给商家"),
            listOf("淘宝", "抖音", "支付成功", "漏抓", "电商", "付款", "没记上")
        ),
        RuleTip(
            "「活动即将结束」这类提醒太烦，想直接不抓",
            "往「垃圾词」里加**完整句式**（不要加单个词根，会误杀真账）。",
            "junk", listOf("活动即将结束", "优惠即将过期"),
            listOf("广告", "打扰", "噪音", "垃圾", "推送", "烦")
        ),
        RuleTip(
            "「925 银」「2 件」里的数字被当成金额",
            "往「规格单位」里补单位，数字后面跟这些单位就不算金额。",
            "specUnits", listOf("盎司", "英尺", "加仑"),
            listOf("金额", "识别错", "规格", "单位", "925银", "数量")
        ),
        RuleTip(
            "某个通知渠道整类都在发广告",
            "往「营销渠道词」加，整类不读（只匹配渠道名，不碰正文）。",
            "marketingChannels", listOf("促销", "活动通知", "优惠通知"),
            listOf("通知", "渠道", "骚扰", "广告", "营销", "烦")
        ),
        RuleTip(
            "某家银行 App 的通知没被当成「支付通道」",
            "补它的包名。不知道包名就先跑一次「抓取诊断」，日志里会写真实包名。",
            "payRailPackages", listOf("com.cmbchina", "com.citiccard.mobilebank"),
            listOf("银行", "App", "包名", "漏抓", "通道")
        ),
        RuleTip(
            "美团 / 点评这类电商 App 没被认出来",
            "补包名。注意：加进来只代表「允许抓」，仍要求有支付成功那类句式。",
            "commercePackages", listOf("com.dianping.v1", "com.taobao.trip"),
            listOf("电商", "包名", "漏抓", "美团", "点评", "携程", "飞猪")
        ),
        RuleTip(
            "红包 / 理财收益没记成收入",
            "往「收入词」里补。",
            "incomeWords", listOf("收益到账", "红包到账"),
            listOf("收入", "红包", "收益", "理财", "漏记", "方向")
        ),
        RuleTip(
            "「代扣 / 扣费」被算成了收入",
            "往「支出词」里补，方向就对了。",
            "expenseWords", listOf("扣费", "代扣"),
            listOf("支出", "代扣", "扣费", "方向", "算反")
        ),
        // ---- 以下三条：加词改不了，属于设计取舍，只解释 ----
        RuleTip(
            "退款被记成「收入」了",
            "这是**故意的**：退款一律进「待确认」，由你决定「冲销原账」还是当收入。" +
                "退款到账时点「冲销原账」，原支出和这笔退款会一起消失，账才对。",
            "", emptyList(),
            listOf("退款", "收入", "冲销", "原账", "退货", "待确认")
        ),
        RuleTip(
            "银行通知先到、微信晚到一分钟，记了两笔",
            "已知边界：跨渠道去重只兜 60 秒（长窗 10 分钟要求本条写着财付通/微信支付）。" +
                "遇到就在「待确认」里点 🚫「以后别再抓这类」，两条都不会再重复。",
            "", emptyList(),
            listOf("重复", "双记", "两笔", "去重", "银行", "微信")
        ),
        RuleTip(
            "一次付款拆成零钱 + 银行卡，怎么记了两笔",
            "合并支付拆金额没法自动合并（这是数据本身的限制）。" +
                "保留一条、把另一条删掉，或直接点 🚫 忽略这一条。",
            "", emptyList(),
            listOf("合并支付", "拆分", "零钱", "银行卡", "两笔", "重复")
        )
    )

    fun table(key: String): RuleTableInfo? = tables.firstOrNull { it.key == key }

    /**
     * 按用户输入找「症状 → 改法」。
     * 空白查询返回前 [limit] 条（= 最高频的几条）；多个词用空格/顿号分隔，命中越多排越前。
     */
    fun search(query: String, limit: Int = 8): List<RuleTip> {
        val q = query.trim()
        if (q.isEmpty()) return tips.take(limit)
        val tokens = q.split(' ', '\u3000', '，', ',', '、', '。', '?', '？', '!', '！')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return tips.take(limit)
        return tips.mapNotNull { t ->
            val hay = buildString {
                append(t.symptom).append(' ').append(t.fix).append(' ')
                append(t.tags.joinToString(" ")).append(' ')
                append(table(t.tableKey)?.title ?: "")
            }.lowercase()
            val hits = tokens.count { hay.contains(it.lowercase()) }
            if (hits > 0) t to hits else null
        }.sortedByDescending { it.second }.map { it.first }.take(limit)
    }
}
