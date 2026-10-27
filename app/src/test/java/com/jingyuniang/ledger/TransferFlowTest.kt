package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资金转移回归（v1.4.4）。
 *
 * 真机事故（2026-10-27 18:16）：用户从**招商银行卡给微信零钱充值 ¥50**，招行短信
 * 「您账户3979于10月27日18:16在【财付通-微信支付-微信零钱充值账户】发生快捷支付扣款」。
 *
 * App 干了三件错事，加起来就是「微信钱还少了」：
 *  1. 没认出是**资金转移**（充值到自己的零钱），记成了支出 → 支出虚增；
 *  2. 账户匹配被正文里的「微信」带跑，扣的是**微信账户**而不是招商银行卡
 *     （`cmb.pb` 不在银行包名表里，pkg 匹配失效）；
 *  3. 就算记成 TRANSFER 也只扣来源（单边），微信零钱永远不会加钱。
 */
class TransferFlowTest {

    private val cash = Account(id = 1, name = "现金", type = AccountType.CASH, keywords = "现金,钱包")
    private val wechat = Account(id = 8, name = "微信", type = AccountType.WECHAT, keywords = "微信,微信支付,零钱,wechat")
    private val bank = Account(id = 9, name = "招商银行", type = AccountType.BANK, keywords = "银行卡,储蓄卡,尾号,银行")
    private val accounts = listOf(cash, wechat, bank)

    private val cats = listOf(
        Category(id = 1, name = "餐饮", type = TxnType.EXPENSE, keywords = "餐,饭,咖啡"),
        Category(id = 23, name = "其他", type = TxnType.EXPENSE, keywords = ""),
        Category(id = 35, name = "待分类", type = TxnType.EXPENSE, keywords = "")
    )

    // 真机原文（招行 → 微信零钱充值）
    private val rechargeSms =
        "招商银行 · 招商银行 您账户3979于10月27日18:16在【财付通-微信支付-微信零钱充值账户】发生快捷支付扣款，人民币50.00元"

    @Test
    fun `银行包名要认得出招行手机银行`() {
        assertEquals(AccountType.BANK, ParseEngine.pkgToType("cmb.pb"))
        assertEquals(AccountType.BANK, ParseEngine.pkgToType("cmb.pb.cn"))
        assertEquals(AccountType.BANK, ParseEngine.pkgToType("com.cmbchina.ccd.pluto.cmbActivity"))
        assertEquals(AccountType.WECHAT, ParseEngine.pkgToType("com.tencent.mm"))
        assertEquals(AccountType.ALIPAY, ParseEngine.pkgToType("com.eg.android.AlipayGphone"))
        // 认不出来就是 null，别乱猜成银行
        assertNull(ParseEngine.pkgToType("com.taobao.taobao"))
    }

    @Test
    fun `零钱充值属于资金转移而不是支出`() {
        assertTrue("零钱充值必须算资金转移", CaptureFilters.isFundTransfer(rechargeSms))
        assertFalse("它不是信用卡还款", CaptureFilters.isRepayment(rechargeSms))
        // 提现方向相反，同样算转移
        assertTrue(CaptureFilters.isFundTransfer("零钱提现到银行卡 100.00元"))
        // 反例：正常消费不能被当成转移
        assertFalse(
            CaptureFilters.isFundTransfer(
                "招商银行 · 您账户3979于10月27日13:20在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币10.50元"
            )
        )
        // 反例：话费充值不是资金转移（不然真实支出会丢）
        assertFalse(CaptureFilters.isFundTransfer("话费充值 100.00元"))
    }

    @Test
    fun `短信里的微信字样不能把账户抢成微信`() {
        val r = ParseEngine.parse(
            pkg = "cmb.pb",
            appName = "招商银行",
            text = rechargeSms,
            accounts = accounts,
            categories = cats,
            rules = emptyList()
        )!!
        assertEquals("钱是从招商银行卡出去的，不能被正文里的「微信」带跑", 9L, r.accountId)
        // 方向由 CapturePipeline 按「是不是资金转移」覆盖成 TRANSFER（见上面 isFundTransfer 断言）
        assertTrue(CaptureFilters.isFundTransfer(rechargeSms))
    }

    @Test
    fun `双边转移要认出另一方账户`() {
        // 银行卡 → 微信零钱
        assertEquals(8L, ParseEngine.matchTransferTarget(rechargeSms, accounts, 9L)?.id)
        // 微信零钱 → 银行卡（提现）
        val withdraw = "微信支付 · 零钱提现到银行卡 100.00元"
        assertEquals(9L, ParseEngine.matchTransferTarget(withdraw, accounts, 8L)?.id)
    }

    @Test
    fun `认不出另一方就不要猜`() {
        val vague = "招商银行 · 您账户3979于10月27日18:16发生快捷支付扣款，人民币50.00元"
        assertNull(ParseEngine.matchTransferTarget(vague, accounts, 9L))
        // 只有来源账户自己出现时也不能返回它
        assertNull(ParseEngine.matchTransferTarget("招商银行转账 50.00元", accounts, 9L))
    }

    @Test
    fun `普通消费不会被当成双边转移`() {
        val shopping = "招商银行 · 您账户3979于10月27日13:20在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币10.50元"
        assertFalse(CaptureFilters.isFundTransfer(shopping))
        val r = ParseEngine.parse(
            pkg = "cmb.pb", appName = "招商银行", text = shopping,
            accounts = accounts, categories = cats, rules = emptyList()
        )!!
        assertEquals(TxnType.EXPENSE, r.direction)
    }
}
