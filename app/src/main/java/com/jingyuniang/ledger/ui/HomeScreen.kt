package com.jingyuniang.ledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenCaptures: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLedgers: () -> Unit,
    onOpenAiChat: () -> Unit
) {
    val p = WhaleTheme.palette
    var showQuickAi by remember { mutableStateOf(false) }
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val txns by vm.monthTxns.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val pending by vm.pendingCaptures.collectAsStateWithLifecycle()
    val ledgerName by vm.currentLedgerName.collectAsStateWithLifecycle()
    val budgetProgress by vm.budgetProgress.collectAsStateWithLifecycle()

    var calendarMode by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf(Dates.startOfDay(System.currentTimeMillis())) }

    val catMap = remember(categories) { categories.associateBy { it.id } }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val totalBudget = budgetProgress.firstOrNull { it.budget.categoryId == 0L }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        // 顶部：账本 + 搜索
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpenLedgers() }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MascotBadge(30.dp)
                Spacer(Modifier.width(6.dp))
                Text(ledgerName, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(" ▾", color = p.sub, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            if (pending.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.primarySoft)
                        .clickable { onOpenCaptures() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("待确认 ${pending.size}", color = p.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(6.dp))
            }
            SearchPill(onClick = onOpenSearch)
        }

        // 月份切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹", fontSize = 22.sp, color = p.sub, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).clickable { vm.shiftMonth(-1) }.padding(horizontal = 8.dp)
            )
            Text(Dates.month(anchor), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "›", fontSize = 22.sp, color = p.sub, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).clickable { vm.shiftMonth(1) }.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            if (!Dates.isSameMonth(anchor, System.currentTimeMillis())) {
                Text(
                    "回到本月",
                    color = p.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.goCurrentMonth() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        MascotAskBar(
            onQuickEntry = { showQuickAi = true },
            onChat = onOpenAiChat
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 178.dp)
        ) {
            item {
                GradientCard(modifier = Modifier.padding(top = 4.dp), art = true) {
                    Text("本月支出", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("¥", color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            Fmt.amount(stats.expense),
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("收入 ¥${Fmt.amount(stats.income)}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "结余 ¥${Fmt.amount(stats.income - stats.expense)}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${stats.expenseCount} 笔 · 日均 ¥${Fmt.amount(stats.avgPerDay)}", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                            if (totalBudget != null && totalBudget.budget.amountCents > 0) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.25f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(totalBudget.ratio.coerceIn(0f, 1f))
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(if (totalBudget.over) Color(0xFFFFD166) else Color.White)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "预算剩 ¥${Fmt.amount(totalBudget.left)}",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChipPill("明细", !calendarMode) { calendarMode = false }
                    ChipPill("日历", calendarMode) { calendarMode = true }
                    Spacer(Modifier.weight(1f))
                    ChipPill("＋ 记一笔", false, onClick = onAdd)
                }
            }

            if (calendarMode) {
                item {
                    CalendarMonth(
                        anchor = anchor,
                        dayTotals = stats.dayTotals,
                        selected = selectedDay,
                        onSelect = { selectedDay = it }
                    )
                }
                val dayTxns = txns.filter { Dates.startOfDay(it.date) == selectedDay }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${Dates.md(selectedDay)} ${Dates.weekday(selectedDay)}",
                            color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "支出 ¥${Fmt.amount(dayTxns.filter { it.type == TxnType.EXPENSE && it.source != com.jingyuniang.ledger.data.TxnSource.ADJUST }.sumOf { it.amountCents })}",
                            color = p.sub, fontSize = 12.sp
                        )
                    }
                }
                if (dayTxns.isEmpty()) {
                    item { EmptyState(title = "这天还没有账目", sub = "点右上角「＋ 记一笔」补上") }
                } else {
                    items(dayTxns.sortedByDescending { it.date }) { t ->
                        TxnRow(t, catMap, accMap, onClick = { onEdit(t.id) })
                    }
                }
            } else {
                if (txns.isEmpty()) {
                    item { EmptyState(title = "本月还没有账目", sub = "记一笔，或者去「自动记账」开启抓取") }
                } else {
                    val grouped = txns.groupBy { Dates.startOfDay(it.date) }.toSortedMap(compareByDescending { it })
                    grouped.forEach { (day, list) ->
                        item(key = "h$day") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${Dates.friendlyDay(day)} ${Dates.weekday(day)}",
                                    color = p.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.weight(1f))
                                // 余额调整不算收支，和本月卡片保持一致
                                val real = list.filter { it.source != com.jingyuniang.ledger.data.TxnSource.ADJUST }
                                val e = real.filter { it.type == TxnType.EXPENSE }.sumOf { it.amountCents }
                                val i = real.filter { it.type == TxnType.INCOME }.sumOf { it.amountCents }
                                Text(
                                    buildString {
                                        if (e > 0) append("支 ¥${Fmt.amount(e)}")
                                        if (e > 0 && i > 0) append("  ")
                                        if (i > 0) append("收 ¥${Fmt.amount(i)}")
                                    },
                                    color = p.sub, fontSize = 11.sp
                                )
                            }
                        }
                        items(list.sortedByDescending { it.date }, key = { it.id }) { t ->
                            TxnRow(t, catMap, accMap, onClick = { onEdit(t.id) })
                        }
                    }
                }
            }
        }
    }
    }

    if (showQuickAi) QuickAiDialog(vm) { showQuickAi = false }
}

@Composable
fun TxnRow(t: Txn, catMap: Map<Long, Category>, accMap: Map<Long, Account>, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    val cat = catMap[t.categoryId]
    val acc = accMap[t.accountId]
    val isIncome = t.type == TxnType.INCOME
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiAvatar(cat?.emoji ?: "📦", Color(cat?.color ?: 0xFF90A4AE), 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cat?.name ?: "未分类",
                    color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (t.source != com.jingyuniang.ledger.data.TxnSource.MANUAL) {
                    Spacer(Modifier.width(5.dp))
                    Tag(
                        when (t.source) {
                            com.jingyuniang.ledger.data.TxnSource.NOTIFY -> "自动"
                            com.jingyuniang.ledger.data.TxnSource.SMS -> "短信"
                            com.jingyuniang.ledger.data.TxnSource.ACCESS -> "页面"
                            com.jingyuniang.ledger.data.TxnSource.IMPORT -> "导入"
                            com.jingyuniang.ledger.data.TxnSource.OCR -> "OCR"
                            com.jingyuniang.ledger.data.TxnSource.AI -> "AI"
                            com.jingyuniang.ledger.data.TxnSource.RECURRING -> "周期"
                            com.jingyuniang.ledger.data.TxnSource.ADJUST -> "调账"
                            else -> ""
                        },
                        if (t.type == TxnType.EXPENSE) Color(0xFF9AA4B8) else p.income
                    )
                }
                if (t.reimburse != com.jingyuniang.ledger.data.ReimburseState.NONE) {
                    Spacer(Modifier.width(5.dp))
                    Tag("报销", p.gold)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    if (t.note.isNotBlank()) append(t.note) else if (t.merchant.isNotBlank()) append(t.merchant)
                    if (acc != null) {
                        if (isNotEmpty()) append(" · ")
                        append(acc.name)
                    }
                    if (t.type == TxnType.TRANSFER) {
                        val to = accMap[t.toAccountId]?.name
                        append(" → ").append(to ?: "其他账户")
                    }
                },
                color = p.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (isIncome) "+" else if (t.type == TxnType.TRANSFER) "" else "-") + Fmt.amount(t.amountCents),
                color = when {
                    t.type == TxnType.TRANSFER -> p.text
                    isIncome -> p.income
                    else -> p.expense
                },
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Text(Dates.hm(t.date), color = p.sub, fontSize = 10.sp)
        }
    }
}

/** 月历热力：颜色越深花得越多 */
@Composable
fun CalendarMonth(
    anchor: Long,
    dayTotals: Map<Long, Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    val p = WhaleTheme.palette
    val ym = remember(anchor) { YearMonth.of(Dates.year(anchor), Dates.monthOfYear(anchor)) }
    val firstDay = ym.atDay(1)
    val offset = (firstDay.dayOfWeek.value - 1)
    val days = ym.lengthOfMonth()
    val max = dayTotals.values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val today = Dates.startOfDay(System.currentTimeMillis())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.surface)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(
                    w, color = p.sub, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        var cell = 0
        val rows = ((offset + days) + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayIndex = r * 7 + c - offset + 1
                    Box(modifier = Modifier.weight(1f).padding(2.dp)) {
                        if (dayIndex in 1..days) {
                            val date = ym.atDay(dayIndex)
                            val ts = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val amount = dayTotals[ts] ?: 0L
                            val intensity = (amount.toFloat() / max).coerceIn(0f, 1f)
                            val isSel = ts == selected
                            val isToday = ts == today
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isSel -> p.primary
                                            amount > 0 -> p.primary.copy(alpha = 0.08f + 0.22f * intensity)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .clickable { onSelect(ts) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "$dayIndex",
                                    color = if (isSel) Color.White else if (isToday) p.primary else p.text,
                                    fontSize = 12.sp,
                                    fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal
                                )
                                if (amount > 0) {
                                    Text(
                                        Fmt.amount(amount).removeSuffix(".00"),
                                        color = if (isSel) Color.White.copy(alpha = 0.9f) else p.sub,
                                        fontSize = 8.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        cell++
                    }
                }
            }
        }
    }
}
