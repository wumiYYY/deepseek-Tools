package com.jingyuniang.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用锁：4 位 PIN。账本是最私密的数据，退出后台再回来要重新解锁。
 * 不做生物识别是为了零依赖、任何机型都能用。
 */
@Composable
fun LockScreen(pin: String, onUnlock: () -> Unit) {
    val p = WhaleTheme.palette
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun press(d: String) {
        if (input.length >= 4) return
        val next = input + d
        input = next
        error = ""
        if (next.length == 4) {
            if (next == pin) onUnlock()
            else {
                error = "密码不对，主人再想想"
                input = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2E5C), Color(0xFF070F26))))
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(96.dp))
        MoodBadge(size = 92.dp)
        Spacer(Modifier.height(14.dp))
        Text("金库已上锁", color = p.gold, fontSize = 22.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("输入 4 位密码，主人", color = p.sub, fontSize = 12.sp)

        Spacer(Modifier.height(28.dp))
        // 四个圆点
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (i < input.length) p.primary else p.primary.copy(alpha = 0.18f))
                        .border(1.dp, p.primary.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(error, color = p.expense, fontSize = 11.sp, textAlign = TextAlign.Center)

        Spacer(Modifier.height(28.dp))
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫")
        )
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                row.forEach { k ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(if (k.isEmpty()) Color.Transparent else Color(0xFF16274F))
                            .border(
                                if (k.isEmpty()) 0.dp else 1.dp,
                                p.primary.copy(alpha = 0.35f),
                                CircleShape
                            )
                            .clickable(enabled = k.isNotEmpty()) {
                                when (k) {
                                    "⌫" -> if (input.isNotEmpty()) input = input.dropLast(1)
                                    else -> press(k)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k.isNotEmpty()) {
                            Text(k, color = p.text, fontSize = 22.sp, fontFamily = WhaleSerif)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("忘记密码可在系统设置里清除应用数据", color = p.sub.copy(alpha = 0.6f), fontSize = 10.sp)
        Spacer(Modifier.height(24.dp))
    }
}

/** 设置/修改应用锁的弹窗：4 位数字，两次输入确认 */
@Composable
fun LockSetupDialog(
    currentPin: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = WhaleTheme.palette
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2E5C), Color(0xFF0A1430))))
                .border(1.2.dp, p.primary.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (step == 0) "设置应用锁密码" else "再输一次确认",
                color = p.gold, fontSize = 15.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(if (currentPin.isNotEmpty()) "当前已启用，重新设置会覆盖" else "4 位数字，进入 App 时要输",
                color = p.sub, fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            val shown = if (step == 0) first else second
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (i < shown.length) p.primary else p.primary.copy(alpha = 0.18f))
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // 数字键
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫")
            )
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { k ->
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(if (k.isEmpty()) Color.Transparent else Color(0xFF16274F))
                                .clickable(enabled = k.isNotEmpty()) {
                                    if (k == "⌫") {
                                        if (step == 0) first = first.dropLast(1) else second = second.dropLast(1)
                                        return@clickable
                                    }
                                    if (step == 0) {
                                        if (first.length < 4) first += k
                                    } else {
                                        if (second.length < 4) second += k
                                    }
                                    msg = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (k.isNotEmpty()) Text(k, color = p.text, fontSize = 19.sp, fontFamily = WhaleSerif)
                        }
                    }
                }
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, color = p.expense, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                        .clickable {
                            if (step == 0) {
                                if (first.length == 4) step = 1 else msg = "要输满 4 位"
                            } else {
                                if (second != first) { msg = "两次不一致，重来"; second = ""; first = ""; step = 0 }
                                else onSave(first)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text(if (step == 0) "下一步" else "启用", color = Color(0xFF10203F), fontSize = 13.sp, fontWeight = FontWeight.Bold) }

                if (currentPin.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF2A1C1C))
                            .border(1.dp, p.expense.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable { onClear() },
                        contentAlignment = Alignment.Center
                    ) { Text("关闭应用锁", color = p.expense, fontSize = 12.sp) }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF16274F))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { Text("取消", color = p.sub, fontSize = 12.sp) }
                }
            }
        }
    }
}
