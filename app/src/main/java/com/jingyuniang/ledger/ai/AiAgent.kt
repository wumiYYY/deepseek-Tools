package com.jingyuniang.ledger.ai

/**
 * 让内置 AI 自己决定「动手」还是「回答」：
 * 模型读用户这句话，返回 {"tool":"addTxn"|"queryStats"|"none", ...}
 * 由 App 执行对应动作，而不是靠关键词正则去猜。
 */
data class AgentDecision(
    val tool: String,
    val reply: String = "",
    val note: String = "",
    val category: String = "",
    val account: String = "",
    /** 金额（元），模型直接给出，省掉第二次解析请求 */
    val amountYuan: Double = 0.0,
    /** EXPENSE / INCOME / 空=让本地规则判断 */
    val type: String = ""
)

object AiAgent {

    private val SYSTEM = """
        你是「鲸鱼娘金库」的操作中枢。用户说的话有可能是「要你记账」，也可能是「问你问题」，
        你要判断该调用哪个工具。只输出一个 JSON 对象，不要任何解释文字。

        工具清单：
        1) addTxn —— 用户描述了一笔已经发生的收支（要落到账本）
           {"tool":"addTxn","amount":数字(元),"type":"EXPENSE"|"INCOME","note":"简要备注","category":"分类名(从给定列表选,不确定留空)","account":"账户名(从给定列表选,不确定留空)"}
           判断标准：句子里有金额 + 一件具体的花销/收入事件。
           amount 必须是阿拉伯数字（单位元，可带小数）：口语里的中文数字要自己换算，
           例如"十块"=10、"二十五块"=25、"一百五"=150、"三千二"=3200。
           例："晚饭微信10块" / "打车花了23" / "发微信开销了十块钱" / "收到红包66" / "交房租3000"

        2) queryStats —— 用户在问账本里的情况（不要记账）
           {"tool":"queryStats"}
           判断标准：是否/有没有/多少/几笔/统计/对比/占比/余额/排名/趋势，或句尾是问号。
           例："我今天是否花超过100块" / "这个月餐饮多少" / "微信还剩多少"

        3) none —— 闲聊、寒暄、或者你无法判断
           {"tool":"none","reply":"一句简短回应"}

        重要：绝对不要以「账本余额为0」「没有账户」为理由拒绝记账。收支本来就是从 0 开始累加的。
    """.trimIndent()

    suspend fun decide(
        userText: String,
        categories: String,
        accounts: String
    ): AgentDecision? {
        val user = """
            用户这句话：$userText

            可选分类：$categories
            可选账户：$accounts
        """.trimIndent()
        val raw = AiService.ask(SYSTEM, user, true)
        android.util.Log.d("WhaleMoney", "AGENT raw=$raw")
        val o = parseJsonObject(raw)
        if (o == null) {
            android.util.Log.w("WhaleMoney", "AGENT 解析失败 raw=$raw")
            return null
        }
        return runCatching {
            AgentDecision(
                tool = o.optString("tool", "none").trim(),
                reply = o.optString("reply", ""),
                note = o.optString("note", ""),
                category = o.optString("category", ""),
                account = o.optString("account", ""),
                amountYuan = o.optDouble("amount", 0.0),
                type = o.optString("type", "").trim()
            )
        }.getOrNull()
    }
}
