package com.jingyuniang.ledger.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.ai.AiProposal
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.ReimburseState
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 数字键盘占用的高度：底部固定吸附，先占位再覆盖，避免被压扁 */
private val KEYPAD_HEIGHT = 214.dp

@Composable
fun AddTxnScreen(vm: AppViewModel, txnId: Long, onDone: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val ledgerId by vm.ledgerId.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Txn?>(null) }
    var type by remember { mutableStateOf(TxnType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(0L) }
    var accountId by remember { mutableStateOf(0L) }
    var toAccountId by remember { mutableStateOf(0L) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(listOf<String>()) }
    var photos by remember { mutableStateOf(listOf<String>()) }
    var reimburse by remember { mutableStateOf(ReimburseState.NONE) }
    var excludeBudget by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showToAccountPicker by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var aiText by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    LaunchedEffect(txnId) {
        if (txnId > 0L) {
            val t = withContext(Dispatchers.IO) { Services.repo.txnById(txnId) }
            if (t != null) {
                editing = t
                type = t.type
                amountText = Fmt.inputText(t.amountCents)
                categoryId = t.categoryId
                accountId = t.accountId
                toAccountId = t.toAccountId
                date = t.date
                note = t.note
                tags = Services.repo.tagList(t.tags)
                photos = t.photos.split(",").filter { it.isNotBlank() }
                reimburse = t.reimburse
                excludeBudget = t.excludeBudget
            }
        } else {
            accountId = accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id ?: 0L
        }
    }

    // 未指定分类时，自动选第一个
    LaunchedEffect(type, categories) {
        if (type != TxnType.TRANSFER && categoryId == 0L) {
            categoryId = categories.firstOrNull { it.type == type }?.id ?: 0L
        }
    }
    LaunchedEffect(accounts) {
        if (accountId == 0L) accountId = accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id ?: 0L
    }

    // ---- 相机拍照（附票据） ----
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (ok && f != null) photos = photos + f.absolutePath
    }

    // ---- 相册选图做 OCR ----
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            vm.ocr(uri, onResult = { msg -> hint = msg }, onLoaded = { r ->
                if (r != null) {
                    if (r.amountCents > 0) amountText = Fmt.inputText(r.amountCents)
                    if (r.merchant.isNotBlank()) note = r.merchant
                    type = if (r.income) TxnType.INCOME else TxnType.EXPENSE
                    hint = "已识别截图内容，请确认金额"
                }
            })
        }
    }

    // ---- 语音 ----
    var showVoice by remember { mutableStateOf(false) }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        android.util.Log.d(
            "WhaleMoney",
            "语音返回 code=${result.resultCode} hasData=${result.data != null} text=$text"
        )
        if (text.isNullOrBlank()) {
            hint = when (result.resultCode) {
                android.app.Activity.RESULT_OK -> "没听清，再说一次（或直接打字）"
                android.app.Activity.RESULT_CANCELED -> "没收到语音结果：可以再说一次，或直接打字（点金额输入框）"
                else -> "语音识别没返回结果（code=${result.resultCode}），可以打字输入"
            }
        }
        if (!text.isNullOrBlank()) {
            aiText = text
            applyAi(vm, text, categories, accounts) { proposal, err ->
                if (proposal != null) {
                    amountText = Fmt.inputText(proposal.amountCents)
                    type = proposal.type
                    if (proposal.categoryId > 0) categoryId = proposal.categoryId
                    if (proposal.accountId > 0) accountId = proposal.accountId
                    if (proposal.note.isNotBlank()) note = proposal.note
                    hint = "语音识别：$text"
                } else {
                    hint = err
                }
            }
        }
    }

    fun applyVoiceText(text: String) {
        aiText = text
        applyAi(vm, text, categories, accounts) { proposal, err ->
            if (proposal != null) {
                amountText = Fmt.inputText(proposal.amountCents)
                type = proposal.type
                if (proposal.categoryId > 0) categoryId = proposal.categoryId
                if (proposal.accountId > 0) accountId = proposal.accountId
                if (proposal.note.isNotBlank()) note = proposal.note
                hint = "语音识别：$text"
            } else {
                hint = err
            }
        }
    }

    // 方案 C：不自建语音，直接打开 AI 文字输入（用户可用输入法自带的麦克风）
    fun startVoice() {
        showAiDialog = true
    }

    /** MIUI 只把识别结果回传给它自己的面板，所以我们自绘面板 1s 内收不到就切到系统面板 */
    fun startSystemVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说一句，例如「晚饭十块微信」")
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure { hint = "这台设备没有可用的语音识别" }
    }

    if (showVoice) {
        VoiceListeningDialog(
            onResult = { text ->
                showVoice = false
                applyVoiceText(text)
            },
            onDismiss = { showVoice = false },
            onFallback = { startSystemVoice() }
        )
    }

    fun save() {
        val cents = Fmt.toCents(amountText)
        if (cents <= 0) {
            hint = "请输入金额"
            return
        }
        val acc = if (accountId > 0) accountId else accounts.firstOrNull()?.id ?: 0L
        val now = System.currentTimeMillis()
        val base = editing
        val txn = Txn(
            id = base?.id ?: 0,
            ledgerId = if (ledgerId > 0) ledgerId else (base?.ledgerId ?: 1L),
            type = type,
            amountCents = cents,
            categoryId = if (type == TxnType.TRANSFER) 0L else categoryId,
            accountId = acc,
            toAccountId = if (type == TxnType.TRANSFER) toAccountId else 0L,
            toAmountCents = if (type == TxnType.TRANSFER) cents else 0L,
            date = date,
            note = note,
            tags = vmTagJoin(tags),
            merchant = base?.merchant ?: "",
            photos = photos.joinToString(","),
            source = base?.source ?: TxnSource.MANUAL,
            reimburse = reimburse,
            excludeBudget = excludeBudget,
            captureId = base?.captureId ?: 0L,
            createdAt = base?.createdAt ?: now,
            updatedAt = now
        )
        vm.saveTxn(txn, edit = base != null) { onDone() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "✕", fontSize = 18.sp, color = p.sub,
                modifier = Modifier.clip(CircleShape).clickable { onDone() }.padding(8.dp)
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(p.surfaceAlt)
                    .padding(3.dp)
            ) {
                listOf(TxnType.EXPENSE to "支出", TxnType.INCOME to "收入", TxnType.TRANSFER to "转账")
                    .forEach { (t, label) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (type == t) p.primary else Color.Transparent)
                                .clickable { type = t }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                color = if (type == t) Color.White else p.sub,
                                fontSize = 13.sp,
                                fontWeight = if (type == t) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
            }
            Spacer(Modifier.weight(1f))
            if (editing != null) {
                Text(
                    "删除", fontSize = 13.sp, color = p.expense,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showDelete = true }.padding(8.dp)
                )
            } else {
                Spacer(Modifier.width(46.dp))
            }
        }

        // 金额
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (type == TxnType.INCOME) "＋ ¥" else if (type == TxnType.TRANSFER) "¥" else "－ ¥",
                    color = p.sub, fontSize = 18.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (amountText.isEmpty()) "0.00" else amountText,
                    color = when (type) {
                        TxnType.INCOME -> p.income
                        TxnType.EXPENSE -> p.expense
                        else -> p.text
                    },
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (note.isEmpty()) {
                            Text("点这里写个备注…", color = p.sub.copy(alpha = 0.7f), fontSize = 14.sp)
                        }
                        inner()
                    }
                )
            }
            if (hint.isNotBlank()) {
                Text(hint, color = p.primary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 分类 / 转账账户
        if (type == TxnType.TRANSFER) {
            Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surface)
                        .clickable { showAccountPicker = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("转出账户", color = p.sub, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        accounts.firstOrNull { it.id == accountId }?.let { "${it.emoji} ${it.name}" } ?: "选择",
                        color = p.text, fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surface)
                        .clickable { showToAccountPicker = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("转入账户", color = p.sub, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        accounts.firstOrNull { it.id == toAccountId }?.let { "${it.emoji} ${it.name}" } ?: "选择",
                        color = p.text, fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                items(categories.filter { it.type == type && !it.archived }) { cat ->
                    CategoryCell(cat, cat.id == categoryId) { categoryId = cat.id }
                }
            }
        }

        // 明细行：账户 / 日期 / 标签 / 报销
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(p.surface)
                .padding(top = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val acc = accounts.firstOrNull { it.id == accountId }
                SmallChip("${acc?.emoji ?: "💳"} ${acc?.name ?: "账户"}") { showAccountPicker = true }
                SmallChip(Dates.md(date)) { openDatePicker(ctx, date) { date = it } }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                        .clickable { startVoice() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⌨️", fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text("打字说", color = Color(0xFF10203F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                SmallChip(if (tags.isEmpty()) "🏷 标签" else "🏷 ${tags.joinToString("/")}") { showTagDialog = true }
                SmallChip(if (reimburse == ReimburseState.NONE) "报销" else "报销✓") {
                    reimburse = when (reimburse) {
                        ReimburseState.NONE -> ReimburseState.PENDING
                        ReimburseState.PENDING -> ReimburseState.DONE
                        ReimburseState.DONE -> ReimburseState.NONE
                    }
                }
            }
            // 一键模板：点一下就把金额/分类/账户/备注填好，省掉键盘输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tpls = listOf(
                    Triple("早餐 10", 1000L, "餐饮"),
                    Triple("午饭 25", 2500L, "餐饮"),
                    Triple("咖啡 22", 2200L, "餐饮"),
                    Triple("打车 23", 2300L, "交通"),
                    Triple("地铁 4", 400L, "交通")
                )
                tpls.forEach { (label, cents, catName) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.primary.copy(alpha = 0.10f))
                            .border(1.dp, p.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .clickable {
                                amountText = Fmt.inputText(cents)
                                type = TxnType.EXPENSE
                                note = label.substringBefore(" ").trim()
                                categories.firstOrNull { it.name.contains(catName) }?.let { categoryId = it.id }
                                accounts.firstOrNull { it.isDefault }?.let { accountId = it.id }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = p.text, fontSize = 11.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmallChip("🤖 AI 记账") { showAiDialog = true }
                Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                                    .clickable { startVoice() }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⌨️", fontSize = 13.sp)
                                    Spacer(Modifier.width(5.dp))
                                    Text("打字说", color = Color(0xFF10203F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                SmallChip("📷 拍照") {
                    val dir = File(ctx.filesDir, "receipts").apply { mkdirs() }
                    val f = File(dir, "r_${System.currentTimeMillis()}.jpg")
                    pendingPhoto = f
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    runCatching { takePicture.launch(uri) }.onFailure { hint = "无法启动相机" }
                }
                SmallChip("🖼 截图识别") {
                    runCatching { pickImage.launch("image/*") }
                        .onFailure { hint = "这台设备没有图片选择器（缺 DocumentsUI）" }
                }
                if (photos.isNotEmpty()) SmallChip("📎 ${photos.size}") { }
            }
        }
        // 给贴底键盘留出高度，避免分类宫格/明细行被它挡住
        Spacer(Modifier.height(KEYPAD_HEIGHT))
        }

        // 键盘永远吸附底部：先占位再覆盖，任何屏幕尺寸都不会被压扁
        Keypad(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(p.surface)
                .navigationBarsPadding()
                .height(KEYPAD_HEIGHT),
            onKey = { k ->
                var s = amountText
                when (k) {
                    "⌫" -> if (s.isNotEmpty()) s = s.dropLast(1)
                    "." -> if (!s.contains(".")) s = if (s.isEmpty()) "0." else "$s."
                    else -> {
                        if (s.contains(".")) {
                            if (s.substringAfter(".").length >= 2) return@Keypad
                        }
                        if (s.replace(".", "").length >= 8) return@Keypad
                        s = if (s == "0") k else s + k
                    }
                }
                amountText = s
            },
            onDone = { save() },
            doneLabel = if (editing != null) "保存修改" else "保存"
        )
    }

    // ---------- 弹窗 ----------
    if (showAccountPicker) {
        AccountPicker(
            accounts,
            if (type == TxnType.TRANSFER) "选择转出账户" else "选择账户",
            onDismiss = { showAccountPicker = false }
        ) {
            accountId = it.id
            showAccountPicker = false
        }
    }
    if (showToAccountPicker) {
        AccountPicker(accounts, "选择转入账户", onDismiss = { showToAccountPicker = false }) {
            toAccountId = it.id
            showToAccountPicker = false
        }
    }
    if (showDelete) {
        ConfirmDialog(
            title = "删除这笔账目？",
            text = "删除后无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { editing?.let { t -> vm.deleteTxn(t) { onDone() } } },
            onDismiss = { showDelete = false }
        )
    }
    if (showTagDialog) {
        TagDialog(tags) {
            tags = it
            showTagDialog = false
        }
    }
    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { showAiDialog = false },
            title = { Text("一句话记账", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("例如：昨天午饭 25 微信 / 工资到账 8000", color = p.sub, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aiText,
                        onValueChange = { aiText = it },
                        placeholder = { Text("说一句话", fontSize = 13.sp) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAiDialog = false
                    applyAi(vm, aiText, categories, accounts) { proposal, err ->
                        if (proposal != null) {
                            amountText = Fmt.inputText(proposal.amountCents)
                            type = proposal.type
                            if (proposal.categoryId > 0) categoryId = proposal.categoryId
                            if (proposal.accountId > 0) accountId = proposal.accountId
                            if (proposal.note.isNotBlank()) note = proposal.note
                            if (proposal.date > 0) date = proposal.date
                            hint = "已解析（${proposal.reason}）"
                        } else hint = err
                    }
                }) { Text("解析", color = p.primary) }
            },
            dismissButton = { TextButton(onClick = { showAiDialog = false }) { Text("取消", color = p.sub) } }
        )
    }
}

private fun vmTagJoin(list: List<String>): String = list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

private fun applyAi(
    vm: AppViewModel,
    text: String,
    categories: List<Category>,
    accounts: List<Account>,
    onResult: (AiProposal?, String) -> Unit
) {
    if (text.isBlank()) {
        onResult(null, "先说一句话吧")
        return
    }
    vm.aiParse(text, onResult = { onResult(it, "") }, onError = { onResult(null, it) })
}

private fun openDatePicker(ctx: android.content.Context, current: Long, onPick: (Long) -> Unit) {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = current
    DatePickerDialog(
        ctx,
        { _, y, m, d ->
            val picked = Dates.of(y, m + 1, d)
            val keepHour = java.util.Calendar.getInstance().apply { timeInMillis = current }
            onPick(picked + keepHour.get(java.util.Calendar.HOUR_OF_DAY) * 3600_000L + keepHour.get(java.util.Calendar.MINUTE) * 60_000L)
        },
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
private fun CategoryCell(cat: Category, selected: Boolean, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) p.primarySoft else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (selected) p.primary else Color(cat.color).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(cat.emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            cat.name,
            color = if (selected) p.primary else p.sub,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SmallChip(text: String, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(p.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = p.sub, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Keypad(
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
    onDone: () -> Unit,
    doneLabel: String
) {
    val p = WhaleTheme.palette
    Column(modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        listOf(
            listOf("1", "2", "3", "⌫"),
            listOf("4", "5", "6", "00"),
            listOf("7", "8", "9", ".")
        ).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { k ->
                    KeyCell(k, Modifier.weight(1f).fillMaxHeight()) { onKey(k) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            KeyCell("0", Modifier.weight(1f).fillMaxHeight()) { onKey("0") }
            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.primary)
                    .clickable { onDone() },
                contentAlignment = Alignment.Center
            ) {
                Text(doneLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun KeyCell(text: String, modifier: Modifier, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.surfaceAlt)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AccountPicker(
    accounts: List<Account>,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Account) -> Unit
) {
    val p = WhaleTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                accounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(acc) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiAvatar(acc.emoji, Color(acc.color), 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(acc.name, color = p.text, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text(accountTypeLabel(acc.type), color = p.sub, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = p.sub) }
        }
    )
}

fun accountTypeLabel(type: AccountType): String = when (type) {
    AccountType.CASH -> "现金"
    AccountType.BANK -> "储蓄卡"
    AccountType.CREDIT -> "信用卡"
    AccountType.WECHAT -> "微信"
    AccountType.ALIPAY -> "支付宝"
    AccountType.OTHER -> "其他"
}

@Composable
private fun TagDialog(current: List<String>, onConfirm: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(current.joinToString(" ")) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("标签", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("多个标签用空格分隔", color = WhaleTheme.palette.sub, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("旅行 报销", fontSize = 13.sp) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(text.split(" ", ",", "，").map { it.trim() }.filter { it.isNotEmpty() })
            }) { Text("确定", color = WhaleTheme.palette.primary) }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(current) }) { Text("取消", color = WhaleTheme.palette.sub) }
        }
    )
}
