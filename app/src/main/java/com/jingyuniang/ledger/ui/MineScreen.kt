package com.jingyuniang.ledger.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.BuildConfig
import com.jingyuniang.ledger.Permissions
import com.jingyuniang.ledger.util.Fmt

@Composable
fun MineScreen(
    vm: AppViewModel,
    onOpenCaptures: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenCategory: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenAiChat: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val pending by vm.pendingCaptures.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshBalances() }

    val listenerOn = remember(settings.captureNotify) { Permissions.isNotificationListenerEnabled(ctx) }
    val totalAssets = accounts.sumOf { balances[it.id] ?: 0L }
    val maidBg = ImageBitmap.imageResource(com.jingyuniang.ledger.R.drawable.maid_full)

    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 头部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        if (w > 0f && h > 0f && maidBg.width > 0) {
                            val scale = w / maidBg.width
                            val dw = (maidBg.width * scale).toInt()
                            val dh = (maidBg.height * scale).toInt()
                            drawImage(
                                image = maidBg,
                                srcSize = IntSize(maidBg.width, maidBg.height),
                                dstOffset = IntOffset(0, 0),
                                dstSize = IntSize(dw, dh),
                                alpha = 0.95f
                            )
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF0A1430).copy(alpha = 0.97f),
                                        Color(0xFF0A1430).copy(alpha = 0.72f),
                                        Color(0xFF0A1430).copy(alpha = 0.35f)
                                    )
                                )
                            )
                            drawLine(
                                color = p.primary.copy(alpha = 0.55f),
                                start = Offset(0f, size.height - 1f),
                                end = Offset(size.width, size.height - 1f),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MascotBadge(54.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "鲸鱼娘金库",
                            color = p.gold,
                            fontSize = 18.sp,
                            fontFamily = WhaleSerif,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (settings.autoBook) "自动记账已开启 · 置信度 ≥ ${settings.autoBookThreshold}%"
                            else "自动记账已暂停",
                            color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    HeaderStat("本月支出", Fmt.money(stats.expense), Modifier.weight(1f))
                    HeaderStat("账目笔数", "${stats.expenseCount + stats.incomeCount}", Modifier.weight(1f))
                    HeaderStat("抓取记录", "${captures.size}", Modifier.weight(1f))
                }
            }

            // 自动记账状态卡
            WhaleCard(
                modifier = Modifier.padding(14.dp),
                onClick = onOpenCaptures
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动记账", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (!listenerOn) "⚠️ 通知使用权未开启，抓不到微信/支付宝账单"
                            else if (pending.isNotEmpty()) "有 ${pending.size} 笔待确认，点这里处理"
                            else "运行中：微信 / 支付宝 / 银行短信 / 云闪付",
                            color = if (!listenerOn) p.expense else p.sub,
                            fontSize = 11.sp
                        )
                    }
                    Text("›", color = p.sub, fontSize = 18.sp)
                }
            }

            WhaleCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow("📒", "账本管理", "多账本、默认账本切换", onClick = onOpenLedger)
                RowDivider()
                SettingRow("🏷", "分类管理", "自定义分类、图标、识别关键词", onClick = onOpenCategory)
                RowDivider()
                SettingRow("💳", "账户管理", "微信 / 支付宝 / 银行卡 / 信用卡", onClick = onOpenAccount)
                RowDivider()
                SettingRow("🔁", "周期账单", "房租、订阅、工资自动生成", onClick = onOpenRecurring)
                RowDivider()
                SettingRow("⚙️", "自动记账规则", "按关键词 / 正则自定义识别", onClick = onOpenRules)
            }

            WhaleCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow(
                    "🫧", "金库管家 · 对话问账",
                    if (!settings.aiEnabled || settings.apiKey.isBlank()) "本地规则也能问基础账目" else "已接入 AI，问真实数据",
                    onClick = onOpenAiChat
                )
                RowDivider()
                SettingRow("🤖", "AI 设置", if (settings.apiKey.isBlank()) "未配置 API Key（可选）" else "已配置 · ${settings.model}", onClick = onOpenAi)
                RowDivider()
                SettingRow("📥", "数据导入导出", "微信/支付宝账单导入、CSV 导出", onClick = onOpenData)
                RowDivider()
                SettingRow("🔐", "权限向导", "一键检查所有必要权限", onClick = onOpenPermission)
            }

            WhaleCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow("🔔", "通知栏常驻「记一笔」", if (settings.quickNotify) "下拉通知栏即可一秒记账" else "未开启") {
                    vm.setQuickNotify(!settings.quickNotify)
                }
                RowDivider()
                SettingRow("🫧", "悬浮球快捷记账", if (settings.floatingBall) "已开启" else "未开启", trailing = "") {
                    val next = !settings.floatingBall
                    vm.setFloatingBall(next)
                    if (next) {
                        if (Permissions.canDrawOverlay(ctx)) Permissions.startFloatingBall(ctx)
                        else Permissions.openOverlaySettings(ctx)
                    } else {
                        Permissions.stopFloatingBall(ctx)
                    }
                }
                RowDivider()
                SettingRow(
                    "🌙", "深色模式",
                    when (settings.darkMode) {
                        1 -> "浅色"
                        2 -> "深色"
                        else -> "跟随系统"
                    }
                ) { vm.setDarkMode((settings.darkMode + 1) % 3) }
                RowDivider()
                SettingRow("🔔", "记账提醒", if (settings.reminderEnabled) "每天 ${settings.reminderHour}:00 提醒" else "已关闭") {
                    vm.setReminderEnabled(!settings.reminderEnabled)
                }
                RowDivider()
                SettingRow("📊", "预算预警", if (settings.budgetAlert) "超支时通知" else "已关闭") {
                    vm.setBudgetAlert(!settings.budgetAlert)
                }
            }

            WhaleCard(modifier = Modifier.padding(14.dp)) {
                SettingRow("📈", "账户资产", Fmt.money(totalAssets), "查看") {
                    vm.refreshBalances()
                    onOpenAccount()
                }
                RowDivider()
                SettingRow("ℹ️", "关于鲸鱼娘", "v${BuildConfig.VERSION_NAME} · 纯本地存储", "", onClick = onOpenAbout)
            }

            Text(
                "数据全部保存在本机 SQLite，不上传任何服务器 🐳",
                color = p.sub.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

fun Context.toast(msg: String) {
    runCatching {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
