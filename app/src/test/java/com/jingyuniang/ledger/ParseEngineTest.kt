package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析引擎的纯 JVM 单测：不依赖安卓设备，直接验证「金额/方向/分类」三大判定 */
class ParseEngineTest {

    private val accounts = listOf(
        Account(id = 1, name = "现金", type = AccountType.CASH, keywords = "现金"),
        Account(id = 2, name = "微信", type = AccountType.WECHAT, keywords = "微信,微信支付,零钱", isDefault = true),
        Account(id = 3, name = "支付宝", type = AccountType.ALIPAY, keywords = "支付宝,花呗"),
        Account(id = 4, name = "银行卡", type = AccountType.BANK, keywords = "银行卡,尾号", cardTail = "8888")
    )

    private val categories = listOf(
        Category(id = 10, name = "餐饮", emoji = "🍜", type = TxnType.EXPENSE, keywords = "餐,饭,外卖,星巴克,咖啡"),
        Category(id = 11, name = "交通", emoji = "🚌", type = TxnType.EXPENSE, keywords = "地铁,公交,滴滴"),
        Category(id = 12, name = "其他", emoji = "📦", type = TxnType.EXPENSE, keywords = ""),
        Category(id = 20, name = "工资", emoji = "💰", type = TxnType.INCOME, keywords = "工资,薪资"),
        Category(id = 21, name = "退款", emoji = "💸", type = TxnType.INCOME, keywords = "退款"),
        Category(id = 22, name = "其他", emoji = "💵", type = TxnType.INCOME, keywords = "")
    )

    private fun parse(text: String, pkg: String? = null) = ParseEngine.parse(
        pkg = pkg,
        appName = "微信",
        text = text,
        accounts = accounts,
        categories = categories,
        rules = emptyList<AutoRule>()
    )

    @Test
    fun `微信支付通知能识别金额与支出方向`() {
        val r = parse("微信支付 · 已支付 25.00 元", pkg = "com.tencent.mm")!!
        assertEquals(2500L, r.amountCents)
        assertEquals(TxnType.EXPENSE, r.direction)
        assertEquals(2L, r.accountId)
    }

    @Test
    fun `支付宝付款能识别`() {
        val r = parse("支付宝 · 你已成功付款 12.5 元", pkg = "com.eg.android.AlipayGphone")!!
        assertEquals(1250L, r.amountCents)
        assertEquals(3L, r.accountId)
    }

    @Test
    fun `银行短信不会把余额当成金额`() {
        val text = "【招商银行】您账户尾号8888于10月22日12:30消费人民币48.00元，余额1,234.56元。"
        val r = parse(text)!!
        assertEquals(4800L, r.amountCents)
        assertEquals(TxnType.EXPENSE, r.direction)
        assertEquals(4L, r.accountId)
    }

    @Test
    fun `工资入账识别为收入`() {
        val r = parse("您尾号8888账户入账工资 8,000.00 元")!!
        assertEquals(800000L, r.amountCents)
        assertEquals(TxnType.INCOME, r.direction)
    }

    @Test
    fun `退款识别为收入`() {
        val r = parse("退款到账 30.00 元")!!
        assertEquals(3000L, r.amountCents)
        assertEquals(TxnType.INCOME, r.direction)
    }

    @Test
    fun `验证码类消息直接丢弃`() {
        assertNull(parse("您的验证码是 123456，5 分钟有效"))
    }

    @Test
    fun `星巴克归到餐饮`() {
        val r = parse("微信支付 · 星巴克消费 35.00 元", pkg = "com.tencent.mm")!!
        assertEquals(3500L, r.amountCents)
        assertEquals(10L, r.categoryId)
    }

    @Test
    fun `银行短信不会把日期当商户`() {
        val text = "【招商银行】您账户尾号8888于10月22日12:30消费人民币48.00元，余额1,234.56元。"
        val r = parse(text)!!
        assertTrue(!r.merchant.contains("月") && !r.merchant.contains(":"))
    }

    @Test
    fun `金额上下文打分：优先付费动词旁的金额`() {
        // 「满100减20，实付 80.00 元」应取 80
        assertEquals(8000L, ParseEngine.extractAmount("满100减20，实付 80.00 元"))
    }

    @Test
    fun `千分位与元字后缀`() {
        assertEquals(123456L, ParseEngine.extractAmount("支付 1,234.56 元"))
        assertEquals(500000L, ParseEngine.extractAmount("¥5,000"))
    }

    @Test
    fun `黑名单过滤优惠券类噪声`() {
        assertTrue(ParseEngine.isJunk("恭喜获得一张优惠券到账"))
    }

    @Test
    fun `金额分转换`() {
        assertEquals(1250L, Fmt.toCents("12.5"))
        assertEquals(1250L, Fmt.toCents("12.50"))
        assertEquals(100L, Fmt.toCents("1."))
        assertEquals(0L, Fmt.toCents(""))
        assertEquals("1,234.56", Fmt.amount(123456L))
        assertEquals("0.05", Fmt.amount(5L))
    }

    // ---------- 中文口语金额（P0-3：AI 一句话记账必须能认「十块钱」） ----------
    @Test
    fun `中文金额能认出十块钱`() {
        assertEquals(1000L, ParseEngine.extractAmount("发微信开销了十块钱"))
        assertEquals(1000L, ParseEngine.parseChineseAmount("十块"))
        assertEquals(2500L, ParseEngine.extractAmount("午饭二十五块"))
        assertEquals(30000L, ParseEngine.extractAmount("交了三百元房租"))
        assertEquals(15000L, ParseEngine.parseChineseAmount("一百五十元"))
        assertEquals(1250L, ParseEngine.parseChineseAmount("十二块五"))
        assertEquals(1005_00L, ParseEngine.parseChineseAmount("一千零五元"))
    }

    @Test
    fun `中文数字没有金额单位时不当金额`() {
        assertEquals(0L, ParseEngine.extractAmount("十分钟后开会"))
        assertEquals(0L, ParseEngine.extractAmount("三月再说吧"))
    }

    @Test
    fun `阿拉伯数字优先于中文金额`() {
        assertEquals(2500L, ParseEngine.extractAmount("午饭 25 块钱"))
    }

    @Test
    fun `中文金额能进本地一句话解析`() {
        // 本地规则兜底（AI 不可用时）也要把「十块钱」落到 10 元支出
        val r = parse("发微信开销了十块钱", pkg = "com.tencent.mm")!!
        assertEquals(1000L, r.amountCents)
        assertEquals(TxnType.EXPENSE, r.direction)
        assertEquals(2L, r.accountId)
    }
}
