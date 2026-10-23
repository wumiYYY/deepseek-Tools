package com.jingyuniang.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.TxnType

/**
 * 规则医生：用户把「抓错了 / 漏抓了」的通知原文贴进来，
 * AI（没配 Key 时用本地规则）生成一条 AutoRule，确认后立即生效并参与后续抓取。
 */
@Composable
fun RuleDoctorCard(vm: AppViewModel) {
    val p = WhaleTheme.palette
    var raw by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<AutoRule?>(null) }
    var explain by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var savedTip by remember { mutableStateOf("") }

    WhaleCard {
        SectionTitle("抓错了 / 漏抓了？告诉管家")
        Text(
            "把那条通知的原文粘进来，我读一遍就生成一条专属规则，确认后立即生效。",
            color = p.sub, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        // 输入区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (p.isDark) Color(0xFF0E1B3B) else Color(0xFFFFF6E6))
                .border(1.dp, p.primary.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            if (raw.isEmpty()) {
                Text(
                    "例：您尾号8888的账户于10月22日14:53消费人民币48.00元，余额1,234.56元",
                    color = p.sub.copy(alpha = 0.55f), fontSize = 11.sp
                )
            }
            BasicTextField(
                value = raw,
                onValueChange = { raw = it; savedTip = "" },
                textStyle = TextStyle(color = p.text, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C)))
                    )
                    .clickable {
                        if (!busy && raw.isNotBlank()) {
                            busy = true
                            draft = null
                            vm.aiSuggestRule(raw) { rule, why ->
                                draft = rule
                                explain = why
                                busy = false
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    if (busy) "管家正在读…" else "让 AI 生成规则",
                    color = Color(0xFF10203F), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            if (raw.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "清空",
                    color = p.sub, fontSize = 11.sp,
                    modifier = Modifier.clickable { raw = ""; draft = null; explain = ""; savedTip = "" }
                )
            }
        }

        // 生成结果
        draft?.let { r ->
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.primary.copy(alpha = 0.10f))
                    .border(0.8.dp, p.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Text("管家是这样理解的", color = p.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(explain, color = p.text.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "规则名：${r.name}",
                        color = p.sub, fontSize = 10.sp
                    )
                    if (r.keyword.isNotBlank()) Text("关键词：${r.keyword}", color = p.sub, fontSize = 10.sp)
                    if (r.regex.isNotBlank()) Text("金额正则：${r.regex}", color = p.sub, fontSize = 10.sp)
                    Text(
                        "方向：${if (r.direction == TxnType.INCOME) "收入" else "支出"}",
                        color = p.sub, fontSize = 10.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(p.primary.copy(alpha = 0.9f))
                                .clickable {
                                    vm.saveRule(r) {
                                        savedTip = "规则已启用，下次遇到同类通知就会按它来记"
                                        draft = null
                                        raw = ""
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text("启用这条规则", color = Color(0xFF10203F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "不启用",
                            color = p.sub, fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { draft = null }
                                .padding(vertical = 7.dp)
                        )
                    }
                }
            }
        }
        if (savedTip.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(savedTip, color = p.primary, fontSize = 11.sp)
        }
    }
}
