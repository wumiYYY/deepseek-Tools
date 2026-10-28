package com.jingyuniang.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt

@Composable
fun StatsScreen(vm: AppViewModel, onOpenAiSettings: () -> Unit) {
    val p = WhaleTheme.palette
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showPie by remember { mutableStateOf(true) }
    var aiReview by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }

    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        HeaderArtBanner(78.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("统计", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "‹", fontSize = 20.sp, color = p.sub,
                modifier = Modifier.clip(CircleShape).clickable { vm.shiftMonth(-1) }.padding(horizontal = 8.dp)
            )
            Text(Dates.month(anchor), color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "›", fontSize = 20.sp, color = p.sub,
                modifier = Modifier.clip(CircleShape).clickable { vm.shiftMonth(1) }.padding(horizontal = 8.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            WhaleCard {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell("支出", Fmt.amount(stats.expense), p.expense, Modifier.weight(1f))
                    StatCell("收入", Fmt.amount(stats.income), p.income, Modifier.weight(1f))
                    StatCell("结余", Fmt.amount(stats.income - stats.expense), p.text, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell("笔数", "${stats.expenseCount + stats.incomeCount}", p.sub, Modifier.weight(1f), small = true)
                    StatCell("日均支出", Fmt.amount(stats.avgPerDay), p.sub, Modifier.weight(1f), small = true)
                    StatCell("最大单笔", Fmt.amount(stats.maxExpense?.amountCents ?: 0), p.sub, Modifier.weight(1f), small = true)
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("支出结构", modifier = Modifier.weight(1f))
                    ChipPill(if (showPie) "环形" else "排行", true) { showPie = !showPie }
                }
                if (stats.catSums.isEmpty()) {
                    EmptyState(emoji = "📊", title = "本月还没有支出数据")
                } else {
                    if (showPie) {
                        val total = stats.expense.coerceAtLeast(1)
                        val top = stats.catSums.take(7)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(
                                slices = top.map { Color(catMap[it.categoryId]?.color ?: 0xFF90A4AE) to (it.cents.toFloat() / total) },
                                centerTitle = "共",
                                centerValue = "${stats.expenseCount} 笔"
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                top.forEach { cs ->
                                    val cat = catMap[cs.categoryId]
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(cat?.color ?: 0xFF90A4AE))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${cat?.emoji ?: ""}${cat?.name ?: "其他"}",
                                            color = p.text, fontSize = 11.sp, maxLines = 1
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "${(cs.cents * 100.0 / total).toInt()}%",
                                            color = p.sub, fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val total = stats.expense.coerceAtLeast(1)
                        stats.catSums.take(12).forEach { cs ->
                            val cat = catMap[cs.categoryId]
                            RankBar(
                                label = cat?.name ?: "其他",
                                emoji = cat?.emoji ?: "📦",
                                color = Color(cat?.color ?: 0xFF90A4AE),
                                cents = cs.cents,
                                ratio = cs.cents.toFloat() / total,
                                count = cs.count
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                SectionTitle("近 6 个月支出")
                BarChart(
                    values = trend.map { it.expense.toFloat() },
                    labels = trend.map { "${Dates.monthOfYear(it.monthStart)}月" },
                    color = p.primary
                )
            }

            if (stats.incomeCatSums.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                WhaleCard {
                    SectionTitle("收入来源")
                    val total = stats.income.coerceAtLeast(1)
                    stats.incomeCatSums.take(6).forEach { cs ->
                        val cat = catMap[cs.categoryId]
                        RankBar(
                            label = cat?.name ?: "其他",
                            emoji = cat?.emoji ?: "💵",
                            color = p.income,
                            cents = cs.cents,
                            ratio = cs.cents.toFloat() / total,
                            count = cs.count
                        )
                    }
                }
            }

            if (stats.tagSums.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                WhaleCard {
                    SectionTitle("标签花费")
                    stats.tagSums.take(8).forEach { (tag, cents, count) ->
                        RankBar(
                            label = tag,
                            emoji = "🏷",
                            color = p.gold,
                            cents = cents,
                            ratio = cents.toFloat() / stats.expense.coerceAtLeast(1),
                            count = count
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(if (settings.aiEnabled && settings.apiKey.isNotBlank()) "鲸鱼娘月报" else "消费洞察", modifier = Modifier.weight(1f))
                    Text(
                        text = if (aiLoading) "分析中…" else "AI 点评",
                        color = p.primary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !aiLoading) {
                                aiLoading = true
                                vm.aiReview { text ->
                                    aiLoading = false
                                    aiReview = if (text.isBlank()) "在「AI 设置」里配置接口地址、Key 和模型，就能让鲸鱼娘帮你写月报了～" else text
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (aiReview.isNotBlank()) {
                    Text(aiReview, color = p.text, fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                }
                InsightRow("支出最多的一天", maxDayText(stats.dayTotals))
                InsightRow("占大头的分类", stats.catSums.firstOrNull()?.let { cs -> catMap[cs.categoryId]?.name ?: "其他" } ?: "-")
                InsightRow("账户使用", stats.accountSums.firstOrNull()?.let { (id, cents) ->
                    (accMap[id]?.name ?: "账户") + " ¥" + Fmt.amount(cents)
                } ?: "-")
                InsightRow("记账笔数", "${stats.expenseCount + stats.incomeCount} 笔")
                if (settings.aiEnabled && settings.apiKey.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点这里配置 AI 接口 →",
                        color = p.primary, fontSize = 12.sp,
                        modifier = Modifier.clickable { onOpenAiSettings() }
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

private fun maxDayText(map: Map<Long, Long>): String {
    val e = map.maxByOrNull { it.value } ?: return "-"
    return "${Dates.md(e.key)} · ¥${Fmt.amount(e.value)}"
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier, small: Boolean = false) {
    val p = WhaleTheme.palette
    Column(modifier = modifier) {
        Text(label, color = p.sub, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = color, fontSize = if (small) 14.sp else 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InsightRow(label: String, value: String) {
    val p = WhaleTheme.palette
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = p.sub, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
