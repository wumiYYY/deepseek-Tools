package com.jingyuniang.ledger.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.Permissions
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.data.Capture
import com.jingyuniang.ledger.data.CaptureStatus
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt

@Composable
fun CaptureScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pendingCaptures.collectAsStateWithLifecycle()
    val all by vm.captures.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()

    var refresh by remember { mutableStateOf(0) }
    var editingCapture by remember { mutableStateOf<Capture?>(null) }
    var showIgnoreAll by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    val listenerOn = remember(refresh, settings.captureNotify) { Permissions.isNotificationListenerEnabled(ctx) }
    val accessOn = remember(refresh) { Permissions.isAccessibilityEnabled(ctx) }
    val batteryOk = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(ctx) }
    val notifOk = remember(refresh) { Permissions.hasPostNotification(ctx) }
    val smsOk = remember(refresh) { Permissions.hasReceiveSms(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        TopBar(title = "自动记账", onBack = onBack, actions = {
            IconAction(emoji = "刷新") { refresh++ }
        })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 30.dp)
        ) {
            WhaleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoodBadge(46.dp, if (settings.captureMaster) R.drawable.mood_delighted else R.drawable.mood_sleepy)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (settings.captureMaster) "自动记账 · 运行中" else "自动记账 · 已暂停",
                            color = if (settings.captureMaster) p.gold else p.sub,
                            fontSize = 15.sp,
                            fontFamily = WhaleSerif,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (settings.captureMaster) "正在读取支付通知与银行短信"
                            else "已停止读取任何通知/短信，几乎不耗电",
                            color = p.sub, fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.captureMaster,
                        onCheckedChange = { vm.setCaptureMaster(it) }
                    )
                }
                if (settings.captureMaster) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "电量大就用它：一键暂停 → 想用时再一键打开，账目不会丢",
                        color = p.sub.copy(alpha = 0.75f), fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            RuleDoctorCard(vm)

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Text("🐳 鲸鱼娘是怎么自动记账的", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "① 你在微信/支付宝/银行 App 里完成付款后，系统通知栏会出现「支付成功」类通知；\n" +
                        "② 鲸鱼娘读取这条通知的文字，抠出金额、商户、账户，生成一笔账；\n" +
                        "③ 拿不准的会推一条通知问你，点「确认入账」即可。\n\n" +
                        "所有解析都在手机本地完成，通知原文只留在你自己的数据库里。",
                    color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                SectionTitle("必要授权")
                PermRow(
                    title = "通知使用权（最关键）",
                    ok = listenerOn,
                    desc = if (listenerOn) "已开启，微信/支付宝/银行通知都能抓"
                    else "未开启 —— 不开这个就抓不到任何账单",
                    actionText = if (listenerOn) "去查看" else "去开启",
                    onAction = {
                        Permissions.openNotificationListenerSettings(ctx)
                        refresh++
                    }
                )
                RowDivider()
                PermRow(
                    title = "通知权限",
                    ok = notifOk,
                    desc = "抓取结果需要发通知问你要不要入账",
                    actionText = if (notifOk) "已允许" else "去允许",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch("android.permission.POST_NOTIFICATIONS")
                        else Permissions.openAppDetails(ctx)
                    }
                )
                RowDivider()
                PermRow(
                    title = "短信权限（银行卡扣款短信）",
                    ok = smsOk,
                    desc = if (smsOk) "已允许，信用卡扣款短信可直接记账"
                    else "未允许。若系统不给权限，鲸鱼娘会自动改用「短信通知」抓取，不影响使用",
                    actionText = if (smsOk) "已允许" else "去允许",
                    onAction = { smsPerm.launch(Manifest.permission.RECEIVE_SMS) }
                )
                RowDivider()
                PermRow(
                    title = "无障碍抓取（增强，可选）",
                    ok = accessOn,
                    desc = "通知被折叠 / 不弹通知时，读支付成功页面补漏。耗电略高",
                    actionText = if (accessOn) "已开启" else "去开启",
                    onAction = {
                        Permissions.openAccessibilitySettings(ctx)
                        refresh++
                    }
                )
                RowDivider()
                PermRow(
                    title = "忽略电池优化（保活）",
                    ok = batteryOk,
                    desc = "国产 ROM 会杀后台，开启后抓取更稳",
                    actionText = if (batteryOk) "已允许" else "去设置",
                    onAction = {
                        Permissions.requestIgnoreBatteryOptimizations(ctx)
                        refresh++
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Text("🧪 抓取自测", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "不用真的付钱：点下面的按钮，会把一条示例通知喂进真实的抓取流水线，" +
                        "能看到「已入账」就说明权限与解析都正常。",
                    color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.primarySoft)
                            .clickable { vm.runCaptureSelfTest(0) { hint = it } }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("微信通知", color = p.primary, fontSize = 12.sp) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.primarySoft)
                            .clickable { vm.runCaptureSelfTest(1) { hint = it } }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("银行短信", color = p.primary, fontSize = 12.sp) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.primarySoft)
                            .clickable { vm.runCaptureSelfTest(2) { hint = it } }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("支付宝通知", color = p.primary, fontSize = 12.sp) }
                }
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(hint, color = p.text, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("通知体检", modifier = Modifier.weight(1f))
                    Text(
                        "清空",
                        color = p.sub,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.surfaceAlt)
                            .clickable { com.jingyuniang.ledger.capture.NotifyDiag.clear() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    "这里列出最近收到的支付类通知以及判定结果。如果微信/支付宝付款后这里一条都没有，" +
                        "说明它们在系统里被禁止发通知了 —— 点下面按钮直接跳过去打开。",
                    color = p.sub, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.primarySoft)
                            .clickable { Permissions.openAppNotificationSettings(ctx, "com.tencent.mm") }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("微信通知设置", color = p.primary, fontSize = 12.sp) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.primarySoft)
                            .clickable { Permissions.openAppNotificationSettings(ctx, "com.eg.android.AlipayGphone") }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("支付宝通知设置", color = p.primary, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                val events = com.jingyuniang.ledger.capture.NotifyDiag.events
                if (events.isEmpty()) {
                    Text("还没有收到过支付类通知。付一笔试试～", color = p.sub, fontSize = 11.sp)
                } else {
                    events.take(8).forEach { ev ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${ev.app}", color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                Tag(
                                    ev.verdict,
                                    when {
                                        ev.verdict.startsWith("已交给") -> p.income
                                        ev.verdict.contains("非支付") || ev.verdict.contains("聊天") -> p.sub
                                        else -> p.expense
                                    }
                                )
                                Spacer(Modifier.weight(1f))
                                Text(Dates.hm(ev.time), color = p.sub, fontSize = 9.sp)
                            }
                            Text(ev.text, color = p.sub, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                SectionTitle("抓取开关")
                SwitchRow("通知栏抓取", "微信 / 支付宝 / 银行 / 云闪付", settings.captureNotify) {
                    vm.setCaptureNotify(it)
                }
                SwitchRow("银行短信抓取", "短信里的扣款通知", settings.captureSms) { vm.setCaptureSms(it) }
                SwitchRow("无障碍增强", "需要单独授权，默认关闭", settings.captureAccess) { vm.setCaptureAccess(it) }
                SwitchRow("高置信自动入账", "不用每次点确认", settings.autoBook) { vm.setAutoBook(it) }
                if (settings.autoBook) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "自动入账置信度阈值：${settings.autoBookThreshold}%（越高越保守）",
                        color = p.sub, fontSize = 11.sp
                    )
                    Slider(
                        value = settings.autoBookThreshold.toFloat(),
                        onValueChange = { vm.setThreshold(it.toInt()) },
                        valueRange = 50f..99f,
                        steps = 48
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("待确认", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    if (pending.isNotEmpty()) Tag("${pending.size}", p.expense)
                    Spacer(Modifier.weight(1f))
                    if (pending.isNotEmpty()) {
                        Text(
                            "全部忽略",
                            color = p.expense, fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(p.expense.copy(alpha = 0.12f))
                                .clickable { showIgnoreAll = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    } else {
                        Text("点金额可改分类入账", color = p.sub, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (pending.isEmpty()) {
                    EmptyState(emoji = "✅", title = "没有待确认的账单", sub = "抓到的账会自动入账或在这里等你确认")
                } else {
                    pending.forEach { c ->
                        CaptureRow(
                            c = c,
                            categories = categories,
                            onConfirm = { vm.confirmCapture(c, c.categoryId, c.accountId) { } },
                            onEdit = { editingCapture = c },
                            onIgnore = { vm.ignoreCapture(c) { } }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            WhaleCard {
                SectionTitle("最近抓取记录")
                if (all.isEmpty()) {
                    EmptyState(emoji = "📭", title = "还没有抓取记录", sub = "去微信付一笔 0.01 元试试看")
                } else {
                    all.take(30).forEach { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when (c.status) {
                                    CaptureStatus.AUTO -> "✅"
                                    CaptureStatus.CONFIRMED -> "✔️"
                                    CaptureStatus.IGNORED -> "🚫"
                                    else -> "⏳"
                                },
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (c.direction == com.jingyuniang.ledger.data.TxnType.INCOME) "+" else "-") +
                                        Fmt.amount(c.amountCents) + "  " + (c.merchant.ifBlank { c.appName }),
                                    color = p.text, fontSize = 12.sp, maxLines = 1
                                )
                                Text(
                                    "${Dates.friendlyDay(c.time)} ${Dates.hm(c.time)} · ${c.rawText.take(40)}",
                                    color = p.sub, fontSize = 10.sp, maxLines = 1
                                )
                            }
                            if (c.status == CaptureStatus.PENDING) {
                                Text(
                                    "确认", color = p.primary, fontSize = 11.sp,
                                    modifier = Modifier.clickable { vm.confirmCapture(c, c.categoryId, c.accountId) { } }
                                        .padding(6.dp)
                                )
                                Text(
                                    "忽略", color = p.sub, fontSize = 11.sp,
                                    modifier = Modifier.clickable { vm.ignoreCapture(c) { } }.padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val target = editingCapture
    if (target != null) {
        CaptureEditDialog(
            capture = target,
            categories = categories,
            accounts = accounts,
            onDismiss = { editingCapture = null },
            onSave = { catId, accId ->
                vm.confirmCapture(target, catId, accId) { }
                editingCapture = null
            }
        )
    }

    if (showIgnoreAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showIgnoreAll = false },
            title = { Text("忽略全部待确认？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "当前 ${pending.size} 条待确认会被标记为「已忽略」，不会写入账本。\n" +
                        "确认过账的记录和已自动入账的账目不受影响。",
                    color = p.sub, fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showIgnoreAll = false
                    val n = pending.size
                    vm.ignoreAllPendingCaptures {
                        hint = "已忽略 $n 条待确认"
                    }
                }) { Text("全部忽略", color = p.expense) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showIgnoreAll = false }) {
                    Text("取消", color = p.sub)
                }
            }
        )
    }
}

@Composable
private fun PermRow(
    title: String,
    ok: Boolean,
    desc: String,
    actionText: String,
    onAction: () -> Unit
) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "🟢" else "🔴", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = p.sub, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Text(
            actionText, color = if (ok) p.sub else p.primary, fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (ok) p.surfaceAlt else p.primarySoft)
                .clickable { onAction() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp)
            Text(desc, color = p.sub, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CaptureRow(
    c: Capture,
    categories: List<com.jingyuniang.ledger.data.Category>,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onIgnore: () -> Unit
) {
    val p = WhaleTheme.palette
    val cat = categories.firstOrNull { it.id == c.categoryId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.surfaceAlt)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🐳", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                (if (c.direction == com.jingyuniang.ledger.data.TxnType.INCOME) "+" else "-") + Fmt.money(c.amountCents),
                color = if (c.direction == com.jingyuniang.ledger.data.TxnType.INCOME) p.income else p.expense,
                fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "${cat?.emoji ?: "❓"}${cat?.name ?: "未分类"} · ${c.merchant.ifBlank { c.appName }} · 置信 ${c.confidence}%",
                color = p.sub, fontSize = 10.sp, maxLines = 1
            )
        }
        Text(
            "改分类", color = p.primary, fontSize = 11.sp,
            modifier = Modifier.clickable { onEdit() }.padding(5.dp)
        )
        Text(
            "入账", color = Color.White, fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(p.primary)
                .clickable { onConfirm() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        Text(
            "✕", color = p.sub, fontSize = 12.sp,
            modifier = Modifier.clickable { onIgnore() }.padding(6.dp)
        )
    }
}

@Composable
private fun CaptureEditDialog(
    capture: Capture,
    categories: List<com.jingyuniang.ledger.data.Category>,
    accounts: List<com.jingyuniang.ledger.data.Account>,
    onDismiss: () -> Unit,
    onSave: (Long, Long) -> Unit
) {
    val p = WhaleTheme.palette
    var catId by remember { mutableStateOf(capture.categoryId) }
    var accId by remember { mutableStateOf(capture.accountId) }
    val type = if (capture.direction == com.jingyuniang.ledger.data.TxnType.INCOME)
        com.jingyuniang.ledger.data.TxnType.INCOME else com.jingyuniang.ledger.data.TxnType.EXPENSE

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认这笔账", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${Fmt.money(capture.amountCents)}  ${capture.merchant}",
                    color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Text(capture.rawText, color = p.sub, fontSize = 10.sp, maxLines = 4)
                Spacer(Modifier.height(8.dp))
                Text("分类", color = p.sub, fontSize = 11.sp)
                Column(modifier = Modifier.height(150.dp).verticalScroll(rememberScrollState())) {
                    categories.filter { it.type == type }.chunked(4).forEach { row ->
                        Row {
                            row.forEach { c ->
                                ChipPill(
                                    "${c.emoji}${c.name}", catId == c.id,
                                    modifier = Modifier.padding(2.dp)
                                ) { catId = c.id }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("账户", color = p.sub, fontSize = 11.sp)
                Column(modifier = Modifier.height(110.dp).verticalScroll(rememberScrollState())) {
                    accounts.chunked(3).forEach { row ->
                        Row {
                            row.forEach { a ->
                                ChipPill(
                                    "${a.emoji}${a.name}", accId == a.id,
                                    modifier = Modifier.padding(2.dp)
                                ) { accId = a.id }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(catId, accId) }) {
                Text("确认入账", color = p.primary)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = p.sub) }
        }
    )
}
