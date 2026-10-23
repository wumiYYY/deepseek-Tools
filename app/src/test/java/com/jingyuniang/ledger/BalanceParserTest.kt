package com.jingyuniang.ledger

import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.ocr.BalanceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「更新余额 · 截图识别」的纯 JVM 单测：OCR 文本 → 余额（分） */
class BalanceParserTest {

    @Test
    fun `支付宝余额页取余额而不是余额宝总金额`() {
        val text = """
            我的
            余额宝
            总金额
            ¥ 12,345.67
            昨日收益 +1.23
            累计收益 456.78
            余额
            ¥ 256.80
        """.trimIndent()
        val hit = BalanceParser.parse(text)!!
        assertEquals(25680L, hit.cents)
        assertEquals("余额", hit.label)
    }

    @Test
    fun `微信钱包取零钱不取零钱通`() {
        val text = """
            钱包
            零钱
            ¥ 88.50
            零钱通
            ¥ 1,000.00
        """.trimIndent()
        val hit = BalanceParser.parse(text)!!
        assertEquals(8850L, hit.cents)
        assertEquals("零钱", hit.label)
    }

    @Test
    fun `标签与数值同行`() {
        assertEquals(100000L, BalanceParser.parse("可用余额 ¥1,000.00")!!.cents)
        assertEquals(1234560L, BalanceParser.parse("账户余额 12,345.6元")!!.cents)
    }

    @Test
    fun `限额和收益行不参与`() {
        val text = """
            余额
            ¥ 100.00
            单笔限额
            ¥ 50,000.00
            昨日收益 +12.34
        """.trimIndent()
        assertEquals(10000L, BalanceParser.parse(text)!!.cents)
    }

    @Test
    fun `百分比和年份日期不误判`() {
        val text = """
            可用余额
            ¥ 1,234.56
            年化 3.5% 2026年 10-23
        """.trimIndent()
        assertEquals(123456L, BalanceParser.parse(text)!!.cents)
    }

    @Test
    fun `没有余额标签就返回空`() {
        assertNull(BalanceParser.parse("微信支付\n午饭 25.00\n支付成功"))
        assertNull(BalanceParser.parse(""))
        assertNull(BalanceParser.parse(null))
    }

    @Test
    fun `总资产兜底`() {
        assertEquals(97500L, BalanceParser.parse("总资产 ¥975")!!.cents)
        assertEquals(100000L, BalanceParser.parse("总金额\n¥1,000")!!.cents)
    }

    @Test
    fun `额度类词汇不算余额`() {
        assertNull(BalanceParser.parse("花呗额度 ¥5,000.00\n待还 ¥200.00"))
    }

    @Test
    fun `OCR 把零钱读成零线也要认出来`() {
        // 真机实测：ML Kit 中文模型会把「零钱」读成「零线」、「余额」读成「佘额」
        val text = """
            钱包
            零线
            ¥ 88.50
            零钱通
            1,000.00
        """.trimIndent()
        assertEquals(8850L, BalanceParser.parse(text)!!.cents)
        assertEquals(25680L, BalanceParser.parse("佘额\n¥ 256.80")!!.cents)
    }

    @Test
    fun `繁体标签也能认`() {
        val text = """
            我的
            餘額寶
            總金額
            ¥ 12,345.67
            餘額
            ¥ 256.80
        """.trimIndent()
        assertEquals(25680L, BalanceParser.parse(text)!!.cents)
    }

    // ---------- 猜账户（截图 OCR 不能把支付宝余额写进微信） ----------
    @Test
    fun `余额截图能猜出是哪个账户`() {
        fun t(s: String) = BalanceParser.guessAccountType(s)
        assertEquals(AccountType.WECHAT, t("钱包 零线 ¥88.50 零钱通 1,000.00"))
        assertEquals(AccountType.ALIPAY, t("我的 餘額寶 總金額 ¥12,345.67 余额 ¥256.80"))
        assertEquals(AccountType.ALIPAY, t("支付宝 余额 ¥20.00"))
        assertEquals(AccountType.BANK, t("招商银行 储蓄卡 尾号8888 可用余额 ¥1,000.00"))
        assertEquals(AccountType.CREDIT, t("信用卡 本期账单 ¥500.00"))
        assertEquals(AccountType.CASH, t("现金 ¥100.00"))
        // 微信钱包首页也会列「银行卡」入口，不能被它带偏（真机踩过）
        assertEquals(AccountType.WECHAT, t("钱包 零钱 ¥88.50 零钱通 1,000.00 银行卡 ¥2,000.00"))
        assertNull(t("随便一张截图 没有品牌词"))
        assertNull(BalanceParser.guessAccountType(null))
    }
}
