package com.jingyuniang.ledger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jingyuniang.ledger.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val quickActionInternal = MutableStateFlow<String?>(null)
    private val quickAction: kotlinx.coroutines.flow.StateFlow<String?> = quickActionInternal.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不启用 edge-to-edge：部分 ROM（以及本机测试用的 ATD 镜像）会把窗口高度按去掉导航栏的
        // 尺寸下发，而 insets 回报 0，导致底部控件被顶出可见区域。让装饰视图贴合窗口最稳。
        quickActionInternal.value = resolveAction(intent)
        handleDiag(intent)
        window.decorView.post {
            runCatching {
                val ins = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
                val bars = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val nav = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                val status = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                com.jingyuniang.ledger.util.AppLog.d(
                    "WIN decor=${window.decorView.width}x${window.decorView.height} bars=$bars nav=$nav status=$status"
                )
            }
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val action by quickAction.collectAsStateWithLifecycle()
            WhaleMoneyTheme(settings.darkMode) {
                var showingSplash by remember { mutableStateOf(true) }
                var bootRoute by remember { mutableStateOf<String?>(null) }
                var unlocked by remember { mutableStateOf(false) }
                if (settings.lockPin.isNotEmpty() && !unlocked) {
                    LockScreen(settings.lockPin) { unlocked = true }
                } else if (!settings.onboardingDone) {
                    OnboardingScreen(
                        onOpenPermission = {
                            bootRoute = "settings-permission"
                            vm.setOnboardingDone()
                        },
                        onDone = { vm.setOnboardingDone() }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WhaleRoot(vm, action, bootRoute) { quickActionInternal.value = null }
                        if (showingSplash) {
                            SplashCurtain { showingSplash = false }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveAction(intent)?.let { quickActionInternal.value = it }
        handleDiag(intent)
    }

    /** 通知栏 / 小组件 / 快捷方式点进来的入口归一化 */
    private fun resolveAction(intent: Intent?): String? {
        if (intent == null) return null
        val screen = intent.getStringExtra("screen")
        val data = intent.data?.toString().orEmpty()
        return when {
            screen == "captures" || data.contains("/open/captures") -> ACTION_OPEN_CAPTURES
            screen == "ai" || data.contains("/open/ai") -> ACTION_OPEN_AI_CHAT
            intent.action != null -> intent.action
            else -> null
        }
    }

    companion object {
        const val ACTION_OPEN_CAPTURES = "com.jingyuniang.ledger.OPEN_CAPTURES"
        const val ACTION_OPEN_AI_CHAT = "com.jingyuniang.ledger.OPEN_AI_CHAT"
    }

    /**
     * adb 自检入口。Compose 控件在 uiautomator 里没有文本节点、中文也打不进输入框，
     * 所以一切 AI / 写库验证都走「Intent 传文本 + logcat 看结果」：
     *
     *   adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "ai:发微信开销了十块钱"
     *   adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "book:午饭25元"
     *   adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "bal:<账户id>:1000"
     *   adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "dump"
     *   adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "del:<txnId>"
     *
     * 只接受 adb shell / root 发来的指令（uid 2000 / 0）：MainActivity 是 exported 的，
     * 不设门槛的话，手机上任何 App 都能用 Intent 改账本。
     */
    private fun handleDiag(intent: Intent?) {
        val cmd = intent?.getStringExtra("diag")?.trim().orEmpty()
        if (cmd.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // 实测：从 adb shell 起 Activity 时 launchedFromUid 常常给 -1（MIUI 不填这个字段），
            // 所以只在「拿得到真实 uid 且不是 shell/root」时才拒绝。
            val uid = runCatching { launchedFromUid }.getOrDefault(-1)
            if (uid > 0 && uid != 2000 && uid != 0) {
                com.jingyuniang.ledger.util.AppLog.w("DIAG 拒绝非 shell 调用 uid=$uid cmd=$cmd")
                return
            }
        }
        com.jingyuniang.ledger.util.AppLog.d("DIAG cmd=$cmd")
        lifecycleScope.launch {
            runCatching {
                when {
                    cmd.startsWith("ai:") -> {
                        val t = cmd.removePrefix("ai:")
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG ai available=${com.jingyuniang.ledger.ai.AiService.available()} text=$t"
                        )
                        vm.aiAgent(t) { dumpStateAsync("after-ai") }
                    }
                    cmd.startsWith("book:") ->
                        vm.quickAiBook(cmd.removePrefix("book:")) { ok, msg, id ->
                            com.jingyuniang.ledger.util.AppLog.d("DIAG book ok=$ok id=$id msg=$msg")
                            dumpStateAsync("after-book")
                        }
                    cmd.startsWith("bal:") -> {
                        val parts = cmd.removePrefix("bal:").split(":")
                        val allAcc = com.jingyuniang.ledger.Services.repo.accountDao.all()
                        val acc = allAcc.firstOrNull { it.id == (parts.getOrNull(0)?.toLongOrNull() ?: -1L) }
                        if (acc == null) {
                            com.jingyuniang.ledger.util.AppLog.w("DIAG bal 账户不存在: $cmd")
                        } else {
                            val target = com.jingyuniang.ledger.util.Fmt.toCents(parts.getOrNull(1).orEmpty())
                            vm.applyBalanceInput(acc, target) { ok, msg ->
                                com.jingyuniang.ledger.util.AppLog.d("DIAG bal ok=$ok msg=$msg")
                                dumpStateAsync("after-bal")
                            }
                        }
                    }
                    cmd.startsWith("ocrbal:") -> {
                        // 自检：拿一张余额截图走真实 OCR → BalanceParser → 猜账户 → 校准
                        val uriStr = cmd.removePrefix("ocrbal:")
                        val allAcc = com.jingyuniang.ledger.Services.repo.accountDao.all()
                        vm.ocrBalance(Uri.parse(uriStr)) { hit, msg, typeHint ->
                            val acc = typeHint?.let { t -> allAcc.firstOrNull { it.type == t } }
                                ?: allAcc.firstOrNull { it.isDefault }
                                ?: allAcc.firstOrNull()
                            com.jingyuniang.ledger.util.AppLog.d(
                                "DIAG ocrbal hit=${hit?.cents} label=${hit?.label} " +
                                    "accType=$typeHint acc=${acc?.name} msg=$msg"
                            )
                            if (hit != null && hit.cents > 0 && acc != null) {
                                vm.applyBalanceInput(acc, hit.cents) { ok, m ->
                                    com.jingyuniang.ledger.util.AppLog.d("DIAG ocrbal apply ok=$ok msg=$m")
                                    dumpStateAsync("after-ocrbal")
                                }
                            } else {
                                dumpStateAsync("after-ocrbal-fail")
                            }
                        }
                    }
                    cmd.startsWith("probe:") -> {
                        // 规则体检：把任意一段通知原文喂进「抓取闸门 + 金额解析」，只打日志不写库。
                        // 用法：--es diag "probe:com.ss.android.ugc.aweme|9.9元包邮 直播间"（带包名）
                        //       或 --es diag "probe:9.9元包邮"（不带包名，按「其他应用」判）
                        // 分隔符用 '#'：adb shell 会把 '|' 当管道、空格当参数分隔，都会截断
                        val raw = cmd.removePrefix("probe:")
                        val sep = when {
                            raw.contains("#") -> "#"
                            raw.contains("|") -> "|"
                            else -> ""
                        }
                        val parts = if (sep.isEmpty()) listOf(raw) else raw.split(sep)
                        val pkg = if (parts.size >= 2) parts[0] else ""
                        val title = if (parts.size >= 3) parts[1] else ""
                        val text = parts.last()
                        val cf = com.jingyuniang.ledger.capture.CaptureFilters
                        val pe = com.jingyuniang.ledger.capture.ParseEngine
                        val kind = when {
                            cf.isSmsApp(pkg) -> "SMS"
                            cf.isChatApp(pkg) -> "CHAT"
                            cf.isContentApp(pkg) -> "CONTENT"
                            cf.isCommerceApp(pkg) -> "COMMERCE"
                            cf.isPayRail(pkg) -> "PAY_RAIL"
                            else -> "OTHER"
                        }
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG probe kind=$kind pkg=${pkg.ifBlank { "(none)" }} text=$text"
                        )
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG probe junk=${pe.isJunk(text)} nonTxn=${cf.isNonTxnNotice(text)} " +
                                "listPage=${cf.looksLikeStatementList(text)} amounts=${cf.amountCount(text)} " +
                                "evidence=${cf.hasTxnEvidence(text)} resultPage=${cf.isPaymentResultPage(text)}"
                        )
                        val should = cf.shouldRead(pkg, text, title)
                        val base = 40 + 20 + 15 + 10 + 5   // 典型通知的粗基准（方向/账户/分类/动词），仅作参考
                        val eff = com.jingyuniang.ledger.capture.CapturePipeline.effectiveConfidence(
                            com.jingyuniang.ledger.data.TxnSource.NOTIFY, pkg, title, text, base
                        )
                        val threshold = com.jingyuniang.ledger.Services.settings.value.autoBookThreshold
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG probe => shouldRead=$should title=${title.ifBlank { "(none)" }} " +
                                "extractAmount=${pe.extractAmount(text)} " +
                                "evidence=${cf.hasTxnEvidence(text)} paySender=${cf.isPaySourceTitle(title)} " +
                                "strongTpl=${cf.isStrongPayTemplate(text)}"
                        )
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG probe => 参考置信度 base=$base → 校正后=$eff（阈值 $threshold，" +
                                "自动入账=${eff >= threshold}）"
                        )
                    }
                    cmd.startsWith("simulate:") -> {
                        // 走**完整流水线**（会写库），用来验证「这条到底记没记、记成什么」
                        // 用法：simulate:cmb.pb#招商银行#您尾号8888信用卡还款成功，金额1000.00元
                        val raw = cmd.removePrefix("simulate:")
                        val parts = raw.split("#")
                        val pkg = parts.getOrNull(0).orEmpty()
                        val title = parts.getOrNull(1).orEmpty()
                        val text = parts.drop(2).joinToString("#")
                        com.jingyuniang.ledger.util.AppLog.d("DIAG simulate pkg=$pkg title=$title text=$text")
                        lifecycleScope.launch {
                            val id = com.jingyuniang.ledger.capture.CapturePipeline.process(
                                com.jingyuniang.ledger.data.TxnSource.NOTIFY,
                                pkg.ifBlank { null },
                                pkg.ifBlank { "unknown" },
                                title,
                                text
                            )
                            com.jingyuniang.ledger.util.AppLog.d("DIAG simulate => captureId=${id ?: "null(被丢弃)"}")
                            dumpStateAsync("after-simulate")
                        }
                    }
                    cmd.startsWith("delcap:") -> {
                        val id = cmd.removePrefix("delcap:").toLongOrNull() ?: 0L
                        val c = com.jingyuniang.ledger.Services.repo.captureDao.all().firstOrNull { it.id == id }
                        if (c == null) {
                            com.jingyuniang.ledger.util.AppLog.w("DIAG delcap 找不到 #$id")
                        } else {
                            com.jingyuniang.ledger.Services.repo.captureDao.delete(c)
                            com.jingyuniang.ledger.util.AppLog.d("DIAG delcap #$id 已删除")
                        }
                    }
                    cmd == "captures" -> {
                        // 自检：列出最近 12 条抓取记录（含 id / 状态），用于排查「这条为什么没入账」
                        val list = com.jingyuniang.ledger.Services.repo.captureDao.all()
                            .sortedByDescending { it.time }.take(12)
                        list.forEach { c ->
                            com.jingyuniang.ledger.util.AppLog.d(
                                "DIAG capture #${c.id} status=${c.status} src=${c.source} pkg=${c.pkg} " +
                                    "amt=${c.amountCents} conf=${c.confidence} txn=${c.txnId} " +
                                    "text=${c.rawText.take(60)}"
                            )
                        }
                        com.jingyuniang.ledger.util.AppLog.d("DIAG captures total=${list.size}")
                    }
                    cmd.startsWith("restorecap:") -> {
                        // 把被误「忽略」的抓取恢复成待确认（用户能再确认入账）
                        val id = cmd.removePrefix("restorecap:").toLongOrNull() ?: 0L
                        val c = com.jingyuniang.ledger.Services.repo.captureDao.all().firstOrNull { it.id == id }
                        if (c == null) {
                            com.jingyuniang.ledger.util.AppLog.w("DIAG restorecap 找不到 #$id")
                        } else {
                            com.jingyuniang.ledger.Services.repo.captureDao.update(
                                c.copy(status = com.jingyuniang.ledger.data.CaptureStatus.PENDING)
                            )
                            com.jingyuniang.ledger.util.AppLog.d("DIAG restorecap #$id → PENDING")
                        }
                    }
                    cmd == "settings" -> {
                        // 自检：升级前后对比配置是否被保住（Key 只打掩码，不落日志）
                        val s = com.jingyuniang.ledger.Services.settings.value
                        val keyMask = if (s.apiKey.isBlank()) "(空)" else "len=${s.apiKey.length} ...${s.apiKey.takeLast(4)}"
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG settings aiEnabled=${s.aiEnabled} model=${s.model} apiBase=${s.apiBase} key=$keyMask"
                        )
                        com.jingyuniang.ledger.util.AppLog.d(
                            "DIAG settings autoBook=${s.autoBook} threshold=${s.autoBookThreshold} " +
                                "captureMaster=${s.captureMaster} notify=${s.captureNotify} sms=${s.captureSms} " +
                                "access=${s.captureAccess} onboardingDone=${s.onboardingDone} " +
                                "lockPin=${if (s.lockPin.isBlank()) "未设置" else "已设置"} " +
                                "blocklist=${s.captureBlocklist.size} ledgerId=${s.ledgerId} lastBackup=${s.lastBackup}"
                        )
                        dumpStateAsync("after-settings")
                    }
                    cmd.startsWith("capture:") -> {
                        // 自检：把一条示例通知喂进真实抓取流水线（0=微信 1=银行短信 2=支付宝）
                        val sample = cmd.removePrefix("capture:").toIntOrNull() ?: 0
                        vm.runCaptureSelfTest(sample) { msg ->
                            com.jingyuniang.ledger.util.AppLog.d("DIAG capture[$sample] $msg")
                            dumpStateAsync("after-capture")
                        }
                    }
                    cmd.startsWith("backup:") -> {
                        // 自检：加密备份（备份文件落在 filesDir/backups/）
                        val pwd = cmd.removePrefix("backup:")
                        vm.backup(pwd) { r ->
                            com.jingyuniang.ledger.util.AppLog.d(
                                "DIAG backup ok=${r.ok} msg=${r.message} " +
                                    "file=${r.file?.name} size=${r.file?.length()}"
                            )
                            dumpStateAsync("after-backup")
                        }
                    }
                    cmd.startsWith("restorefile:") -> {
                        // 自检：恢复 filesDir/backups 里的备份（跳过系统文件选择器，逻辑和用户点恢复一致）
                        // 文件名带中文，adb 传参会丢字节，所以支持 "latest" 取最新一份
                        val parts = cmd.removePrefix("restorefile:").split(":")
                        val name = parts.getOrNull(0).orEmpty()
                        val pwd = parts.getOrNull(1).orEmpty()
                        val dir = java.io.File(applicationContext.filesDir, "backups")
                        val f = if (name.isBlank() || name == "latest") {
                            dir.listFiles()?.filter { it.extension == "whalebk" }?.maxByOrNull { it.lastModified() }
                        } else {
                            java.io.File(dir, name)
                        }
                        if (f == null || !f.exists()) {
                            com.jingyuniang.ledger.util.AppLog.w("DIAG restorefile 文件不存在: $name")
                        } else {
                            com.jingyuniang.ledger.util.AppLog.d("DIAG restorefile 使用 ${f.name}")
                            vm.restore(Uri.fromFile(f), pwd) { r ->
                                com.jingyuniang.ledger.util.AppLog.d("DIAG restore ok=${r.ok} msg=${r.message}")
                                dumpStateAsync("after-restore")
                            }
                        }
                    }
                    cmd.startsWith("del:") -> {
                        val id = cmd.removePrefix("del:").toLongOrNull() ?: 0L
                        val t = com.jingyuniang.ledger.Services.repo.txnById(id)
                        if (t == null) {
                            com.jingyuniang.ledger.util.AppLog.w("DIAG del 找不到 txn#$id")
                        } else {
                            com.jingyuniang.ledger.Services.repo.deleteTxn(t)
                            com.jingyuniang.ledger.util.AppLog.d("DIAG del txn#$id 已删除")
                            dumpStateAsync("after-del")
                        }
                    }
                    else -> dumpState("dump")
                }
            }.onFailure { com.jingyuniang.ledger.util.AppLog.e("DIAG 执行失败: $cmd", it) }
        }
    }

    /** 把「余额 + 最近账目」打到 logcat，作为写库是否真的落地的证据 */
    private fun dumpStateAsync(tag: String) {
        lifecycleScope.launch { dumpState(tag) }
    }

    private suspend fun dumpState(tag: String) {
        val repo = com.jingyuniang.ledger.Services.repo
        val bal = runCatching { repo.accountBalanceMap() }.getOrElse { emptyMap() }
        val txns = runCatching { repo.allTxns() }.getOrElse { emptyList() }
        val accs = runCatching { repo.accountDao.all() }.getOrElse { emptyList() }
        com.jingyuniang.ledger.util.AppLog.d(
            "DIAG[$tag] accounts=" +
                accs.joinToString(", ") { "${it.id}:${it.name}${if (it.isDefault) "*" else ""}" }
        )
        com.jingyuniang.ledger.util.AppLog.d(
            "DIAG[$tag] balances=" + bal.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )
        val pend = runCatching { repo.pendingCapturesCountNow() }.getOrDefault(0)
        com.jingyuniang.ledger.util.AppLog.d("DIAG[$tag] txnCount=${txns.size} pendingCaptures=$pend")
        txns.take(6).forEach { t ->   // allTxns() 已按 date DESC 排序，take(6) 才是最近 6 笔
            com.jingyuniang.ledger.util.AppLog.d(
                "DIAG[$tag] txn#${t.id} ${t.type} ${t.amountCents} acc=${t.accountId} " +
                    "cat=${t.categoryId} src=${t.source} note=${t.note} date=${t.date}"
            )
        }
    }
}

@Composable
fun WhaleRoot(
    vm: AppViewModel,
    quickAction: String?,
    initialRoute: String? = null,
    onConsumeAction: () -> Unit
) {
    val p = WhaleTheme.palette
    val nav = rememberNavController()

    LaunchedEffect(initialRoute) {
        if (initialRoute != null) nav.navigate(initialRoute) { launchSingleTop = true }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val showBar = route in setOf("home", "stats", "plan", "mine")

    fun go(r: String) {
        nav.navigate(r) {
            launchSingleTop = true
        }
    }

    fun goTab(r: String) {
        nav.navigate(r) {
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = p.bg,
        bottomBar = {
            if (showBar) {
                WhaleBottomBar(
                    current = route,
                    onSelect = { goTab(it) },
                    onAdd = { go("add?txnId=0") }
                )
            }
        }
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(pad)
        ) {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onAdd = { go("add?txnId=0") },
                    onEdit = { id -> go("add?txnId=$id") },
                    onOpenCaptures = { go("captures") },
                    onOpenSearch = { go("search") },
                    onOpenLedgers = { go("ledger") },
                    onOpenAiChat = { go("ai-chat") }
                )
            }
            composable("stats") { StatsScreen(vm) { go("settings-ai") } }
            composable("plan") { PlanScreen(vm) { go("recurring") } }
            composable("mine") {
                MineScreen(
                    vm = vm,
                    onOpenCaptures = { go("captures") },
                    onOpenLedger = { go("ledger") },
                    onOpenCategory = { go("category") },
                    onOpenAccount = { go("account") },
                    onOpenRecurring = { go("recurring") },
                    onOpenRules = { go("rules") },
                    onOpenData = { go("settings-data") },
                    onOpenAi = { go("settings-ai") },
                    onOpenAiChat = { go("ai-chat") },
                    onOpenPermission = { go("settings-permission") },
                    onOpenAbout = { go("settings-about") }
                )
            }
            composable("ai-chat") { AiChatScreen(vm) { nav.popBackStack() } }
            composable("search") { SearchScreen(vm) { nav.popBackStack() } }
            composable("captures") { CaptureScreen(vm) { nav.popBackStack() } }
            composable("ledger") { LedgerScreen(vm) { nav.popBackStack() } }
            composable("category") { CategoryScreen(vm) { nav.popBackStack() } }
            composable("account") { AccountScreen(vm) { nav.popBackStack() } }
            composable("recurring") { RecurringScreen(vm) { nav.popBackStack() } }
            composable("rules") { RulesScreen(vm) { nav.popBackStack() } }
            composable("settings-ai") { AiSettingsScreen(vm) { nav.popBackStack() } }
            composable("settings-data") { DataScreen(vm) { nav.popBackStack() } }
            composable("settings-permission") { PermissionWizardScreen(vm) { nav.popBackStack() } }
            composable("settings-about") { AboutScreen { nav.popBackStack() } }
            composable(
                route = "add?txnId={txnId}",
                arguments = listOf(navArgument("txnId") {
                    type = NavType.LongType
                    defaultValue = 0L
                })
            ) { entry ->
                val txnId = entry.arguments?.getLong("txnId") ?: 0L
                AddTxnScreen(vm, txnId) { nav.popBackStack() }
            }
        }
    }

    LaunchedEffect(quickAction) {
        when (quickAction) {
            "com.jingyuniang.ledger.QUICK_ADD" -> go("add?txnId=0")
            "com.jingyuniang.ledger.QUICK_VOICE" -> go("add?txnId=0")
            "add", "voice" -> go("add?txnId=0")
            MainActivity.ACTION_OPEN_CAPTURES -> go("captures")
            MainActivity.ACTION_OPEN_AI_CHAT -> go("ai-chat")
        }
        if (quickAction != null) onConsumeAction()
    }
}

@Composable
private fun WhaleBottomBar(current: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val p = WhaleTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, p.primary.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        ) {}
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem("home", "账房", R.drawable.ic_nav_ledger, current, onSelect, Modifier.weight(1f))
            TabItem("stats", "账目", R.drawable.ic_nav_chart, current, onSelect, Modifier.weight(1f))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                        .border(1.5.dp, p.gold, CircleShape)
                        .clickable { onAdd() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋", color = Color(0xFF10203F), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            TabItem("plan", "计划", R.drawable.ic_nav_plan, current, onSelect, Modifier.weight(1f))
            TabItem("mine", "我的", R.drawable.ic_nav_me, current, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(
    route: String,
    label: String,
    iconRes: Int,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = WhaleTheme.palette
    val active = current == route
    Column(
        modifier = modifier
            .clickable { onSelect(route) }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            colorFilter = ColorFilter.tint(if (active) p.primary else p.sub.copy(alpha = 0.7f)),
            modifier = Modifier.size(if (active) 25.dp else 23.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (active) p.primary else p.sub,
            fontSize = 10.sp,
            fontFamily = WhaleSerif,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
