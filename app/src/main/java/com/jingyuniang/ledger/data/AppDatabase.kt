package com.jingyuniang.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Ledger::class, Account::class, Category::class, Txn::class, Budget::class,
        Recurring::class, SavingGoal::class, Debt::class, AutoRule::class,
        Capture::class, MerchantRule::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun txnDao(): TxnDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun savingGoalDao(): SavingGoalDao
    abstract fun debtDao(): DebtDao
    abstract fun autoRuleDao(): AutoRuleDao
    abstract fun captureDao(): CaptureDao
    abstract fun merchantRuleDao(): MerchantRuleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * 手动迁移列表 —— **加字段 / 改表必须走这里**：
         *   1) @Database(version = N + 1)
         *   2) 在 MIGRATIONS 里加一条 Migration(N, N+1)
         *   3) schemas/ 下的导出版本一起提交，方便比对
         *
         * ⚠️ 下面的 fallbackToDestructiveMigration() 会在上面两步漏掉时**静默清空整库**：
         *    用户的账目、账户、预算全没了，App 还照常启动、不报错。
         *    真要删库重来，宁可报错让用户从「数据 → 备份恢复」回来。
         */
        private val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            // 模板：
            // object M1to2 : Migration(1, 2) {
            //     override fun migrate(db: SupportSQLiteDatabase) {
            //         db.execSQL("ALTER TABLE txn ADD COLUMN someFlag INTEGER NOT NULL DEFAULT 0")
            //     }
            // }
        )

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "whale_money.db"
            )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
