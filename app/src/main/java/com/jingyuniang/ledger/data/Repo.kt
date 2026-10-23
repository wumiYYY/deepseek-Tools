package com.jingyuniang.ledger.data

import com.jingyuniang.ledger.util.Dates
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Totals(val expense: Long, val income: Long) {
    val net: Long get() = income - expense
}

data class CategorySum(val categoryId: Long, val cents: Long, val count: Int)

data class DaySum(val dayStart: Long, val cents: Long)

data class MonthSum(val monthStart: Long, val expense: Long, val income: Long)

/** 应用唯一数据入口：所有 UI / 服务都通过它读写数据库 */
class Repo(val db: AppDatabase) {

    val ledgerDao get() = db.ledgerDao()
    val accountDao get() = db.accountDao()
    val categoryDao get() = db.categoryDao()
    val txnDao get() = db.txnDao()
    val budgetDao get() = db.budgetDao()
    val recurringDao get() = db.recurringDao()
    val savingDao get() = db.savingGoalDao()
    val debtDao get() = db.debtDao()
    val ruleDao get() = db.autoRuleDao()
    val captureDao get() = db.captureDao()
    val merchantDao get() = db.merchantRuleDao()

    // ---------------- 基础流 ----------------
    val ledgers: Flow<List<Ledger>> get() = db.ledgerDao().observeAll()
    val accounts: Flow<List<Account>> get() = db.accountDao().observeAll()
    val categories: Flow<List<Category>> get() = db.categoryDao().observeAll()
    val budgets: Flow<List<Budget>> get() = db.budgetDao().observeAll()
    val recurring: Flow<List<Recurring>> get() = db.recurringDao().observeAll()
    val savingGoals: Flow<List<SavingGoal>> get() = db.savingGoalDao().observeAll()
    val debts: Flow<List<Debt>> get() = db.debtDao().observeAll()
    val autoRules: Flow<List<AutoRule>> get() = db.autoRuleDao().observeAll()
    val captures: Flow<List<Capture>> get() = db.captureDao().observeRecent()
    val pendingCaptures: Flow<List<Capture>> get() = db.captureDao().observePending()
    val pendingCount: Flow<Int> get() = db.captureDao().observePendingCount()

    fun txns(ledgerId: Long, from: Long, to: Long): Flow<List<Txn>> =
        db.txnDao().observeRange(ledgerId, from, to)

    // ---------------- 初始化 ----------------
    suspend fun ensureSeeded() {
        if (db.ledgerDao().count() == 0) {
            db.ledgerDao().insert(Ledger(name = "日常账本", emoji = "🐳", sortOrder = 0))
        }
        if (db.accountDao().count() == 0) {
            SeedData.accounts.forEachIndexed { i, a ->
                db.accountDao().insert(
                    Account(
                        name = a.name, emoji = a.emoji, type = a.type, color = a.color,
                        keywords = a.keywords, isDefault = a.isDefault, sortOrder = i
                    )
                )
            }
        }
        if (db.categoryDao().count() == 0) {
            var order = 0
            SeedData.expense.forEachIndexed { i, (name, emoji, kw) ->
                db.categoryDao().insert(
                    Category(
                        name = name, emoji = emoji, type = TxnType.EXPENSE,
                        color = SeedData.categoryColor(i), sortOrder = order++,
                        isSystem = true, keywords = kw
                    )
                )
            }
            SeedData.income.forEachIndexed { i, (name, emoji, kw) ->
                db.categoryDao().insert(
                    Category(
                        name = name, emoji = emoji, type = TxnType.INCOME,
                        color = SeedData.categoryColor(i + 3), sortOrder = order++,
                        isSystem = true, keywords = kw
                    )
                )
            }
        }
        if (db.autoRuleDao().count() == 0) {
            val accounts = db.accountDao().all()
            SeedData.rules.forEachIndexed { i, r ->
                val acc = accounts.firstOrNull { it.name == r.accountName }
                db.autoRuleDao().insert(
                    AutoRule(
                        name = r.name, pkg = r.pkg, keyword = r.keyword,
                        accountId = acc?.id ?: 0, direction = TxnType.EXPENSE,
                        priority = 100 - i, builtin = true
                    )
                )
            }
        }
    }

    // ---------------- 账目 ----------------
    suspend fun addTxn(txn: Txn): Long = db.txnDao().insert(txn)

    suspend fun updateTxn(txn: Txn) = db.txnDao().update(txn.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteTxn(txn: Txn) = db.txnDao().delete(txn)

    suspend fun txnById(id: Long): Txn? = db.txnDao().byId(id)

    suspend fun listRange(ledgerId: Long, from: Long, to: Long): List<Txn> =
        db.txnDao().listRange(ledgerId, from, to)

    suspend fun allTxns(): List<Txn> = db.txnDao().all()

    suspend fun totals(ledgerId: Long, from: Long, to: Long): Totals = Totals(
        expense = db.txnDao().sumRange(ledgerId, TxnType.EXPENSE, from, to),
        income = db.txnDao().sumRange(ledgerId, TxnType.INCOME, from, to)
    )

    suspend fun countRange(from: Long, to: Long): Int = db.txnDao().countRange(from, to)

    suspend fun accountBalance(account: Account): Long =
        account.initialBalanceCents + db.txnDao().balanceDelta(account.id)

    suspend fun totalAssets(): Long {
        var sum = 0L
        db.accountDao().all().forEach { acc ->
            val bal = accountBalance(acc)
            sum += if (acc.type == AccountType.CREDIT) bal else bal
        }
        return sum
    }

    // ---------------- 统计聚合 ----------------
    /** 统计口径：余额调整只是校准账户余额，不算收支，别让它污染图表和 AI 摘要 */
    private fun List<Txn>.statsOnly(): List<Txn> = filter { it.source != TxnSource.ADJUST }

    suspend fun categorySums(
        ledgerId: Long, from: Long, to: Long, type: TxnType
    ): List<CategorySum> {
        val list = db.txnDao().listRange(ledgerId, from, to).statsOnly().filter { it.type == type }
        val map = LinkedHashMap<Long, Long>()
        val cnt = LinkedHashMap<Long, Int>()
        list.forEach {
            map[it.categoryId] = (map[it.categoryId] ?: 0L) + it.amountCents
            cnt[it.categoryId] = (cnt[it.categoryId] ?: 0) + 1
        }
        return map.entries
            .map { CategorySum(it.key, it.value, cnt[it.key] ?: 0) }
            .sortedByDescending { it.cents }
    }

    suspend fun accountSums(ledgerId: Long, from: Long, to: Long, type: TxnType): List<Pair<Long, Long>> {
        val list = db.txnDao().listRange(ledgerId, from, to).statsOnly().filter { it.type == type }
        val map = LinkedHashMap<Long, Long>()
        list.forEach { map[it.accountId] = (map[it.accountId] ?: 0L) + it.amountCents }
        return map.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    suspend fun dailySums(ledgerId: Long, from: Long, to: Long, type: TxnType): List<DaySum> {
        val list = db.txnDao().listRange(ledgerId, from, to).statsOnly().filter { it.type == type }
        val map = LinkedHashMap<Long, Long>()
        list.forEach {
            val d = Dates.startOfDay(it.date)
            map[d] = (map[d] ?: 0L) + it.amountCents
        }
        return map.entries.map { DaySum(it.key, it.value) }.sortedBy { it.dayStart }
    }

    suspend fun monthlySums(ledgerId: Long, endTs: Long, months: Int): List<MonthSum> {
        val out = ArrayList<MonthSum>(months)
        var cursor = Dates.startOfMonth(endTs)
        val list = ArrayList<Long>()
        for (i in 0 until months) {
            list.add(cursor)
            cursor = Dates.addMonths(cursor, -1)
        }
        list.reversed().forEach { m ->
            val from = m
            val to = Dates.startOfNextMonth(m)
            out.add(
                MonthSum(
                    monthStart = m,
                    expense = db.txnDao().sumRange(ledgerId, TxnType.EXPENSE, from, to),
                    income = db.txnDao().sumRange(ledgerId, TxnType.INCOME, from, to)
                )
            )
        }
        return out
    }

    suspend fun tagSums(ledgerId: Long, from: Long, to: Long): List<Triple<String, Long, Int>> {
        val list = db.txnDao().listRange(ledgerId, from, to)
        val map = LinkedHashMap<String, Long>()
        val cnt = LinkedHashMap<String, Int>()
        list.forEach { t ->
            tagList(t.tags).forEach { tag ->
                map[tag] = (map[tag] ?: 0L) + t.amountCents
                cnt[tag] = (cnt[tag] ?: 0) + 1
            }
        }
        return map.entries.map { Triple(it.key, it.value, cnt[it.key] ?: 0) }
            .sortedByDescending { it.second }
    }

    suspend fun searchTxns(keyword: String, ledgerId: Long): List<Txn> {
        if (keyword.isBlank()) return emptyList()
        val k = keyword.trim()
        return db.txnDao().all().filter { t ->
            (ledgerId <= 0 || t.ledgerId == ledgerId) &&
                (t.note.contains(k, true) || t.merchant.contains(k, true) ||
                    t.tags.contains(k, true) || t.photos.contains(k, true))
        }
    }

    suspend fun allTags(ledgerId: Long): List<String> {
        val set = LinkedHashSet<String>()
        db.txnDao().all().filter { ledgerId <= 0 || it.ledgerId == ledgerId }
            .forEach { set.addAll(tagList(it.tags)) }
        return set.toList()
    }

    fun tagList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun tagsToRaw(list: List<String>): String =
        list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    // ---------------- 账户余额快照 ----------------
    suspend fun accountBalanceMap(): Map<Long, Long> {
        val map = LinkedHashMap<Long, Long>()
        db.accountDao().all().forEach { map[it.id] = accountBalance(it) }
        return map
    }

    /** 取（或创建）一个系统分类，给「余额调整」这类内部账目用 */
    suspend fun ensureCategory(
        name: String,
        type: TxnType,
        emoji: String = "⚖️",
        color: Long = 0xFFC5A468
    ): Long {
        db.categoryDao().all().firstOrNull { it.name == name && it.type == type && !it.archived }
            ?.let { return it.id }
        return db.categoryDao().insert(
            Category(
                name = name, emoji = emoji, type = type, color = color,
                sortOrder = 90, isSystem = true, keywords = name
            )
        )
    }

    // ---------------- 预算 ----------------
    suspend fun budgetProgress(ledgerId: Long, monthTs: Long): List<BudgetProgress> {
        val from = Dates.startOfMonth(monthTs)
        val to = Dates.startOfNextMonth(monthTs)
        val budgets = db.budgetDao().all().filter { it.ledgerId == 0L || it.ledgerId == ledgerId }
        val sums = categorySums(ledgerId, from, to, TxnType.EXPENSE).associateBy { it.categoryId }
        val totalExpense = db.txnDao().sumRange(ledgerId, TxnType.EXPENSE, from, to)
        return budgets.map { b ->
            val spent = if (b.categoryId == 0L) totalExpense else (sums[b.categoryId]?.cents ?: 0L)
            BudgetProgress(b, spent)
        }
    }

    // ---------------- 周期账单 ----------------
    suspend fun runRecurring(now: Long = System.currentTimeMillis()): Int {
        var created = 0
        db.recurringDao().enabled().forEach { r ->
            var next = if (r.nextAt > 0) r.nextAt else firstOccurrence(r, now)
            var guard = 0
            var lastBooked = r.lastBookedAt
            while (next <= now && guard++ < 60) {
                if (r.autoBook) {
                    db.txnDao().insert(
                        Txn(
                            ledgerId = if (r.ledgerId > 0) r.ledgerId else currentLedgerFallback(),
                            type = r.type,
                            amountCents = r.amountCents,
                            categoryId = r.categoryId,
                            accountId = r.accountId,
                            date = next,
                            note = if (r.note.isBlank()) r.name else r.note,
                            merchant = r.name,
                            source = TxnSource.RECURRING
                        )
                    )
                    created++
                }
                lastBooked = next
                next = nextOccurrence(r, next)
            }
            if (next != r.nextAt || lastBooked != r.lastBookedAt) {
                db.recurringDao().update(r.copy(nextAt = next, lastBookedAt = lastBooked))
            }
        }
        return created
    }

    private fun currentLedgerFallback(): Long = 1L

    private fun firstOccurrence(r: Recurring, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (r.freq) {
            Freq.DAILY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            Freq.WEEKLY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            Freq.MONTHLY -> {
                val day = r.dayOfMonth.coerceIn(1, today.lengthOfMonth())
                val cand = today.withDayOfMonth(day)
                val date = if (cand.isAfter(today)) cand else monthlyNext(today, r.dayOfMonth)
                date.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            Freq.YEARLY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    private fun nextOccurrence(r: Recurring, from: Long): Long {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val next = when (r.freq) {
            Freq.DAILY -> d.plusDays(1)
            Freq.WEEKLY -> d.plusWeeks(1)
            Freq.MONTHLY -> monthlyNext(d, r.dayOfMonth)
            Freq.YEARLY -> d.plusYears(1)
        }
        return next.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun monthlyNext(d: LocalDate, day: Int): LocalDate {
        val first = d.plusMonths(1).withDayOfMonth(1)
        return first.withDayOfMonth(day.coerceIn(1, first.lengthOfMonth()))
    }

    // ---------------- 学习记忆 ----------------
    suspend fun learnMerchant(merchant: String, categoryId: Long, accountId: Long) {
        val key = merchant.trim()
        if (key.isEmpty() || categoryId <= 0) return
        val old = db.merchantRuleDao().byMerchant(key)
        db.merchantRuleDao().upsert(
            MerchantRule(
                merchant = key,
                categoryId = categoryId,
                accountId = if (accountId > 0) accountId else (old?.accountId ?: 0),
                hits = (old?.hits ?: 0) + 1
            )
        )
    }

    // ---------------- 数据清空 / 恢复 ----------------
    suspend fun wipeTransactions() {
        db.txnDao().clear()
        db.captureDao().clear()
    }

    suspend fun pruneCaptures() {
        db.captureDao().prune(System.currentTimeMillis() - 60L * 24 * 3600 * 1000)
    }
}

data class BudgetProgress(val budget: Budget, val spentCents: Long) {
    val ratio: Float get() = if (budget.amountCents <= 0) 0f else (spentCents.toFloat() / budget.amountCents)
    val left: Long get() = budget.amountCents - spentCents
    val over: Boolean get() = spentCents > budget.amountCents && budget.amountCents > 0
}
