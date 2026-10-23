package com.jingyuniang.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.data.Budget
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Debt
import com.jingyuniang.ledger.data.DebtType
import com.jingyuniang.ledger.data.SavingGoal
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt

@Composable
fun PlanScreen(vm: AppViewModel, onOpenRecurring: () -> Unit) {
    val p = WhaleTheme.palette
    val budgets by vm.budgetProgress.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.savingGoals.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var budgetTarget by remember { mutableStateOf<Long>(0L) }
    var showSavingDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<SavingGoal?>(null) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var debtType by remember { mutableStateOf(DebtType.RECEIVABLE) }

    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        HeaderArtBanner(
            78.dp,
            mood = if (budgets.any { it.over }) R.drawable.mood_determined else null
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("计划", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "周期账单 ›", color = p.sub, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onOpenRecurring() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            listOf("预算", "储蓄", "借贷").forEachIndexed { i, t ->
                ChipPill(t, tab == i, modifier = Modifier.padding(end = 8.dp)) { tab = i }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            when (tab) {
                0 -> {
                    val total = budgets.firstOrNull { it.budget.categoryId == 0L }
                    val catBudgets = budgets.filter { it.budget.categoryId != 0L }
                    WhaleCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("本月总预算", color = p.sub, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (total == null) "还没设置" else Fmt.money(total.budget.amountCents),
                                    color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (total == null) "设置一个数，鲸鱼娘帮你盯着"
                                    else "已花 ${Fmt.money(total.spentCents)} · 剩 ${Fmt.money(total.left)}",
                                    color = if (total?.over == true) p.expense else p.sub, fontSize = 11.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(p.primarySoft)
                                    .clickable { budgetTarget = 0L; showBudgetDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(if (total == null) "去设置" else "改预算", color = p.primary, fontSize = 12.sp)
                            }
                        }
                        if (total != null) {
                            Spacer(Modifier.height(12.dp))
                            ProgressBar(
                                ratio = total.ratio,
                                color = if (total.over) p.expense else p.primary,
                                height = 10.dp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "预算已用 ${(total.ratio * 100).toInt()}%",
                                color = p.sub, fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    WhaleCard {
                        SectionTitle(
                            "分类预算",
                            action = "＋ 添加",
                            onAction = { budgetTarget = 0L; showBudgetDialog = true }
                        )
                        if (catBudgets.isEmpty()) {
                            EmptyState(emoji = "🎯", title = "还没有分类预算", sub = "给「餐饮」「购物」单独设上限，超支会提醒你")
                        } else {
                            catBudgets.forEach { bp ->
                                val cat = categories.firstOrNull { it.id == bp.budget.categoryId }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            budgetTarget = bp.budget.categoryId
                                            showBudgetDialog = true
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    EmojiAvatar(cat?.emoji ?: "📦", Color(cat?.color ?: 0xFF90A4AE), 34.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row {
                                            Text(cat?.name ?: "已删除分类", color = p.text, fontSize = 13.sp)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "${Fmt.amount(bp.spentCents)} / ${Fmt.amount(bp.budget.amountCents)}",
                                                color = if (bp.over) p.expense else p.sub, fontSize = 11.sp
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        ProgressBar(
                                            ratio = bp.ratio,
                                            color = if (bp.over) p.expense else Color(cat?.color ?: 0xFF4D6BFE),
                                            height = 5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    WhaleCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("储蓄计划", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "共 ${goals.size} 个目标 · 已完成 ${goals.count { it.done }} 个",
                                    color = p.sub, fontSize = 11.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(p.primarySoft)
                                    .clickable { editingGoal = null; showSavingDialog = true }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) { Text("＋ 新目标", color = p.primary, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (goals.isEmpty()) {
                        WhaleCard { EmptyState(emoji = "🐳", title = "建一个存钱目标吧", sub = "比如「旅行基金 5000」，每存一笔都能看到进度") }
                    } else {
                        goals.forEach { goal ->
                            WhaleCard(modifier = Modifier.padding(bottom = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    EmojiAvatar(goal.emoji, Color(goal.color), 42.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(goal.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${Fmt.money(goal.savedCents)} / ${Fmt.money(goal.targetCents)}" +
                                                if (goal.done) " · 已达成 🎉" else "",
                                            color = if (goal.done) p.income else p.sub, fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        "存入", color = p.primary, fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(p.primarySoft)
                                            .clickable { vm.addToSaving(goal, 10000) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                ProgressBar(
                                    ratio = if (goal.targetCents > 0) goal.savedCents.toFloat() / goal.targetCents else 0f,
                                    color = Color(goal.color)
                                )
                                Spacer(Modifier.height(6.dp))
                                Row {
                                    Text("点行进度条右侧可编辑", color = p.sub.copy(alpha = 0.7f), fontSize = 10.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "编辑", color = p.sub, fontSize = 11.sp,
                                        modifier = Modifier.clickable { editingGoal = goal; showSavingDialog = true }
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    val receivable = debts.filter { it.type == DebtType.RECEIVABLE && !it.settled }
                    val payable = debts.filter { it.type == DebtType.PAYABLE && !it.settled }
                    WhaleCard {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("别人欠我", color = p.sub, fontSize = 12.sp)
                                Text(
                                    Fmt.money(receivable.sumOf { it.amountCents - it.settledCents }),
                                    color = p.income, fontSize = 18.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("我欠别人", color = p.sub, fontSize = 12.sp)
                                Text(
                                    Fmt.money(payable.sumOf { it.amountCents - it.settledCents }),
                                    color = p.expense, fontSize = 18.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(p.primarySoft)
                                    .clickable { debtType = DebtType.RECEIVABLE; showDebtDialog = true }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("＋ 借出", color = p.primary, fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(p.primarySoft)
                                    .clickable { debtType = DebtType.PAYABLE; showDebtDialog = true }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("＋ 借入", color = p.primary, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (debts.isEmpty()) {
                        WhaleCard { EmptyState(emoji = "🤝", title = "没有借贷记录", sub = "AA、垫付、借还都能记在这里") }
                    } else {
                        WhaleCard {
                            debts.forEach { d ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    EmojiAvatar(
                                        if (d.type == DebtType.RECEIVABLE) "💰" else "💸",
                                        if (d.type == DebtType.RECEIVABLE) p.income else p.expense,
                                        34.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${d.person}${if (d.settled) "（已结清）" else ""}",
                                            color = p.text, fontSize = 13.sp
                                        )
                                        Text(
                                            buildString {
                                                append(if (d.type == DebtType.RECEIVABLE) "借出" else "借入")
                                                if (d.note.isNotBlank()) append(" · ").append(d.note)
                                                if (d.dueDate > 0) append(" · 约定 ").append(Dates.md(d.dueDate))
                                            },
                                            color = p.sub, fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        (if (d.type == DebtType.RECEIVABLE) "+" else "-") + Fmt.amount(d.amountCents - d.settledCents),
                                        color = if (d.type == DebtType.RECEIVABLE) p.income else p.expense,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                    if (!d.settled) {
                                        Text(
                                            " 结清", color = p.primary, fontSize = 11.sp,
                                            modifier = Modifier.clickable { vm.settleDebt(d) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showBudgetDialog) {
        BudgetDialog(
            categories = categories,
            initialCategory = budgetTarget,
            existing = budgets.firstOrNull { it.budget.categoryId == budgetTarget }?.budget,
            onDismiss = { showBudgetDialog = false },
            onSave = { catId, cents ->
                vm.setBudget(catId, cents) { showBudgetDialog = false }
            }
        )
    }
    if (showSavingDialog) {
        SavingDialog(
            initial = editingGoal,
            onDismiss = { showSavingDialog = false },
            onSave = { goal ->
                vm.saveSaving(goal) { showSavingDialog = false }
            },
            onDelete = { goal -> vm.deleteSaving(goal) { showSavingDialog = false } }
        )
    }
    if (showDebtDialog) {
        DebtDialog(
            type = debtType,
            onDismiss = { showDebtDialog = false },
            onSave = { debt -> vm.saveDebt(debt) { showDebtDialog = false } }
        )
    }
}

@Composable
private fun BudgetDialog(
    categories: List<Category>,
    initialCategory: Long,
    existing: Budget?,
    onDismiss: () -> Unit,
    onSave: (Long, Long) -> Unit
) {
    val p = WhaleTheme.palette
    var catId by remember { mutableStateOf(initialCategory) }
    var amount by remember { mutableStateOf(existing?.let { Fmt.inputText(it.amountCents) } ?: "") }
    var pickerOpen by remember { mutableStateOf(false) }
    val cat = categories.firstOrNull { it.id == catId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置预算", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.surfaceAlt)
                        .clickable { pickerOpen = !pickerOpen }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("预算对象", color = p.sub, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (catId == 0L) "本月总预算" else "${cat?.emoji ?: ""}${cat?.name ?: ""}", color = p.text, fontSize = 13.sp)
                }
                if (pickerOpen) {
                    Column(modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { catId = 0L; pickerOpen = false }.padding(10.dp)
                        ) { Text("本月总预算", fontSize = 13.sp) }
                        categories.filter { it.type == com.jingyuniang.ledger.data.TxnType.EXPENSE }.forEach { c ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { catId = c.id; pickerOpen = false }
                                    .padding(10.dp)
                            ) { Text("${c.emoji} ${c.name}", fontSize = 13.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("每月金额") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(catId, Fmt.toCents(amount)) }) {
                Text("保存", color = p.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}

@Composable
private fun SavingDialog(
    initial: SavingGoal?,
    onDismiss: () -> Unit,
    onSave: (SavingGoal) -> Unit,
    onDelete: (SavingGoal) -> Unit
) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🎯") }
    var target by remember { mutableStateOf(initial?.let { Fmt.inputText(it.targetCents) } ?: "") }
    var saved by remember { mutableStateOf(initial?.let { Fmt.inputText(it.savedCents) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建储蓄目标" else "编辑目标", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row {
                    listOf("🎯", "✈️", "🏠", "🚗", "💍", "🎮", "🐳").forEach { e ->
                        Text(
                            e, fontSize = 20.sp,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(if (emoji == e) p.primarySoft else Color.Transparent)
                                .clickable { emoji = e }
                                .padding(4.dp)
                        )
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("目标名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("目标金额") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = saved, onValueChange = { saved = it }, label = { Text("已存金额") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        SavingGoal(
                            id = initial?.id ?: 0,
                            name = name,
                            emoji = emoji,
                            targetCents = Fmt.toCents(target),
                            savedCents = Fmt.toCents(saved),
                            color = initial?.color ?: 0xFF4D6BFE
                        )
                    )
                }
            }) { Text("保存", color = p.primary) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) { Text("删除", color = p.expense) }
                }
                TextButton(onClick = onDismiss) { Text("取消", color = p.sub) }
            }
        }
    )
}

@Composable
private fun DebtDialog(type: DebtType, onDismiss: () -> Unit, onSave: (Debt) -> Unit) {
    val p = WhaleTheme.palette
    var person by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == DebtType.RECEIVABLE) "借出（别人欠我）" else "借入（我欠别人）", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = person, onValueChange = { person = it }, label = { Text("对方") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Fmt.toCents(amount)
                if (person.isNotBlank() && cents > 0) {
                    onSave(
                        Debt(
                            type = type,
                            person = person,
                            amountCents = cents,
                            note = note,
                            date = System.currentTimeMillis(),
                            ledgerId = 0
                        )
                    )
                }
            }) { Text("保存", color = p.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}
