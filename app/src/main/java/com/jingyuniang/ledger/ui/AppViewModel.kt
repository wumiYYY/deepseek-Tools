package com.jingyuniang.ledger.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.ai.AiProposal
import com.jingyuniang.ledger.ai.AiEndpoint
import com.jingyuniang.ledger.ai.DeepSeekClient
import com.jingyuniang.ledger.ai.parseJsonObject
import com.jingyuniang.ledger.ai.AiService
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.Budget
import com.jingyuniang.ledger.data.BudgetProgress
import com.jingyuniang.ledger.data.Capture
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.CategorySum
import com.jingyuniang.ledger.data.Debt
import com.jingyuniang.ledger.data.Ledger
import com.jingyuniang.ledger.data.MonthSum
import com.jingyuniang.ledger.data.Recurring
import com.jingyuniang.ledger.data.Repo
import com.jingyuniang.ledger.data.SavingGoal
import com.jingyuniang.ledger.data.Settings
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.io.BackupManager
import com.jingyuniang.ledger.io.BillImporter
import com.jingyuniang.ledger.ocr.OcrHelper
import com.jingyuniang.ledger.util.AppLog
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ChatMsg(val role: String, val text: String, val time: Long = System.currentTimeMillis())

data class MonthStats(    val expense: Long = 0,
    val income: Long = 0,
    val expenseCount: Int = 0,
    val incomeCount: Int = 0,
    val catSums: List<CategorySum> = emptyList(),
    val accountSums: List<Pair<Long, Long>> = emptyList(),
    val dayTotals: Map<Long, Long> = emptyMap(),
    val maxExpense: Txn? = null,
    val avgPerDay: Long = 0,
    val tagSums: List<Triple<String, Long, Int>> = emptyList(),
    val incomeCatSums: List<CategorySum> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: Repo = Services.repo
    private val prefs = Services.prefs
    private val scope = viewModelScope

    val settings: StateFlow<Settings> = Services.settings.asStateFlow()

    private val _anchor = MutableStateFlow(Dates.startOfMonth(System.currentTimeMillis()))
    val anchor: StateFlow<Long> = _anchor.asStateFlow()

    val ledgerId: StateFlow<Long> = Services.settings
        .map { it.ledgerId }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val ledgers: StateFlow<List<Ledger>> = repo.ledgers.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val accounts: StateFlow<List<Account>> = repo.accounts.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val categories: StateFlow<List<Category>> = repo.categories.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val budgets: StateFlow<List<Budget>> = repo.budgets.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val savingGoals: StateFlow<List<SavingGoal>> = repo.savingGoals.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val debts: StateFlow<List<Debt>> = repo.debts.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val recurringRules: StateFlow<List<Recurring>> = repo.recurring.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val autoRules: StateFlow<List<AutoRule>> = repo.autoRules.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val captures: StateFlow<List<Capture>> = repo.captures.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())
    val pendingCaptures: StateFlow<List<Capture>> = repo.pendingCaptures.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())

    val monthTxns: StateFlow<List<Txn>> = combine(ledgerId, anchor) { l, a -> l to a }
        .flatMapLatest { (l, a) -> repo.txns(l, Dates.startOfMonth(a), Dates.startOfNextMonth(a)) }
        .stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())

    val stats: StateFlow<MonthStats> = monthTxns
        .map { computeStats(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(4000), MonthStats())

    val budgetProgress: StateFlow<List<BudgetProgress>> =
        combine(monthTxns, budgets, ledgerId) { txns, bs, lid ->
            val expenseTotal = txns.filter { it.type == TxnType.EXPENSE && !it.excludeBudget }
                .sumOf { it.amountCents }
            val byCat = txns.filter { it.type == TxnType.EXPENSE }
                .groupBy { it.categoryId }
                .mapValues { e -> e.value.sumOf { it.amountCents } }
            bs.filter { it.ledgerId == 0L || it.ledgerId == lid }
                .sortedBy { if (it.categoryId == 0L) -1 else it.categoryId }
                .map { b -> BudgetProgress(b, if (b.categoryId == 0L) expenseTotal else (byCat[b.categoryId] ?: 0L)) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())

    val trend: StateFlow<List<MonthSum>> = combine(ledgerId, anchor) { l, a -> l to a }
        .flatMapLatest { (l, a) -> flow { emit(repo.monthlySums(l, a, 6)) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(4000), emptyList())

    private val _balances = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val balances: StateFlow<Map<Long, Long>> = _balances.asStateFlow()

    val currentLedgerName: StateFlow<String> = combine(ledgers, ledgerId) { ls, id ->
        ls.firstOrNull { it.id == id }?.name ?: "日常账本"
    }.stateIn(scope, SharingStarted.WhileSubscribed(4000), "日常账本")

    init {
        scope.launch {
            runCatching {
                repo.ensureSeeded()
                if (Services.settings.value.ledgerId == 0L) {
                    repo.ledgerDao.all().firstOrNull()?.let { prefs.setLedgerId(it.id) }
                }
                repo.runRecurring()
                refreshBalances()
            }
        }
    }

    private fun computeStats(all: List<Txn>): MonthStats {
        // 余额调整不算收支：它只是把账户余额校准到真实值，进统计会污染「本月花了多少」
        val list = all.filter { it.source != TxnSource.ADJUST }
        val expenseList = list.filter { it.type == TxnType.EXPENSE }
        val incomeList = list.filter { it.type == TxnType.INCOME }
        val expense = expenseList.sumOf { it.amountCents }
        val income = incomeList.sumOf { it.amountCents }
        val dayTotals = LinkedHashMap<Long, Long>()
        expenseList.forEach { t ->
            val d = Dates.startOfDay(t.date)
            dayTotals[d] = (dayTotals[d] ?: 0L) + t.amountCents
        }
        val catSum = expenseList.groupBy { it.categoryId }
            .map { (cid, items) -> CategorySum(cid, items.sumOf { it.amountCents }, items.size) }
            .sortedByDescending { it.cents }
        val incSum = incomeList.groupBy { it.categoryId }
            .map { (cid, items) -> CategorySum(cid, items.sumOf { it.amountCents }, items.size) }
            .sortedByDescending { it.cents }
        val accSum = expenseList.groupBy { it.accountId }
            .map { (aid, items) -> aid to items.sumOf { it.amountCents } }
            .sortedByDescending { it.second }
        val tagMap = LinkedHashMap<String, Long>()
        val tagCnt = LinkedHashMap<String, Int>()
        expenseList.forEach { t ->
            repo.tagList(t.tags).forEach { tag ->
                tagMap[tag] = (tagMap[tag] ?: 0L) + t.amountCents
                tagCnt[tag] = (tagCnt[tag] ?: 0) + 1
            }
        }
        val tagSums = tagMap.entries.map { Triple(it.key, it.value, tagCnt[it.key] ?: 0) }
            .sortedByDescending { it.second }
        val maxTxn = expenseList.maxByOrNull { it.amountCents }
        val days = Dates.daysInMonth(_anchor.value)
        return MonthStats(
            expense = expense,
            income = income,
            expenseCount = expenseList.size,
            incomeCount = incomeList.size,
            catSums = catSum,
            accountSums = accSum,
            dayTotals = dayTotals,
            maxExpense = maxTxn,
            avgPerDay = if (days > 0) expense / days else 0,
            tagSums = tagSums,
            incomeCatSums = incSum
        )
    }

    // ---------------- 月份切换 ----------------
    fun setAnchor(ts: Long) { _anchor.value = Dates.startOfMonth(ts) }
    fun shiftMonth(delta: Int) { _anchor.value = Dates.addMonths(_anchor.value, delta) }
    fun goCurrentMonth() { _anchor.value = Dates.startOfMonth(System.currentTimeMillis()) }

    // ---------------- 账目 ----------------
    fun saveTxn(txn: Txn, edit: Boolean, onDone: () -> Unit = {}) = scope.launch {
        runCatching {
            if (edit) repo.updateTxn(txn) else repo.addTxn(txn)
            // 只有「像商户」的才学：App 名（微信/招商银行）、渠道名、纯分店名（盛夏路店）
            // 一旦被学进商户记忆，会把该来源的所有账单锁死到同一个分类（v1.4.1 真机事故）。
            if (txn.categoryId > 0 &&
                com.jingyuniang.ledger.capture.ParseEngine.isUsableMerchant(txn.merchant)
            ) {
                repo.learnMerchant(txn.merchant, txn.categoryId, txn.accountId)
            }
        }
        onDone()
    }

    fun deleteTxn(txn: Txn, onDone: () -> Unit = {}) = scope.launch {
        runCatching { repo.deleteTxn(txn) }
        onDone()
    }

    // ---------------- 分类 / 账户 / 账本 ----------------
    fun saveCategory(category: Category, onDone: () -> Unit = {}) = scope.launch {
        if (category.id == 0L) repo.categoryDao.insert(category) else repo.categoryDao.update(category)
        onDone()
    }

    fun deleteCategory(category: Category, onDone: () -> Unit = {}) = scope.launch {
        runCatching { repo.categoryDao.delete(category.copy(archived = true)) }
        onDone()
    }

    fun saveAccount(account: Account, onDone: () -> Unit = {}) = scope.launch {
        if (account.id == 0L) repo.accountDao.insert(account) else repo.accountDao.update(account)
        refreshBalances()
        onDone()
    }

    /** 删除账户前先问它名下有多少笔账目（>0 时必须先选迁移目标，否则账目会变孤儿） */
    fun accountTxnCount(accountId: Long, onResult: (Int) -> Unit) = scope.launch {
        onResult(runCatching { repo.txnCountOfAccount(accountId) }.getOrDefault(0))
    }

    /**
     * 删除（归档）账户。
     * @param migrateTo 该账户名下的账目改挂到哪个账户；有账目却传 null 会被拒绝并回一句提示
     */
    fun deleteAccount(
        account: Account,
        migrateTo: Long? = null,
        onDone: (String) -> Unit = {}
    ) = scope.launch {
        val msg = runCatching {
            val n = repo.txnCountOfAccount(account.id)
            if (n > 0 && migrateTo == null) {
                return@runCatching "这个账户下还有 $n 笔账目，先选一个账户把它们转过去"
            }
            val moved = if (n > 0 && migrateTo != null) repo.migrateAccountTxns(account.id, migrateTo) else 0
            repo.accountDao.delete(account.copy(archived = true))
            refreshBalances()
            if (moved > 0) "已删除账户，$moved 笔账目已迁移过去" else "已删除账户"
        }.getOrElse { "删除失败：${it.message}" }
        onDone(msg)
    }

    fun saveLedger(ledger: Ledger, onDone: () -> Unit = {}) = scope.launch {
        if (ledger.id == 0L) repo.ledgerDao.insert(ledger) else repo.ledgerDao.update(ledger)
        onDone()
    }

    fun selectLedger(id: Long) = scope.launch { prefs.setLedgerId(id) }

    // ---------------- 预算 / 储蓄 / 借贷 / 周期 ----------------
    fun setBudget(categoryId: Long, amountCents: Long, onDone: () -> Unit = {}) = scope.launch {
        val lid = ledgerId.value
        val existing = repo.budgetDao.all().firstOrNull { it.categoryId == categoryId && (it.ledgerId == lid || it.ledgerId == 0L) }
        if (amountCents <= 0) {
            existing?.let { repo.budgetDao.delete(it) }
        } else if (existing != null) {
            repo.budgetDao.update(existing.copy(amountCents = amountCents, ledgerId = lid))
        } else {
            repo.budgetDao.insert(Budget(ledgerId = lid, categoryId = categoryId, amountCents = amountCents))
        }
        onDone()
    }

    fun deleteBudget(budget: Budget, onDone: () -> Unit = {}) = scope.launch {
        repo.budgetDao.delete(budget)
        onDone()
    }

    fun saveSaving(goal: SavingGoal, onDone: () -> Unit = {}) = scope.launch {
        if (goal.id == 0L) repo.savingDao.insert(goal) else repo.savingDao.update(goal)
        onDone()
    }

    fun deleteSaving(goal: SavingGoal, onDone: () -> Unit = {}) = scope.launch {
        repo.savingDao.delete(goal)
        onDone()
    }

    fun addToSaving(goal: SavingGoal, deltaCents: Long, onDone: () -> Unit = {}) = scope.launch {
        val saved = (goal.savedCents + deltaCents).coerceAtLeast(0)
        repo.savingDao.update(
            goal.copy(savedCents = saved, done = saved >= goal.targetCents && goal.targetCents > 0)
        )
        onDone()
    }

    fun saveDebt(debt: Debt, onDone: () -> Unit = {}) = scope.launch {
        if (debt.id == 0L) repo.debtDao.insert(debt) else repo.debtDao.update(debt)
        onDone()
    }

    fun settleDebt(debt: Debt, onDone: () -> Unit = {}) = scope.launch {
        repo.debtDao.update(debt.copy(settled = true, settledCents = debt.amountCents))
        onDone()
    }

    fun deleteDebt(debt: Debt, onDone: () -> Unit = {}) = scope.launch {
        repo.debtDao.delete(debt)
        onDone()
    }

    fun saveRecurring(rule: Recurring, onDone: () -> Unit = {}) = scope.launch {
        if (rule.id == 0L) repo.recurringDao.insert(rule) else repo.recurringDao.update(rule)
        onDone()
    }

    fun toggleRecurring(rule: Recurring) = scope.launch {
        repo.recurringDao.update(rule.copy(enabled = !rule.enabled))
    }

    fun deleteRecurring(rule: Recurring, onDone: () -> Unit = {}) = scope.launch {
        repo.recurringDao.delete(rule)
        onDone()
    }

    fun runRecurringNow(onResult: (String) -> Unit) = scope.launch {
        val n = runCatching { repo.runRecurring() }.getOrDefault(0)
        onResult(if (n > 0) "已生成 $n 笔周期账目" else "当前没有到期的周期账单")
    }

    // ---------------- 自动规则 ----------------
    fun saveRule(rule: AutoRule, onDone: () -> Unit = {}) = scope.launch {
        if (rule.id == 0L) repo.ruleDao.insert(rule) else repo.ruleDao.update(rule)
        onDone()
    }

    fun deleteRule(rule: AutoRule, onDone: () -> Unit = {}) = scope.launch {
        repo.ruleDao.delete(rule)
        onDone()
    }

    // ---------------- 抓取待确认 ----------------
    fun confirmCapture(capture: Capture, categoryId: Long, accountId: Long, onDone: (String) -> Unit = {}) =
        scope.launch {
            val r = runCatching {
                com.jingyuniang.ledger.capture.CapturePipeline.bookNow(capture, categoryId, accountId)
            }
            onDone(if (r.isSuccess) "已入账 ${Fmt.money(capture.amountCents)}" else "入账失败")
        }

    fun ignoreCapture(capture: Capture, onDone: () -> Unit = {}) = scope.launch {
        runCatching { com.jingyuniang.ledger.capture.CapturePipeline.ignore(capture.id) }
        onDone()
    }

    /**
     * 退款冲正（P1-5）：把「疑似为 #originTxnId 退款」的这条当成冲销 —— 删掉原支出，本条不记收入。
     * 效果：本月支出减少，而不是收入增加。
     */
    fun refundOffCapture(capture: com.jingyuniang.ledger.data.Capture, originTxnId: Long, onDone: (String) -> Unit) =
        scope.launch {
            val msg = runCatching {
                if (originTxnId > 0) repo.txnDao.deleteById(originTxnId)
                repo.captureDao.update(
                    capture.copy(
                        status = com.jingyuniang.ledger.data.CaptureStatus.IGNORED,
                        reason = "已按退款冲销：删除了原支出 #$originTxnId，本条不计收入"
                    )
                )
                com.jingyuniang.ledger.capture.CaptureNotifier.cancel(capture)
                "已冲销原支出 #$originTxnId，这笔退款没有记成收入"
            }.getOrElse { "冲销失败：${it.message}" }
            onDone(msg)
        }

    /** 加入抓取黑名单（`pkg|keyword`，见 CaptureBlocklist） */
    fun blockCaptureSource(entry: String) = scope.launch {
        AppLog.d("加入抓取黑名单：$entry")
        runCatching { prefs.addCaptureBlock(entry) }
    }

    fun unblockCaptureSource(entry: String) = scope.launch {
        runCatching { prefs.removeCaptureBlock(entry) }
    }

    fun clearCaptureBlocks() = scope.launch { runCatching { prefs.clearCaptureBlocks() } }

    /** 一键忽略所有待确认（不动已入账的账目） */
    fun ignoreAllPendingCaptures(onDone: () -> Unit = {}) = scope.launch {
        val n = runCatching { repo.ignoreAllPendingCaptures() }.getOrDefault(0)
        AppLog.d("一键忽略待确认：$n 条")
        onDone()
    }

    fun undoCapture(capture: Capture) = scope.launch {
        runCatching { com.jingyuniang.ledger.capture.CapturePipeline.undo(capture.id) }
    }

    // ---------------- 权限 ----------------
    fun setCaptureNotify(v: Boolean) = scope.launch { prefs.setCaptureNotify(v) }
    fun setCaptureSms(v: Boolean) = scope.launch { prefs.setCaptureSms(v) }
    fun setCaptureAccess(v: Boolean) = scope.launch { prefs.setCaptureAccess(v) }
    fun setAutoBook(v: Boolean) = scope.launch { prefs.setAutoBook(v) }
    fun setThreshold(v: Int) = scope.launch { prefs.setThreshold(v) }
    fun setFloatingBall(v: Boolean) = scope.launch { prefs.setFloatingBall(v) }
    fun setCaptureMaster(v: Boolean) = scope.launch { prefs.setCaptureMaster(v) }
    fun setLockPin(v: String) = scope.launch { prefs.setLockPin(v) }
    fun setQuickNotify(v: Boolean) = scope.launch {
        prefs.setQuickNotify(v)
        val app = getApplication<Application>()
        if (v) com.jingyuniang.ledger.capture.QuickEntryNotifier.show(app)
        else com.jingyuniang.ledger.capture.QuickEntryNotifier.hide(app)
    }
    fun setDarkMode(v: Int) = scope.launch { prefs.setDarkMode(v) }
    fun setAiEnabled(v: Boolean) = scope.launch { prefs.setAiEnabled(v) }
    fun setApiKey(v: String) = scope.launch { prefs.setApiKey(v) }
    fun setModel(v: String) = scope.launch { prefs.setModel(v) }
    fun saveAndTestAi(baseUrl: String, apiKey: String, model: String, onResult: (String) -> Unit) = scope.launch {
        var saved = false
        val result = runCatching {
            val url = AiEndpoint.normalizeBaseUrl(baseUrl)
            val key = apiKey.trim()
            val modelName = model.trim()
            require(modelName.isNotEmpty()) { "请填写模型名称" }
            require(key.all { it.code in 33..126 }) { "API Key 不能包含空格、换行或中文字符" }
            prefs.setAiConfig(url, key, modelName)
            saved = true
            if (key.isBlank()) return@runCatching "已保存，未填写 API Key，使用本地规则"
            // 直接使用本次保存的配置，不依赖异步更新的全局设置，也不触发记账。
            val response = DeepSeekClient(key, modelName, url).chatResult(
                "你是连接测试助手。只输出 JSON 对象，字段 amount 为数字，单位为元。",
                "测试十二元",
                jsonMode = true
            ).getOrThrow()
            check(parseJsonObject(response)?.optDouble("amount") == 12.0) {
                "已连接，但模型返回的记账 JSON 不符合预期"
            }
            "已保存，连通成功 ✅ 模型：$modelName"
        }
        result.fold(onSuccess = onResult, onFailure = {
            if (it is CancellationException) throw it
            val message = when (it) {
                is java.net.UnknownHostException -> "无法解析接口地址，请检查网络或公司内网连接"
                is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
                is java.io.IOException -> "网络连接失败，请检查网络或公司内网连接"
                is IllegalArgumentException, is IllegalStateException -> it.message ?: "请检查接口配置"
                else -> "响应解析失败，请检查接口地址和模型名称"
            }
            onResult(if (saved) "已保存，测试未通过：$message" else "未保存：$message")
        })
    }
    fun setApiBase(v: String) = scope.launch { prefs.setApiBase(v) }
    fun setReminderEnabled(v: Boolean) = scope.launch { prefs.setReminderEnabled(v) }
    fun setBudgetAlert(v: Boolean) = scope.launch { prefs.setBudgetAlert(v) }
    fun setOnboardingDone() = scope.launch { prefs.setOnboardingDone(true) }

    fun refreshBalances() = scope.launch {
        runCatching { _balances.value = repo.accountBalanceMap() }
    }

    /** 当前账本 id：设置里没读到就退回第一个账本，绝不写死成 1（否则新账本里看不到这笔） */
    private fun resolveLedgerId(): Long =
        ledgerId.value.takeIf { it > 0 }
            ?: ledgers.value.firstOrNull()?.id
            ?: 1L

    // ---------------- 更新余额：截图识别（第 2 刀） ----------------
    /**
     * 读一张余额截图 → 抽出余额（分）+ 猜它属于哪种账户。
     * 识别到的数只用来**预填**输入框，最终仍由用户点「校准」。
     * 回调：(命中结果, 失败时的提示, 账户类型线索)
     */
    fun ocrBalance(
        uri: Uri,
        onResult: (com.jingyuniang.ledger.ocr.BalanceParser.Hit?, String?, com.jingyuniang.ledger.data.AccountType?) -> Unit
    ) = scope.launch {
        val r = runCatching { OcrHelper.recognize(getApplication(), uri) }.getOrNull()
        if (r == null) {
            AppLog.w("BALANCE ocr 识别失败 uri=$uri")
            onResult(null, "这张图没读出来，换一张试试", null)
            return@launch
        }
        val hit = com.jingyuniang.ledger.ocr.BalanceParser.parse(r.text)
        val typeHint = com.jingyuniang.ledger.ocr.BalanceParser.guessAccountType(r.text)
        AppLog.d(
            "BALANCE ocr hit=${hit?.cents} label=${hit?.label} accType=$typeHint " +
                "raw=${r.text.replace('\n', '|').take(160)}"
        )
        onResult(
            hit,
            when {
                hit == null -> "没在截图里找到余额，可以手动输入"
                hit.cents <= 0 -> "读到的余额是 0，确认一下再输入"
                else -> null
            },
            typeHint
        )
    }

    // ---------------- 更新余额：手动输入（第 1 刀） ----------------
    /**
     * 把某个账户的余额一次性校准到 [targetCents]。
     * 不直接改期初余额，而是补一笔「余额调整」流水：可追溯、可删除回退。
     */
    fun applyBalanceInput(
        acc: Account,
        targetCents: Long,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) = scope.launch {
        val current = runCatching { repo.accountBalance(acc) }.getOrDefault(0L)
        val delta = targetCents - current
        if (delta == 0L) {
            onResult(false, "余额没变化，还是 ${Fmt.money(current)}")
            return@launch
        }
        val type = if (delta > 0) TxnType.INCOME else TxnType.EXPENSE
        val catId = runCatching { repo.ensureCategory("余额调整", type, "⚖️") }.getOrDefault(0L)
        val txn = Txn(
            ledgerId = resolveLedgerId(),
            type = type,
            amountCents = kotlin.math.abs(delta),
            categoryId = catId,
            accountId = acc.id,
            date = System.currentTimeMillis(),
            note = "余额调整 → ${Fmt.money(targetCents)}",
            merchant = "",
            source = TxnSource.ADJUST,
            excludeBudget = true
        )
        val id = runCatching { repo.addTxn(txn) }.getOrElse {
            AppLog.w("余额调整写库失败: ${it.message}", it)
            onResult(false, "写入失败：${it.message}")
            return@launch
        }
        refreshBalances()
        AppLog.d(
            "BALANCE adjust acc=${acc.name}#${acc.id} ${Fmt.amount(current)} → ${Fmt.amount(targetCents)} " +
                "delta=${Fmt.amount(delta)} txn=$id"
        )
        onResult(true, "已校准到 ${Fmt.money(targetCents)}")
    }

    // ---------------- 数据 IO ----------------
    fun importBill(uri: Uri, onResult: (String) -> Unit) = scope.launch {
        val r = runCatching { BillImporter.import(getApplication(), uri, ledgerId.value) }
        onResult(r.getOrNull()?.message ?: "导入失败：${r.exceptionOrNull()?.message}")
        refreshBalances()
    }

    fun exportCsv(onResult: (BackupManager.Result) -> Unit) = scope.launch {
        onResult(BackupManager.exportCsv(getApplication(), ledgerId.value))
    }

    fun backup(password: String, onResult: (BackupManager.Result) -> Unit) = scope.launch {
        onResult(BackupManager.export(getApplication(), password))
    }

    fun restore(uri: Uri, password: String, onResult: (BackupManager.Result) -> Unit) = scope.launch {
        val r = BackupManager.restore(getApplication(), uri, password)
        if (r.ok) {
            runCatching {
                repo.ensureSeeded()
                repo.ledgerDao.all().firstOrNull()?.let { prefs.setLedgerId(it.id) }
                refreshBalances()
            }
        }
        onResult(r)
    }

    fun backupFile(): File? = runCatching {
        File(getApplication<Application>().filesDir, "backups").listFiles()?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    fun exportFile(): File? = runCatching {
        File(getApplication<Application>().filesDir, "exports").listFiles()?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    // ---------------- AI / OCR / 语音 ----------------
    fun aiParse(text: String, onResult: (AiProposal?) -> Unit, onError: (String) -> Unit) = scope.launch {
        if (!AiService.available()) {
            // 本地兜底：解析「午饭 25 微信」
            val local = localParse(text)
            if (local != null) onResult(local) else onError("没听懂，试试「午饭 25」这种说法")
            return@launch
        }
        val r = runCatching { AiService.parseNaturalLanguage(text, categories.value, accounts.value) }
        val p = r.getOrNull()
        if (p != null) onResult(p) else {
            val local = localParse(text)
            if (local != null) onResult(local) else onError("AI 没能解析，换个说法试试")
        }
    }

    private fun localParse(text: String): AiProposal? {
        val cents = com.jingyuniang.ledger.capture.ParseEngine.extractAmount(text)
        if (cents <= 0) return null
        val type = com.jingyuniang.ledger.capture.ParseEngine.detectDirection(text)
        val cat = com.jingyuniang.ledger.capture.ParseEngine.matchCategory(
            text, "", type, categories.value
        )
        val acc = com.jingyuniang.ledger.capture.ParseEngine.matchAccount(null, text, accounts.value)
        return AiProposal(
            amountCents = cents,
            type = type,
            categoryId = cat,
            accountId = acc?.id ?: 0L,
            note = text.take(30),
            merchant = com.jingyuniang.ledger.capture.ParseEngine.extractMerchant(text, ""),
            date = System.currentTimeMillis(),
            reason = "本地规则"
        )
    }

    fun aiReview(onResult: (String) -> Unit) = scope.launch {
        if (!AiService.available()) {
            onResult("")
            return@launch
        }
        val s = stats.value
        val topCats = s.catSums.take(5).joinToString("、") { cs ->
            val name = categories.value.firstOrNull { it.id == cs.categoryId }?.name ?: "其他"
            "$name ${Fmt.money(cs.cents)}"
        }
        val summary = buildString {
            append("本月支出 ${Fmt.money(s.expense)}，收入 ${Fmt.money(s.income)}，")
            append("账目 ${s.expenseCount + s.incomeCount} 笔，日均 ${Fmt.money(s.avgPerDay)}。")
            append("前五分类：$topCats。")
            append("最大一笔：")
            s.maxExpense?.let { append("${Fmt.money(it.amountCents)}${if (it.note.isNotBlank()) "（${it.note.take(12)}）" else ""}") }
            val trend = trend.value
            if (trend.size >= 2) {
                val last = trend[trend.size - 1].expense
                val prev = trend[trend.size - 2].expense
                if (prev > 0) append("。环比上月 ${((last - prev) * 100.0 / prev).toInt()}%")
            }
        }
        onResult(runCatching { AiService.monthlyReview(summary) }.getOrNull() ?: "")
    }

    fun ocr(uri: Uri, onResult: (String) -> Unit, onLoaded: (OcrHelper.OcrResult?) -> Unit) = scope.launch {
        val r = runCatching { OcrHelper.recognize(getApplication(), uri) }.getOrNull()
        onLoaded(r)
        if (r == null) onResult("识别失败，换张图试试")
    }

    fun search(keyword: String, onResult: (List<Txn>) -> Unit) = scope.launch {
        onResult(runCatching { repo.searchTxns(keyword, ledgerId.value) }.getOrDefault(emptyList()))
    }

    /**
     * 抓取自测：把一条示例通知喂进真实流水线，用户能立刻确认权限/解析是否正常。
     * sample: 0=微信支付通知 1=银行扣款短信 2=支付宝通知
     */
    fun runCaptureSelfTest(sample: Int, onResult: (String) -> Unit) = scope.launch {
        val (source, pkg, app, title, text) = when (sample) {
            1 -> Quad(
                com.jingyuniang.ledger.data.TxnSource.SMS, null, "招商银行", "招商银行",
                "您账户尾号8888于${Dates.md(System.currentTimeMillis())}12:30消费人民币48.00元，余额1,234.56元。"
            )
            2 -> Quad(
                com.jingyuniang.ledger.data.TxnSource.NOTIFY, "com.eg.android.AlipayGphone", "支付宝", "支付宝",
                "你已成功付款 12.50 元，商户：美团外卖"
            )
            else -> Quad(
                com.jingyuniang.ledger.data.TxnSource.NOTIFY, "com.tencent.mm", "微信", "微信支付",
                "支付成功，已支付 ¥25.00 元，收款方：星巴克咖啡"
            )
        }
        val id = runCatching {
            com.jingyuniang.ledger.capture.CapturePipeline.process(source, pkg, app, title, text)
        }.getOrNull()
        if (id == null) {
            onResult("⚠️ 没识别出来：可能被判为垃圾/重复，或者金额解析失败")
            return@launch
        }
        val c = runCatching { repo.captureDao.recentSince(0).firstOrNull { it.id == id } }.getOrNull()
        onResult(
            when (c?.status) {
                com.jingyuniang.ledger.data.CaptureStatus.AUTO -> "✅ 已自动入账 ${Fmt.money(c.amountCents)}（置信度 ${c.confidence}%）"
                com.jingyuniang.ledger.data.CaptureStatus.CONFIRMED -> "✅ 已确认入账 ${Fmt.money(c.amountCents)}"
                else -> "⏳ 已进「待确认」${Fmt.money(c?.amountCents ?: 0)}（置信度 ${c?.confidence ?: 0}%），可以手动改分类"
            }
        )
    }

    private data class Quad(val source: com.jingyuniang.ledger.data.TxnSource, val pkg: String?, val app: String, val title: String, val text: String)

    fun allTags(onResult: (List<String>) -> Unit) = scope.launch {
        onResult(runCatching { repo.allTags(ledgerId.value) }.getOrDefault(emptyList()))
    }

    // ================= 金库管家：对话式问账 =================
    private val _chat = mutableStateListOf<ChatMsg>()
    val chat: List<ChatMsg> get() = _chat

    fun clearChat() = _chat.clear()

    /** 供界面直接追加一条气泡（AI 执行动作后的回执） */
    fun say(role: String, text: String) { _chat.add(ChatMsg(role, text)) }

    private fun pushMsg(role: String, text: String) {
        _chat.add(ChatMsg(role, text, System.currentTimeMillis()))
    }

    /** 组装给模型的账本摘要（控制长度，够用为准） */
    private suspend fun buildLedgerContext(): String {
        val lid = ledgerId.value
        val now = System.currentTimeMillis()
        val thisMonthStart = Dates.startOfMonth(now)
        val lastMonthStart = Dates.addMonths(thisMonthStart, -1)
        val thisMonth = repo.totals(lid, thisMonthStart, Dates.startOfNextMonth(now))
        val lastMonth = repo.totals(lid, lastMonthStart, thisMonthStart)
        val cats = repo.categoryDao.all().associateBy { it.id }
        val accs = repo.accountDao.all().associateBy { it.id }
        val catSums = repo.categorySums(lid, thisMonthStart, Dates.startOfNextMonth(now), TxnType.EXPENSE)
        val budgets = repo.budgetProgress(lid, thisMonthStart)
        val recent = repo.txnDao.listRange(lid, lastMonthStart, Dates.startOfNextMonth(now))
            .sortedByDescending { it.date }.take(12)
        val balances = repo.accountBalanceMap()

        return buildString {
            appendLine("今天：${Dates.ymd(now)}；账本：${currentLedgerName.value}")
            appendLine("本月(${Dates.month(thisMonthStart)})：支出 ¥${Fmt.amount(thisMonth.expense)}，收入 ¥${Fmt.amount(thisMonth.income)}")
            appendLine("上月(${Dates.month(lastMonthStart)})：支出 ¥${Fmt.amount(lastMonth.expense)}，收入 ¥${Fmt.amount(lastMonth.income)}")
            if (catSums.isNotEmpty()) {
                append("本月支出分类：")
                appendLine(
                    catSums.take(6).joinToString("；") { cs ->
                        "${cats[cs.categoryId]?.name ?: "其他"} ¥${Fmt.amount(cs.cents)}(${cs.count}笔)"
                    }
                )
            }
            budgets.forEach { bp ->
                val name = if (bp.budget.categoryId == 0L) "总预算" else (cats[bp.budget.categoryId]?.name ?: "分类")
                appendLine("预算[$name]：¥${Fmt.amount(bp.budget.amountCents)}，已用 ¥${Fmt.amount(bp.spentCents)}${if (bp.over) "（已超支）" else ""}")
            }
            if (balances.isNotEmpty()) {
                append("账户余额：")
                appendLine(
                    accs.values.joinToString("；") { a -> "${a.name} ¥${Fmt.amount(balances[a.id] ?: 0)}" }
                )
            }
            if (recent.isNotEmpty()) {
                appendLine("最近账目：")
                recent.forEach { t ->
                    val sign = if (t.type == TxnType.INCOME) "+" else "-"
                    val cat = cats[t.categoryId]?.name ?: "未分类"
                    val acc = accs[t.accountId]?.name ?: ""
                    appendLine("${Dates.md(t.date)} $cat $sign¥${Fmt.amount(t.amountCents)} ${t.note.ifBlank { t.merchant }}${if (acc.isNotBlank()) "($acc)" else ""}")
                }
            }
        }
    }

    /** 没有 API Key 时的本地兜底回答，保证对话页永远可用 */
    private suspend fun localAnswer(q: String): String {
        val lid = ledgerId.value
        val now = System.currentTimeMillis()
        val start = Dates.startOfMonth(now)
        val end = Dates.startOfNextMonth(now)
        val t = repo.totals(lid, start, end)
        val cats = repo.categoryDao.all().associateBy { it.id }
        val sums = repo.categorySums(lid, start, end, TxnType.EXPENSE)
        val top = sums.firstOrNull()
        val topName = top?.let { cats[it.categoryId]?.name ?: "其他" } ?: "-"
        val last = repo.totals(lid, Dates.addMonths(start, -1), start)
        return when {
            q.contains("预算") -> {
                val bp = repo.budgetProgress(lid, start)
                val total = bp.firstOrNull { it.budget.categoryId == 0L }
                if (total == null) "本月还没设总预算哦，去「计划」里设一个，我帮你盯着。"
                else "总预算 ¥${Fmt.amount(total.budget.amountCents)}，已花 ¥${Fmt.amount(total.spentCents)}，" +
                    if (total.over) "已经超支 ¥${Fmt.amount(-total.left)}，要不要调一下分类预算？" else "还剩 ¥${Fmt.amount(total.left)}。"
            }
            q.contains("上月") || q.contains("对比") || q.contains("环比") -> {
                val diff = t.expense - last.expense
                val pct = if (last.expense > 0) (diff * 100.0 / last.expense).toInt() else 0
                "本月支出 ¥${Fmt.amount(t.expense)}，上月 ¥${Fmt.amount(last.expense)}，" +
                    (if (diff >= 0) "多了 ¥${Fmt.amount(diff)}（+$pct%）" else "少了 ¥${Fmt.amount(-diff)}（$pct%）")
            }
            q.contains("收入") || q.contains("赚") ->
                "本月收入 ¥${Fmt.amount(t.income)}，支出 ¥${Fmt.amount(t.expense)}，结余 ¥${Fmt.amount(t.income - t.expense)}。"
            else -> "本月支出 ¥${Fmt.amount(t.expense)}，共 ${sums.sumOf { it.count }} 笔，花得最多的是「$topName」¥${Fmt.amount(top?.cents ?: 0)}。" +
                if (last.expense > 0) "比上月${if (t.expense > last.expense) "多" else "少"} ¥${Fmt.amount(kotlin.math.abs(t.expense - last.expense))}。" else ""
        } + "（本地规则回答，填 API Key 可获得更细的分析）"
    }

    /** 提问：有 Key 走 DeepSeek，没 Key 走本地规则 */
    fun askVault(question: String, onResult: (String) -> Unit) = scope.launch {
        val q = question.trim()
        if (q.isEmpty()) return@launch
        pushMsg("user", q)
        val answer = runCatching {
            if (AiService.available()) {
                val ctx = buildLedgerContext()
                val history = _chat.takeLast(7).filter { it.role != "typing" }
                    .map { it.role to it.text }
                    .chunked(2).filter { it.size == 2 }
                    .map { (u, a) -> u.second to a.second }
                AiService.askVault(q, ctx, history)
            } else null
        }.getOrNull()
        val final = answer?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { localAnswer(q) }.getOrDefault("账本里没有这项记录哦。")
        pushMsg("assistant", final)
        onResult(final)
    }

    // ================= 首页 AI 气泡：一句话直接出账 =================
    /**
     * 一句话记账。
     * [seed] 是「AI 中枢已经判定过」的结构化结果：给了它就不再发第二次解析请求，
     * 一次模型调用完成「判工具 + 出金额」，也避免两次解析结果打架。
     */
    fun quickAiBook(
        text: String,
        seed: AiProposal? = null,
        onResult: (Boolean, String, Long) -> Unit
    ) = scope.launch {
        val input = text.trim()
        if (input.isEmpty()) {
            onResult(false, "先说一句吧，比如「午饭 25 微信」", 0L)
            return@launch
        }
        var source = "local"
        val proposal = seed?.takeIf { it.amountCents > 0 }?.also { source = "agent" }
            ?: runCatching {
                if (AiService.available()) AiService.parseNaturalLanguage(input, categories.value, accounts.value)
                else null
            }.getOrNull()?.also { source = "ai" }
            ?: localParse(input)?.also { source = "local" }
        if (proposal == null || proposal.amountCents <= 0) {
            onResult(false, "没解析出金额，试试「午饭 25」这种说法", 0L)
            return@launch
        }
        val acc = proposal.accountId.takeIf { it > 0 }
            ?: accounts.value.firstOrNull { it.isDefault }?.id
            ?: accounts.value.firstOrNull()?.id ?: 0L
        // 模型没给出分类时，用本地关键词补一个（最差也有「其他」，不留「未分类」）
        val cat = proposal.categoryId.takeIf { it > 0 }
            ?: runCatching {
                com.jingyuniang.ledger.capture.ParseEngine.matchCategory(
                    input, proposal.merchant, proposal.type, categories.value
                )
            }.getOrDefault(0L)
        val txn = Txn(
            ledgerId = resolveLedgerId(),
            type = proposal.type,
            amountCents = proposal.amountCents,
            categoryId = cat,
            accountId = acc,
            date = if (proposal.date > 0) proposal.date else System.currentTimeMillis(),
            note = proposal.note.ifBlank { input.take(30) },
            merchant = proposal.merchant,
            source = com.jingyuniang.ledger.data.TxnSource.AI
        )
        AppLog.d(
            "AI book source=$source amount=${txn.amountCents} type=${txn.type} cat=$cat acc=$acc " +
                "ledger=${txn.ledgerId} note=${txn.note}"
        )
        val id = runCatching { repo.addTxn(txn) }.getOrElse {
            AppLog.w("AI book 写库失败: ${it.message}", it)
            onResult(false, "保存失败：${it.message}", 0L)
            return@launch
        }
        AppLog.d("AI book ok txnId=$id")
        refreshBalances()
        val catName = categories.value.firstOrNull { it.id == cat }?.name ?: "未分类"
        val accName = accounts.value.firstOrNull { it.id == acc }?.name ?: ""
        onResult(
            true,
            "已记一笔 ${Fmt.money(proposal.amountCents)} · $catName${if (accName.isNotBlank()) " · $accName" else ""}",
            id
        )
    }

    /** 用户反馈一条通知 → 生成可启用的 AutoRule（有 Key 用 AI，无 Key 用本地规则兜底） */
    /** 把 AI 中枢的判定转成一次性的结构化提案（不再回头请求第二次解析） */
    private fun seedFromDecision(d: com.jingyuniang.ledger.ai.AgentDecision, text: String): AiProposal {
        val cents = if (d.amountYuan > 0) kotlin.math.round(d.amountYuan * 100).toLong()
        else com.jingyuniang.ledger.capture.ParseEngine.extractAmount(text)
        val type = when {
            d.type.equals("INCOME", true) -> TxnType.INCOME
            d.type.equals("EXPENSE", true) -> TxnType.EXPENSE
            else -> com.jingyuniang.ledger.capture.ParseEngine.detectDirection(text)
        }
        return AiProposal(
            amountCents = cents,
            type = type,
            categoryId = categories.value.firstOrNull { it.name == d.category }?.id ?: 0L,
            accountId = accounts.value.firstOrNull { it.name == d.account }?.id ?: 0L,
            note = d.note.ifBlank { text.take(30) },
            date = System.currentTimeMillis(),
            reason = "AI 中枢"
        )
    }

    /** AI 中枢：由模型自己决定「记账 / 查账 / 闲聊」 */
    fun aiAgent(text: String, onDone: () -> Unit = {}) = scope.launch {
        val cats = categories.value.joinToString("；") { it.name }
        val accs = accounts.value.joinToString("；") { it.name }
        val d = runCatching {
            if (AiService.available()) com.jingyuniang.ledger.ai.AiAgent.decide(text, cats, accs) else null
        }.getOrNull()
        android.util.Log.d("WhaleMoney", "AGENT tool=${d?.tool} text=$text")
        // 模型没给出有效判定时，用最朴素的规则兜底：有金额且不是在提问 → 记账
        if (d == null) {
            val hasAmount = Regex("[0-9零一二两三四五六七八九十百千万]+").containsMatchIn(text)
            val ask = Regex("是否|有没有|是不是|超没超|多少|几笔|吗|呢|查询|统计|看看|余额|占比|对比|分析").containsMatchIn(text)
            if (hasAmount && !ask) {
                say("user", text)
                quickAiBook(text) { ok, msg, _ ->
                    say("assistant", if (ok) "记好啦 ✅\n$msg" else msg)
                    onDone()
                }
                return@launch
            }
        }
        when (d?.tool) {
            "addTxn" -> {
                say("user", text)
                val seed = d?.let { seedFromDecision(it, text) }
                quickAiBook(text, seed) { ok, msg, _ ->
                    say("assistant", if (ok) "记好啦 ✅\n$msg" else msg)
                    onDone()
                }
            }
            "none" -> {
                say("user", text)
                say("assistant", d?.reply?.ifBlank { "我在的，主人。" } ?: "我在的，主人。")
                onDone()
            }
            else -> askVault(text) { onDone() }
        }
    }

    fun aiSuggestRule(rawText: String, onResult: (AutoRule?, String) -> Unit) = scope.launch {
        val text = rawText.trim()
        if (text.length < 6) {
            onResult(null, "至少粘一条通知原文进来吧")
            return@launch
        }
        val cats = categories.value
        val accs = accounts.value
        val draft = runCatching {
            if (AiService.available()) {
                AiService.suggestRule(text, "未知", cats, accs)
            } else null
        }.getOrNull()

        val rule: AutoRule
        val explain: String
        if (draft != null) {
            rule = AutoRule(
                name = draft.name.ifBlank { "AI 规则" },
                enabled = true,
                pkg = draft.pkg,
                keyword = draft.keyword,
                regex = draft.regex,
                direction = draft.direction,
                categoryId = draft.categoryId,
                accountId = draft.accountId,
                priority = 200
            )
            explain = draft.explain.ifBlank { "AI 已根据这条原文生成规则，确认后启用。" }
        } else {
            val amount = com.jingyuniang.ledger.capture.ParseEngine.extractAmount(text)
            val dir = com.jingyuniang.ledger.capture.ParseEngine.detectDirection(text)
            val cat = com.jingyuniang.ledger.capture.ParseEngine.matchCategory(text, "", dir, cats)
            val acc = com.jingyuniang.ledger.capture.ParseEngine.matchAccount(null, text, accs)?.id ?: 0L
            val kw = text.replace(Regex("""[\d,，.:：¥￥()（）\[\]【】]+"""), " ")
                .split(" ").filter { it.length in 2..8 }.distinct().take(3).joinToString(",")
            rule = AutoRule(
                name = "自定义：" + kw.take(12),
                enabled = true,
                keyword = kw,
                regex = """(?:¥|￥|人民币|金额[:：]?)\s*([\d,]+(?:\.\d{1,2})?)""",
                direction = dir,
                categoryId = cat,
                accountId = acc,
                priority = 200
            )
            explain = if (amount > 0) {
                "本地识别：金额 ¥${Fmt.amount(amount)}，方向${if (dir == TxnType.INCOME) "收入" else "支出"}。" +
                    "已用关键词「${kw}」+ 金额正则生成规则（未配置 API Key，用本地规则生成）。"
            } else {
                "这条原文里没识别出金额，规则仍然可以按关键词强制归类，确认后启用。"
            }
        }
        onResult(rule, explain)
    }

    fun undoQuickBook(txnId: Long) = scope.launch {
        runCatching { repo.txnDao.deleteById(txnId) }
    }
}
