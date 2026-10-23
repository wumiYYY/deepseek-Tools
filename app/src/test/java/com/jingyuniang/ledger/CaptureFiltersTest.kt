package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
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
}
