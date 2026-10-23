package com.jingyuniang.ledger.ai

import org.json.JSONObject

/**
 * 从模型回复里抠出第一个完整的 JSON 对象。
 *
 * 踩过的坑（P0-3）：原来写的是
 *   `raw.substringAfter("{", "{").substringBeforeLast("}")`
 * 当模型回复本身就是纯 JSON（最常见的情况）时，substringAfter 会把开头的 `{` 一起吃掉，
 * 再 substringBeforeLast 又砍掉结尾的 `}`，于是拿到 `"tool":"addTxn",...` 这种半截串，
 * JSONObject 必然抛异常 → decide() 返回 null → 「AI 判断对了却什么都没执行」。
 * 这里统一按「第一个 { 到最后一个 }」取，顺带剥掉 markdown 代码块。
 */
internal fun parseJsonObject(raw: String?): JSONObject? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = s.indexOf('{')
    val end = s.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull()
}
