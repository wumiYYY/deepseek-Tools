package com.jingyuniang.ledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账目类型 */
enum class TxnType { EXPENSE, INCOME, TRANSFER }

/** 账户类型 */
enum class AccountType { CASH, BANK, CREDIT, WECHAT, ALIPAY, OTHER }

/** 账目来源（区分手记与自动抓取）；ADJUST = 「更新余额」产生的余额调整流水，不计入收支统计 */
enum class TxnSource { MANUAL, NOTIFY, SMS, ACCESS, IMPORT, OCR, AI, RECURRING, ADJUST }

/** 抓取记录状态 */
enum class CaptureStatus { PENDING, AUTO, CONFIRMED, IGNORED }

/** 周期频率 */
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** 借贷方向：RECEIVABLE=别人欠我（应收），PAYABLE=我欠别人（应付） */
enum class DebtType { RECEIVABLE, PAYABLE }

/** 报销状态 */
enum class ReimburseState { NONE, PENDING, DONE }

@Entity(tableName = "ledger")
data class Ledger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🐳",
    val color: Long = 0xFF4D6BFE,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false
)

@Entity(tableName = "account")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType = AccountType.CASH,
    val emoji: String = "💳",
    val color: Long = 0xFF4D6BFE,
    val initialBalanceCents: Long = 0,
    /** 卡号后四位，用于匹配银行短信 */
    val cardTail: String = "",
    val creditLimitCents: Long = 0,
    /** 账单日 / 还款日（信用卡，1-31，0=不设置） */
    val billDay: Int = 0,
    val repayDay: Int = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val isDefault: Boolean = false,
    /** 自动识别关键词，逗号分隔，例如 "微信,微信支付,wechat" */
    val keywords: String = ""
)

@Entity(tableName = "category", indices = [Index("type")])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "📦",
    val type: TxnType = TxnType.EXPENSE,
    val color: Long = 0xFF90A4AE,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val isSystem: Boolean = false,
    /** 自动分类关键词，逗号分隔 */
    val keywords: String = ""
)

@Entity(
    tableName = "txn",
    indices = [Index("date"), Index("ledgerId"), Index("categoryId"), Index("accountId"), Index("captureId")]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val type: TxnType = TxnType.EXPENSE,
    /** 金额，单位：分，恒为正数 */
    val amountCents: Long = 0,
    val categoryId: Long = 0,
    val accountId: Long = 0,
    val toAccountId: Long = 0,
    val toAmountCents: Long = 0,
    val date: Long = System.currentTimeMillis(),
    val note: String = "",
    /** 标签，逗号分隔，首尾带逗号便于查询 */
    val tags: String = "",
    val merchant: String = "",
    /** 票据照片路径，逗号分隔 */
    val photos: String = "",
    val source: TxnSource = TxnSource.MANUAL,
    val reimburse: ReimburseState = ReimburseState.NONE,
    val excludeBudget: Boolean = false,
    val captureId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "budget")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long = 0,
    /** 0 = 总预算，其它 = 分类预算 */
    val categoryId: Long = 0,
    val amountCents: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recurring")
data class Recurring(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long = 0,
    val name: String,
    val emoji: String = "🔁",
    val type: TxnType = TxnType.EXPENSE,
    val amountCents: Long = 0,
    val categoryId: Long = 0,
    val accountId: Long = 0,
    val note: String = "",
    val freq: Freq = Freq.MONTHLY,
    /** 每月几号（MONTHLY/YEARLY 使用） */
    val dayOfMonth: Int = 1,
    val nextAt: Long = 0,
    val autoBook: Boolean = true,
    val enabled: Boolean = true,
    val lastBookedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saving_goal")
data class SavingGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🎯",
    val targetCents: Long = 0,
    val savedCents: Long = 0,
    val deadline: Long = 0,
    val color: Long = 0xFF4D6BFE,
    val note: String = "",
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "debt")
data class Debt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: DebtType = DebtType.RECEIVABLE,
    val person: String,
    val amountCents: Long = 0,
    val settledCents: Long = 0,
    val date: Long = System.currentTimeMillis(),
    val dueDate: Long = 0,
    val note: String = "",
    val ledgerId: Long = 0,
    val accountId: Long = 0,
    val settled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "auto_rule")
data class AutoRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** 包名精确匹配，空 = 不限 */
    val pkg: String = "",
    /** 关键词，逗号分隔，任一命中即匹配；空 = 不限 */
    val keyword: String = "",
    /** 金额正则，第 1 个分组为金额；空 = 用内置解析 */
    val regex: String = "",
    val direction: TxnType = TxnType.EXPENSE,
    val categoryId: Long = 0,
    val accountId: Long = 0,
    val priority: Int = 0,
    val builtin: Boolean = false,
    val hitCount: Int = 0
)

@Entity(tableName = "capture", indices = [Index("time"), Index("status"), Index("dedupeKey")])
data class Capture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: TxnSource = TxnSource.NOTIFY,
    val pkg: String = "",
    val appName: String = "",
    val rawTitle: String = "",
    val rawText: String = "",
    val amountCents: Long = 0,
    val direction: TxnType = TxnType.EXPENSE,
    val merchant: String = "",
    val accountId: Long = 0,
    val categoryId: Long = 0,
    val status: CaptureStatus = CaptureStatus.PENDING,
    val txnId: Long = 0,
    val confidence: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val dedupeKey: String = "",
    /** 判定说明（"为什么抓/为什么没抓"），v2 新增；空串 = 老数据 */
    val reason: String = ""
)

@Entity(tableName = "merchant_rule")
data class MerchantRule(
    @PrimaryKey val merchant: String,
    val categoryId: Long,
    val accountId: Long = 0,
    val hits: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)
