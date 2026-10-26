package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.capture.TxnIdentity
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **规则表全覆盖**：遍历 `CaptureFilters.Tables` / `ParseEngine.Tables` 里的**每一条规则**，
 * 逐条断言它真的起作用。
 *
 * 为什么这么写：规则表是可读的常量清单，但「某个词到底有没有生效」人工看不出来。
 * 这里把每条规则拿去做行为断言 —— 换句话说 **删掉/改坏任何一条，这个文件就会红**。
 * 规则定版说明见 `docs/CAPTURE_RULES_V1.md`。
 */
class CaptureAllRulesTest {

    private val cf = CaptureFilters
    private val f = CaptureFilters.Tables
    private val pe = ParseEngine
    private val pt = ParseEngine.Tables

    private fun run(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            throw AssertionError("【$name】${e.message}", e)
        }
    }

    // ================= 一、营销词表：每条都要能把「纯营销」挡在门外 =================
    @Test
    fun `营销词表逐条生效`() {
        val skipped = ArrayList<String>()
        f.adMarkers.forEach { marker ->
            // 该词本身含「支付证据」时，广告门按设计不生效（需要靠非动账模板/渠道拦），这里跳过并记录
            if (f.payEvidence.any { marker.contains(it) }) {
                skipped.add(marker)
                return@forEach
            }
            run("营销词:$marker") {
                assertFalse(
                    "含「$marker」的纯营销推送应被拦",
                    cf.shouldRead("cmb.pb", "好物推荐 $marker 100 元", "招商银行")
                )
            }
        }
        println("营销词共 ${f.adMarkers.size} 条，其中 ${skipped.size} 条与支付证据重叠（跳过）：$skipped")
    }

    // ================= 二、非动账模板：每条都要拦住「带金额但不是动账」 =================
    @Test
    fun `非动账模板逐条生效`() {
        f.nonTxnMarkers.forEach { marker ->
            run("非动账:$marker") {
                assertFalse(
                    "含「$marker」的通知不该记账",
                    cf.shouldRead("cmb.pb", "您有一笔 100 元 $marker", "招商银行")
                )
            }
        }
        println("非动账模板共 ${f.nonTxnMarkers.size} 条，全部通过")
    }

    // ================= 三、资金转移：每条都要被识别成 TRANSFER，且放行 =================
    @Test
    fun `资金转移词表逐条生效`() {
        f.fundTransferMarkers.forEach { marker ->
            val text = "尾号8888 $marker 人民币 500.00 元"
            run("资金转移:$marker") {
                assertTrue("含「$marker」应识别为资金转移", cf.isFundTransfer(text))
                assertTrue("资金转移应放行给流水线（再决定记转账还是跳过）", cf.shouldRead("cmb.pb", text, "招商银行"))
            }
        }
        println("资金转移词共 ${f.fundTransferMarkers.size} 条，全部通过")
    }

    // ================= 四、强支付模板：内容/电商类命中它才放行 =================
    @Test
    fun `强支付模板逐条生效`() {
        f.strongPayTemplate.forEach { tpl ->
            run("强模板:$tpl") {
                assertTrue("isStrongPayTemplate 应命中「$tpl」", cf.isStrongPayTemplate(tpl))
                assertTrue(
                    "内容类 App 含「$tpl」应放行",
                    cf.shouldRead("com.ss.android.ugc.aweme", "$tpl ¥12.00", "抖音支付")
                )
            }
        }
        println("强支付模板共 ${f.strongPayTemplate.size} 条，全部通过")
    }

    // ================= 五、结构证据：每条都要被判为「有证据」 =================
    @Test
    fun `结构证据词表逐条生效`() {
        f.txnEvidence.forEach { ev ->
            run("证据:$ev") {
                assertTrue("含「$ev」应算有结构证据", cf.hasTxnEvidence("$ev 123"))
            }
        }
        println("结构证据共 ${f.txnEvidence.size} 条，全部通过")
    }

    // ================= 六、渠道过滤：每条营销渠道名都要被识别 =================
    @Test
    fun `营销渠道词逐条生效`() {
        f.marketingChannels.forEach { ch ->
            run("渠道:$ch") {
                assertTrue("渠道「$ch」应判为营销", cf.isMarketingChannel("id_$ch", "$ch"))
                assertFalse(
                    "营销渠道的通知不该记账",
                    cf.shouldRead("cmb.pb", "支付成功 ¥10.00", "招商银行", "ch", "$ch")
                )
            }
        }
        println("营销渠道词共 ${f.marketingChannels.size} 条，全部通过")
    }

    // ================= 七、三类 App 名单：每个包名都要落在正确分类 =================
    @Test
    fun `支付通道包名逐个归类正确`() {
        f.payRailPackages.forEach { pkg ->
            if (cf.isChatApp(pkg)) return@forEach   // 微信等聊天类走特殊分支
            run("payRail:$pkg") { assertTrue("$pkg 应判为支付通道", cf.isPayRail(pkg)) }
        }
        println("支付通道包名 ${f.payRailPackages.size} 个，全部通过")
    }

    @Test
    fun `电商与内容包名逐个归类正确`() {
        f.commercePackages.forEach { pkg ->
            run("commerce:$pkg") { assertTrue("$pkg 应判为电商", cf.isCommerceApp(pkg)) }
        }
        f.contentPackages.forEach { pkg ->
            run("content:$pkg") {
                assertTrue("$pkg 应判为内容类", cf.isContentApp(pkg))
                assertFalse("$pkg 不该被当成支付通道", cf.isPayRail(pkg))
            }
        }
        println("电商 ${f.commercePackages.size} 个 / 内容 ${f.contentPackages.size} 个，全部通过")
    }

    // ================= 八、支付发送方标题 / 转账卡片 / 银行短信句式 =================
    @Test
    fun `支付发送方标题逐条生效`() {
        f.paySourceMarkers.forEach { m ->
            run("paySource:$m") { assertTrue("标题含「$m」应判为支付发送方", cf.isPaySourceTitle(m)) }
        }
    }

    @Test
    fun `转账卡片词逐条放行`() {
        f.transferMarkers.forEach { m ->
            // 转账卡片有时不带金额，只要命中卡片标记就该放行给解析引擎
            run("transfer:$m") {
                assertTrue("聊天里含「$m」应放行", cf.shouldRead("com.tencent.mm", "[转账] $m", "老张"))
            }
        }
    }

    @Test
    fun `银行短信句式在群里逐条被拦`() {
        f.bankTextMarkers.forEach { m ->
            run("bankText:$m") {
                assertFalse(
                    "微信群里含银行句式「$m」的转发不该记账",
                    cf.shouldRead("com.tencent.mm", "老张: $m 消费人民币 48.00 元", "家人群")
                )
            }
        }
    }

    // ================= 九、垃圾词 & 规格单位（ParseEngine） =================
    @Test
    fun `垃圾词逐条生效`() {
        pt.junk.forEach { j ->
            run("junk:$j") { assertTrue("含「$j」应判为垃圾", pe.isJunk("您有一条${j}信息")) }
        }
    }

    @Test
    fun `规格单位逐条不会当成金额`() {
        pt.specUnits.forEach { unit ->
            run("spec:$unit") {
                // 「1999<单位>」不是金额；用无其他数字的短文本，避免被别的候选救回来
                val amt = pe.extractAmount("1999$unit")
                assertTrue("「1999${unit}」不该解析出金额，实际 $amt", amt == 0L)
            }
        }
    }

    // ================= 十、方向判断 / 金额解析 / 商户提取 =================
    @Test
    fun `收入词逐条判为收入`() {
        pt.incomeWords.forEach { w ->
            run("income:$w") { assertEquals("含「$w」应判收入", TxnType.INCOME, pe.detectDirection("$w 100 元")) }
        }
    }

    @Test
    fun `支出词逐条判为支出`() {
        pt.expenseWords.forEach { w ->
            run("expense:$w") { assertEquals("含「$w」应判支出", TxnType.EXPENSE, pe.detectDirection("$w 100 元")) }
        }
    }

    @Test
    fun `金额解析覆盖各种写法`() {
        assertEquals(1000L, pe.extractAmount("支付 10.00 元"))
        assertEquals(10000L, pe.extractAmount("支付 100.00 元"))
        assertEquals(100000L, pe.extractAmount("支付 1000.00 元"))      // 四位数（曾经的 bug）
        assertEquals(1000000L, pe.extractAmount("支付 10000.00 元"))    // 五位数
        assertEquals(123456L, pe.extractAmount("支付 1,234.56 元"))     // 千分位
        assertEquals(1230000L, pe.extractAmount("支付 12,300.00 元"))
        assertEquals(1000L, pe.extractAmount("花了 10 块"))              // 一位小数都没有
        assertEquals(1050L, pe.extractAmount("花了 10.5 元"))            // 一位小数
        assertEquals(1000L, pe.extractAmount("微信 10 块"))              // 无「元」
        assertEquals(0L, pe.extractAmount("十分钟后开会"))                // 没有单位的中文数字
        assertEquals(1000L, pe.extractAmount("发微信开销了十块钱"))        // 中文口语
        assertEquals(2500L, pe.extractAmount("午饭二十五块"))
        assertEquals(8000L, pe.extractAmount("满100减20，实付 80.00 元"))   // 取「实付」那个
    }

    @Test
    fun `商户提取覆盖常见句式`() {
        assertEquals("星巴克咖啡", pe.extractMerchant("支付成功 ¥25.00 收款方：星巴克咖啡", ""))
        assertEquals("美团外卖", pe.extractMerchant("你已成功付款 12.50 元，商户：美团外卖", ""))
        assertEquals("老张", pe.extractMerchant("向老张付款 100.00 元", ""))
        // 日期/卡号不能被当商户
        assertNotEquals("10月22日12:30", pe.extractMerchant("您账户于10月22日12:30消费48.00元", "银行"))
    }

    @Test
    fun `账户匹配按关键词与卡尾号`() {
        val accounts = listOf(
            com.jingyuniang.ledger.data.Account(id = 1, name = "现金",
                type = com.jingyuniang.ledger.data.AccountType.CASH, keywords = "现金"),
            com.jingyuniang.ledger.data.Account(id = 2, name = "微信",
                type = com.jingyuniang.ledger.data.AccountType.WECHAT, keywords = "微信,微信支付,零钱"),
            com.jingyuniang.ledger.data.Account(id = 4, name = "招行卡",
                type = com.jingyuniang.ledger.data.AccountType.BANK, keywords = "招商银行", cardTail = "8888")
        )
        assertEquals(2L, pe.matchAccount(null, "微信支付 支付成功", accounts)?.id)
        assertEquals(4L, pe.matchAccount(null, "您尾号8888的账户消费", accounts)?.id)
        assertEquals(4L, pe.matchAccount("com.cmbchina.ccd.pluto.cmbActivity", "任意文本", accounts)?.id)
        assertEquals(null, pe.matchAccount(null, "毫无线索的一句话", accounts))
    }

    @Test
    fun `分类匹配按关键词与兜底其他`() {
        val cats = listOf(
            Category(id = 10, name = "餐饮", type = TxnType.EXPENSE, keywords = "餐,饭,外卖,星巴克"),
            Category(id = 11, name = "交通", type = TxnType.EXPENSE, keywords = "地铁,打车"),
            Category(id = 12, name = "其他", type = TxnType.EXPENSE, keywords = ""),
            Category(id = 20, name = "工资", type = TxnType.INCOME, keywords = "工资")
        )
        assertEquals(10L, pe.matchCategory("星巴克咖啡 25 元", "星巴克咖啡", TxnType.EXPENSE, cats))
        assertEquals(11L, pe.matchCategory("打车 23 元", "", TxnType.EXPENSE, cats))
        assertEquals(12L, pe.matchCategory("不知道是什么 5 元", "", TxnType.EXPENSE, cats))
        assertEquals(20L, pe.matchCategory("工资入账", "", TxnType.INCOME, cats))
    }

    // ================= 十一、置信度分支 + 交易身份 =================
    @Test
    fun `置信度四条分支`() {
        val pipe = com.jingyuniang.ledger.capture.CapturePipeline
        val N = com.jingyuniang.ledger.data.TxnSource.NOTIFY
        val A = com.jingyuniang.ledger.data.TxnSource.ACCESS
        val S = com.jingyuniang.ledger.data.TxnSource.SMS

        // 1) 读屏永远封顶 79
        assertEquals(79, pipe.effectiveConfidence(A, "cmb.pb", "招商银行", "账户 消费 48.00 元", 95))
        // 2) 通知有结构证据 → 按原分
        assertEquals(95, pipe.effectiveConfidence(N, "cmb.pb", "招商银行", "尾号8888 消费 48.00 元", 95))
        // 3) 通知无证据但有强模板 → 按原分
        assertEquals(95, pipe.effectiveConfidence(N, "com.tencent.mm", "微信支付", "已支付 ¥10.00", 95))
        // 4) 两者皆无 → 封顶 79
        assertEquals(59, pipe.effectiveConfidence(N, "com.tencent.mm", "某人", "给你 10.00 元", 95)) // 封顶 79 再降 20
        // 5) 聊天群消息再降 20
        assertEquals(75, pipe.effectiveConfidence(N, "com.tencent.mm", "家人群", "尾号8888 消费 48.00 元", 95))
        // 6) 短信保持原分
        assertEquals(95, pipe.effectiveConfidence(S, null, "95555", "消费 48.00 元", 95))
    }

    @Test
    fun `交易身份四种形态`() {
        val t = 1_700_000_000_000L
        assertTrue(TxnIdentity.of("cmb.pb", "交易单号: AB123456 消费 48.00 元", 4800, t).startsWith("ref:"))
        assertTrue(TxnIdentity.of("cmb.pb", "尾号8888 消费 48.00 元", 4800, t).startsWith("card:8888|"))
        assertTrue(TxnIdentity.of("com.eg.android.AlipayGphone", "付款 48.00 元", 4800, t).startsWith("app:"))
        // 同一笔跨渠道同桶 → 相同
        assertEquals(
            TxnIdentity.of("cmb.pb", "尾号8888 消费 48.00 元", 4800, t),
            TxnIdentity.of("com.android.mms", "尾号8888 消费 48.00 元", 4800, t + 60_000)
        )
        // 5 分钟桶外 → 不同
        assertNotEquals(
            TxnIdentity.of("cmb.pb", "尾号8888 消费 48.00 元", 4800, t),
            TxnIdentity.of("cmb.pb", "尾号8888 消费 48.00 元", 4800, t + TxnIdentity.BUCKET_MS + 1)
        )
    }

    // ================= 十二、规则表总量自检（防止有人删规则） =================
    @Test
    fun `规则表规模不低于基线`() {
        assertTrue("营销词 ≥ 40", f.adMarkers.size >= 40)
        assertTrue("非动账模板 ≥ 30", f.nonTxnMarkers.size >= 30)
        assertTrue("资金转移词 ≥ 8", f.fundTransferMarkers.size >= 8)
        assertTrue("强支付模板 ≥ 12", f.strongPayTemplate.size >= 12)
        assertTrue("结构证据 ≥ 18", f.txnEvidence.size >= 18)
        assertTrue("营销渠道 ≥ 10", f.marketingChannels.size >= 10)
        assertTrue("支付通道包 ≥ 18", f.payRailPackages.size >= 18)
        assertTrue("电商包 ≥ 12", f.commercePackages.size >= 12)
        assertTrue("内容包 ≥ 12", f.contentPackages.size >= 12)
        assertTrue("垃圾词 ≥ 30", pt.junk.size >= 30)
        assertTrue("规格单位 ≥ 20", pt.specUnits.size >= 20)
        println(
            "规则规模：营销${f.adMarkers.size} 非动账${f.nonTxnMarkers.size} 资金转移${f.fundTransferMarkers.size} " +
                "强模板${f.strongPayTemplate.size} 证据${f.txnEvidence.size} 渠道${f.marketingChannels.size} " +
                "支付通道${f.payRailPackages.size} 电商${f.commercePackages.size} 内容${f.contentPackages.size} " +
                "垃圾${pt.junk.size} 规格单位${pt.specUnits.size}"
        )
    }
}
