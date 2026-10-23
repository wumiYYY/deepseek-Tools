package com.jingyuniang.ledger.io

import android.content.Context
import android.net.Uri
import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.AppLog
import com.jingyuniang.ledger.util.Fmt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * 微信 / 支付宝 账单导入（官方导出的 csv，或包含 csv 的 zip）。
 * 支付宝导出一般是 GBK 编码，微信是 UTF-8，这里自动识别；带密码的 zip 需要先在电脑上解压。
 */
object BillImporter {

    data class ImportResult(
        val inserted: Int = 0,
        val skipped: Int = 0,
        val total: Int = 0,
        val message: String = ""
    )

    suspend fun import(context: Context, uri: Uri, ledgerId: Long): ImportResult {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return ImportResult(message = "读不到文件，换一个试试")

        val csvTexts: List<String> = if (isZip(bytes)) {
            unzipCsv(bytes)
        } else {
            listOf(decode(bytes))
        }
        if (csvTexts.isEmpty()) return ImportResult(message = "压缩包里没找到 csv 账单（若设了密码请先解压）")

        val repo = Services.repo
        val categories = repo.categoryDao.all()
        val accounts = repo.accountDao.all()
        val existing = repo.allTxns().map { existingKey(it.date, it.amountCents, it.merchant) }.toHashSet()

        var inserted = 0
        var skipped = 0
        var total = 0
        csvTexts.forEach { text ->
            val rows = parseRows(text)
            rows.forEach { r ->
                total++
                val key = existingKey(r.date, r.amountCents, r.merchant)
                if (existing.contains(key)) {
                    skipped++
                    return@forEach
                }
                val cat = ParseEngine.matchCategory(
                    "${r.merchant} ${r.note}", r.merchant, r.direction, categories
                )
                val acc: Long = ParseEngine.matchAccount(null, r.payMethod, accounts)?.id
                    ?: accounts.firstOrNull { it.isDefault }?.id ?: 0L
                repo.addTxn(
                    Txn(
                        ledgerId = ledgerId,
                        type = r.direction,
                        amountCents = r.amountCents,
                        categoryId = cat,
                        accountId = acc,
                        date = r.date,
                        note = r.note.take(80),
                        merchant = r.merchant.take(40),
                        source = TxnSource.IMPORT
                    )
                )
                existing.add(key)
                inserted++
            }
        }
        return ImportResult(
            inserted = inserted,
            skipped = skipped,
            total = total,
            message = "共识别 $total 条，导入 $inserted 条，跳过 $skipped 条（重复或无效）"
        )
    }

    private fun existingKey(date: Long, cents: Long, merchant: String): String {
        val zone = ZoneId.systemDefault()
        val d = LocalDateTime.ofEpochSecond(date / 1000, 0, zone.rules.getOffset(java.time.Instant.ofEpochMilli(date)))
        return "${d.toLocalDate()}|$cents|${merchant.take(8)}"
    }

    /** 解析出来的一行账单（internal 以便单元测试直接验证） */
    internal data class Row(
        val date: Long,
        val amountCents: Long,
        val direction: TxnType,
        val merchant: String,
        val note: String,
        val payMethod: String
    )

    internal fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun unzipCsv(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var guard = 0
                while (entry != null && guard++ < 40) {
                    if (!entry.isDirectory && (entry.name.endsWith(".csv", true) || entry.name.endsWith(".txt", true))) {
                        val buf = ByteArrayOutputStream()
                        zip.readBytesTo(buf)
                        out.add(decode(buf.toByteArray()))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }.onFailure { AppLog.e("解压账单失败", it) }
        return out
    }

    private fun java.util.zip.ZipInputStream.readBytesTo(out: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return runCatching {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
        }
    }

    // ---------------- CSV 解析 ----------------
    internal fun parseRows(text: String): List<Row> {
        val lines = text.split("\r\n", "\n", "\r").filter { it.isNotBlank() }
        var headerIdx = -1
        lines.forEachIndexed { i, line ->
            if (headerIdx < 0 && line.contains("交易时间") && (line.contains("收/支") || line.contains("金额"))) {
                headerIdx = i
            }
        }
        if (headerIdx < 0) return emptyList()
        val header = splitCsv(lines[headerIdx]).map { it.trim().removePrefix("\uFEFF") }

        fun idx(vararg keys: String): Int {
            for (k in keys) {
                val i = header.indexOfFirst { it.replace(" ", "").contains(k) }
                if (i >= 0) return i
            }
            return -1
        }

        val iDate = idx("交易时间", "交易日期")
        val iType = idx("收/支")
        val iAmount = idx("金额(元)", "金额")
        val iMerchant = idx("交易对方", "对方")
        val iProduct = idx("商品", "商品说明", "备注")
        val iPay = idx("支付方式", "收/付款方式", "付款方式")
        if (iDate < 0 || iAmount < 0) return emptyList()

        val out = ArrayList<Row>()
        for (i in headerIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("-") || line.contains("---------")) break
            if (line.contains("导出时间") || line.contains("账单明细")) continue
            val split = splitCsv(line)
            if (split.size <= maxOf(iDate, iAmount)) continue
            // 千分位金额在未加引号的 csv 里会被逗号切开（¥1,234.56 → ¥1 / 234.56），这里合并回来
            val cols = mergeAmountGroups(split, iAmount)
            val date = parseDate(cols[iDate].trim()) ?: continue
            val amountRaw = cols[iAmount].trim().replace("¥", "").replace("￥", "").replace(",", "").trim()
            val cents = Fmt.toCents(amountRaw)
            if (cents <= 0L) continue
            val typeText = if (iType >= 0 && iType < cols.size) cols[iType].trim() else ""
            if (typeText.contains("不计") || typeText.contains("中性")) continue
            val direction = if (typeText.contains("收") && !typeText.contains("支")) TxnType.INCOME else TxnType.EXPENSE
            val merchant = if (iMerchant >= 0 && iMerchant < cols.size) cols[iMerchant].trim() else ""
            val product = if (iProduct >= 0 && iProduct < cols.size) cols[iProduct].trim() else ""
            val payMethod = if (iPay >= 0 && iPay < cols.size) cols[iPay].trim() else ""
            out.add(Row(date, cents, direction, merchant, product, payMethod))
        }
        return out
    }

    /** 把被逗号切开的千分位金额拼回去，例如 ["¥1","234.56"] → ["¥1,234.56"] */
    internal fun mergeAmountGroups(cols: List<String>, amountIdx: Int): List<String> {
        if (amountIdx < 0 || amountIdx >= cols.size) return cols
        val out = cols.toMutableList()
        var guard = 0
        while (guard++ < 4 && amountIdx + 1 < out.size) {
            val cur = out[amountIdx].trim()
            val next = out[amountIdx + 1].trim()
            val digits = cur.replace("¥", "").replace("￥", "").filter { it.isDigit() }
            val curIsPartial = Regex("""^[¥￥]?\d{1,3}(,\d{1,3})*$""").matches(cur) &&
                (cur.endsWith(",") || digits.length <= 3)
            val nextIsGroup = Regex("""^\d{3}(\.\d{1,2})?$""").matches(next)
            if (!curIsPartial || !nextIsGroup) break
            out[amountIdx] = cur + next
            out.removeAt(amountIdx + 1)
        }
        return out
    }

    internal fun splitCsv(line: String): List<String> {        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else inQuote = !inQuote
                }
                ch == ',' && !inQuote -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseDate(raw: String): Long? {
        val s = raw.trim().replace("/", "-").replace("年", "-").replace("月", "-").replace("日", "")
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy-M-d H:m:s", "yyyy-M-d"
        )
        patterns.forEach { p ->
            runCatching {
                val fmt = java.time.format.DateTimeFormatter.ofPattern(p)
                val dt = runCatching { LocalDateTime.parse(s, fmt) }
                    .getOrElse { java.time.LocalDate.parse(s, fmt).atStartOfDay() }
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }
}
