package com.jingyuniang.ledger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.jingyuniang.ledger.data.Prefs
import com.jingyuniang.ledger.data.Repo
import com.jingyuniang.ledger.data.Settings
import com.jingyuniang.ledger.util.AppLog
import com.jingyuniang.ledger.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 轻量服务定位器：没有引入 DI 框架，服务 / 广播 / Worker 统一从这里取 Repo 和设置。
 */
object Services {
    private lateinit var ctx: Context
    val appContext: Context get() = ctx
    lateinit var prefs: Prefs
        private set
    val repo: Repo by lazy { Repo(com.jingyuniang.ledger.data.AppDatabase.get(ctx)) }
    val settings = MutableStateFlow(Settings())

    /** 当前 App 的 versionCode（用于「升级后首次启动」判定） */
    val appVersionCode: Long by lazy {
        runCatching {
            val pm = ctx.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        // ① 最早：如果上次安排了「从迁移前快照恢复」，必须在数据库被打开之前压回
        runCatching {
            com.jingyuniang.ledger.io.SnapshotManager.applyPendingRestore(ctx)?.let { AppLog.i(it) }
        }
        prefs = Prefs(ctx)
        appScope.launch {
            prefs.flow.collect { settings.value = it }
        }
    }
}

class WhaleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.init(this)
        createChannels(this)
        Services.appScope.launch {
            runCatching {
                Services.repo.ensureSeeded()
                // 老安装的分类行是首次启动时写进库的，改 SeedData 不会生效 → 这里补一次并集
                Services.repo.refreshCategoryKeywords()
                val s = Services.settings.value
                if (s.ledgerId == 0L) {
                    Services.repo.ledgerDao.all().firstOrNull()?.let { Services.prefs.setLedgerId(it.id) }
                }
                Services.repo.runRecurring()
                Services.repo.pruneCaptures()
                // ② 自动快照（升级后首次启动 / 距上次 >24h）—— 数据库此时已迁移成功，可安全导出
                com.jingyuniang.ledger.io.SnapshotManager
                    .dueReason(this@WhaleApp, Services.appVersionCode)
                    ?.let { reason ->
                        com.jingyuniang.ledger.io.SnapshotManager.writeDaily(this@WhaleApp, reason)
                    }
                if (Services.settings.value.quickNotify) {
                    com.jingyuniang.ledger.capture.QuickEntryNotifier.show(this@WhaleApp)
                }
            }.onFailure { AppLog.w("初始化数据失败", it) }
        }
        runCatching { WorkScheduler.scheduleAll(this) }
    }

    private fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, "自动记账", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "抓到一笔消费后提醒你确认"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AUTO, "已自动记账", NotificationManager.IMPORTANCE_LOW).apply {
                description = "高置信度消费已自动入账的静默通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "记账提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每日记账提醒与预算预警"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "悬浮球 / 常驻服务", NotificationManager.IMPORTANCE_MIN).apply {
                description = "快捷记账悬浮球运行中"
            }
        )
    }

    companion object {
        const val CH_CAPTURE = "whale_capture"
        const val CH_AUTO = "whale_auto"
        const val CH_REMIND = "whale_remind"
        const val CH_SERVICE = "whale_service"
    }
}
