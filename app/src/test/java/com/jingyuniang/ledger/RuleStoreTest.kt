package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.RuleStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 规则库（可编辑 JSON）的自检。
 *
 * 这是 P1-4 的关键保证：**用户改坏 JSON 时不能把 App 的识别能力改没**，
 * 所以既要测得「正常文件能加载」，也要测得「坏文件会被拒绝」。
 */
class RuleStoreTest {

    /** 测试工作目录是模块根（app/），直接读源集里的 assets */
    private fun assetsFile() = File("src/main/assets/capture_rules.json")

    @Test
    fun `随包发布的规则文件存在且能解析`() {
        val f = assetsFile()
        assertTrue("assets/capture_rules.json 不存在：${f.absolutePath}", f.exists())
        val rules = RuleStore.parseOrNull(f.readText(), "test")
        assertNotNull("内置规则 JSON 解析失败（格式或词条有问题）", rules)
        rules!!
        assertTrue("规则总量太少：${rules.totalEntries}", rules.totalEntries >= 400)
        println("规则库：${rules.totalEntries} 条，来源 ${rules.source}")
    }

    @Test
    fun `代码内兜底规则同样有效`() {
        val d = RuleStore.DEFAULTS
        assertNull("兜底规则自检失败：${d.validate()}", d.validate())
        assertTrue("兜底规则太少：${d.totalEntries}", d.totalEntries >= 400)
    }

    @Test
    fun `坏的规则文件会被拒绝而不是当成空规则`() {
        // 1) 不是 JSON
        assertNull(RuleStore.parseOrNull("这不是 json", "test"))
        // 2) JSON 但词表为空（用户误删）
        assertNull(RuleStore.parseOrNull("""{"version":1,"adMarkers":[]}""", "test"))
        // 3) 词表里有空串
        assertNull(
            RuleStore.parseOrNull(
                """{"adMarkers":["券",""],"nonTxnMarkers":["账单"],"junk":["验证码"]}""", "test"
            )
        )
        // 4) 条数过少（文件被截断）
        assertNull(
            RuleStore.parseOrNull(
                """{"adMarkers":["券"],"nonTxnMarkers":["账单"],"junk":["验证码"]}""", "test"
            )
        )
    }

    @Test
    fun `导出的 JSON 能被自己再解析回来`() {
        val json = RuleStore.exportJson(RuleStore.DEFAULTS)
        val back = RuleStore.parseOrNull(json, "roundtrip")
        assertNotNull("导出的 JSON 解析不回来", back)
        assertTrue(
            "往返后条数不一致：${RuleStore.DEFAULTS.totalEntries} vs ${back!!.totalEntries}",
            RuleStore.DEFAULTS.totalEntries == back.totalEntries
        )
        assertTrue("往返后营销词丢失", back.adMarkers.contains("立减"))
    }
}