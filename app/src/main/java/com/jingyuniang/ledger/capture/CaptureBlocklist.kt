package com.jingyuniang.ledger.capture

/**
 * 「以后别再抓这类」的黑名单。
 *
 * 为什么不放数据库表：加表=要改 Room schema 版本 + 写迁移，一旦迁移写错就会触发
 * `fallbackToDestructiveMigration()` 清空用户账本。黑名单是低频小数据，放进 DataStore
 * （JSON 字符串集合）更安全，也让它随「设置」一起被备份带走。
 *
 * 条目格式（用 `|` 分隔，避免关键词里含分隔符问题）：
 *   `"com.cmbchina.ccd.pluto.cmbActivity|"`              → 这个 App 的通知全都不抓
 *   `"com.cmbchina.ccd.pluto.cmbActivity|招商银行"`      → 只屏蔽「这个 App + 含该关键词」的通知
 *   `"*|满减"`                                          → 不分 App，只要含这个关键词就不抓
 *
 * 纯函数，可直接单测。
 */
object CaptureBlocklist {

    const val WILDCARD = "*"

    fun encode(pkg: String, keyword: String): String =
        "${pkg.trim()}|${keyword.trim().take(30)}"

    fun pkgOf(entry: String): String = entry.substringBefore('|').trim()

    fun keywordOf(entry: String): String = entry.substringAfter('|', "").trim()

    /** 人性化展示 */
    fun describe(entry: String, appName: String = ""): String {
        val pkg = pkgOf(entry)
        val kw = keywordOf(entry)
        val who = when (pkg) {
            WILDCARD -> "所有应用"
            else -> appName.ifBlank { pkg }
        }
        return if (kw.isBlank()) "$who（全部）" else "$who · 含「$kw」"
    }

    /**
     * 这条通知/页面文字是否命中黑名单。
     * @param blocklist 形如 ["com.cmbchina…|", "*|满减"]
     */
    fun matches(blocklist: Collection<String>, pkg: String, text: String): Boolean {
        if (blocklist.isEmpty()) return false
        for (e in blocklist) {
            val bp = pkgOf(e)
            val kw = keywordOf(e)
            val pkgOk = bp == WILDCARD || bp.equals(pkg, ignoreCase = true)
            if (!pkgOk) continue
            if (kw.isBlank()) return true
            if (text.contains(kw, ignoreCase = true)) return true
        }
        return false
    }

    /** 从一条抓取记录里猜一个「有辨识度的关键词」，给「只屏蔽这类」用 */
    fun guessKeyword(rawText: String, merchant: String): String {
        if (merchant.isNotBlank() && merchant.length >= 2) return merchant.take(12)
        // 退化成原文里第一段像「标题」的文字
        return rawText.split("·", "\n", " ")
            .map { it.trim() }
            .firstOrNull { it.length in 4..12 } ?: ""
    }
}
