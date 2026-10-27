package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.SeedData
import com.jingyuniang.ledger.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类关键词表的**场景回归**（v1.4.3）。
 *
 * 跟别的测试不同，这里**直接拿 SeedData 里的词表**建分类，所以：
 * - 谁把「瑞幸」「滴滴」从种子表里删了，这里立刻红；
 * - 谁往某个分类塞了宽泛词（导致别类被抢），这里也会红。
 *
 * 用例都是按真机通知的写法造的：`商户名 + 金额`，覆盖 23 个支出类 / 9 个收入类，
 * 外加一组「就该认不出来」的反例（必须落「待分类」，不许瞎猜）。
 */
class CategoryScenarioTest {

    private val cats: List<Category> =
        SeedData.expense.mapIndexed { i, (n, _, kw) ->
            Category(id = (i + 1).toLong(), name = n, type = TxnType.EXPENSE, keywords = kw)
        } + SeedData.income.mapIndexed { i, (n, _, kw) ->
            Category(id = (200 + i).toLong(), name = n, type = TxnType.INCOME, keywords = kw)
        }

    private fun nameOf(id: Long) = cats.firstOrNull { it.id == id }?.name ?: "待分类"

    private fun classify(text: String, merchant: String = "", income: Boolean = false): String {
        val dir = if (income) TxnType.INCOME else TxnType.EXPENSE
        return nameOf(ParseEngine.matchCategory(text, merchant, dir, cats))
    }

    /** 每个用例：正文 to 商户名 */
    private fun expect(name: String, vararg cases: Pair<String, String>) {
        cases.forEach { (text, merchant) ->
            assertEquals("「$text」（商户=$merchant）应该归到「$name」", name, classify(text, merchant))
        }
    }

    private fun expectIncome(name: String, vararg cases: Pair<String, String>) {
        cases.forEach { (text, merchant) ->
            assertEquals("收入「$text」应该归到「$name」", name, classify(text, merchant, income = true))
        }
    }

    // ---------------- 支出 23 类 ----------------

    @Test
    fun `餐饮`() = expect(
        "餐饮",
        "在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币10.50元" to "瑞幸咖啡",
        "在【财付通-微信支付-兰溪手擀面】发生快捷支付扣款，人民币26.60元" to "兰溪手擀面",
        "美团外卖订单支付成功 32.00元" to "美团外卖",
        "蜜雪冰城 4.00元" to "蜜雪冰城",
        "海底捞火锅 268.00元" to "海底捞",
        "衢州鸭头益江路店 30.00元" to "衢州鸭头",
        "早餐 包子豆浆 8.00元" to ""
    )

    @Test
    fun `买菜`() = expect(
        "买菜",
        "盒马鲜生 88.00元" to "盒马鲜生",
        "叮咚买菜 45.00元" to "叮咚买菜",
        "菜市场 12.00元" to "菜市场",
        "永辉超市 66.00元" to "永辉超市"
    )

    @Test
    fun `交通`() = expect(
        "交通",
        "在【财付通-微信支付-滴滴出行】发生快捷支付扣款，人民币13.50元" to "滴滴出行",
        "中石化加油 300.00元" to "中石化",
        "地铁 4.00元" to "",
        "12306火车票 553.00元" to "12306",
        "停车费 10.00元" to ""
    )

    @Test
    fun `购物`() = expect(
        "购物",
        "淘宝订单 99.00元" to "淘宝",
        "京东商城 199.00元" to "京东",
        "拼多多 9.90元" to "拼多多"
    )

    @Test
    fun `日用`() = expect(
        "日用",
        "全家便利店 15.00元" to "全家便利店",
        "名创优品 39.00元" to "名创优品",
        "洗衣液 纸巾 40.00元" to ""
    )

    @Test
    fun `住房`() = expect(
        "住房",
        "房租 3500.00元" to "",
        "物业费 280.00元" to "",
        "电费 120.00元" to ""
    )

    @Test
    fun `通讯`() = expect(
        "通讯",
        "话费充值 100.00元" to "",
        "中国移动 58.00元" to "中国移动"
    )

    @Test
    fun `娱乐`() = expect(
        "娱乐",
        "万达影城 电影票 80.00元" to "万达影城",
        "Steam 游戏 68.00元" to "Steam",
        "剧本杀 128.00元" to ""
    )

    @Test
    fun `医疗`() = expect(
        "医疗",
        "益丰大药房 45.00元" to "益丰大药房",
        "牙科诊所 800.00元" to "",
        "挂号 门诊 30.00元" to ""
    )

    @Test
    fun `教育`() = expect(
        "教育",
        "网课 299.00元" to "",
        "书店 买书 58.00元" to "书店",
        "驾校学车 4800.00元" to ""
    )

    @Test
    fun `服饰`() = expect(
        "服饰",
        "优衣库 199.00元" to "优衣库",
        "耐克 运动鞋 599.00元" to "耐克"
    )

    @Test
    fun `美容`() = expect(
        "美容",
        "理发 45.00元" to "",
        "美容院 388.00元" to "美容院"
    )

    @Test
    fun `人情`() = expect(
        "人情",
        "结婚礼金 1000.00元" to "",
        "红包 200.00元" to ""
    )

    @Test
    fun `宠物`() = expect("宠物", "猫粮 129.00元" to "猫粮", "宠物医院 320.00元" to "宠物医院")

    @Test
    fun `运动`() = expect("运动", "健身房 月卡 299.00元" to "健身房", "羽毛球馆 60.00元" to "球馆")

    @Test
    fun `旅行`() = expect("旅行", "携程 酒店 458.00元" to "携程", "民宿 320.00元" to "民宿")

    @Test
    fun `数码`() = expect("数码", "数码配件 数据线 39.00元" to "", "相机镜头 4500.00元" to "")

    @Test
    fun `母婴`() = expect("母婴", "奶粉 268.00元" to "奶粉", "纸尿裤 129.00元" to "纸尿裤")

    @Test
    fun `订阅`() = expect("订阅", "iCloud 存储 月费 21.00元" to "iCloud", "视频会员续费 25.00元" to "")

    @Test
    fun `保险`() = expect("保险", "车险保费 3200.00元" to "", "重疾险 投保 8000.00元" to "")

    @Test
    fun `税费`() = expect("税费", "个人所得税 1200.00元" to "", "违章罚款 200.00元" to "")

    @Test
    fun `文具`() = expect("文具", "晨光文具 25.00元" to "晨光文具", "打印复印 3.00元" to "")

    // ---------------- 收入 9 类 ----------------

    @Test
    fun `收入_工资`() = expectIncome("工资", "工资入账 15000.00元" to "", "代发工资 8600.00元" to "")

    @Test
    fun `收入_奖金`() = expectIncome("奖金", "年终奖 30000.00元" to "", "季度奖 5000.00元" to "")

    @Test
    fun `收入_兼职`() = expectIncome("兼职", "稿费 800.00元" to "")

    @Test
    fun `收入_理财`() = expectIncome("理财", "余额宝收益 12.35元" to "", "基金分红 300.00元" to "")

    @Test
    fun `收入_红包`() = expectIncome("红包", "收到微信红包 66.00元" to "")

    @Test
    fun `收入_报销`() = expectIncome("报销", "差旅报销 1200.00元" to "")

    @Test
    fun `收入_退款`() = expectIncome("退款", "退款到账 68.00元" to "")

    @Test
    fun `收入_经营`() = expectIncome("经营", "商户收款 88.00元" to "")

    // ---------------- 反例：就该认不出来 ----------------

    @Test
    fun `认不出来的必须落待分类而不是瞎猜`() {
        val unknown = listOf(
            "在【财付通-微信支付-上海一网通办缴费平台】发生快捷支付扣款，人民币20.00元" to "上海一网通办缴费平台",
            "在【财付通-微信支付-优之膳】发生快捷支付扣款，人民币35.00元" to "优之膳",
            "在【财付通-微信支付-赵六杂货】发生快捷支付扣款，人民币7.70元" to "赵六杂货",
            "账户管理费 3.00元" to "",
            "某某转账 500.00元" to ""
        )
        unknown.forEach { (text, merchant) ->
            val got = classify(text, merchant)
            assertEquals("「$text」不该被瞎猜（拿到了「$got」）", "待分类", got)
        }
    }

    // ---------------- 词表本身的体检 ----------------

    @Test
    fun `每个分类都要有足够的关键词`() {
        SeedData.expense.filter { it.first != "其他" }.forEach { (name, _, kw) ->
            val n = kw.split(",", "，").count { it.isNotBlank() }
            assertTrue("「$name」只有 $n 个关键词，太少（至少要 8 个）", n >= 8)
        }
        SeedData.income.filter { it.first != "其他" }.forEach { (name, _, kw) ->
            val n = kw.split(",", "，").count { it.isNotBlank() }
            assertTrue("「$name」只有 $n 个关键词，太少（至少要 8 个）", n >= 8)
        }
    }

    @Test
    fun `同一个词不要出现在两个支出分类里`() {
        // 词重复 = 两个分类同分 = 结果取决于排序，属于「薛定谔的分类」，必须避免
        val seen = mutableMapOf<String, String>()
        SeedData.expense.forEach { (name, _, kw) ->
            kw.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { k ->
                val prev = seen.put(k, name)
                if (prev != null && prev != name) {
                    throw AssertionError("关键词「$k」同时出现在「$prev」和「$name」")
                }
            }
        }
    }

    @Test
    fun `没有空词条、也没有过短的单字`() {
        val allowSingle = setOf("餐", "饭", "鞋") // 这几个单字在正文里几乎只表达字面意思
        (SeedData.expense + SeedData.income).forEach { (name, _, kw) ->
            kw.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { k ->
                assertTrue("「$name」里有空词条", k.isNotBlank())
                assertTrue(
                    "「$name」里的「$k」只有 1 个字，正文里太容易误伤（要加就加进 allowSingle 并写清理由）",
                    k.length >= 2 || allowSingle.contains(k)
                )
            }
        }
    }

    /**
     * 真机回归（v1.4.3）：商户抽不到时会退成「App 名/包名」，而包名早年又被学进过商户记忆，
     * 于是整批短信都被判成同一条记忆里的分类（携程酒店、iCloud 月费都被判成餐饮）。
     * 现在：包名不算商户 + 商户不可信时不查记忆。
     */
    @Test
    fun `商户不可信时不查商户记忆`() {
        val text = "招商银行 · 您账户3979于10月27日15:10在【财付通-微信支付-携程酒店】发生快捷支付扣款，人民币458.15元"
        val polluted = mapOf("cmb.pb" to (1L to 0L), "招商银行" to (1L to 0L))
        val r = ParseEngine.parse(
            pkg = "cmb.pb",
            appName = "cmb.pb",
            text = text,
            accounts = emptyList(),
            categories = cats,
            rules = emptyList(),
            merchantRules = polluted
        )
        assertEquals("携程酒店应该靠关键词归到「旅行」，而不是被包名记忆判成餐饮", 16L, r!!.categoryId)
    }

    @Test
    fun `包名与域名不算商户`() {
        assertTrue(!ParseEngine.isUsableMerchant("cmb.pb"))
        assertTrue(!ParseEngine.isUsableMerchant("com.tencent.mm"))
        assertTrue(ParseEngine.isUsableMerchant("携程酒店"))
        assertTrue(!ParseEngine.isUsableMerchant("盛夏路店"))
    }
}
