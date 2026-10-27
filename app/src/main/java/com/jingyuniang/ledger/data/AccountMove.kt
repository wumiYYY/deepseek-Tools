package com.jingyuniang.ledger.data

/**
 * 删除账户前把账目「改挂」到另一个账户（v1.4.5）。
 *
 * 背景：账户被删（archived）后，它的账目仍留在库里、却不再被任何账户统计到 ——
 * 用户看到的就是「删了个账户，余额凭空变了」，2026-10-27 真机上发生过一次。
 *
 * 这里只做**纯数据搬运**，方便直接断言：
 * - `accountId == fromId` → 改成 `toId`（转出方/收支所属账户）
 * - `toAccountId == fromId` → 改成 `toId`（转入方）
 * - 搬完可能变成「自己转给自己」→ 保留双边字段，[BalanceMath] 对它按净 0 处理，
 *   所以钱不会凭空多/少（旧 SQL 的 CASE 会先命中转出分支，反而少一笔）。
 */
object AccountMove {

    /** @return 真正需要写回的账目（没变化的不会出现在结果里） */
    fun remap(txns: List<Txn>, fromId: Long, toId: Long): List<Txn> {
        if (fromId <= 0L || toId <= 0L || fromId == toId) return emptyList()
        return txns.mapNotNull { t ->
            var n = t
            if (n.accountId == fromId) n = n.copy(accountId = toId)
            if (n.toAccountId == fromId) n = n.copy(toAccountId = toId)
            if (n != t) n else null
        }
    }

    /** 该账户名下的账目（含作为转入方出现的），用于「删除前提示有多少笔」 */
    fun attachedTo(txns: List<Txn>, accountId: Long): List<Txn> =
        txns.filter { it.accountId == accountId || it.toAccountId == accountId }
}
