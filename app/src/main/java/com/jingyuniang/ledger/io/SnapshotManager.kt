package com.jingyuniang.ledger.io

import android.content.Context
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.util.AppLog
import java.io.File

/**
 * 自动快照（安全网）。
 *
 * 分两种，用途不同：
 *
 * | 类型 | 形式 | 生成时机 | 恢复方式 |
 * |---|---|---|---|
 * | **迁移前快照** `pre_migrate_v{N}_*.db` | 直接复制数据库文件（含 -wal/-shm） | 打开数据库**之前**，且检测到 `user_version < 目标版本` | 下次启动早期自动压回（见 `restorePending`） |
 * | **日常快照** `auto_*.json` | 明文 JSON（结构同备份包，但**不加密**） | 升级后首次启动、以及距上次 >24h | 设置页「从自动快照恢复」 |
 *
 * 为什么日常快照不加密：自动跑的时候没有密码可问。它只存在 App 私有目录（`filesDir/snapshots`），
 * 不落到公共目录，所以明文不会外泄；如果整个 App 被卸载，私有目录也一起没了——那种情况请用
 * 「数据 → 另存到文件」导出的加密备份。
 *
 * 为什么迁移前快照是「复制 db 文件」而不是 JSON：迁移要在打开数据库时发生，
 * 而导出 JSON 又必须打开数据库——顺序上不可能。复制文件则可以完全绕开打开动作。
 */
object SnapshotManager {

    private const val DIR_SNAPSHOTS = "snapshots"
    private const val PENDING_RESTORE = "restore_pending.txt"
    private const val DAILY_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val KEEP_DAILY = 7
    private const val KEEP_PREMIGRATE = 3

    fun dir(context: Context): File = File(context.filesDir, DIR_SNAPSHOTS).apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 最近一份「日常快照」 */
    fun latestDaily(context: Context): File? =
        list(context).firstOrNull { it.name.startsWith("auto_") && it.name.endsWith(".json") }

    // ---------------- 迁移前快照 ----------------

    /**
     * 在打开数据库**之前**调用：若磁盘上的 user_version 低于 [targetVersion]，先把 db 文件抄一份。
     * @return 快照文件；不需要快照或失败返回 null
     */
    fun beforeMigration(context: Context, targetVersion: Int): File? {
        return runCatching {
            val dbFile = context.getDatabasePath("whale_money.db")
            if (!dbFile.exists()) return null
            val current = readUserVersion(dbFile)
            if (current >= targetVersion || current <= 0) return null
            val stamp = System.currentTimeMillis()
            val target = File(dir(context), "pre_migrate_v${current}_to_v${targetVersion}_$stamp.db")
            copyDbTriplet(dbFile, target)
            // wal/shm 也一起复制（数据可能还没 checkpoint 进主库）
            AppLog.i("迁移前快照已生成：${target.name}（v$current → v$targetVersion）")
            prune(context, "pre_migrate_", KEEP_PREMIGRATE)
            target
        }.onFailure { AppLog.e("迁移前快照失败", it) }.getOrNull()
    }

    private fun copyDbTriplet(srcDb: File, dstBase: File) {
        // 目标命名：xxx.db（+ 可选 -wal/-shm 内容会合并进单个文件，便于压回）
        // 用 SQLite 的备份思路太重，这里直接拼「主库 + wal + shm」为 3 个伴生文件：
        // dstBase 本身是主库，另两个用同名后缀，恢复时一并压回。
        srcDb.copyTo(dstBase, overwrite = true)
        File(srcDb.path + "-wal").takeIf { it.exists() }?.copyTo(File(dstBase.path + "-wal"), overwrite = true)
        File(srcDb.path + "-shm").takeIf { it.exists() }?.copyTo(File(dstBase.path + "-shm"), overwrite = true)
    }

    /** 读 SQLite 文件头里的 user_version（偏移 60，4 字节大端） */
    internal fun readUserVersion(dbFile: File): Int = runCatching {
        dbFile.inputStream().use { ins ->
            val header = ByteArray(100)
            if (ins.read(header) < 100) return 0
            if (String(header, 0, 15) != "SQLite format 3") return 0
            ((header[60].toInt() and 0xFF) shl 24) or
                ((header[61].toInt() and 0xFF) shl 16) or
                ((header[62].toInt() and 0xFF) shl 8) or
                (header[63].toInt() and 0xFF)
        }
    }.getOrDefault(0)

    // ---------------- 日常快照 ----------------

    /**
     * 需要跑日常快照吗？（升级后首次启动 / 距上次 >24h）
     * @return 触发原因，null 表示不需要
     */
    fun dueReason(context: Context, versionCode: Long): String? {
        val s = Services.settings.value
        if (s.lastSnapshotVersionCode != versionCode) {
            return "upgrade_from_v${s.lastSnapshotVersionCode}_to_v$versionCode"
        }
        if (System.currentTimeMillis() - s.lastSnapshotTime > DAILY_INTERVAL_MS) return "daily"
        return null
    }

    suspend fun writeDaily(context: Context, reason: String): File? = runCatching {
        val json = BackupManager.buildExportJson()
        val stamp = System.currentTimeMillis()
        val file = File(dir(context), "auto_${reason}_$stamp.json")
        file.writeText(json.toString())
        Services.prefs.setSnapshotMeta(Services.appVersionCode, stamp)
        AppLog.i("自动快照完成：${file.name}（${file.length() / 1024} KB）")
        prune(context, "auto_", KEEP_DAILY)
        file
    }.onFailure { AppLog.e("自动快照失败", it) }.getOrNull()

    // ---------------- 恢复 ----------------

    /** 安排一次「下次启动时把某个迁移前快照压回数据库」 */
    fun scheduleRestoreDb(context: Context, snapshot: File): Boolean = runCatching {
        File(dir(context), PENDING_RESTORE).writeText(snapshot.name)
        AppLog.i("已安排恢复：${snapshot.name}（重启 App 后生效）")
        true
    }.getOrDefault(false)

    /**
     * 在 [com.jingyuniang.ledger.WhaleApp] 最早处调用：如果存在待恢复标记，
     * 把快照压回 databases/（此时数据库还没被打开，安全）。
     */
    fun applyPendingRestore(context: Context): String? = runCatching {
        val flag = File(dir(context), PENDING_RESTORE)
        if (!flag.exists()) return null
        val name = flag.readText().trim()
        val snap = File(dir(context), name)
        if (!snap.exists()) {
            flag.delete()
            return "待恢复的快照不存在：$name"
        }
        val dbFile = context.getDatabasePath("whale_money.db")
        dbFile.parentFile?.mkdirs()
        // 先把现有库挪走（万一恢复失败还能找回）
        if (dbFile.exists()) {
            dbFile.copyTo(File(dbFile.path + ".before_restore"), overwrite = true)
        }
        snap.copyTo(dbFile, overwrite = true)
        File(snap.path + "-wal").takeIf { it.exists() }
            ?.copyTo(File(dbFile.path + "-wal"), overwrite = true)
        File(snap.path + "-shm").takeIf { it.exists() }
            ?.copyTo(File(dbFile.path + "-shm"), overwrite = true)
        // 删除可能残留的 -journal，避免旧日志回放
        File(dbFile.path + "-journal").delete()
        flag.delete()
        AppLog.i("已从快照恢复数据库：$name")
        "已从快照恢复数据库：$name"
    }.onFailure { AppLog.e("恢复快照失败", it) }.getOrNull()

    // ---------------- 清理 ----------------

    private fun prune(context: Context, prefix: String, keep: Int) {
        list(context).filter { it.name.startsWith(prefix) }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { f ->
                f.delete()
                File(f.path + "-wal").delete()
                File(f.path + "-shm").delete()
            }
    }

    /** 供设置页展示 */
    fun describe(context: Context): String {
        val all = list(context)
        if (all.isEmpty()) return "还没有自动快照"
        val latest = all.first()
        val kb = (latest.length() / 1024).coerceAtLeast(1)
        return "最近：${latest.name}（${kb} KB）· 共 ${all.size} 份"
    }
}
