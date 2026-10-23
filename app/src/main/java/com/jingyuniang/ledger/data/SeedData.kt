package com.jingyuniang.ledger.data

/** 首次启动写入的默认分类 / 账户 / 账本 / 自动规则 */
object SeedData {

    private val C = longArrayOf(
        0xFFFF7A45, 0xFF52C41A, 0xFF1890FF, 0xFFEB2F96, 0xFF13C2C2, 0xFF722ED1,
        0xFF2F54EB, 0xFFF5222D, 0xFFFF4D4F, 0xFFFA8C16, 0xFFFAAD14, 0xFFFF85C0,
        0xFFFF4D6D, 0xFFA0522D, 0xFF36CFC9, 0xFF40A9FF, 0xFF597EF7, 0xFF9254DE,
        0xFF08979C, 0xFF8C8C8C, 0xFF90A4AE
    )

    /** 支出分类：名称 / emoji / 关键词 */
    val expense: List<Triple<String, String, String>> = listOf(
        Triple("餐饮", "🍜", "餐,饭,外卖,美团,饿了么,肯德基,麦当劳,星巴克,咖啡,奶茶,食堂,烧烤,火锅,早餐,午餐,晚餐,夜间,小笼包,面馆"),
        Triple("买菜", "🥬", "菜市场,生鲜,超市,盒马,永辉,沃尔玛,水果,买菜,菜"),
        Triple("交通", "🚌", "地铁,公交,打车,滴滴,高铁,火车,加油,停车,机票,航空,出租,共享单车,高速"),
        Triple("购物", "🛍️", "淘宝,天猫,京东,拼多多,商场,唯品会,得物,网购,旗舰店"),
        Triple("日用", "🧻", "便利,日用品,洗衣,纸巾,五金,家居,名创"),
        Triple("住房", "🏠", "房租,物业,水费,电费,燃气,宽带,供暖,中介"),
        Triple("通讯", "📱", "话费,流量,电信,移动,联通,网费"),
        Triple("娱乐", "🎮", "电影,游戏,ktv,演唱会,门票,腾讯视频,爱奇艺,网易云,steam,剧本杀,桌游,景区"),
        Triple("医疗", "💊", "医院,药,挂号,诊所,体检,牙,门诊,医保"),
        Triple("教育", "📚", "学费,书,课程,培训,考试,网课,书店,教育"),
        Triple("服饰", "👕", "衣服,鞋,优衣库,nike,adidas,内衣,裤,裙,帽"),
        Triple("美容", "💅", "理发,美发,美容,护肤,化妆,美甲,医美"),
        Triple("人情", "🎁", "红包,礼金,份子,送礼,请客,随礼"),
        Triple("宠物", "🐱", "猫粮,狗粮,宠物,铲屎,宠物医院"),
        Triple("运动", "⚽", "健身,瑜伽,游泳,跑步,球,运动,舞蹈"),
        Triple("旅行", "✈️", "酒店,民宿,景区,机票,旅行,度假,跟团"),
        Triple("数码", "💻", "手机,电脑,耳机,充电,数码,配件,键盘,显示器"),
        Triple("母婴", "👶", "奶粉,尿布,母婴,幼儿园,宝宝"),
        Triple("订阅", "🔄", "会员,订阅,vip,netflix,续费,会员费"),
        Triple("保险", "🛡️", "保险,保费"),
        Triple("税费", "🧾", "税,罚款,手续费,利息,违约金"),
        Triple("文具", "✏️", "文具,打印,办公,笔,本子"),
        Triple("其他", "📦", "")
    )

    /** 收入分类 */
    val income: List<Triple<String, String, String>> = listOf(
        Triple("工资", "💰", "工资,薪资,薪金,代发,月薪"),
        Triple("奖金", "🏆", "奖金,年终,绩效,提成,奖励"),
        Triple("兼职", "💼", "兼职,外快,劳务,稿费,副业"),
        Triple("理财", "📈", "利息,分红,收益,基金,股票,理财,余额宝"),
        Triple("红包", "🧧", "红包,礼金,压岁,转账收入"),
        Triple("报销", "↩️", "报销"),
        Triple("退款", "💸", "退款,退货,返现,退"),
        Triple("经营", "🏪", "收款,经营,营业额"),
        Triple("其他", "💵", "")
    )

    fun categoryColor(index: Int): Long = C[index % C.size]

    data class SeedAccount(val name: String, val emoji: String, val type: AccountType, val color: Long, val keywords: String, val isDefault: Boolean)

    val accounts: List<SeedAccount> = listOf(
        SeedAccount("现金", "💵", AccountType.CASH, 0xFF52C41A, "现金,钱包", false),
        SeedAccount("微信", "💚", AccountType.WECHAT, 0xFF07C160, "微信,微信支付,零钱,wechat", true),
        SeedAccount("支付宝", "💙", AccountType.ALIPAY, 0xFF1677FF, "支付宝,余额宝,花呗,alipay", false),
        SeedAccount("银行卡", "💳", AccountType.BANK, 0xFF4D6BFE, "银行卡,储蓄卡,尾号,银行", false),
        SeedAccount("信用卡", "🪪", AccountType.CREDIT, 0xFFFF7A45, "信用卡,信用卡还款,信用", false)
    )

    /** 内置自动规则（账户归属），优先级高的先匹配 */
    data class SeedRule(val name: String, val pkg: String, val keyword: String, val accountName: String)

    val rules: List<SeedRule> = listOf(
        SeedRule("微信支付自动化", "com.tencent.mm", "微信支付,微信,零钱", "微信"),
        SeedRule("支付宝自动化", "com.eg.android.AlipayGphone", "支付宝,花呗,余额宝", "支付宝"),
        SeedRule("云闪付自动化", "com.unionpay", "云闪付,银联", "银行卡")
    )
}
