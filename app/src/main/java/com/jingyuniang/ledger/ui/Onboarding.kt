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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.jingyuniang.ledger.R

/**
 * 首次安装后的三步引导：
 * ① 认识鲸鱼娘（角色 + 一句话定位）
 * ② 自动记账怎么工作（通知/短信/导入，以及为什么需要权限）
 * ③ 一键去开权限 / 直接进金库
 */
@Composable
fun OnboardingScreen(
    onOpenPermission: () -> Unit,
    onDone: () -> Unit
) {
    val p = WhaleTheme.palette
    var step by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1B2E5C), Color(0xFF070F26)))
            )
    ) {
        // 右上：跳过
        Text(
            "跳过",
            color = p.sub,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp)
                .clip(RoundedCornerShape(50))
                .border(1.dp, p.primary.copy(alpha = 0.4f), RoundedCornerShape(50))
                .clickable { onDone() }
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(52.dp))

            when (step) {
                0 -> {
                    Image(
                        painter = painterResource(moodResPublic()),
                        contentDescription = "鲸鱼女仆",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(210.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "主人，我是鲸鱼娘",
                        color = p.gold,
                        fontSize = 24.sp,
                        fontFamily = WhaleSerif,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "以后你的每一笔钱，都由我替你记。\n你只管付钱，剩下的交给我和这只金库。",
                        color = p.text.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center
                    )
                }
                1 -> {
                    MascotBadge(96.dp)
                    Spacer(Modifier.height(18.dp))
                    Text("我是怎么自动记账的", color = p.gold, fontSize = 20.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    GuideRow("①", "读通知", "微信/支付宝付款后会弹「支付成功」，我读这条通知，抠出金额、商户、账户")
                    GuideRow("②", "读短信", "银行卡扣款短信最准，几乎 100% 能记上")
                    GuideRow("③", "账单兜底", "漏了也没关系：导入微信/支付宝的账单文件，一次补齐")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "所有解析都在手机本地完成，通知原文只留在你自己的数据库里，不会上传。",
                        color = p.sub, fontSize = 11.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
                    )
                }
                else -> {
                    MascotBadge(96.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("还差一步就能开工", color = p.gold, fontSize = 20.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "「通知使用权」是自动记账的命门，\n没有它我一条账单都读不到。",
                        color = p.text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 22.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                            .clickable { onOpenPermission() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("去开启权限（跟着向导点 4 步）", color = Color(0xFF10203F), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "也可以先进入 App，在「我的 → 权限向导」里随时开",
                        color = p.sub, fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 步骤点
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(
                        modifier = Modifier
                            .size(width = if (i == step) 18.dp else 7.dp, height = 7.dp)
                            .clip(CircleShape)
                            .background(if (i == step) p.primary else p.primary.copy(alpha = 0.3f))
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 主按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (step == 2) Brush.horizontalGradient(listOf(Color(0xFF1B2E5C), Color(0xFF132657)))
                        else Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C)))
                    )
                    .border(1.dp, p.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable {
                        if (step < 2) step++ else onDone()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (step < 2) "下一步" else "进入金库",
                    color = if (step == 2) p.gold else Color(0xFF10203F),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun GuideRow(no: String, title: String, desc: String) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(p.primary.copy(alpha = 0.16f))
                .border(1.dp, p.primary.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(no, color = p.gold, fontSize = 11.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = p.sub, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

/** Art.kt 里的表情选择器是 internal 的 Composable 函数，这里转发一份供引导页使用 */
@Composable
fun moodResPublic(): Int = moodRes()
