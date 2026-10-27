package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类准确性回归（v1.4.2）。
 *
 * 全部用例来自**真机抓到的原文**（2026-10-27 的招行短信 / 支付宝 / 微信通知）。
 * 之前的事故链：商户抽不到 → 退成 App 名「招商银行」→ 被学进 merchant_rule →
 * 之后所有招行短信都被强制成「餐饮」（连滴滴出行都是餐饮）。
 * 这里逐条把那条链钉住，删掉任何一环都会红。
 */
class CategoryAccuracyTest {

    private val pe = ParseEngine

    private val cats = listOf(
        Category(id = 10, name = "餐饮", type = TxnType.EXPENSE, keywords = "餐,饭,外卖,美团,咖啡,面馆,滴滴,烧烤"),
        Category(id = 11, name = "交通", type = TxnType.EXPENSE, keywords = "地铁,公交,打车,滴滴,出行,加油"),
        Category(id = 12, name = "其他", type = TxnType.EXPENSE, keywords = ""),
        Category(id = 13, name = "缴费", type = TxnType.EXPENSE, keywords = "缴费,话费,水电"),
        Category(id = 23, name = "待分类", type = TxnType.EXPENSE, keywords = ""),
        Category(id = 20, name = "工资", type = TxnType.INCOME, keywords = "工资,薪资")
    )

    // 真机原文（招行 13:20 瑞幸 / 13:05 手擀面 / 12:49 缴费平台 / 11:59 滴滴）
    private val cmbLuckin =
        "招商银行 · 招商银行 您账户3979于10月27日13:20在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币10.50元"
    private val cmbNoodle =
        "招商银行 · 招商银行 您账户3979于10月27日13:05在【财付通-微信支付-兰溪手擀面（盛夏路店）】发生快捷支付扣款，人民币26.60元"
    private val cmbGov =
        "招商银行 · 招商银行 您账户3979于10月27日12:49在【财付通-微信支付-上海一网通办缴费平台】发生快捷支付扣款，人民币20.00元"
    private val cmbDidi =
        "招商银行 · 招商银行 您账户3979于10月27日11:59在【财付通-微信支付-滴滴出行】发生快捷支付扣款，人民币13.50元"

    @Test
    fun `银行短信的方括号商户能抽出来`() {
        // 原来这里抽出的是 App 名「招商银行」（因为只认「商户:xxx」标签）
        assertEquals("瑞幸咖啡", pe.extractMerchant(cmbLuckin, "招商银行"))
        assertEquals("滴滴出行", pe.extractMerchant(cmbDidi, "招商银行"))
        assertEquals(
            "上海一网通办缴费平台",
            pe.extractMerchant(cmbGov, "招商银行")
        )
    }

    @Test
    fun `渠道前缀与分店括号要剥掉`() {
        assertEquals("瑞幸咖啡", pe.normalizeMerchant("财付通-微信支付-瑞幸咖啡"))
        assertEquals("兰溪手擀面", pe.normalizeMerchant("财付通-微信支付-兰溪手擀面（盛夏路店）"))
        assertEquals("滴滴出行", pe.normalizeMerchant("支付宝-滴滴出行"))
        // 门店名不能再抢主商户
        assertEquals("兰溪手擀面", pe.extractMerchant(cmbNoodle, "招商银行"))
    }

    @Test
    fun `App 名和银行名不算商户`() {
        assertFalse("招商银行 是银行，不是商户", pe.isUsableMerchant("招商银行", "招商银行"))
        assertFalse(pe.isUsableMerchant("微信支付", "微信支付"))
        assertFalse(pe.isUsableMerchant("支付宝", "支付宝"))
        assertFalse("纯分店名不算", pe.isUsableMerchant("盛夏路店"))
        assertFalse(pe.isUsableMerchant("10月22日"))
        assertTrue(pe.isUsableMerchant("瑞幸咖啡", "招商银行"))
        assertTrue(pe.isUsableMerchant("美团外卖", "支付宝"))
    }

    @Test
    fun `微信通知没有商户，拿到的 App 名不许拿去学习`() {
        val m = pe.extractMerchant("微信支付 · 微信支付 已支付¥10.00", "微信支付")
        assertFalse("「$m」不能作为商户记忆", pe.isUsableMerchant(m, "微信支付"))
    }

    @Test
    fun `历史污染的记忆会被自动忽略`() {
        // v1.4.1 在用户手机上真的写进去过「招商银行 → 餐饮」这种行
        val polluted = mapOf(
            "招商银行" to (10L to 0L),
            "盛夏路店" to (10L to 0L)
        )
        assertNull("垃圾键必须被忽略，否则滴滴还是餐饮", pe.matchMerchantRule("招商银行", polluted))
        assertNull(pe.matchMerchantRule("盛夏路店", polluted))
    }

    @Test
    fun `用户教过的商户记忆只做精确或前缀匹配`() {
        val rules = mapOf("瑞幸咖啡" to (10L to 0L), "微信" to (12L to 0L))
        assertEquals(10L, pe.matchMerchantRule("瑞幸咖啡", rules)?.first)
        assertEquals("门店名也要能命中主商户", 10L, pe.matchMerchantRule("瑞幸咖啡国贸店", rules)?.first)
        // 两个字以内的泛词（微信）不许吃遍全场
        assertNull(pe.matchMerchantRule("微信支付", rules))
    }

    @Test
    fun `分类判对：瑞幸餐饮、滴滴交通`() {
        assertEquals(10L, pe.matchCategory(cmbLuckin, "瑞幸咖啡", TxnType.EXPENSE, cats))
        assertEquals(11L, pe.matchCategory(cmbDidi, "滴滴出行", TxnType.EXPENSE, cats))
    }

    @Test
    fun `猜不出来返回 0 而不是塞进其他`() {
        val id = pe.matchCategory("某某不认识的东西 5 元", "", TxnType.EXPENSE, cats)
        assertEquals(0L, id)
        // 而且不能变成「其他」那个 id
        assertTrue(id != 12L)
    }

    @Test
    fun `待分类的名字是固定的`() {
        assertEquals("待分类", ParseEngine.UNCERTAIN_CATEGORY)
    }
}
