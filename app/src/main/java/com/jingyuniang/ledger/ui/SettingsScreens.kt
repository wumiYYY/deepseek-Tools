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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var hint by remember { mutableStateOf("") }

    ScreenColumn("AI 设置", onBack) {
        WhaleCard {
            Text("🐳 鲸鱼娘的大脑", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "填一个 DeepSeek 开放平台的 API Key，就能用：\n" +
                    "· 一句话记账（「昨天午饭 25 微信」）\n" +
                    "· 通知账单智能分类\n" +
                    "· 每月消费点评（鲸鱼娘月报）\n\n" +
                    "不填也能用 —— 所有识别都会退回本地规则，完全离线。Key 只存在本机，请求直连 api.deepseek.com。",
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
                Switch(checked = settings.aiEnabled, onCheckedChange = { vm.setAiEnabled(it) })
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型（deepseek-chat / deepseek-reasoner）") },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.primary)
                        .clickable {
                            vm.setApiKey(key.trim())
                            vm.setModel(model.trim().ifBlank { "deepseek-chat" })
                            vm.setAiEnabled(true)
                            hint = "已保存，正在测试…"
                            vm.aiParse("测试 12 元", onResult = { r ->
                                hint = if (r != null) "连通成功 ✅ 解析出 ${Fmt.money(r.amountCents)}（${r.reason}）"
                                else "已保存，但解析失败，检查 Key 或网络"
                            }, onError = { hint = "已保存。$it" })
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text("保存并测试", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.surfaceAlt)
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://platform.deepseek.com/api_keys")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text("去申请 Key", color = p.primary, fontSize = 13.sp) }
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
                "deepseek-chat 按 token 计费，日常记账用量很小（一次分类约几百 token）。所有 AI 功能都能在「我的」里一键关闭。",
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
    var shareTarget by remember { mutableStateOf<File?>(null) }
    var askPassword by remember { mutableStateOf<String?>(null) } // "backup" / "restore"
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

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

        if (hint.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            WhaleCard {
                Text(hint, color = p.text, fontSize = 12.sp, lineHeight = 18.sp)
                if (shareTarget != null) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton("分享这个文件") { shareFile(ctx, shareTarget!!) }
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
fun AboutScreen(onBack: () -> Unit) {
    val p = WhaleTheme.palette
    ScreenColumn("关于鲸鱼娘", onBack) {
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
                    "· 只有你主动使用 AI 功能时，才会把「商户名 + 通知片段」发到 DeepSeek；\n" +
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
