package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureBlocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「以后别再抓这类」黑名单的匹配规则（纯 JVM） */
class CaptureBlocklistTest {

    @Test
    fun `屏蔽整个 App 时不看关键词`() {
        val list = listOf(CaptureBlocklist.encode("cmb.pb", ""))
        assertTrue(CaptureBlocklist.matches(list, "cmb.pb", "您尾号8888消费48.00元"))
        assertTrue(CaptureBlocklist.matches(list, "cmb.pb", "随便什么内容"))
        assertFalse(CaptureBlocklist.matches(list, "com.eg.android.AlipayGphone", "您尾号8888消费48.00元"))
    }

    @Test
    fun `只屏蔽含关键词的通知`() {
        val list = listOf(CaptureBlocklist.encode("cmb.pb", "招商银行"))
        assertTrue(CaptureBlocklist.matches(list, "cmb.pb", "招商银行 · 您尾号8888消费48.00元"))
        assertFalse(CaptureBlocklist.matches(list, "cmb.pb", "尾号8888消费48.00元"))
    }

    @Test
    fun `通配 App 只按关键词拦`() {
        val list = listOf(CaptureBlocklist.encode("*", "满减"))
        assertTrue(CaptureBlocklist.matches(list, "any.pkg", "消费满100元减20 满减活动"))
        assertFalse(CaptureBlocklist.matches(list, "any.pkg", "消费 48.00 元"))
    }

    @Test
    fun `空黑名单不拦任何东西`() {
        assertFalse(CaptureBlocklist.matches(emptyList(), "cmb.pb", "消费48.00元"))
    }

    @Test
    fun `条目能解析回 App 与关键词`() {
        val e = CaptureBlocklist.encode("cmb.pb", "招商银行")
        assertEquals("cmb.pb", CaptureBlocklist.pkgOf(e))
        assertEquals("招商银行", CaptureBlocklist.keywordOf(e))
        assertEquals("cmb.pb（全部）", CaptureBlocklist.describe(CaptureBlocklist.encode("cmb.pb", "")))
        assertEquals("招商银行 · 含「满减」", CaptureBlocklist.describe(CaptureBlocklist.encode("cmb.pb", "满减"), "招商银行"))
        assertEquals("所有应用 · 含「满减」", CaptureBlocklist.describe(CaptureBlocklist.encode("*", "满减")))
    }

    @Test
    fun `关键词优先取商户名`() {
        assertEquals("招商银行", CaptureBlocklist.guessKeyword("招商银行 · 您尾号8888", "招商银行"))
        assertEquals("美团外卖", CaptureBlocklist.guessKeyword("随便一段", "美团外卖"))
    }
}
