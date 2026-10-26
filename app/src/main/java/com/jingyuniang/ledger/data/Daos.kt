package com.jingyuniang.ledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Ledger>>

    @Query("SELECT * FROM ledger WHERE archived = 0 ORDER BY sortOrder, id")
    suspend fun all(): List<Ledger>

    @Query("SELECT COUNT(*) FROM ledger")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: Ledger): Long

    @Update
    suspend fun update(item: Ledger)

    @Delete
    suspend fun delete(item: Ledger)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE archived = 0 ORDER BY sortOrder, id")
    suspend fun all(): List<Account>

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: Account): Long

    @Update
    suspend fun update(item: Account)

    @Delete
    suspend fun delete(item: Account)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY sortOrder, id")
    suspend fun all(): List<Category>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: Category): Long

    @Update
    suspend fun update(item: Category)

    @Delete
    suspend fun delete(item: Category)
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId AND date >= :from AND date < :to ORDER BY date DESC, id DESC")
    fun observeRange(ledgerId: Long, from: Long, to: Long): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId AND date >= :from AND date < :to ORDER BY date DESC, id DESC")
    suspend fun listRange(ledgerId: Long, from: Long, to: Long): List<Txn>

    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId ORDER BY date DESC, id DESC LIMIT :limit")
    fun observeRecent(ledgerId: Long, limit: Int): Flow<List<Txn>>

    @Query("SELECT * FROM txn ORDER BY date DESC, id DESC")
    suspend fun all(): List<Txn>

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: Long): Txn?

    @Query("SELECT * FROM txn WHERE amountCents = :amount AND date >= :since AND type != :excludeType AND source != 'ADJUST' ORDER BY date DESC LIMIT 1")
    suspend fun findSimilar(amount: Long, since: Long, excludeType: TxnType = TxnType.TRANSFER): Txn?

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM txn WHERE ledgerId = :ledgerId AND type = :type AND date >= :from AND date < :to AND excludeBudget = 0")
    suspend fun sumRange(ledgerId: Long, type: TxnType, from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM txn WHERE ledgerId = :ledgerId AND type = :type AND date >= :from AND date < :to")
    suspend fun sumRangeAll(ledgerId: Long, type: TxnType, from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM txn WHERE type = :type AND date >= :from AND date < :to")
    suspend fun sumAllLedgers(type: TxnType, from: Long, to: Long): Long

    @Query(
        """SELECT COALESCE(SUM(CASE
            WHEN type='INCOME' AND accountId=:accountId THEN amountCents
            WHEN type='EXPENSE' AND accountId=:accountId THEN -amountCents
            WHEN type='TRANSFER' AND accountId=:accountId THEN -amountCents
            WHEN type='TRANSFER' AND toAccountId=:accountId THEN toAmountCents
            ELSE 0 END),0) FROM txn"""
    )
    suspend fun balanceDelta(accountId: Long): Long

    @Query("SELECT COUNT(*) FROM txn WHERE date >= :from AND date < :to")
    suspend fun countRange(from: Long, to: Long): Int

    @Query("SELECT * FROM txn WHERE date >= :from AND date < :to")
    suspend fun listAllLedgersRange(from: Long, to: Long): List<Txn>

    @Insert
    suspend fun insert(item: Txn): Long

    @Update
    suspend fun update(item: Txn)

    @Delete
    suspend fun delete(item: Txn)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM txn")
    suspend fun clear()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget ORDER BY categoryId")
    fun observeAll(): Flow<List<Budget>>

    @Query("SELECT * FROM budget")
    suspend fun all(): List<Budget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Budget): Long

    @Update
    suspend fun update(item: Budget)

    @Delete
    suspend fun delete(item: Budget)

    @Query("DELETE FROM budget")
    suspend fun clear()
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring ORDER BY enabled DESC, id")
    fun observeAll(): Flow<List<Recurring>>

    @Query("SELECT * FROM recurring WHERE enabled = 1")
    suspend fun enabled(): List<Recurring>

    @Query("SELECT * FROM recurring")
    suspend fun all(): List<Recurring>

    @Insert
    suspend fun insert(item: Recurring): Long

    @Update
    suspend fun update(item: Recurring)

    @Delete
    suspend fun delete(item: Recurring)
}

@Dao
interface SavingGoalDao {
    @Query("SELECT * FROM saving_goal ORDER BY done, id DESC")
    fun observeAll(): Flow<List<SavingGoal>>

    @Query("SELECT * FROM saving_goal")
    suspend fun all(): List<SavingGoal>

    @Insert
    suspend fun insert(item: SavingGoal): Long

    @Update
    suspend fun update(item: SavingGoal)

    @Delete
    suspend fun delete(item: SavingGoal)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debt ORDER BY settled, date DESC")
    fun observeAll(): Flow<List<Debt>>

    @Query("SELECT * FROM debt")
    suspend fun all(): List<Debt>

    @Insert
    suspend fun insert(item: Debt): Long

    @Update
    suspend fun update(item: Debt)

    @Delete
    suspend fun delete(item: Debt)
}

@Dao
interface AutoRuleDao {
    @Query("SELECT * FROM auto_rule ORDER BY priority DESC, id")
    fun observeAll(): Flow<List<AutoRule>>

    @Query("SELECT * FROM auto_rule WHERE enabled = 1 ORDER BY priority DESC, id")
    suspend fun enabled(): List<AutoRule>

    @Query("SELECT * FROM auto_rule ORDER BY priority DESC, id")
    suspend fun all(): List<AutoRule>

    @Query("SELECT COUNT(*) FROM auto_rule")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: AutoRule): Long

    @Update
    suspend fun update(item: AutoRule)

    @Delete
    suspend fun delete(item: AutoRule)
}

@Dao
interface CaptureDao {
    @Query("SELECT * FROM capture ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<Capture>>

    @Query("SELECT * FROM capture WHERE status = 'PENDING' ORDER BY time DESC")
    fun observePending(): Flow<List<Capture>>

    @Query("SELECT COUNT(*) FROM capture WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM capture WHERE dedupeKey = :key AND time >= :since")
    suspend fun countDedupe(key: String, since: Long): Int

    @Query("SELECT * FROM capture WHERE time >= :since ORDER BY time DESC")
    suspend fun recentSince(since: Long): List<Capture>

    @Query("SELECT * FROM capture")
    suspend fun all(): List<Capture>

    @Insert
    suspend fun insert(item: Capture): Long

    @Update
    suspend fun update(item: Capture)

    @Delete
    suspend fun delete(item: Capture)

    @Query("DELETE FROM capture WHERE time < :before AND status != 'PENDING'")
    suspend fun prune(before: Long)

    /**
     * 某个渠道 App 在时间窗内是否报过同金额（用于「微信付款 + 银行扣款」同一笔的长窗合并）。
     */
    @Query(
        "SELECT COUNT(*) FROM capture WHERE pkg = :pkg AND amountCents = :amount " +
            "AND time >= :since AND status != 'IGNORED'"
    )
    suspend fun countFromPkgSince(pkg: String, amount: Long, since: Long): Int

    /**
     * 跨渠道同一笔：同金额、不同来源 App、时间窗内。
     * 微信通知和招行通知都算 NOTIFY，靠 source 区分不了，只能靠包名。
     */
    @Query(
        "SELECT COUNT(*) FROM capture WHERE amountCents = :amount AND time >= :since " +
            "AND pkg != :pkg AND status != 'IGNORED'"
    )
    suspend fun countCrossSourceSimilar(amount: Long, since: Long, pkg: String): Int

    /** 一键把当前所有「待确认」标记为已忽略（不动已入账的账目） */
    @Query("UPDATE capture SET status = 'IGNORED' WHERE status = 'PENDING'")
    suspend fun ignoreAllPending(): Int

    @Query("DELETE FROM capture")
    suspend fun clear()
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rule")
    suspend fun all(): List<MerchantRule>

    @Query("SELECT * FROM merchant_rule")
    fun observeAll(): Flow<List<MerchantRule>>

    @Query("SELECT * FROM merchant_rule WHERE merchant = :merchant")
    suspend fun byMerchant(merchant: String): MerchantRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MerchantRule)

    @Query("DELETE FROM merchant_rule")
    suspend fun clear()
}
