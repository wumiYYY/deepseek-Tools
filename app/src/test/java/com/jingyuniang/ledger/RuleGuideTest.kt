package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.RuleGuide
import com.jingyuniang.ledger.capture.RuleStore
import com.jingyuniang.ledger.capture.appendWords
import com.jingyuniang.ledger.capture.removeWord
import com.jingyuniang.ledger.capture.table
import com.jingyuniang.ledger.capture.withTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「说人话的规则」（Backlog P1）的自检。
 *
 * 这一层最容易出的不是崩，而是**说了假话**：推荐的词根本落不进任何表、
 * 或者加完把规则改坏、或者推荐一个已经存在的词让用户以为没生效。
 * 所以这里逐条把 25 条建议都真的加一遍。
 */
class RuleGuideTest {

    private fun assetsRules() =
        RuleStore.parseOrNull(File("src/main/assets/capture_rules.json").readText(), "test")!!

    @Test
    fun `18 张表的说明与规则键一一对应`() {
        assertEquals("说明条数与 TABLE_KEYS 不一致", RuleStore.TABLE_KEYS.size, RuleGuide.tables.size)
        assertEquals(RuleStore.TABLE_KEYS, RuleGuide.tables.map { it.key })
        RuleGuide.tables.forEach {
            assertTrue("${it.key} 缺少人话标题", it.title.isNotBlank())
            assertTrue("${it.key} 缺少用途说明", it.purpose.isNotBlank())
            assertTrue("${it.key} 缺少风险提醒", it.caution.isNotBlank())
            assertNotNull("${it.key} 用 table() 查不到", RuleGuide.table(it.key))
        }
    }

    @Test
    fun `每条建议都能真的落到某张表里`() {
        RuleGuide.tips.filter { it.tableKey.isNotBlank() }.forEach { tip ->
            val appended = RuleStore.DEFAULTS.appendWords(tip.tableKey, tip.words)
            assertNotNull("「${tip.symptom}」的目标表不存在：${tip.tableKey}", appended)
            appended!!
            assertNull("「${tip.symptom}」加完规则不自检：${appended.validate()}", appended.validate())
            val table = appended.table(tip.tableKey)!!
            tip.words.forEach { w ->
                assertTrue("「${tip.symptom}」的词「$w」没进表", table.contains(w))
            }
        }
    }

    @Test
    fun `建议词不许是单字宽泛词`() {
        RuleGuide.tips.forEach { tip ->
            tip.words.forEach { w ->
                assertTrue(
                    "「${tip.symptom}」建议了太短的词「$w」—— 历史上 junk 塞「领取」误杀过真实红包（HANDOFF §6.9）",
                    w.trim().length >= 2
                )
            }
        }
    }

    @Test
    fun `建议词必须是内置规则里还没有的`() {
        val assets = assetsRules()
        RuleGuide.tips.filter { it.tableKey.isNotBlank() }.forEach { tip ->
            val plain = RuleStore.DEFAULTS.table(tip.tableKey)!!
            val shipped = assets.table(tip.tableKey)!!
            tip.words.forEach { w ->
                assertTrue("「$w」已经在代码默认规则里，推荐它没有意义", !plain.contains(w))
                assertTrue("「$w」已经在内置 assets 规则里，推荐它没有意义", !shipped.contains(w))
            }
        }
    }

    @Test
    fun `18 张表都能单独读写`() {
        RuleStore.TABLE_KEYS.forEach { key ->
            assertNotNull("table($key) 返回 null", RuleStore.DEFAULTS.table(key))
            assertNotNull("withTable($key) 返回 null", RuleStore.DEFAULTS.withTable(key, listOf("x1", "x2")))
            assertEquals(2, RuleStore.countOf(key, RuleStore.DEFAULTS.withTable(key, listOf("x1", "x2"))!!))
        }
        // 不认识的键不能崩
        assertNull(RuleStore.DEFAULTS.table("nope"))
        assertNull(RuleStore.DEFAULTS.withTable("nope", listOf("x")))
        assertEquals(0, RuleStore.countOf("nope"))
        assertNull(RuleStore.DEFAULTS.appendWords("nope", listOf("x")))
        assertNull(RuleStore.DEFAULTS.removeWord("nope", "x"))
    }

    @Test
    fun `重复加同一个词不会让表变长`() {
        val one = RuleStore.DEFAULTS.appendWords("adMarkers", listOf("券后价"))!!
        val sizeAfterFirst = one.adMarkers.size
        val two = one.appendWords("adMarkers", listOf("券后价"))!!
        assertEquals("重复加词把表撑长了", sizeAfterFirst, two.adMarkers.size)
        assertSame("没变化时应返回同一个对象（UI 用它判断无需刷新）", one, two)
        // 全是空白字符等于没加
        assertSame(one, one.appendWords("adMarkers", listOf("  ", "")))
    }

    @Test
    fun `加错了能撤回`() {
        val added = RuleStore.DEFAULTS.appendWords("nonTxnMarkers", listOf("本期应还"))!!
        assertTrue(added.nonTxnMarkers.contains("本期应还"))
        val removed = added.removeWord("nonTxnMarkers", "本期应还")!!
        assertTrue("没有真的删掉", !removed.nonTxnMarkers.contains("本期应还"))
        assertEquals(RuleStore.DEFAULTS.nonTxnMarkers.size, removed.nonTxnMarkers.size)
        // 词本来就不在表里 → 返回同一个对象
        assertSame(added, added.removeWord("nonTxnMarkers", "根本不存在的词"))
    }

    @Test
    fun `加词后导出再解析不丢词`() {
        // addWords 的持久化路径就是 exportJson 写文件、下次启动 parseOrNull 读回来，
        // 这条测的是那条路径没把词丢掉（丢了用户会以为"加了没用"）
        val tip = RuleGuide.tips.first { it.tableKey == "adMarkers" }
        val next = RuleStore.DEFAULTS.appendWords(tip.tableKey, tip.words)!!
        val back = RuleStore.parseOrNull(RuleStore.exportJson(next), "roundtrip")
        assertNotNull("加词后的规则导出再解析失败", back)
        back!!
        assertEquals(next.totalEntries, back.totalEntries)
        tip.words.forEach { assertTrue("往返后丢了「$it」", back.adMarkers.contains(it)) }
    }

    @Test
    fun `把全部建议都加一遍规则依然合法`() {
        var rules = RuleStore.DEFAULTS
        RuleGuide.tips.filter { it.tableKey.isNotBlank() }.forEach { tip ->
            rules = rules.appendWords(tip.tableKey, tip.words)!!
        }
        assertNull("把所有建议都加上之后规则不自检：${rules.validate()}", rules.validate())
        assertTrue("加完反而变少了", rules.totalEntries > RuleStore.DEFAULTS.totalEntries)
        // 还能被解析回来（写文件后下次启动要能读）
        assertNotNull(RuleStore.parseOrNull(RuleStore.exportJson(rules), "all"))
    }

    @Test
    fun `搜索能命中常见抱怨`() {
        mapOf(
            "淘宝" to "adMarkers",
            "账单" to "nonTxnMarkers",
            "额度" to "nonTxnMarkers",
            "退款" to "",
            "双记" to "",
            "快递" to "nonTxnMarkers"
        ).forEach { (q, expectedTable) ->
            val hits = RuleGuide.search(q)
            assertTrue("搜「$q」什么都没有", hits.isNotEmpty())
            assertTrue(
                "搜「$q」没命中预期的表「$expectedTable」：${hits.map { it.tableKey + "|" + it.symptom }}",
                hits.any { it.tableKey == expectedTable }
            )
        }
    }

    @Test
    fun `搜索的边界：空查询给高频条目，怪词给空`() {
        val all = RuleGuide.search("")
        assertTrue("空查询应给出前几条建议", all.isNotEmpty())
        assertEquals("空查询的条数应等于 limit", 8, all.size)
        assertTrue("怪词不该硬凑结果", RuleGuide.search("zzz不存在的症状").isEmpty())
        // 多个词用顿号/空格分隔也要能搜
        assertTrue(RuleGuide.search("微信、转账").isNotEmpty())
    }

    @Test
    fun `没有可加词的建议只能是解释型的`() {
        RuleGuide.tips.forEach { tip ->
            if (tip.tableKey.isBlank()) {
                assertTrue("解释型建议不该带词：${tip.symptom}", tip.words.isEmpty())
                assertTrue("解释型建议要写清原因", tip.fix.length >= 30)
            } else {
                assertTrue("改了表却没给词：${tip.symptom}", tip.words.isNotEmpty())
                assertNotNull("目标表不认识：${tip.tableKey}", RuleGuide.table(tip.tableKey))
            }
        }
    }
}
