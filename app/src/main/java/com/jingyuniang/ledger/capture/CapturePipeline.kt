package com.jingyuniang.ledger.capture

import com.jingyuniang.ledger.Services
import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.Capture
import com.jingyuniang.ledger.data.CaptureStatus
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.Txn
import com.jingyuniang.ledger.data.TxnSource
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.AppLog

/**
 * 抓取流水线：过滤 → 解析 → 去重 → 入账 或 待确认。
 * 通知监听、短信、无障碍、Widget 快捷入口都走这里。
 */
object CapturePipeline {

    private const val DEDUPE_WINDOW = 150_000L
    // 与「已入账账目」的去重窗口：只用来挡「同一笔被两个渠道各记一次」（微信通知 + 银行短信）
    private const val TXN_DEDUPE_WINDOW = 180_000L

    /**
     * 最终置信度 = 解析分 + 抓取来源/证据校正。
     * 提成纯函数是为了让 `diag probe:` 能打印「如果现在收到这条通知，最终会得多少分、会不会自动入账」。
     *
     * 规则：
     * - 读屏（ACCESS）永远封顶 79 → 只进待确认，绝不自动入账
     * - 通知：有结构证据（尾号/账户/余额/单号/商户…）就按原分；
     *   没有证据，但确实是「支付发送方 + 强支付模板」（微信支付/支付宝的常见通知）→ 也按原分；
     *   两者皆无 → 封顶 79
     * - 聊天类：标题不是支付发送方（即群消息/私聊）才降 20
     */
    fun effectiveConfidence(
        source: TxnSource,
        pkg: String?,
        title: String,
        text: String,
        base: Int
    ): Int {
        var conf = base
        val hasEvidence = CaptureFilters.hasTxnEvidence(text)
        val paySender = CaptureFilters.isPaySourceTitle(title) ||
            (pkg != null && CaptureFilters.isPayRail(pkg))
        val strongTpl = CaptureFilters.isStrongPayTemplate(text)
        if (source == TxnSource.ACCESS) {
            conf = minOf(conf, 79)
        } else if (source == TxnSource.NOTIFY && !hasEvidence && !(paySender && strongTpl)) {
            conf = minOf(conf, 79)
        }
        val chatPenalty = pkg != null && CaptureFilters.isChatApp(pkg) &&
            !CaptureFilters.isPaySourceTitle(title)
        if (chatPenalty) conf = (conf - 20).coerceAtLeast(0)
        return conf.coerceIn(0, 99)
    }

    suspend fun process(
        source: TxnSource,
        pkg: String?,
        appName: String,
        title: String,
        text: String
    ): Long? = try {
        processInner(source, pkg, appName, title, text)
    } catch (t: Throwable) {
        AppLog.e("抓取处理失败", t)
        null
    }

    private suspend fun processInner(
        source: TxnSource,
        pkg: String?,
        appName: String,
        title: String,
        text: String
    ): Long? {
        val settings = Services.settings.value
        // 总开关：用户一键暂停后，通知/短信/无障碍全部不再读取，省电
        if (!settings.captureMaster) return null
        val enabled = when (source) {
            TxnSource.NOTIFY -> settings.captureNotify
            TxnSource.SMS -> settings.captureSms
            TxnSource.ACCESS -> settings.captureAccess
            else -> true
        }
        if (!enabled) return null

        val full = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").trim()
        if (full.length < 6) return null
        // 用户手动拉黑的来源：不再解析（比任何规则都准，因为是他自己点的）
        if (CaptureBlocklist.matches(settings.captureBlocklist, pkg ?: "", full)) {
            AppLog.d("命中抓取黑名单，已跳过 pkg=$pkg")
            return null
        }
        if (!CaptureFilters.shouldRead(pkg ?: "", full, title)) return null
        if (pkg == "com.tencent.mm" && !CaptureFilters.wechatLikelyPayment(full)) return null

        val repo = Services.repo
        val accounts = repo.accountDao.all()
        val categories = repo.categoryDao.all()
        val rules = repo.ruleDao.enabled()
        val merchantRules = repo.merchantDao.all().associate { it.merchant to (it.categoryId to it.accountId) }

        val raw = ParseEngine.parse(pkg, appName, full, accounts, categories, rules, merchantRules)
            ?: return null
        // 置信度封顶（宁可多问一次，但**不能把真账单也压成待确认**）：
        //   · 读屏（ACCESS）永远不自动入账 → 封顶 79（默认阈值 80）
        //   · 通知：有「结构证据」（尾号/账户/余额/单号/商户…）→ 保持原分，正常自动入账
        //           没有结构证据，但确实是「支付发送方 + 强支付模板」→ 也放行（微信支付/支付宝的常规通知）
        //           两者都不满足 → 封顶 79，进待确认
        //   · 聊天类转发文本再降 20
        //   · 短信是最可靠的来源，保持原分
        val parsed = raw.copy(
            confidence = effectiveConfidence(source, pkg, title, full, raw.confidence)
        )
        val now = System.currentTimeMillis()

        // 1) 抓取去重：同来源 + 同金额，2.5 分钟内只认一次
        val key = dedupeKey(pkg, parsed.amountCents)
        if (repo.captureDao.countDedupe(key, now - DEDUPE_WINDOW) > 0) {
            AppLog.d("重复抓取，已跳过")
            return null
        }
        // 2) 与「已入账账目」去重：只挡**跨渠道**的同一笔（微信通知 + 银行短信各来一条）
        //    同来源、同金额、短时间内的两笔，可能是真实的两笔消费（连买两杯同价咖啡），
        //    不能一律当重复 —— 同来源的重复由上面的 dedupeKey（150s）负责。
        val similar = repo.txnDao.findSimilar(parsed.amountCents, now - TXN_DEDUPE_WINDOW)
        if (similar != null && similar.source != TxnSource.MANUAL && similar.source != source) {
            AppLog.d("跨渠道同一笔（已有 ${similar.source} 记录），已跳过：${parsed.amountCents}")
            return null
        }

        val ledgerId = currentLedgerId()
        val capture = Capture(
            source = source,
            pkg = pkg ?: "",
            appName = appName,
            rawTitle = title.take(200),
            rawText = full.take(600),
            amountCents = parsed.amountCents,
            direction = parsed.direction,
            merchant = parsed.merchant,
            accountId = parsed.accountId,
            categoryId = parsed.categoryId,
            status = CaptureStatus.PENDING,
            confidence = parsed.confidence,
            time = now,
            dedupeKey = key
        )
        val captureId = repo.captureDao.insert(capture)
        val saved = capture.copy(id = captureId)

        val autoBook = settings.autoBook && parsed.confidence >= settings.autoBookThreshold
        if (autoBook) {
            val txnId = book(ledgerId, saved, parsed.categoryId, parsed.accountId)
            repo.captureDao.update(saved.copy(status = CaptureStatus.AUTO, txnId = txnId))
            CaptureNotifier.notifyAutoBooked(
                saved.copy(txnId = txnId, status = CaptureStatus.AUTO),
                categories.firstOrNull { it.id == parsed.categoryId }?.name ?: "未分类",
                accounts.firstOrNull { it.id == parsed.accountId }?.name ?: ""
            )
        } else {
            CaptureNotifier.notifyPending(
                saved,
                categories.firstOrNull { it.id == parsed.categoryId }?.name ?: "未分类",
                accounts.firstOrNull { it.id == parsed.accountId }?.name ?: ""
            )
        }
        AppLog.i("抓取成功 来源=$source 金额=${parsed.amountCents} 置信度=${parsed.confidence} 自动=$autoBook")
        return captureId
    }

    /** 确认入账（用户点通知里的「确认」或在待确认页操作） */
    suspend fun confirm(captureId: Long, categoryId: Long? = null, accountId: Long? = null): Long? {
        val repo = Services.repo
        val capture = repo.captureDao.recentSince(0).firstOrNull { it.id == captureId } ?: return null
        if (capture.status == CaptureStatus.CONFIRMED || capture.status == CaptureStatus.AUTO) {
            return capture.txnId.takeIf { it > 0 }
        }
        val cid = categoryId ?: capture.categoryId
        val aid = accountId ?: capture.accountId
        val txnId = book(currentLedgerId(), capture, cid, aid)
        repo.captureDao.update(capture.copy(status = CaptureStatus.CONFIRMED, txnId = txnId))
        CaptureNotifier.cancel(capture)
        return txnId
    }

    suspend fun ignore(captureId: Long) {
        val repo = Services.repo
        val capture = repo.captureDao.recentSince(0).firstOrNull { it.id == captureId } ?: return
        repo.captureDao.update(capture.copy(status = CaptureStatus.IGNORED))
        CaptureNotifier.cancel(capture)
    }

    /** 撤销一笔自动入账 */
    suspend fun undo(captureId: Long) {
        val repo = Services.repo
        val capture = repo.captureDao.recentSince(0).firstOrNull { it.id == captureId } ?: return
        if (capture.txnId > 0) repo.txnDao.deleteById(capture.txnId)
        repo.captureDao.update(capture.copy(status = CaptureStatus.IGNORED, txnId = 0))
        CaptureNotifier.cancel(capture)
    }

    /** 用户修改待确认项的分类/账户后直接入账 */
    suspend fun bookNow(capture: Capture, categoryId: Long, accountId: Long): Long {
        val repo = Services.repo
        val txnId = book(currentLedgerId(), capture, categoryId, accountId)
        repo.captureDao.update(capture.copy(status = CaptureStatus.CONFIRMED, txnId = txnId, categoryId = categoryId, accountId = accountId))
        CaptureNotifier.cancel(capture)
        return txnId
    }

    private suspend fun book(
        ledgerId: Long,
        capture: Capture,
        categoryId: Long,
        accountId: Long
    ): Long {
        val repo = Services.repo
        val categories = repo.categoryDao.all()
        val accounts = repo.accountDao.all()
        val type = if (capture.direction == TxnType.INCOME) TxnType.INCOME else TxnType.EXPENSE
        val cat = categoryId.takeIf { it > 0 }
            ?: categories.firstOrNull { it.type == type && it.name == "其他" }?.id
            ?: categories.firstOrNull { it.type == type }?.id ?: 0L
        val acc = accountId.takeIf { it > 0 }
            ?: accounts.firstOrNull { it.isDefault }?.id
            ?: accounts.firstOrNull { it.type == com.jingyuniang.ledger.data.AccountType.WECHAT }?.id
            ?: accounts.firstOrNull()?.id ?: 0L
        val txnId = repo.addTxn(
            Txn(
                ledgerId = ledgerId,
                type = type,
                amountCents = capture.amountCents,
                categoryId = cat,
                accountId = acc,
                date = capture.time,
                note = capture.merchant.ifBlank { cleanNote(capture.rawText) },
                merchant = capture.merchant,
                source = capture.source,
                captureId = capture.id
            )
        )
        if (capture.merchant.isNotBlank()) {
            runCatching { repo.learnMerchant(capture.merchant, cat, acc) }
        }
        return txnId
    }

    suspend fun currentLedgerId(): Long {
        val fromPrefs = Services.settings.value.ledgerId
        if (fromPrefs > 0) return fromPrefs
        val first = Services.repo.ledgerDao.all().firstOrNull()
        if (first != null) {
            runCatching { Services.prefs.setLedgerId(first.id) }
            return first.id
        }
        return 0L
    }

    private fun dedupeKey(pkg: String?, amountCents: Long): String =
        "${pkg ?: "sms"}|${amountCents / 100}"

    /** 自动账单的备注：去掉来源 App 名、去掉过长前缀，留一段能读懂的描述 */
    private fun cleanNote(raw: String): String {
        val t = raw.split("·").lastOrNull()?.trim().orEmpty().ifBlank { raw.trim() }
        return t.take(40)
    }

    /** 供 UI 使用的分类/账户名称查询 */
    fun categoryName(categories: List<Category>, id: Long): String =
        categories.firstOrNull { it.id == id }?.name ?: "未分类"

    fun accountName(accounts: List<Account>, id: Long): String =
        accounts.firstOrNull { it.id == id }?.name ?: "未指定"
}
