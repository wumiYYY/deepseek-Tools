package com.jingyuniang.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.jingyuniang.ledger.util.AppLog

@Database(
    entities = [
        Ledger::class, Account::class, Category::class, Txn::class, Budget::class,
        Recurring::class, SavingGoal::class, Debt::class, AutoRule::class,
        Capture::class, MerchantRule::class
    ],
    version = 2,
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
        /** 数据库版本号，必须与 @Database(version = N) 保持一致 */
        const val DB_VERSION = 2

        /**
         * v1 → v2：给抓取记录加「判定原因」，让用户能看到「这条为什么没抓／为什么只进待确认」。
         * ⚠️ 只加列、不删列，老数据自动拿到默认空串。
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2
        )

        fun get(context: Context): AppDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                // 迁移前快照：必须在 Room 打开数据库之前跑（打开即触发迁移）
                com.jingyuniang.ledger.io.SnapshotManager
                    .beforeMigration(context.applicationContext, DB_VERSION)
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whale_money.db"
                )
                    .addMigrations(*MIGRATIONS)
                    // ⚠️ 这里**故意不加** fallbackToDestructiveMigration()：
                    // 以前加了它，一旦有人加字段却忘了写迁移，Room 会静默清空整库（账目全没、App 还照常启动）。
                    // 现在宁可抛异常：数据文件原样留在磁盘上，用户回装旧版 APK 就能继续用，也能从自动快照恢复。
                    .build()
                try {
                    // 主动触发打开，让迁移在此刻发生（并且能被 catch 到）
                    db.openHelper.writableDatabase
                    AppLog.i("数据库就绪（schema v$DB_VERSION）")
                } catch (t: Throwable) {
                    AppLog.e(
                        "数据库打开/迁移失败 —— 数据文件未删除，可用「从自动快照恢复」或回装旧版",
                        t
                    )
                    throw t
                }
                instance = db
                return db
            }
        }
    }
}