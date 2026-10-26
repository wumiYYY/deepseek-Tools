package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些通知才该被当成账单」的回归测试。
 * 核心场景：QQ/微信群里的银行短信转发文本 **不能**被记成账。
 */
class CaptureFiltersTest {

    private val bankChatText =
        "老张: 【招商银行】您账户尾号8888于10月22日12:30消费人民币48.00元，余额1,234.56元。"

    @Test
    fun `QQ群里的银行短信转发不算账`() {
        assertFalse(CaptureFilters.shouldRead("com.tencent.mobileqq", bankChatText, "大学同学群(6)"))
    }

    @Test
    fun `QQ私聊里的银行短信转发也不算账`() {
        assertFalse(CaptureFilters.shouldRead("com.tencent.mobileqq", bankChatText, "老张"))
    }

    @Test
    fun `微信群里的银行短信转发不算账`() {
        assertFalse(CaptureFilters.shouldRead("com.tencent.mm", bankChatText, "家人群"))
    }

    @Test
    fun `微信支付公众号的通知正常入账`() {
        assertTrue(
            CaptureFilters.shouldRead(
                "com.tencent.mm",
                "支付成功，已支付 ¥25.00 元，收款方：星巴克咖啡",
                "微信支付"
            )
        )
    }

    @Test
    fun `QQ钱包通知正常入账`() {
        assertTrue(
            CaptureFilters.shouldRead(
                "com.tencent.mobileqq",
                "QQ钱包支付成功，消费 12.50 元",
                "QQ钱包"
            )
        )
    }

    @Test
    fun `钉钉群消息不算账`() {
        assertFalse(
            CaptureFilters.shouldRead(
                "com.alibaba.android.rimet",
                "财务: 本笔消费 200 元已报销，请查收",
                "部门群"
            )
        )
    }

    @Test
    fun `银行 App 自己的通知照常抓`() {
        assertTrue(
            CaptureFilters.shouldRead(
                "com.cmbchina.ccd.pluto.cmbActivity",
                "您尾号8888的账户支出人民币48.00元",
                "招商银行"
            )
        )
    }

    @Test
    fun `银行动账短信照常抓`() {
        assertTrue(
            CaptureFilters.shouldRead(
                "com.android.mms",
                "【招商银行】您账户尾号8888于12:30消费人民币48.00元",
                "95555"
            )
        )
    }

    @Test
    fun `聊天类与支付类判定互斥`() {
        assertTrue(CaptureFilters.isChatApp("com.tencent.mobileqq"))
        assertFalse(CaptureFilters.isPaymentApp("com.tencent.mobileqq"))
        assertTrue(CaptureFilters.isPaymentApp("com.eg.android.AlipayGphone"))
        assertFalse(CaptureFilters.isChatApp("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `没有金额的群消息直接不算`() {
        assertFalse(CaptureFilters.shouldRead("com.tencent.mobileqq", "在吗？晚上一起吃饭", "老李"))
    }

    @Test
    fun `别人给你转账的聊天通知要抓`() {
        assertTrue(
            CaptureFilters.shouldRead(
                "com.tencent.mm",
                "[转账]100.00元 请收款",
                "老张"
            )
        )
    }

    @Test
    fun `微信红包通知要抓`() {
        assertTrue(
            CaptureFilters.shouldRead("com.tencent.mm", "[微信红包] 恭喜发财，大吉大利", "老婆")
        )
    }

    @Test
    fun `群里转发的银行短信仍要拦`() {
        val text = "老张: 您尾号8888账户于今日消费人民币48.00元，余额1,234.56元"
        assertFalse(CaptureFilters.shouldRead("com.tencent.mm", text, "家人群(12)"))
    }

    @Test
    fun `转账通知优先判为收入`() {
        val r = com.jingyuniang.ledger.capture.ParseEngine.detectDirection("你收到一笔转账 100.00 元 请收款")
        assertTrue(r == com.jingyuniang.ledger.data.TxnType.INCOME)
    }

    // ---------- 2026-10 抓取准确率：待确认爆炸的几类来源 ----------

    @Test
    fun `银行账单提醒带金额也不算动账`() {
        assertFalse(
            CaptureFilters.shouldRead(
                "cmb.pb",
                "【招商银行】您信用卡本期账单金额 1,234.56 元，最后还款日 11月5日",
                "招商银行"
            )
        )
        assertFalse(
            CaptureFilters.shouldRead("com.icbc", "您尾号8888信用卡额度已提升至 50000 元", "工商银行")
        )
    }

    @Test
    fun `营销渠道的推送直接丢`() {
        assertFalse(
            CaptureFilters.shouldRead(
                "cmb.pb", "消费满100元立减20元，速来领取", "招商银行", "marketing_1", "营销活动"
            )
        )
        // 同样的文案但渠道是交易提醒 → 仍按内容规则判（含营销词且无动账证据 → 丢）
        assertFalse(
            CaptureFilters.shouldRead(
                "cmb.pb", "消费满100元立减20元，速来领取", "招商银行", "txn_1", "交易提醒"
            )
        )
    }

    @Test
    fun `账单列表页一次多笔金额整段不算`() {
        val listPage = "支付 25.00 元 星巴克 支付 38.50 元 麦当劳 支付 12.00 元 地铁 支付 9.90 元 便利店"
        assertFalse(CaptureFilters.shouldRead("com.eg.android.AlipayGphone", listPage, "账单"))
        assertTrue(CaptureFilters.looksLikeStatementList(listPage))
        assertEquals(4, CaptureFilters.amountCount(listPage))
    }

    @Test
    fun `读屏只认支付结果页`() {
        assertTrue(CaptureFilters.isPaymentResultPage("支付成功 ¥25.00 收款方 星巴克咖啡"))
        assertFalse(CaptureFilters.isPaymentResultPage("账单 支付 25.00 元 支付 38.50 元 支付 12.00 元"))
        // 结果页文案但文本超长（列表页）→ 不算
        val long = "支付成功" + " 明细".repeat(200)
        assertFalse(CaptureFilters.isPaymentResultPage(long))
    }

    @Test
    fun `有结构证据才算可信交易`() {
        assertTrue(CaptureFilters.hasTxnEvidence("您尾号8888的账户支出人民币48.00元，余额1,234.56元"))
        assertTrue(CaptureFilters.hasTxnEvidence("支付成功 ¥25.00，收款方：星巴克"))
        assertFalse(CaptureFilters.hasTxnEvidence("支付成功 ¥25.00"))
    }
}
