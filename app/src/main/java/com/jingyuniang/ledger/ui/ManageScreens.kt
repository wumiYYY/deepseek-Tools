package com.jingyuniang.ledger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Freq
import com.jingyuniang.ledger.data.Ledger
import com.jingyuniang.ledger.data.Recurring
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt

private val EMOJI_PALETTE = listOf(
    "🐳", "🍜", "🥬", "🚌", "🛍️", "🧻", "🏠", "📱", "🎮", "💊", "📚", "👕",
    "💅", "🎁", "🐱", "⚽", "✈️", "💻", "👶", "🔄", "🛡️", "🧾", "💰", "🏆",
    "💼", "📈", "🧧", "↩️", "💸", "🏪", "💵", "💳", "🪪", "📦", "🎯", "🏠"
)

@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = WhaleTheme.palette
    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        TopBar(title = title, onBack = onBack, actions = { action?.invoke() })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            content = content
        )
    }
}

// ================= 账本 =================
@Composable
fun LedgerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val ledgers by vm.ledgers.collectAsStateWithLifecycle()
    val current by vm.ledgerId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Ledger?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    PageScaffold(
        title = "账本管理",
        onBack = onBack,
        action = {
            IconAction(emoji = "＋ 新建", onClick = { editing = null; showDialog = true })
        }
    ) {
        WhaleCard {
            Text(
                "不同场景可以用不同账本，例如「日常」「装修」「旅行」。顶部账本名可快速切换。",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        ledgers.forEach { l ->
            WhaleCard(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = { vm.selectLedger(l.id) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatar(l.emoji, Color(l.color), 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(l.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (l.id == current) "当前账本" else "点击切换",
                            color = if (l.id == current) p.primary else p.sub, fontSize = 11.sp
                        )
                    }
                    Text(
                        "编辑", color = p.sub, fontSize = 12.sp,
                        modifier = Modifier.clickable { editing = l; showDialog = true }.padding(6.dp)
                    )
                }
            }
        }
    }

    if (showDialog) {
        LedgerDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { vm.saveLedger(it) { showDialog = false } }
        )
    }
}

@Composable
private fun LedgerDialog(initial: Ledger?, onDismiss: () -> Unit, onSave: (Ledger) -> Unit) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🐳") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建账本" else "编辑账本", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                EmojiRow(emoji) { emoji = it }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账本名称") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(Ledger(id = initial?.id ?: 0, name = name, emoji = emoji, color = initial?.color ?: 0xFF4D6BFE))
                }
            }) { Text("保存", color = p.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}

@Composable
private fun EmojiRow(selected: String, onSelect: (String) -> Unit) {
    val p = WhaleTheme.palette
    Column(modifier = Modifier.height(96.dp).verticalScroll(rememberScrollState())) {
        EMOJI_PALETTE.chunked(9).forEach { row ->
            Row {
                row.forEach { e ->
                    Text(
                        e, fontSize = 20.sp,
                        modifier = Modifier
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(if (selected == e) p.primarySoft else Color.Transparent)
                            .clickable { onSelect(e) }
                            .padding(3.dp)
                    )
                }
            }
        }
    }
}

// ================= 分类 =================
@Composable
fun CategoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val categories by vm.categories.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TxnType.EXPENSE) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    val list = categories.filter { it.type == tab }

    PageScaffold(
        title = "分类管理",
        onBack = onBack,
        action = {
            IconAction(emoji = "＋ 新增", onClick = { editing = null; showDialog = true })
        }
    ) {
        Row {
            ChipPill("支出", tab == TxnType.EXPENSE) { tab = TxnType.EXPENSE }
            Spacer(Modifier.width(8.dp))
            ChipPill("收入", tab == TxnType.INCOME) { tab = TxnType.INCOME }
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text(
                "分类里的「关键词」用于自动记账时智能归类，例如「餐饮」包含 外卖、美团、星巴克。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(((list.size + 3) / 4 * 84).dp.coerceAtLeast(90.dp))
        ) {
            items(list) { c ->
                Column(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surface)
                        .clickable { editing = c; showDialog = true }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmojiAvatar(c.emoji, Color(c.color), 38.dp)
                    Spacer(Modifier.height(5.dp))
                    Text(c.name, color = p.text, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "提示：长按不可用，点击分类即可改名 / 换图标 / 删除",
            color = p.sub.copy(alpha = 0.6f), fontSize = 10.sp
        )
    }

    if (showDialog) {
        CategoryDialog(
            initial = editing,
            type = if (editing != null) editing!!.type else tab,
            onDismiss = { showDialog = false },
            onSave = { vm.saveCategory(it) { showDialog = false } },
            onDelete = { deleteTarget = it; showDialog = false }
        )
    }
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "删除分类？",
            text = "已记账目会保留，但会显示为「未分类」。",
            confirmText = "删除",
            danger = true,
            onConfirm = { deleteTarget?.let { vm.deleteCategory(it) {} } },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun CategoryDialog(
    initial: Category?,
    type: TxnType,
    onDismiss: () -> Unit,
    onSave: (Category) -> Unit,
    onDelete: (Category) -> Unit
) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "📦") }
    var keywords by remember { mutableStateOf(initial?.keywords ?: "") }
    var colorIdx by remember { mutableStateOf(0) }
    val colors = listOf(
        0xFFFF7A45, 0xFF52C41A, 0xFF1890FF, 0xFFEB2F96, 0xFF13C2C2,
        0xFF722ED1, 0xFFF5222D, 0xFFFAAD14, 0xFF90A4AE
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增分类" else "编辑分类", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(340.dp).verticalScroll(rememberScrollState())) {
                EmojiRow(emoji) { emoji = it }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("识别关键词（逗号分隔）") },
                    singleLine = false,
                    maxLines = 3
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { colorIdx = colors.indexOf(c) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        Category(
                            id = initial?.id ?: 0,
                            name = name,
                            emoji = emoji,
                            type = initial?.type ?: type,
                            color = if (initial != null) initial.color else colors[colorIdx % colors.size],
                            keywords = keywords,
                            isSystem = initial?.isSystem ?: false,
                            sortOrder = initial?.sortOrder ?: 99
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

// ================= 账户 =================
@Composable
fun AccountScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Account?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showBalance by remember { mutableStateOf(false) }
    var balancePrefill by remember { mutableStateOf("") }
    var balanceHint by remember { mutableStateOf("") }
    var balanceAccId by remember { mutableStateOf(0L) }
    var ocrBusy by remember { mutableStateOf(false) }
    // 删除账户时如果名下还有账目：先选迁移目标，避免账目变孤儿（v1.4.5）
    var deleteAcc by remember { mutableStateOf<Account?>(null) }
    var deleteAccCount by remember { mutableStateOf(0) }
    var accountMsg by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    // ---- 「更新余额 · 截图识别」：选图 → OCR → 余额 + 账户一起预填 ----
    val pickBalanceShot = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrBusy = true
        vm.ocrBalance(uri) { hit, msg, typeHint ->
            ocrBusy = false
            balancePrefill = hit?.cents?.takeIf { it > 0 }?.let { Fmt.inputText(it) }.orEmpty()
            // 按截图里的品牌词猜账户（零钱→微信、余额宝→支付宝），猜不到就落到默认账户
            val guessed = typeHint?.let { t -> accounts.firstOrNull { it.type == t } }
            val fallback = accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()
            balanceAccId = (guessed ?: fallback)?.id ?: 0L
            balanceHint = when {
                msg != null -> msg
                hit == null -> "没在截图里找到余额，可以手动输入"
                guessed != null ->
                    "已从截图读到「${hit.label}」${Fmt.money(hit.cents)} · 账户猜为「${guessed.name}」，不对就在下面换"
                else -> "已从截图读到「${hit.label}」${Fmt.money(hit.cents)} · 请确认是哪个账户"
            }
            showBalance = true
        }
    }

    LaunchedEffect(Unit) { vm.refreshBalances() }

    PageScaffold(
        title = "账户管理",
        onBack = onBack,
        action = { IconAction(emoji = "＋ 新增", onClick = { editing = null; showDialog = true }) }
    ) {
        WhaleCard {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text("总资产", color = p.sub, fontSize = 11.sp)
                    Text(
                        Fmt.money(accounts.sumOf { balances[it.id] ?: 0L }),
                        color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "初始余额 + 收支流水自动计算",
                    color = p.sub.copy(alpha = 0.7f), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Bottom)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (accountMsg.isNotBlank()) {
            Text(accountMsg, color = p.primary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        // ---- 更新余额：三行入口（截图识别 / 手动输入已开，读屏待第 3 刀） ----
        WhaleCard(modifier = Modifier.padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "更新余额", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = WhaleSerif
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (ocrBusy) "正在读图…" else "截图 / 手动已开放",
                    color = p.gold, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.gold.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "报一个真实余额，差额会记成「余额调整」流水：可追溯、可删、不算进本月收支。",
                color = p.sub, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(6.dp))
            BalanceSourceRow(
                "📷", "截图识别", "拍 / 选一张余额截图，自动读出余额并预填",
                enabled = true, busy = ocrBusy
            ) { pickBalanceShot.launch("image/*") }
            RowDivider(inset = 0.dp)
            BalanceSourceRow("♿", "读屏识别", "打开微信 / 支付宝余额页自动读取", false) {}
            RowDivider(inset = 0.dp)
            BalanceSourceRow("✍️", "手动输入", "鲸鱼娘记下你现在真实有多少钱", true, actionLabel = "去输入") {
                balancePrefill = ""
                balanceHint = ""
                balanceAccId = 0L
                showBalance = true
            }
        }
        accounts.forEach { acc ->
            WhaleCard(modifier = Modifier.padding(bottom = 8.dp), onClick = { editing = acc; showDialog = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatar(acc.emoji, Color(acc.color), 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            acc.name + if (acc.isDefault) " · 默认" else "",
                            color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            accountTypeLabel(acc.type) + if (acc.cardTail.isNotBlank()) " 尾号${acc.cardTail}" else "",
                            color = p.sub, fontSize = 11.sp
                        )
                    }
                    Text(
                        Fmt.money(balances[acc.id] ?: acc.initialBalanceCents),
                        color = if ((balances[acc.id] ?: 0) >= 0) p.text else p.expense,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        WhaleCard {
            Text(
                "账户会自动匹配：微信通知 → 「微信」，支付宝通知 → 「支付宝」，银行短信里出现「尾号8888」→ 匹配尾号 8888 的卡。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
    }

    if (showDialog) {
        AccountDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { vm.saveAccount(it) { showDialog = false } },
            onDelete = { acc ->
                // 名下还有账目 → 不能直接删（账单会变孤儿、余额凭空变），先问迁移到哪
                vm.accountTxnCount(acc.id) { n ->
                    showDialog = false
                    if (n == 0) {
                        vm.deleteAccount(acc) { accountMsg = it }
                    } else {
                        accountMsg = ""
                        deleteAccCount = n
                        deleteAcc = acc
                    }
                }
            }
        )
    }

    // ---- 删除账户 · 迁移账目（v1.4.5）----
    deleteAcc?.let { victim ->
        val others = accounts.filter { it.id != victim.id }
        AlertDialog(
            onDismissRequest = { deleteAcc = null },
            title = {
                Text(
                    "「${victim.name}」下还有 $deleteAccCount 笔账目",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "删账户前先把这些账目转走，否则它们会挂在已删除的账户上 —— 余额会凭空少一块。",
                        color = p.sub, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (others.isEmpty()) {
                        Text("没有别的账户可迁，请先新建一个账户。", color = p.expense, fontSize = 11.sp)
                    } else {
                        others.forEach { acc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val target = acc.id
                                        deleteAcc = null
                                        vm.deleteAccount(victim, migrateTo = target) { accountMsg = it }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EmojiAvatar(acc.emoji, Color(acc.color), 26.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("转到「${acc.name}」", color = p.text, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text("›", color = p.sub, fontSize = 15.sp)
                            }
                            RowDivider(inset = 4.dp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deleteAcc = null }) { Text("取消", color = p.sub) } }
        )
    }

    if (showBalance) {
        BalanceInputDialog(
            accounts = accounts,
            balances = balances,
            initialAmount = balancePrefill,
            prefillHint = balanceHint,
            initialAccountId = balanceAccId,
            onDismiss = { showBalance = false; balanceHint = "" },
            onConfirm = { acc, cents ->
                vm.applyBalanceInput(acc, cents) { ok, msg ->
                    showBalance = false
                    balanceHint = ""
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    if (ok) vm.refreshBalances()
                }
            }
        )
    }
}

/** 「更新余额」里的一行来源入口：enabled=false 的行灰显，点不动 */
@Composable
private fun BalanceSourceRow(
    emoji: String,
    title: String,
    desc: String,
    enabled: Boolean,
    busy: Boolean = false,
    actionLabel: String = "去识别",
    onClick: () -> Unit
) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (enabled) p.primarySoft else p.surfaceAlt),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 14.sp) }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) p.text else p.sub.copy(alpha = 0.55f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
            Text(
                desc,
                color = p.sub.copy(alpha = if (enabled) 0.9f else 0.45f),
                fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        Text(
            when {
                !enabled -> "即将上线"
                busy -> "读取中…"
                else -> actionLabel
            },
            color = if (enabled) p.primary else p.sub.copy(alpha = 0.55f),
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (enabled) p.primarySoft else p.surfaceAlt)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** 手动更新余额：选账户 + 输入现在的真实余额，差额写成「余额调整」流水 */
@Composable
private fun BalanceInputDialog(
    accounts: List<Account>,
    balances: Map<Long, Long>,
    initialAmount: String = "",
    prefillHint: String = "",
    initialAccountId: Long = 0L,
    onDismiss: () -> Unit,
    onConfirm: (Account, Long) -> Unit
) {
    val p = WhaleTheme.palette
    var accId by remember {
        mutableStateOf(
            initialAccountId.takeIf { id -> accounts.any { it.id == id } }
                ?: accounts.firstOrNull { it.isDefault }?.id
                ?: accounts.firstOrNull()?.id ?: 0L
        )
    }
    var input by remember { mutableStateOf(initialAmount) }
    val acc = accounts.firstOrNull { it.id == accId }
    val current = balances[accId] ?: acc?.initialBalanceCents ?: 0L
    val target = Fmt.toCents(input)
    val delta = target - current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新余额", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (prefillHint.isNotBlank()) {
                    Text(
                        prefillHint,
                        color = if (initialAmount.isNotBlank()) p.gold else p.sub,
                        fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("选一个账户", color = p.sub, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accounts.forEach { a ->
                        ChipPill(a.name, a.id == accId) { accId = a.id }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { it.isDigit() || it == '.' }.take(12) },
                    label = { Text("现在的余额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(6.dp))
                Text("当前记录 ${Fmt.money(current)}", color = p.sub, fontSize = 11.sp)
                if (input.isNotBlank() && target > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (delta == 0L) "和当前一致，不需要调整"
                        else "将补一笔「余额调整」${if (delta > 0) "+" else "−"}${Fmt.amount(kotlin.math.abs(delta))}，" +
                            "账户余额校准到 ${Fmt.money(target)}",
                        color = if (delta == 0L) p.sub else p.gold,
                        fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { acc?.let { onConfirm(it, target) } },
                enabled = acc != null && input.isNotBlank() && target > 0
            ) { Text("校准", color = p.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}

@Composable
private fun AccountDialog(
    initial: Account?,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
    onDelete: (Account) -> Unit
) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "💳") }
    var type by remember { mutableStateOf(initial?.type ?: AccountType.BANK) }
    var tail by remember { mutableStateOf(initial?.cardTail ?: "") }
    var initialBalance by remember { mutableStateOf(initial?.let { Fmt.inputText(it.initialBalanceCents) } ?: "") }
    var keywords by remember { mutableStateOf(initial?.keywords ?: "") }
    var isDefault by remember { mutableStateOf(initial?.isDefault ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增账户" else "编辑账户", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                EmojiRow(emoji) { emoji = it }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("账户名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.values().forEach { t ->
                        ChipPill(accountTypeLabel(t), type == t) { type = t }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = tail, onValueChange = { tail = it.take(4) }, label = { Text("卡号后四位（可空）") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = initialBalance, onValueChange = { initialBalance = it }, label = { Text("初始余额") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("识别关键词（逗号分隔）") }, singleLine = false, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设为默认账户", color = p.text, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        Account(
                            id = initial?.id ?: 0,
                            name = name,
                            type = type,
                            emoji = emoji,
                            color = initial?.color ?: 0xFF4D6BFE,
                            initialBalanceCents = Fmt.toCents(initialBalance),
                            cardTail = tail,
                            keywords = keywords,
                            isDefault = isDefault,
                            sortOrder = initial?.sortOrder ?: 99
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

// ================= 周期账单 =================
@Composable
fun RecurringScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val rules by vm.recurringRules.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    PageScaffold(
        title = "周期账单",
        onBack = onBack,
        action = { IconAction(emoji = "＋ 新增", onClick = { showDialog = true }) }
    ) {
        WhaleCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("到点自动记账", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "房租、会员、工资这类固定账单，到日子自动生成账目，也可以只提醒不记账。",
                        color = p.sub, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
                Text(
                    "立即执行", color = p.primary, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(p.primarySoft)
                        .clickable { vm.runRecurringNow { hint = it } }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = p.primary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (rules.isEmpty()) {
            WhaleCard { EmptyState(emoji = "🔁", title = "还没有周期账单", sub = "点右上角「＋ 新增」，把房租和会员费交给鲸鱼娘") }
        }
        rules.forEach { r ->
            val cat = categories.firstOrNull { it.id == r.categoryId }
            WhaleCard(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatar(cat?.emoji ?: "🔁", Color(cat?.color ?: 0xFF4D6BFE), 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append(freqLabel(r.freq))
                                if (r.freq == Freq.MONTHLY || r.freq == Freq.YEARLY) append(" ${r.dayOfMonth} 号")
                                append(" · ")
                                append(if (r.type == TxnType.INCOME) "+" else "-")
                                append(Fmt.amount(r.amountCents))
                                if (r.nextAt > 0) append(" · 下次 ").append(Dates.md(r.nextAt))
                            },
                            color = p.sub, fontSize = 11.sp
                        )
                    }
                    Switch(checked = r.enabled, onCheckedChange = { vm.toggleRecurring(r) })
                    Text(
                        "删", color = p.sub, fontSize = 11.sp,
                        modifier = Modifier.clickable { vm.deleteRecurring(r) }.padding(6.dp)
                    )
                }
            }
        }
    }

    if (showDialog) {
        RecurringDialog(
            categories = categories,
            accounts = accounts,
            onDismiss = { showDialog = false },
            onSave = { vm.saveRecurring(it) { showDialog = false } }
        )
    }
}

private fun freqLabel(f: Freq): String = when (f) {
    Freq.DAILY -> "每天"
    Freq.WEEKLY -> "每周"
    Freq.MONTHLY -> "每月"
    Freq.YEARLY -> "每年"
}

@Composable
private fun RecurringDialog(
    categories: List<Category>,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onSave: (Recurring) -> Unit
) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxnType.EXPENSE) }
    var freq by remember { mutableStateOf(Freq.MONTHLY) }
    var day by remember { mutableStateOf("1") }
    var categoryId by remember { mutableStateOf(0L) }
    var accountId by remember { mutableStateOf(accounts.firstOrNull { it.isDefault }?.id ?: 0L) }
    var autoBook by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增周期账单", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（如 房租）") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipPill("支出", type == TxnType.EXPENSE) { type = TxnType.EXPENSE }
                    ChipPill("收入", type == TxnType.INCOME) { type = TxnType.INCOME }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Freq.values().forEach { f -> ChipPill(freqLabel(f), freq == f) { freq = f } }
                }
                Spacer(Modifier.height(6.dp))
                if (freq == Freq.MONTHLY || freq == Freq.YEARLY) {
                    OutlinedTextField(value = day, onValueChange = { day = it.take(2) }, label = { Text("几号（1-31）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                }
                Text("分类", color = p.sub, fontSize = 11.sp)
                Column(modifier = Modifier.height(90.dp).verticalScroll(rememberScrollState())) {
                    Row {
                        ChipPill("未指定", categoryId == 0L) { categoryId = 0L }
                    }
                    categories.filter { it.type == type }.chunked(4).forEach { row ->
                        Row {
                            row.forEach { c ->
                                ChipPill("${c.emoji}${c.name}", categoryId == c.id, modifier = Modifier.padding(2.dp)) { categoryId = c.id }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("账户", color = p.sub, fontSize = 11.sp)
                Row {
                    accounts.take(5).forEach { a ->
                        ChipPill("${a.emoji}${a.name}", accountId == a.id, modifier = Modifier.padding(2.dp)) { accountId = a.id }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动记账（关闭则只创建草稿）", color = p.text, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = autoBook, onCheckedChange = { autoBook = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Fmt.toCents(amount)
                if (name.isNotBlank() && cents > 0) {
                    val now = System.currentTimeMillis()
                    onSave(
                        Recurring(
                            ledgerId = 0,
                            name = name,
                            type = type,
                            amountCents = cents,
                            categoryId = categoryId,
                            accountId = accountId,
                            freq = freq,
                            dayOfMonth = (day.toIntOrNull() ?: 1).coerceIn(1, 31),
                            nextAt = now,
                            autoBook = autoBook,
                            enabled = true
                        )
                    )
                }
            }) { Text("保存", color = p.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}

// ================= 自动规则 =================
@Composable
fun RulesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val rules by vm.autoRules.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AutoRule?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    PageScaffold(
        title = "自动记账规则",
        onBack = onBack,
        action = { IconAction(emoji = "＋ 新增", onClick = { editing = null; showDialog = true }) }
    ) {
        WhaleCard {
            Text(
                "内置规则负责把「微信支付」归到「微信」账户；你也可以加规则：某 App 通知里出现某关键词时，" +
                    "直接记成指定分类/账户。优先级高的先匹配。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        rules.forEach { r ->
            WhaleCard(modifier = Modifier.padding(bottom = 8.dp), onClick = { editing = r; showDialog = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.builtin) "🔒" else "⚙️", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.name, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                if (r.pkg.isNotBlank()) append("包名:").append(r.pkg).append("  ")
                                if (r.keyword.isNotBlank()) append("关键词:").append(r.keyword)
                                if (r.regex.isNotBlank()) append("  正则:").append(r.regex)
                            }.ifBlank { "无限制（不推荐）" },
                            color = p.sub, fontSize = 10.sp, maxLines = 2
                        )
                        Text(
                            "→ " + (categories.firstOrNull { it.id == r.categoryId }?.name ?: "分类不改")
                                + " / " + (accounts.firstOrNull { it.id == r.accountId }?.name ?: "账户自动"),
                            color = p.primary, fontSize = 10.sp
                        )
                    }
                    Switch(checked = r.enabled, onCheckedChange = { vm.saveRule(r.copy(enabled = !r.enabled)) })
                }
            }
        }
    }

    if (showDialog) {
        RuleDialog(
            initial = editing,
            categories = categories,
            accounts = accounts,
            onDismiss = { showDialog = false },
            onSave = { vm.saveRule(it) { showDialog = false } },
            onDelete = { vm.deleteRule(it) { showDialog = false } }
        )
    }
}

@Composable
private fun RuleDialog(
    initial: AutoRule?,
    categories: List<Category>,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onSave: (AutoRule) -> Unit,
    onDelete: (AutoRule) -> Unit
) {
    val p = WhaleTheme.palette
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pkg by remember { mutableStateOf(initial?.pkg ?: "") }
    var keyword by remember { mutableStateOf(initial?.keyword ?: "") }
    var regex by remember { mutableStateOf(initial?.regex ?: "") }
    var direction by remember { mutableStateOf(initial?.direction ?: TxnType.EXPENSE) }
    var categoryId by remember { mutableStateOf(initial?.categoryId ?: 0L) }
    var accountId by remember { mutableStateOf(initial?.accountId ?: 0L) }
    var priority by remember { mutableStateOf(initial?.priority?.toString() ?: "50") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("规则名") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text("包名（可空，如 com.tencent.mm）") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = keyword, onValueChange = { keyword = it }, label = { Text("关键词，逗号分隔") }, singleLine = false, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = regex, onValueChange = { regex = it }, label = { Text("金额正则（第1个括号是金额，可空）") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipPill("支出", direction == TxnType.EXPENSE) { direction = TxnType.EXPENSE }
                    ChipPill("收入", direction == TxnType.INCOME) { direction = TxnType.INCOME }
                }
                Spacer(Modifier.height(6.dp))
                Text("目标分类", color = p.sub, fontSize = 11.sp)
                Column(modifier = Modifier.height(80.dp).verticalScroll(rememberScrollState())) {
                    Row { ChipPill("不改", categoryId == 0L) { categoryId = 0L } }
                    categories.filter { it.type == direction }.chunked(4).forEach { row ->
                        Row {
                            row.forEach { c ->
                                ChipPill("${c.emoji}${c.name}", categoryId == c.id, modifier = Modifier.padding(2.dp)) { categoryId = c.id }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("目标账户", color = p.sub, fontSize = 11.sp)
                Row {
                    ChipPill("自动", accountId == 0L) { accountId = 0L }
                    accounts.take(4).forEach { a ->
                        ChipPill("${a.emoji}${a.name}", accountId == a.id, modifier = Modifier.padding(2.dp)) { accountId = a.id }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = priority, onValueChange = { priority = it.take(3) }, label = { Text("优先级(越大越先)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        AutoRule(
                            id = initial?.id ?: 0,
                            name = name,
                            enabled = initial?.enabled ?: true,
                            pkg = pkg.trim(),
                            keyword = keyword,
                            regex = regex.trim(),
                            direction = direction,
                            categoryId = categoryId,
                            accountId = accountId,
                            priority = priority.toIntOrNull() ?: 50,
                            builtin = initial?.builtin ?: false
                        )
                    )
                }
            }) { Text("保存", color = p.primary) }
        },
        dismissButton = {
            Row {
                if (initial != null && !initial.builtin) {
                    TextButton(onClick = { onDelete(initial) }) { Text("删除", color = p.expense) }
                }
                TextButton(onClick = onDismiss) { Text("取消", color = p.sub) }
            }
        }
    )
}
