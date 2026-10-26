package com.jingyuniang.ledger.io

import android.content.Context
import android.net.Uri
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.AccountType
import com.jingyuniang.ledger.data.AutoRule
import com.jingyuniang.ledger.data.Budget
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Debt
import com.jingyuniang.ledger.data.DebtType
import com.jingyuniang.ledger.data.Freq
import com.jingyuniang.ledger.data.Ledger
import com.jingyuniang.ledger.data.MerchantRule
import com.jingyuniang.ledger.data.Recurring
import com.jingyuniang.ledger.data.ReimburseState
import com.jingyuniang.ledger.data.SavingGoal
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.AppLog
import com.jingyuniang.ledger.util.Dates
import com.jingyuniang.ledger.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地加密备份：整库导出成 AES-GCM 加密的单文件，自己存网盘 / 传电脑。
 * 文件后缀 .whalebk，恢复时必须输入同一个密码。
 */
object BackupManager {

    private const val MAGIC = "WHALEBK1"
    private const val ITER = 120_000

    data class Result(val ok: Boolean, val message: String, val file: File? = null)

    /** 把整库打包成 JSON（导出与自动快照共用；不含加密） */
    suspend fun buildExportJson(): JSONObject {
            val repo = Services.repo
            val root = JSONObject()
            root.put("version", 1)
            root.put("createdAt", System.currentTimeMillis())
            root.put("ledgers", JSONArray().apply { repo.ledgerDao.all().forEach { put(ledgerJson(it)) } })
            root.put("accounts", JSONArray().apply { repo.accountDao.all().forEach { put(accountJson(it)) } })
            root.put("categories", JSONArray().apply { repo.categoryDao.all().forEach { put(categoryJson(it)) } })
            root.put("budgets", JSONArray().apply { repo.budgetDao.all().forEach { put(budgetJson(it)) } })
            root.put("savings", JSONArray().apply { repo.savingDao.all().forEach { put(savingJson(it)) } })
            root.put("debts", JSONArray().apply { repo.debtDao.all().forEach { put(debtJson(it)) } })
            root.put("recurring", JSONArray().apply { repo.recurringDao.all().forEach { put(recurringJson(it)) } })
            root.put("rules", JSONArray().apply { repo.ruleDao.all().forEach { put(ruleJson(it)) } })
            root.put("merchants", JSONArray().apply { repo.merchantDao.all().forEach { put(merchantJson(it)) } })
            root.put("txns", JSONArray().apply { repo.txnDao.all().forEach { put(txnJson(it)) } })
            return root
    }

    suspend fun export(context: Context, password: String): Result = withContext(Dispatchers.IO) {
        try {
            val root = buildExportJson()
            val plain = root.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(plain, password)
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val name = "鲸鱼娘备份-${Dates.year(System.currentTimeMillis())}" +
                "${Dates.monthOfYear(System.currentTimeMillis()).toString().padStart(2, '0')}" +
                "${Dates.dayOfMonth(System.currentTimeMillis()).toString().padStart(2, '0')}-" +
                "${System.currentTimeMillis() % 100000}.whalebk"
            val file = File(dir, name)
            file.writeBytes(encrypted)
            Services.prefs.setLastBackup(System.currentTimeMillis())
            Result(true, "备份成功：${file.name}（${file.length() / 1024} KB）", file)
        } catch (t: Throwable) {
            AppLog.e("备份失败", t)
            Result(false, "备份失败：${t.message}")
        }
    }

    suspend fun restore(context: Context, uri: Uri, password: String): Result = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result(false, "读不到备份文件")
            val plain = decrypt(bytes, password) ?: return@withContext Result(false, "密码不对，或文件已损坏")
            val root = JSONObject(String(plain, Charsets.UTF_8))
            val repo = Services.repo
            val db = repo.db

            db.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM txn")
                execSQL("DELETE FROM budget")
                execSQL("DELETE FROM recurring")
                execSQL("DELETE FROM saving_goal")
                execSQL("DELETE FROM debt")
                execSQL("DELETE FROM auto_rule")
                execSQL("DELETE FROM merchant_rule")
                execSQL("DELETE FROM capture")
                execSQL("DELETE FROM category")
                execSQL("DELETE FROM account")
                execSQL("DELETE FROM ledger")
            }

            root.optJSONArray("ledgers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.ledgerDao.insert(
                        Ledger(
                            id = o.optLong("id"), name = o.optString("name"), emoji = o.optString("emoji"),
                            color = o.optLong("color"), sortOrder = o.optInt("sortOrder"),
                            createdAt = o.optLong("createdAt"), archived = o.optBoolean("archived")
                        )
                    )
                }
            }
            root.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.accountDao.insert(
                        Account(
                            id = o.optLong("id"), name = o.optString("name"),
                            type = enumOf(o.optString("type"), AccountType.CASH),
                            emoji = o.optString("emoji"), color = o.optLong("color"),
                            initialBalanceCents = o.optLong("initialBalanceCents"),
                            cardTail = o.optString("cardTail"), creditLimitCents = o.optLong("creditLimitCents"),
                            billDay = o.optInt("billDay"), repayDay = o.optInt("repayDay"),
                            sortOrder = o.optInt("sortOrder"), archived = o.optBoolean("archived"),
                            isDefault = o.optBoolean("isDefault"), keywords = o.optString("keywords")
                        )
                    )
                }
            }
            root.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.categoryDao.insert(
                        Category(
                            id = o.optLong("id"), name = o.optString("name"), emoji = o.optString("emoji"),
                            type = enumOf(o.optString("type"), TxnType.EXPENSE), color = o.optLong("color"),
                            sortOrder = o.optInt("sortOrder"), archived = o.optBoolean("archived"),
                            isSystem = o.optBoolean("isSystem"), keywords = o.optString("keywords")
                        )
                    )
                }
            }
            root.optJSONArray("budgets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.budgetDao.insert(
                        Budget(
                            id = o.optLong("id"), ledgerId = o.optLong("ledgerId"),
                            categoryId = o.optLong("categoryId"), amountCents = o.optLong("amountCents"),
                            createdAt = o.optLong("createdAt")
                        )
                    )
                }
            }
            root.optJSONArray("savings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.savingDao.insert(
                        SavingGoal(
                            id = o.optLong("id"), name = o.optString("name"), emoji = o.optString("emoji"),
                            targetCents = o.optLong("targetCents"), savedCents = o.optLong("savedCents"),
                            deadline = o.optLong("deadline"), color = o.optLong("color"),
                            note = o.optString("note"), done = o.optBoolean("done"),
                            createdAt = o.optLong("createdAt")
                        )
                    )
                }
            }
            root.optJSONArray("debts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.debtDao.insert(
                        Debt(
                            id = o.optLong("id"), type = enumOf(o.optString("type"), DebtType.RECEIVABLE),
                            person = o.optString("person"), amountCents = o.optLong("amountCents"),
                            settledCents = o.optLong("settledCents"), date = o.optLong("date"),
                            dueDate = o.optLong("dueDate"), note = o.optString("note"),
                            ledgerId = o.optLong("ledgerId"), accountId = o.optLong("accountId"),
                            settled = o.optBoolean("settled"), createdAt = o.optLong("createdAt")
                        )
                    )
                }
            }
            root.optJSONArray("recurring")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.recurringDao.insert(
                        Recurring(
                            id = o.optLong("id"), ledgerId = o.optLong("ledgerId"), name = o.optString("name"),
                            emoji = o.optString("emoji"), type = enumOf(o.optString("type"), TxnType.EXPENSE),
                            amountCents = o.optLong("amountCents"), categoryId = o.optLong("categoryId"),
                            accountId = o.optLong("accountId"), note = o.optString("note"),
                            freq = enumOf(o.optString("freq"), Freq.MONTHLY), dayOfMonth = o.optInt("dayOfMonth"),
                            nextAt = o.optLong("nextAt"), autoBook = o.optBoolean("autoBook", true),
                            enabled = o.optBoolean("enabled", true), lastBookedAt = o.optLong("lastBookedAt"),
                            createdAt = o.optLong("createdAt")
                        )
                    )
                }
            }
            root.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.ruleDao.insert(
                        AutoRule(
                            id = o.optLong("id"), name = o.optString("name"),
                            enabled = o.optBoolean("enabled", true), pkg = o.optString("pkg"),
                            keyword = o.optString("keyword"), regex = o.optString("regex"),
                            direction = enumOf(o.optString("direction"), TxnType.EXPENSE),
                            categoryId = o.optLong("categoryId"), accountId = o.optLong("accountId"),
                            priority = o.optInt("priority"), builtin = o.optBoolean("builtin"),
                            hitCount = o.optInt("hitCount")
                        )
                    )
                }
            }
            root.optJSONArray("merchants")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.merchantDao.upsert(
                        MerchantRule(
                            merchant = o.optString("merchant"), categoryId = o.optLong("categoryId"),
                            accountId = o.optLong("accountId"), hits = o.optInt("hits"),
                            updatedAt = o.optLong("updatedAt")
                        )
                    )
                }
            }
            var count = 0
            root.optJSONArray("txns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    repo.txnDao.insert(
                        Txn(
                            id = o.optLong("id"), ledgerId = o.optLong("ledgerId"),
                            type = enumOf(o.optString("type"), TxnType.EXPENSE),
                            amountCents = o.optLong("amountCents"), categoryId = o.optLong("categoryId"),
                            accountId = o.optLong("accountId"), toAccountId = o.optLong("toAccountId"),
                            toAmountCents = o.optLong("toAmountCents"), date = o.optLong("date"),
                            note = o.optString("note"), tags = o.optString("tags"),
                            merchant = o.optString("merchant"), photos = o.optString("photos"),
                            source = enumOf(o.optString("source"), TxnSource.MANUAL),
                            reimburse = enumOf(o.optString("reimburse"), ReimburseState.NONE),
                            excludeBudget = o.optBoolean("excludeBudget"), captureId = o.optLong("captureId"),
                            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt")
                        )
                    )
                    count++
                }
            }
            Result(true, "恢复完成，共 $count 笔账目")
        } catch (t: Throwable) {
            AppLog.e("恢复失败", t)
            Result(false, "恢复失败：${t.message}")
        }
    }

    // ---------------- 序列化 ----------------
    private fun ledgerJson(x: Ledger) = JSONObject().apply {
        put("id", x.id); put("name", x.name); put("emoji", x.emoji); put("color", x.color)
        put("sortOrder", x.sortOrder); put("createdAt", x.createdAt); put("archived", x.archived)
    }

    private fun accountJson(x: Account) = JSONObject().apply {
        put("id", x.id); put("name", x.name); put("type", x.type.name); put("emoji", x.emoji)
        put("color", x.color); put("initialBalanceCents", x.initialBalanceCents); put("cardTail", x.cardTail)
        put("creditLimitCents", x.creditLimitCents); put("billDay", x.billDay); put("repayDay", x.repayDay)
        put("sortOrder", x.sortOrder); put("archived", x.archived); put("isDefault", x.isDefault)
        put("keywords", x.keywords)
    }

    private fun categoryJson(x: Category) = JSONObject().apply {
        put("id", x.id); put("name", x.name); put("emoji", x.emoji); put("type", x.type.name)
        put("color", x.color); put("sortOrder", x.sortOrder); put("archived", x.archived)
        put("isSystem", x.isSystem); put("keywords", x.keywords)
    }

    private fun budgetJson(x: Budget) = JSONObject().apply {
        put("id", x.id); put("ledgerId", x.ledgerId); put("categoryId", x.categoryId)
        put("amountCents", x.amountCents); put("createdAt", x.createdAt)
    }

    private fun savingJson(x: SavingGoal) = JSONObject().apply {
        put("id", x.id); put("name", x.name); put("emoji", x.emoji); put("targetCents", x.targetCents)
        put("savedCents", x.savedCents); put("deadline", x.deadline); put("color", x.color)
        put("note", x.note); put("done", x.done); put("createdAt", x.createdAt)
    }

    private fun debtJson(x: Debt) = JSONObject().apply {
        put("id", x.id); put("type", x.type.name); put("person", x.person)
        put("amountCents", x.amountCents); put("settledCents", x.settledCents); put("date", x.date)
        put("dueDate", x.dueDate); put("note", x.note); put("ledgerId", x.ledgerId)
        put("accountId", x.accountId); put("settled", x.settled); put("createdAt", x.createdAt)
    }

    private fun recurringJson(x: Recurring) = JSONObject().apply {
        put("id", x.id); put("ledgerId", x.ledgerId); put("name", x.name); put("emoji", x.emoji)
        put("type", x.type.name); put("amountCents", x.amountCents); put("categoryId", x.categoryId)
        put("accountId", x.accountId); put("note", x.note); put("freq", x.freq.name)
        put("dayOfMonth", x.dayOfMonth); put("nextAt", x.nextAt); put("autoBook", x.autoBook)
        put("enabled", x.enabled); put("lastBookedAt", x.lastBookedAt); put("createdAt", x.createdAt)
    }

    private fun ruleJson(x: AutoRule) = JSONObject().apply {
        put("id", x.id); put("name", x.name); put("enabled", x.enabled); put("pkg", x.pkg)
        put("keyword", x.keyword); put("regex", x.regex); put("direction", x.direction.name)
        put("categoryId", x.categoryId); put("accountId", x.accountId); put("priority", x.priority)
        put("builtin", x.builtin); put("hitCount", x.hitCount)
    }

    private fun merchantJson(x: MerchantRule) = JSONObject().apply {
        put("merchant", x.merchant); put("categoryId", x.categoryId); put("accountId", x.accountId)
        put("hits", x.hits); put("updatedAt", x.updatedAt)
    }

    private fun txnJson(x: Txn) = JSONObject().apply {
        put("id", x.id); put("ledgerId", x.ledgerId); put("type", x.type.name)
        put("amountCents", x.amountCents); put("categoryId", x.categoryId); put("accountId", x.accountId)
        put("toAccountId", x.toAccountId); put("toAmountCents", x.toAmountCents); put("date", x.date)
        put("note", x.note); put("tags", x.tags); put("merchant", x.merchant); put("photos", x.photos)
        put("source", x.source.name); put("reimburse", x.reimburse.name)
        put("excludeBudget", x.excludeBudget); put("captureId", x.captureId)
        put("createdAt", x.createdAt); put("updatedAt", x.updatedAt)
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String, def: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(def)

    // ---------------- 加解密 ----------------
    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + ct
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray? = runCatching {
        val magicLen = MAGIC.length
        if (data.size < magicLen + 28) return null
        val magic = String(data, 0, magicLen, Charsets.US_ASCII)
        if (magic != MAGIC) return null
        val salt = data.copyOfRange(magicLen, magicLen + 16)
        val iv = data.copyOfRange(magicLen + 16, magicLen + 28)
        val ct = data.copyOfRange(magicLen + 28, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITER, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    // ---------------- CSV 导出 ----------------
    suspend fun exportCsv(context: Context, ledgerId: Long): Result = withContext(Dispatchers.IO) {
        try {
            val repo = Services.repo
            val list = repo.txnDao.all().filter { ledgerId <= 0 || it.ledgerId == ledgerId }
            val cats = repo.categoryDao.all().associateBy { it.id }
            val accs = repo.accountDao.all().associateBy { it.id }
            val sb = StringBuilder("\uFEFF日期,时间,类型,分类,金额,账户,商户,备注,标签,来源,报销\n")
            list.sortedBy { it.date }.forEach { t ->
                sb.append(Dates.ymd(t.date)).append(',')
                sb.append(Dates.hm(t.date)).append(',')
                sb.append(
                    when (t.type) {
                        TxnType.EXPENSE -> "支出"
                        TxnType.INCOME -> "收入"
                        TxnType.TRANSFER -> "转账"
                    }
                ).append(',')
                sb.append(cats[t.categoryId]?.name ?: "").append(',')
                sb.append(Fmt.amount(t.amountCents)).append(',')
                sb.append(accs[t.accountId]?.name ?: "").append(',')
                sb.append(t.merchant.replace(",", " ")).append(',')
                sb.append(t.note.replace(",", " ").replace("\n", " ")).append(',')
                sb.append(t.tags).append(',')
                sb.append(t.source.name).append(',')
                sb.append(
                    when (t.reimburse) {
                        ReimburseState.NONE -> ""
                        ReimburseState.PENDING -> "待报销"
                        ReimburseState.DONE -> "已报销"
                    }
                ).append('\n')
            }
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, "鲸鱼娘账目-${Dates.ymd(System.currentTimeMillis())}.csv")
            file.writeText(sb.toString(), Charsets.UTF_8)
            Result(true, "已导出 ${list.size} 笔到 ${file.name}", file)
        } catch (t: Throwable) {
            AppLog.e("导出失败", t)
            Result(false, "导出失败：${t.message}")
        }
    }
}
