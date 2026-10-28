package com.jingyuniang.ledger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.BuildConfig
import com.jingyuniang.ledger.Permissions
import com.jingyuniang.ledger.ai.AiEndpoint
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.io.BackupManager
import com.jingyuniang.ledger.util.Fmt
import java.io.File

@Composable
private fun ScreenColumn(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = WhaleTheme.palette
    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        TopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 28.dp),
            content = content
        )
    }
}

// ================= AI 设置 =================
@Composable
fun AiSettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val settings by vm.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.apiBase) }
    var model by remember { mutableStateOf(settings.model) }
    var hint by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }

    LaunchedEffect(settings.apiKey, settings.apiBase, settings.model) {
        if (!edited) {
            key = settings.apiKey
            baseUrl = settings.apiBase
            model = settings.model
        }
    }

    ScreenColumn("AI 设置", onBack) {
        WhaleCard {
            Text("🐳 鲸鱼娘的大脑", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "默认使用公司 AI，也支持 DeepSeek 官方及其他 OpenAI 兼容接口。配置后就能用：\n" +
                    "· 一句话记账（「昨天午饭 25 微信」）\n" +
                    "· 通知账单智能分类\n" +
                    "· 每月消费点评（鲸鱼娘月报）\n\n" +
                    "不填也能用本地规则。Key 只存在本机，AI 请求发送到你配置的接口地址。",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用 AI 能力", color = p.text, fontSize = 13.sp)
                    Text("关闭后不产生任何 API 请求", color = p.sub, fontSize = 10.sp)
                }
                Switch(checked = settings.aiEnabled, onCheckedChange = { vm.setAiEnabled(it) }, enabled = !testing)
            }
            Spacer(Modifier.height(10.dp))
            Text("快捷配置（切换后请填写对应的 Key）", color = p.sub, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = AiEndpoint.sameService(baseUrl, AiEndpoint.SOUTHCHIP_BASE_URL), enabled = !testing, onClick = {
                    edited = true
                    if (!AiEndpoint.sameService(baseUrl, AiEndpoint.SOUTHCHIP_BASE_URL)) key = ""
                    baseUrl = AiEndpoint.SOUTHCHIP_BASE_URL
                    model = AiEndpoint.SOUTHCHIP_MODEL
                    hint = "已选择公司 AI，请填写 Key 后保存"
                }, label = { Text("公司 AI") })
                FilterChip(selected = AiEndpoint.sameService(baseUrl, AiEndpoint.DEEPSEEK_BASE_URL), enabled = !testing, onClick = {
                    edited = true
                    if (!AiEndpoint.sameService(baseUrl, AiEndpoint.DEEPSEEK_BASE_URL)) key = ""
                    baseUrl = AiEndpoint.DEEPSEEK_BASE_URL
                    model = AiEndpoint.DEEPSEEK_MODEL
                    hint = "已选择 DeepSeek 官方，请填写 Key 后保存"
                }, label = { Text("DeepSeek 官方") })
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { edited = true; baseUrl = it; hint = "" },
                label = { Text("接口地址（Base URL）") },
                supportingText = { Text("公司：https://litellm.southchips.net") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                enabled = !testing,
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { edited = true; key = it; hint = "" },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !testing,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { edited = true; model = it; hint = "" },
                label = { Text("模型名称") },
                supportingText = { Text("公司：sc-deepseek-flash；官方：deepseek-chat") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !testing,
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.primary)
                        .clickable(enabled = !testing) {
                            testing = true
                            hint = "正在保存并测试…"
                            vm.saveAndTestAi(baseUrl, key, model) {
                                hint = it
                                testing = false
                            }
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (testing) "测试中…" else "保存并测试", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp) }
            }
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(hint, color = p.primary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("费用说明", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "费用、额度及模型权限以所选服务的规则为准。公司 AI 需要手机能访问公司网关。所有 AI 功能都能在「我的」里一键关闭。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
    }
}

// ================= 数据导入导出 =================
@Composable
fun DataScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    var hint by remember { mutableStateOf("") }
    var autoSnapNote by remember { mutableStateOf("") }
    var ruleInfo by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // 导出/导入识别规则（P1-4：490 条规则交给用户自己改）
    val exportRulesLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = com.jingyuniang.ledger.capture.RuleStore
                    .exportJson(com.jingyuniang.ledger.capture.RuleStore.current)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ruleInfo = "已导出（${com.jingyuniang.ledger.capture.RuleStore.current.totalEntries} 条规则）"
            }.onFailure { ruleInfo = "导出失败：${it.message}" }
        }
    }
    val importRulesLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    ruleInfo = "读不到这个文件"
                } else {
                    val err = com.jingyuniang.ledger.capture.RuleStore.importFrom(ctx, bytes)
                    ruleInfo = if (err == null) {
                        "导入成功：${com.jingyuniang.ledger.capture.RuleStore.current.totalEntries} 条，已立即生效"
                    } else {
                        "导入失败：$err（现有规则未变）"
                    }
                }
            }.onFailure { ruleInfo = "导入失败：${it.message}" }
        }
    }
    var shareTarget by remember { mutableStateOf<File?>(null) }
    var askPassword by remember { mutableStateOf<String?>(null) } // "backup" / "restore"
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    // 规则被「说人话的规则」改过后要重算上面的「共 N 条规则」卡片；
    // RuleStore 是普通全局对象，不放进状态里这一行永远显示 490 / App 内置（真机踩过）。
    var rulesVersion by remember { mutableStateOf(0) }

    val pickBill = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            hint = "正在导入，账单多的话要等几秒…"
            vm.importBill(uri) { hint = it }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            askPassword = "restore"
        }
    }
    // 备份另存（ACTION_CREATE_DOCUMENT）：可直接存到下载目录/网盘，换手机时带走
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val src = shareTarget
        if (uri == null || src == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)
        hint = if (ok) "已保存到所选位置 ✅（换机时把它带到新手机，再用「从备份恢复」）"
        else "保存失败，试试「分享这个文件」"
    }

    ScreenColumn("数据导入导出", onBack) {
        WhaleCard {
            Text("导入微信 / 支付宝账单", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "微信：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账（会收到 zip，解压出 csv）\n" +
                    "支付宝：我的 → 账单 → 右上角 → 开具交易流水证明 → 用于个人对账（csv/zip）\n\n" +
                    "选文件即可，重复的账目会自动跳过。带密码的 zip 请先在电脑上解压。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            ActionButton("选择账单文件（csv / zip）") {
                runCatching { pickBill.launch(arrayOf("*/*")) }
                    .onFailure { hint = "这台设备没有文件选择器，无法选文件" }
            }
        }

        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("导出账目", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("导出为 CSV（UTF-8，Excel 可直接打开），可分享到微信 / 邮箱 / 网盘。", color = p.sub, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            ActionButton("导出全部账目 CSV") {
                vm.exportCsv { r ->
                    hint = r.message
                    if (r.ok) shareTarget = r.file
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("加密备份 / 恢复", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "整库打包成一个 AES-GCM 加密文件（.whalebk），需要密码才能恢复。换手机、防误删必备。",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            ActionButton("创建加密备份") { askPassword = "backup" }
            Spacer(Modifier.height(8.dp))
            ActionButton("从备份恢复（会覆盖当前数据）", danger = true) {
                runCatching { pickBackup.launch(arrayOf("*/*")) }
                    .onFailure { hint = "这台设备没有文件选择器，无法选文件" }
            }
        }

        // 自动快照（App 自动做的「救命备份」）：升级后/每天一份，不需要密码
        // 识别规则（可编辑）：把 490 条规则暴露给用户，改词不用等发版
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("识别规则（可自己改）", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // 三个触发源都要重算：说人话加词（rulesVersion）/ 导入 / 恢复默认（都改 ruleInfo）
            val rules = remember(rulesVersion, ruleInfo) { com.jingyuniang.ledger.capture.RuleStore.current }
            Text(
                "当前：${rules.source} · 共 ${rules.totalEntries} 条规则",
                color = p.sub, fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "遇到「老是抓错」或「老是漏抓」，可以先导出规则、在对照表里加/删几个词，再导入——立即生效，不用等更新。\n" +
                    "改坏了也不要紧：文件校验不过会自动回退到内置规则。",
                color = p.sub, fontSize = 10.sp, lineHeight = 15.sp
            )
            if (ruleInfo.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(ruleInfo, color = p.primary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                ActionButton("导出规则") {
                    runCatching {
                        exportRulesLauncher.launch("capture_rules.json")
                    }.onFailure { ruleInfo = "这台设备没有文件保存器" }
                }
                Spacer(Modifier.width(8.dp))
                ActionButton("导入规则") {
                    runCatching {
                        importRulesLauncher.launch(arrayOf("application/json", "*/*"))
                    }.onFailure { ruleInfo = "这台设备没有文件选择器" }
                }
                Spacer(Modifier.width(8.dp))
                ActionButton("恢复默认", danger = true) {
                    val ok = com.jingyuniang.ledger.capture.RuleStore.resetToBuiltIn(ctx)
                    ruleInfo = if (ok) "已恢复内置规则（${com.jingyuniang.ledger.capture.RuleStore.current.totalEntries} 条）"
                    else "恢复失败"
                }
            }
        }

        // 「说人话的规则」：不用读 JSON，说出症状就能加词（Backlog P1）
        Spacer(Modifier.height(10.dp))
        RuleGuideCard(ctx, onRulesChanged = { rulesVersion++ })

        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("自动快照（App 自动生成）", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                com.jingyuniang.ledger.io.SnapshotManager.describe(ctx),
                color = p.sub, fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "① 每次升级后、以及每天第一次打开 App 时各存一份（保留最近 7 份）\n" +
                    "② 升级要改数据库表结构之前，会先把整个数据库文件抄一份（迁移失败也不丢）\n" +
                    "③ 这些只存在 App 私有目录，卸载 App 会一起消失；跨手机请用上面的加密备份",
                color = p.sub, fontSize = 10.sp, lineHeight = 15.sp
            )
            if (autoSnapNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(autoSnapNote, color = p.primary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            ActionButton("现在生成一份快照") {
                autoSnapNote = "正在生成…"
                scope.launch {
                    val f = com.jingyuniang.ledger.io.SnapshotManager
                        .writeDaily(ctx, "manual")
                    autoSnapNote = if (f != null) {
                        "已生成：${f.name}（${f.length() / 1024} KB）"
                    } else {
                        "生成失败，详见「抓取/诊断日志」"
                    }
                }
            }
        }

        if (hint.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Text(hint, color = p.text, fontSize = 12.sp, lineHeight = 18.sp)
                if (shareTarget != null) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton("分享这个文件") { shareFile(ctx, shareTarget!!) }
                    Spacer(Modifier.height(8.dp))
                    // 换手机的关键一步：直接把备份另存到「下载/网盘/微信文件」，不用先分享再翻
                    ActionButton("另存到文件 / 云盘（换手机用这个）") {
                        runCatching {
                            saveLauncher.launch((shareTarget?.name ?: "鲸鱼娘备份.whalebk"))
                        }.onFailure { hint = "这台设备没有文件保存器" }
                    }
                }
            }
        }
    }

    val mode = askPassword
    if (mode != null) {
        PasswordDialog(
            title = if (mode == "backup") "设置备份密码" else "输入备份密码",
            onDismiss = { askPassword = null; pendingRestoreUri = null },
            onConfirm = { pwd ->
                askPassword = null
                if (mode == "backup") {
                    vm.backup(pwd) { r ->
                        hint = r.message
                        if (r.ok) shareTarget = r.file
                    }
                } else {
                    val uri = pendingRestoreUri
                    if (uri != null) {
                        vm.restore(uri, pwd) { r -> hint = r.message }
                    }
                    pendingRestoreUri = null
                }
            }
        )
    }
}

@Composable
private fun ActionButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) p.expense.copy(alpha = 0.12f) else p.primarySoft)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (danger) p.expense else p.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val p = WhaleTheme.palette
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("密码丢失后备份文件无法打开，请记牢。", color = p.sub, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码（至少 4 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pwd.length >= 4) onConfirm(pwd) }) {
                Text("确定", color = p.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.sub) } }
    )
}

fun shareFile(ctx: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".csv")) "text/csv" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ================= 权限向导 =================
@Composable
fun PermissionWizardScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    val listener = remember(tick) { Permissions.isNotificationListenerEnabled(ctx) }
    val notif = remember(tick) { Permissions.hasPostNotification(ctx) }
    val sms = remember(tick) { Permissions.hasReceiveSms(ctx) }
    val access = remember(tick) { Permissions.isAccessibilityEnabled(ctx) }
    val battery = remember(tick) { Permissions.isIgnoringBatteryOptimizations(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    ScreenColumn("权限向导", onBack) {
        WhaleCard {
            Text("按顺序开这 4 项，自动记账就稳了", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("点每一行右边的按钮，系统会直接跳到对应设置页。", color = p.sub, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val doneCount = listOf(listener, notif, sms, battery).count { it }
        WhaleCard {
            ProgressBar(ratio = doneCount / 4f, color = p.primary, height = 10.dp)
            Spacer(Modifier.height(6.dp))
            Text("已完成 $doneCount / 4", color = p.sub, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))

        WhaleCard {
            StepRow("1", "通知使用权", "抓取微信 / 支付宝 / 银行通知的关键权限", listener) {
                Permissions.openNotificationListenerSettings(ctx)
                tick++
            }
            RowDivider()
            StepRow("2", "通知权限", "抓取后要能弹通知问你确认", notif) {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch("android.permission.POST_NOTIFICATIONS")
                else Permissions.openAppDetails(ctx)
            }
            RowDivider()
            StepRow("3", "短信权限", "银行卡扣款短信直接入账（拿不到也不影响通知抓取）", sms) {
                smsPerm.launch(android.Manifest.permission.RECEIVE_SMS)
            }
            RowDivider()
            StepRow("4", "忽略电池优化", "防止国产系统把抓取服务杀掉", battery) {
                Permissions.requestIgnoreBatteryOptimizations(ctx)
                tick++
            }
            RowDivider()
            StepRow("＋", "无障碍增强（可选）", "通知被折叠时读屏补漏", access) {
                Permissions.openAccessibilitySettings(ctx)
                tick++
            }
        }

        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("自启动白名单", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "小米：设置 → 应用设置 → 应用管理 → 鲸鱼娘金库 → 自启动 / 后台弹出界面 打开\n" +
                    "华为/荣耀：设置 → 应用 → 应用启动管理 → 鲸鱼娘金库 → 手动管理，全部打开\n" +
                    "OPPO/一加：设置 → 电池 → 应用耗电管理 → 允许后台运行\n" +
                    "vivo：设置 → 电池 → 后台耗电管理 → 允许后台高耗电\n" +
                    "三星：设置 → 电池 → 后台使用限制 → 把鲸鱼娘加入「永不休眠」",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            ActionButton("打开本应用系统设置") { Permissions.openAppDetails(ctx) }
        }
    }
}

@Composable
private fun StepRow(num: String, title: String, desc: String, ok: Boolean, onAction: () -> Unit) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (ok) p.income.copy(alpha = 0.15f) else p.surfaceAlt),
            contentAlignment = Alignment.Center
        ) { Text(if (ok) "✓" else num, color = if (ok) p.income else p.sub, fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = p.sub, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Text(
            if (ok) "已完成" else "去开启",
            color = if (ok) p.sub else p.primary,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (ok) p.surfaceAlt else p.primarySoft)
                .clickable { onAction() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ================= 关于 =================
@Composable
fun AboutScreen(onBack: () -> Unit, onCheckUpdate: () -> Unit = {}) {
    val p = WhaleTheme.palette
    ScreenColumn("关于鲸鱼娘", onBack) {
        WhaleCard {
            Text("当前版本 v${BuildConfig.VERSION_NAME}", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("启动后每天检查一次新版本，确认后才下载更新。", color = p.sub, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            ActionButton("检查更新", onClick = onCheckUpdate)
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.primary),
                    contentAlignment = Alignment.Center
                ) { Text("🐳", fontSize = 30.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("鲸鱼娘金库", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("v${BuildConfig.VERSION_NAME} · 纯本地记账 App", color = p.sub, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "一个把「自动抓账单」做到底的手机记账工具。灵感来自钱迹与一木记账，但把重点放在" +
                    "「不用手动记」上：通知栏抓取 + 银行短信 + 账单导入 + 无障碍增强，四路兜底。",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("隐私", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "· 账目、通知原文、票据照片全部存在本机 SQLite / 私有目录；\n" +
                    "· 没有账号系统，不上传服务器，没有广告 SDK，没有埋点；\n" +
                    "· 使用 AI 功能时，相关账目、通知片段或账本摘要会发到你在 AI 设置中配置的服务；\n" +
                    "· 备份文件由你的密码加密，连开发者也无法解开。",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("自动记账为什么不是 100%？", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 出于安全不允许任何 App 直接读取微信/支付宝的内部账单。所有记账 App（包括钱迹、一木）" +
                    "都只能读「通知」「短信」「屏幕文字」这些公开信息。\n\n" +
                    "所以：通知被折叠、App 静音、被系统清理后台时，可能漏抓。鲸鱼娘的对策是四路并举 + " +
                    "账单导入兜底 + 悬浮球一秒手记。漏了哪笔，导入一次账单就补齐了。",
                color = p.sub, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        WhaleCard {
            Text("技术栈", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kotlin + Jetpack Compose + Room + WorkManager + ML Kit(中文 OCR)\n" +
                    "minSdk 26（Android 8.0）· 无任何第三方统计/广告依赖",
                color = p.sub, fontSize = 11.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "素材署名：鲸鱼娘原角色 上善（pixiv/62155430）→ 女仆二创 ZipZipPipe（pixiv/18604994）→ 皮肤 深海女仆工坊 Small-tailqwq，授权 CC BY-NC-SA 4.0；另含蓝色大肥鱼公开存档图片，版权归原作者。本 App 仅供个人非商业使用。\n\n用鲸鱼娘金库，钱会自己排队进账本 🐳",
            color = p.sub.copy(alpha = 0.8f),
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ================= 搜索 =================
@Composable
fun SearchScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Txn>>(emptyList()) }
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }

    Column(modifier = Modifier.fillMaxSize().background(p.bg)) {
        TopBar(title = "搜索账目", onBack = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.surface)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    vm.search(it) { r -> results = r }
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 14.sp),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                decorationBox = { inner ->
                    if (keyword.isEmpty()) Text("搜备注 / 商户 / 标签", color = p.sub, fontSize = 14.sp)
                    inner()
                }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            if (keyword.isBlank()) {
                EmptyState(emoji = "🔍", title = "输入关键词开始搜索", sub = "例如「星巴克」「房租」「报销」")
            } else if (results.isEmpty()) {
                EmptyState(emoji = "🫥", title = "没找到相关账目")
            } else {
                Text("共 ${results.size} 条", color = p.sub, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                results.take(200).forEach { t ->
                    TxnRow(t, catMap, accMap, onClick = { })
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
