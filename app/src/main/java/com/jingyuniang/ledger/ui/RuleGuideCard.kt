package com.jingyuniang.ledger.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyuniang.ledger.capture.RuleGuide
import com.jingyuniang.ledger.capture.RuleStore
import com.jingyuniang.ledger.capture.RuleTip

/**
 * 「说人话的规则」（Backlog P1 规则卡人话索引）。
 *
 * 用户不懂 18 张表，只知道自己被什么烦到了 / 什么账记错了。
 * 这里：输入症状 → 给出「该动哪张表 + 该加哪几个词」→ 一键加词并立即生效。
 * 加词走 [RuleStore.addWords]（写用户规则文件，和「导入规则」同一条路径，重启也不丢）。
 */
@Composable
fun RuleGuideCard(ctx: Context) {
    val p = WhaleTheme.palette
    var query by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    // 加词后 RuleStore.current 变了，但它是普通全局对象，不会触发重组 → 手动记版本号
    var version by remember { mutableStateOf(0) }
    val results = remember(query) { RuleGuide.search(query) }
    val rules = remember(version) { RuleStore.current }

    WhaleCard {
        Text("说人话的规则", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "不用看懂那 490 条 JSON。用大白话描述你遇到的问题（比如「淘宝老推送券后价」），" +
                "我给出一张该动的表和几个该加的词，点一下立即生效。",
            color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; msg = "" },
            label = { Text("哪里不对？（搜「淘宝」「账单」「退款」「重复」…）", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        if (results.isEmpty()) {
            Text(
                "没找到对应的症状。换个词试试，或者用下面的「自己加一个词」。",
                color = p.sub, fontSize = 11.sp
            )
        }
        results.forEach { tip ->
            TipBlock(
                tip = tip,
                currentCount = RuleStore.countOf(tip.tableKey, rules),
                onApply = { words, tableKey ->
                    msg = when (val err = RuleStore.addWords(ctx, tableKey, words)) {
                        null -> {
                            version++
                            "已加入「${RuleGuide.table(tableKey)?.title}」：${words.joinToString("、")} —— 已经生效"
                        }
                        else -> err
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
        }

        if (msg.isNotBlank()) {
            Text(msg, color = p.primary, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(8.dp))
        }

        FreeWordAdder(ctx) { text ->
            version++
            msg = text
        }
    }
}

/** 一条症状卡片 */
@Composable
private fun TipBlock(
    tip: RuleTip,
    currentCount: Int,
    onApply: (List<String>, String) -> Unit
) {
    val p = WhaleTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.primary.copy(alpha = if (p.isDark) 0.10f else 0.06f))
            .padding(10.dp)
    ) {
        Text(tip.symptom, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text(tip.fix, color = p.sub, fontSize = 11.sp, lineHeight = 16.sp)

        if (tip.tableKey.isBlank()) {
            // 加词改不了的（设计取舍 / 已知边界），只解释
            Spacer(Modifier.height(4.dp))
            Text("这条改不了规则，是抓取逻辑本身的取舍。", color = p.gold, fontSize = 10.sp)
            return@Column
        }

        val tableTitle = RuleGuide.table(tip.tableKey)?.title ?: tip.tableKey
        Spacer(Modifier.height(6.dp))
        Text("该动：$tableTitle（现有 $currentCount 条）", color = p.gold, fontSize = 10.sp)
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tip.words.joinToString("、"),
                    color = p.text.copy(alpha = 0.85f), fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            MiniButton("加入这 ${tip.words.size} 个词") { onApply(tip.words, tip.tableKey) }
        }
    }
}

/** 兜底：用户想自己塞一个词（比如某个 App 特有的话术） */
@Composable
private fun FreeWordAdder(ctx: Context, onDone: (String) -> Unit) {
    val p = WhaleTheme.palette
    var word by remember { mutableStateOf("") }
    var tableKey by remember { mutableStateOf("adMarkers") }
    val choices = listOf("adMarkers", "nonTxnMarkers", "junk", "fundTransferMarkers")

    Spacer(Modifier.height(4.dp))
    Text("自己加一个词", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "词写长一点、具体一点。单个词根（比如只写「券」）容易误杀真实支付。",
        color = p.sub, fontSize = 10.sp, lineHeight = 15.sp
    )
    Spacer(Modifier.height(6.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        choices.forEach { key ->
            val info = RuleGuide.table(key)
            val selected = key == tableKey
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) p.primary.copy(alpha = 0.9f) else p.primary.copy(alpha = 0.10f))
                    .clickable { tableKey = key }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    info?.title ?: key,
                    color = if (selected) p.bg else p.sub,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = word,
            onValueChange = { word = it },
            label = { Text("要加的词", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        MiniButton("加上去") {
            val w = word.trim()
            if (w.isEmpty()) {
                onDone("先填一个词")
            } else if (w.length < 2) {
                onDone("「$w」太短了：单个字容易误杀真实账目，请写具体一点")
            } else {
                val err = RuleStore.addWords(ctx, tableKey, listOf(w))
                if (err == null) {
                    val title = RuleGuide.table(tableKey)?.title ?: tableKey
                    word = ""
                    onDone("已加入「$title」：$w —— 已经生效")
                } else {
                    onDone(err)
                }
            }
        }
    }
}

@Composable
private fun MiniButton(text: String, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(p.primary.copy(alpha = 0.92f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = p.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
