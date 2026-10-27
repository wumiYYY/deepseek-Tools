package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountMove
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.BalanceMath
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 余额口径回归（v1.4.5）。
 *
 * 这里的断言全部落在「**哪个账户动了多少钱**」上，而不是只测函数返回什么值 ——
 * 因为这段时间踩的坑全是余额对不上：充值算成支出（微信少了）、删账户（余额凭空少）、
 * 自己转给自己（旧 SQL 的 CASE 先命中转出分支，凭空少一笔）。
 */
class BalanceFlowTest {

    private val cash = Account(id = 1, name = "现金", type = AccountType.CASH, initialBalanceCents = 0, keywords = "现金")
    private val wechat = Account(id = 8, name = "微信", type = AccountType.WECHAT, initialBalanceCents = 20000, keywords = "微信,微信支付,零钱")
    private val bank = Account(id = 9, name = "招商银行", type = AccountType.BANK, initialBalanceCents = 1000000, keywords = "银行卡,储蓄卡,银行")
    private val credit = Account(id = 10, name = "信用卡", type = AccountType.CREDIT, initialBalanceCents = 0, keywords = "信用卡")
    private val accounts = listOf(cash, wechat, bank, credit)

    private var seq = 0L
    private fun txn(
        type: TxnType,
        cents: Long,
        acc: Long,
        to: Long = 0L,
        toCents: Long = 0L,
        source: TxnSource = TxnSource.MANUAL,
        note: String = ""
    ) = Txn(
        id = ++seq, ledgerId = 1, type = type, amountCents = cents,
        accountId = acc, toAccountId = to, toAmountCents = toCents,
        source = source, note = note
    )

    private fun bal(list: List<Txn>) = BalanceMath.balances(list, accounts)

    // ---------------- 余额调整（更新余额） ----------------

    @Test
    fun `余额调高要真的加钱`() {
        // 微信实际有 ¥300，App 里记着 ¥200 → 补一笔 INCOME 调整 ¥100
        val list = listOf(txn(TxnType.INCOME, 10000, wechat.id, source = TxnSource.ADJUST, note = "余额调整 → ¥300"))
        assertEquals(30000L, bal(list)[wechat.id])
        assertEquals("别的账户不能被带着变", 1000000L, bal(list)[bank.id])
    }

    @Test
    fun `余额调低要真的扣钱`() {
        val list = listOf(txn(TxnType.EXPENSE, 5000, wechat.id, source = TxnSource.ADJUST))
        assertEquals(15000L, bal(list)[wechat.id])
    }

    @Test
    fun `连续调整两次等于只调到最后一次`() {
        val list = listOf(
            txn(TxnType.INCOME, 10000, wechat.id, source = TxnSource.ADJUST),  // 200 → 300
            txn(TxnType.EXPENSE, 25000, wechat.id, source = TxnSource.ADJUST)  // 300 → 50
        )
        assertEquals(5000L, bal(list)[wechat.id])
    }

    @Test
    fun `调整不算收支但算余额`() {
        // excludeBudget / source=ADJUST 只影响统计口径，绝不能连余额一起不算
        val list = listOf(
            txn(TxnType.INCOME, 10000, wechat.id, source = TxnSource.ADJUST).copy(excludeBudget = true)
        )
        assertEquals(30000L, bal(list)[wechat.id])
    }

    // ---------------- 收到别人转账 ----------------

    @Test
    fun `收到别人转账是收入且余额增加`() {
        val text = "微信支付 · [转账] 张三向你转账 100.00 元，请收款"
        val r = ParseEngine.parse(
            pkg = "com.tencent.mm", appName = "微信支付", text = text,
            accounts = accounts, categories = emptyList(), rules = emptyList()
        )!!
        assertEquals("收钱必须是收入", TxnType.INCOME, r.direction)
        assertEquals("要落到微信账户", wechat.id, r.accountId)

        val list = listOf(txn(r.direction, r.amountCents, r.accountId))
        assertEquals(30000L, bal(list)[wechat.id])
    }

    @Test
    fun `微信红包也是收入`() {
        val text = "微信支付 · [微信红包] 恭喜发财，金额 66.00 元"
        val r = ParseEngine.parse(
            pkg = "com.tencent.mm", appName = "微信支付", text = text,
            accounts = accounts, categories = emptyList(), rules = emptyList()
        )
        // 红包文案不一定带金额（这条带），带就必须是收入；不带金额时解析为 null 也算合理
        if (r != null) {
            assertEquals(TxnType.INCOME, r.direction)
            assertEquals(wechat.id, r.accountId)
        }
    }

    @Test
    fun `银行入账也是收入且落到银行卡`() {
        val text = "招商银行 · 您账户3979于10月27日18:00入账人民币500.00元，余额10,500.00元"
        val r = ParseEngine.parse(
            pkg = "cmb.pb", appName = "招商银行", text = text,
            accounts = accounts, categories = emptyList(), rules = emptyList()
        )!!
        assertEquals(TxnType.INCOME, r.direction)
        assertEquals(bank.id, r.accountId)
        assertEquals(1050000L, bal(listOf(txn(TxnType.INCOME, r.amountCents, r.accountId)))[bank.id])
    }

    // ---------------- 双边转账 ----------------

    @Test
    fun `转账要从一个账户扣到另一个账户加`() {
        val list = listOf(txn(TxnType.TRANSFER, 25000, bank.id, to = wechat.id, toCents = 25000))
        val b = bal(list)
        assertEquals(975000L, b[bank.id])
        assertEquals(45000L, b[wechat.id])
        assertEquals("转账是左口袋到右口袋，总资产不变", 1020000L, b.values.sum())
    }

    @Test
    fun `只记单边时目标账户不动`() {
        val list = listOf(txn(TxnType.TRANSFER, 25000, bank.id, to = 0, toCents = 25000))
        val b = bal(list)
        assertEquals(975000L, b[bank.id])
        assertEquals(20000L, b[wechat.id])
    }

    @Test
    fun `自己转给自己不能凭空少钱`() {
        // 迁移账户后可能出现 accountId == toAccountId，旧 SQL 只扣不加 → 少一笔
        val list = listOf(txn(TxnType.TRANSFER, 10000, wechat.id, to = wechat.id, toCents = 10000))
        assertEquals(20000L, bal(list)[wechat.id])
    }

    @Test
    fun `信用卡还款是储蓄卡到信用卡`() {
        val list = listOf(txn(TxnType.TRANSFER, 30000, bank.id, to = credit.id, toCents = 30000))
        val b = bal(list)
        assertEquals(970000L, b[bank.id])
        assertEquals(30000L, b[credit.id])
    }

    @Test
    fun `toAmountCents 缺失时按 amountCents 算`() {
        val list = listOf(txn(TxnType.TRANSFER, 10000, bank.id, to = wechat.id, toCents = 0))
        val b = bal(list)
        assertEquals(990000L, b[bank.id])
        assertEquals(30000L, b[wechat.id])
    }

    // ---------------- 孤儿账目 ----------------

    @Test
    fun `账户被删后账目不再影响任何余额但要能数出来`() {
        val list = listOf(
            txn(TxnType.EXPENSE, 5000, 77L),                                  // 77 = 已删除的账户
            txn(TxnType.TRANSFER, 8000, cash.id, to = wechat.id, toCents = 8000) // 转入方还在
        )
        val b = bal(list)
        assertEquals(28000L, b[wechat.id])   // 200 + 80：转入方仍应到账
        assertEquals(1000000L, b[bank.id])
        assertEquals(-8000L, b[cash.id])
        assertEquals("只有挂在已删账户上的那笔算孤儿", 1, BalanceMath.orphanCount(list, accounts))
    }

    @Test
    fun `账户不存在时返回 null 而不是 0`() {
        assertNull(BalanceMath.balanceOf(emptyList(), accounts, 999L))
        assertNotNull(BalanceMath.balanceOf(emptyList(), accounts, wechat.id))
    }

    // ---------------- 删除账户前的迁移 ----------------

    @Test
    fun `迁移账目后总资产不变`() {
        val list = listOf(
            txn(TxnType.EXPENSE, 5000, bank.id),
            txn(TxnType.INCOME, 3000, bank.id)
        )
        val before = bal(list).values.sum()
        val moved = AccountMove.remap(list, bank.id, wechat.id)
        assertEquals("两笔都该动", 2, moved.size)
        val after = bal(moved).values.sum()
        assertEquals(before, after)
        assertEquals(0, BalanceMath.orphanCount(moved, accounts))
    }

    @Test
    fun `迁移要把转入方也一起改`() {
        val list = listOf(txn(TxnType.TRANSFER, 10000, bank.id, to = credit.id, toCents = 10000))
        val moved = AccountMove.remap(list, credit.id, wechat.id)
        assertEquals(1, moved.size)
        assertEquals(wechat.id, moved[0].toAccountId)
        assertEquals("转出方不能被动", bank.id, moved[0].accountId)
    }

    @Test
    fun `把两个账户并成一个时不会凭空少钱`() {
        // A→B 的转账，把 A 迁到 B → 变成 B→B（自己转自己），必须净 0
        val list = listOf(txn(TxnType.TRANSFER, 10000, bank.id, to = wechat.id, toCents = 10000))
        val moved = AccountMove.remap(list, bank.id, wechat.id)
        val b = bal(moved)
        assertEquals(20000L, b[wechat.id])
        assertEquals("迁走的账户应归 0（已不受影响）", 1000000L, b[bank.id])
    }

    @Test
    fun `迁移源等于目标时什么都不做`() {
        val list = listOf(txn(TxnType.EXPENSE, 5000, bank.id))
        assertEquals(0, AccountMove.remap(list, bank.id, bank.id).size)
        assertEquals(0, AccountMove.remap(list, 0L, bank.id).size)
    }

    @Test
    fun `attachedTo 要把作为转入方的账目也算进去`() {
        val list = listOf(
            txn(TxnType.EXPENSE, 5000, bank.id),
            txn(TxnType.TRANSFER, 10000, cash.id, to = bank.id, toCents = 10000)
        )
        assertEquals(2, AccountMove.attachedTo(list, bank.id).size)
    }

    // ---------------- 综合 ----------------

    @Test
    fun `一整天的流水最后余额要能对上`() {
        val list = listOf(
            txn(TxnType.EXPENSE, 1050, wechat.id),                                   // 咖啡
            txn(TxnType.TRANSFER, 25000, bank.id, to = wechat.id, toCents = 25000),   // 银行→零钱充值
            txn(TxnType.INCOME, 10000, wechat.id),                                    // 收到别人转账
            txn(TxnType.INCOME, 5000, wechat.id, source = TxnSource.ADJUST)            // 余额校准 +5
        )
        val b = bal(list)
        assertEquals("200 + 250 + 100 + 5 - 10.5", 58950L, b[wechat.id])
        assertEquals("1000 - 250", 975000L, b[bank.id])
        assertEquals("收支不能混进余额口径", 1033950L, b.values.sum())
    }
}
