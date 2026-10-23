package com.jingyuniang.ledger.work

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.capture.CaptureNotifier
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import java.util.concurrent.TimeUnit

/** 后台定时任务：周期账单生成 + 记账提醒 + 预算预警 */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Services.init(applicationContext)
            val repo = Services.repo
            repo.ensureSeeded()
            repo.runRecurring()

            val now = System.currentTimeMillis()
            val from = Dates.startOfDay(now)
            val to = from + 86_400_000L
            val todayCount = repo.countRange(from, to)
            val s = Services.settings.value
            val hour = Dates.hm(now).take(2).toIntOrNull() ?: 0

            // 今日还没记账，且已过提醒时间
            if (s.reminderEnabled && todayCount == 0 && hour >= s.reminderHour) {
                CaptureNotifier.notifyReminder(7001, "🐳 今天还没记账哦", "点一下，把今天的消费补上吧～")
            }

            // 预算预警
            if (s.budgetAlert) {
                val month = Dates.startOfMonth(now)
                val progress = repo.budgetProgress(s.ledgerId, month)
                val total = progress.firstOrNull { it.budget.categoryId == 0L }
                if (total != null && total.budget.amountCents > 0 && total.spentCents > total.budget.amountCents) {
                    CaptureNotifier.notifyReminder(
                        7002, "⚠️ 本月预算已超支",
                        "已花 ${Fmt.money(total.spentCents)} / 预算 ${Fmt.money(total.budget.amountCents)}"
                    )
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}

object WorkScheduler {
    private const val UNIQUE_DAILY = "whale_daily_work"

    fun scheduleAll(ctx: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextMorning(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    private fun delayToNextMorning(): Long {
        val now = System.currentTimeMillis()
        val today9 = Dates.startOfDay(now) + 9 * 3600_000L
        return if (now < today9) today9 - now else today9 + 86_400_000L - now
    }

    fun startFloatingBall(ctx: Context) {
        val i = Intent(ctx, com.jingyuniang.ledger.service.FloatingBallService::class.java)
        runCatching { ctx.startForegroundService(i) }
    }

    fun stopFloatingBall(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, com.jingyuniang.ledger.service.FloatingBallService::class.java)) }
    }
}
