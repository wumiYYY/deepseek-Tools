package com.jingyuniang.ledger.data

/**
 * 账户余额的唯一算法（v1.4.5）。
 *
 * 为什么要把这十几行单独拎出来：
 * 之前余额是散在 SQL（`balanceDelta`）里的，改一处逻辑就得同时改 SQL 和手算，
 * 于是「充值算成支出」「删账户后账目变孤儿」这类问题只能在真机上发现。
 * 现在算法只在这里，**余额的所有口径都以它为准**，并且可以直接写单测断言
 * 「这笔账到底让哪个账户动了多少」。
 *
 * 规则（和界面上的「账户资产」一致）：
 * - `INCOME`  且 `accountId == id`           → +amount
 * - `EXPENSE` 且 `accountId == id`           → -amount
 * - `TRANSFER` 且 `accountId == id`          → -amount（从该账户转出）
 * - `TRANSFER` 且 `toAccountId == id`        → +toAmountCents（转入该账户）
 * - 其余（含 `accountId == 0`、账户已被删除的历史账目）→ 不影响任何账户
 *
 * 两个刻意的取舍：
 * 1. **自己转给自己**（`accountId == toAccountId`，迁移账户后可能出现）按**净 0** 处理，
 *    不能只扣不进 —— 旧 SQL 的 `CASE` 会先命中第一个分支，导致凭空少钱。
 * 2. `余额调整`（source = ADJUST）**要**计入余额（它就是为了把余额校准到真实值），
 *    但统计口径会把它排除（见 `Repo` 里按 source 过滤的地方），两件事别混。
 */
object BalanceMath {

    /** 单个账户的余额变动（不含期初余额） */
    fun delta(txns: List<Txn>, accountId: Long): Long {
        if (accountId <= 0L) return 0L
        var sum = 0L
        txns.forEach { t ->
            when (t.type) {
                TxnType.INCOME -> if (t.accountId == accountId) sum += t.amountCents
                TxnType.EXPENSE -> if (t.accountId == accountId) sum -= t.amountCents
                TxnType.TRANSFER -> {
                    if (t.accountId == accountId) sum -= t.amountCents
                    // 自己转给自己：上面减了，这里加回来 → 净 0
                    if (t.toAccountId == accountId) {
                        sum += if (t.toAmountCents > 0) t.toAmountCents else t.amountCents
                    }
                }
            }
        }
        return sum
    }

    /** 每个账户的当前余额 = 期初余额 + 流水变动 */
    fun balances(txns: List<Txn>, accounts: List<Account>): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>(accounts.size)
        accounts.forEach { acc ->
            out[acc.id] = acc.initialBalanceCents + delta(txns, acc.id)
        }
        return out
    }

    /** 某个账户的当前余额（找不到该账户时返回 null，便于调用方区分「账户不存在」和「余额 0」） */
    fun balanceOf(txns: List<Txn>, accounts: List<Account>, accountId: Long): Long? {
        val acc = accounts.firstOrNull { it.id == accountId } ?: return null
        return acc.initialBalanceCents + delta(txns, accountId)
    }

    /** 有账目却找不到账户的笔数（账户被删后留下的孤儿，界面上应该提示用户） */
    fun orphanCount(txns: List<Txn>, accounts: List<Account>): Int {
        val ids = accounts.map { it.id }.toSet()
        return txns.count { t ->
            (t.accountId > 0 && t.accountId !in ids) ||
                (t.toAccountId > 0 && t.toAccountId !in ids)
        }
    }
}
