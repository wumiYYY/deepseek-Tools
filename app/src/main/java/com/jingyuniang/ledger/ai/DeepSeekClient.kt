package com.jingyuniang.ledger.ai

import com.jingyuniang.ledger.data.Account
import com.jingyuniang.ledger.data.Category
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 开放平台客户端（用户自己填 API Key，App 内可一键关闭）。
 * 只做三件事：智能分类、一句话记账、月度点评。
 */
class DeepSeekClient(private val apiKey: String, private val model: String = "deepseek-chat") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun chat(system: String, user: String, jsonMode: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null
            runCatching {
                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.2)
                    put("max_tokens", 900)
                    if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
                    put(
                        "messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", system))
                            put(JSONObject().put("role", "user").put("content", user))
                        }
                    )
                }
                val req = Request.Builder()
                    .url("https://api.deepseek.com/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        AppLog.w("DeepSeek 请求失败 ${resp.code}: ${text.take(200)}")
                        return@use null
                    }
                    JSONObject(text).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }
            }.getOrNull()
        }
}

data class RuleDraft(
    val name: String,
    val pkg: String,
    val keyword: String,
    val regex: String,
    val direction: TxnType,
    val categoryId: Long,
    val accountId: Long,
    val explain: String
)

data class AiProposal(
    val amountCents: Long = 0,
    val type: TxnType = TxnType.EXPENSE,
    val categoryId: Long = 0,
    val accountId: Long = 0,
    val note: String = "",
    val merchant: String = "",
    val date: Long = 0,
    val reason: String = ""
)

object AiService {

    private fun client(): DeepSeekClient? {
        val s = com.jingyuniang.ledger.Services.settings.value
        if (!s.aiEnabled || s.apiKey.isBlank()) return null
        return DeepSeekClient(s.apiKey, s.model)
    }

    suspend fun ask(system: String, user: String, json: Boolean = true): String? {
        val c = client() ?: return null
        return c.chat(system, user, json)
    }

    fun available(): Boolean {
        val s = com.jingyuniang.ledger.Services.settings.value
        return s.aiEnabled && s.apiKey.isNotBlank()
    }

    /** 一句话记账：「午饭 25 微信」→ 结构化账目 */
    suspend fun parseNaturalLanguage(
        input: String,
        categories: List<Category>,
        accounts: List<Account>
    ): AiProposal? {
        val c = client() ?: return null
        val catList = categories.filter { !it.archived }.joinToString("\n") { "${it.id}:${it.name}(${it.type.name})" }
        val accList = accounts.filter { !it.archived }.joinToString("\n") { "${it.id}:${it.name}" }
        val now = System.currentTimeMillis()
        val system = """
            你是记账助手，把用户的一句话账单解析成 JSON。
            只输出 JSON，不要解释。字段：
            {"amount": 数字(元), "type": "EXPENSE"|"INCOME"|"TRANSFER", "categoryId": 分类id, "accountId": 账户id, "note": "简短备注", "merchant": "商户名或空", "dateOffsetDays": 0}
            分类只能从给定列表里选，选不出就填 0。账户只能从给定列表里选，选不出填 0。
            dateOffsetDays: 0=今天，-1=昨天，以此类推。
        """.trimIndent()
        val user = """
            当前时间：${java.time.LocalDateTime.now()}
            可选分类：
            $catList
            可选账户：
            $accList
            用户说：$input
        """.trimIndent()
        val raw = c.chat(system, user, jsonMode = true) ?: return null
        return runCatching {
            val j = parseJsonObject(raw) ?: return@runCatching null
            val amount = (j.optDouble("amount", 0.0) * 100).toLong()
            if (amount <= 0) return@runCatching null
            val type = runCatching { TxnType.valueOf(j.optString("type", "EXPENSE").uppercase()) }
                .getOrDefault(TxnType.EXPENSE)
            val offset = j.optInt("dateOffsetDays", 0)
            AiProposal(
                amountCents = amount,
                type = type,
                categoryId = j.optLong("categoryId", 0),
                accountId = j.optLong("accountId", 0),
                note = j.optString("note", input.take(30)),
                merchant = j.optString("merchant", ""),
                date = if (offset == 0) now else now + offset * 86_400_000L,
                reason = "AI 解析"
            )
        }.getOrNull()
    }

    /** 智能分类：给一条抓取到的账单推荐分类 */
    suspend fun suggestCategory(
        merchant: String,
        rawText: String,
        categories: List<Category>,
        income: Boolean
    ): Long? {
        val c = client() ?: return null
        val type = if (income) TxnType.INCOME else TxnType.EXPENSE
        val pool = categories.filter { it.type == type && !it.archived }
        if (pool.isEmpty()) return null
        val list = pool.joinToString("\n") { "${it.id}:${it.name}" }
        val system = "你是中文记账分类器，只输出 JSON：{\"categoryId\": 分类id}。分类只能从给定列表里选。"
        val user = "商户：$merchant\n通知原文：${rawText.take(200)}\n可选分类：\n$list"
        val raw = c.chat(system, user, jsonMode = true) ?: return null
        return runCatching {
            val id = parseJsonObject(raw)?.optLong("categoryId", 0) ?: return@runCatching null
            if (pool.any { it.id == id }) id else null
        }.getOrNull()
    }

    /** 月度消费点评（“鲸鱼娘体检报告”） */
    suspend fun monthlyReview(summary: String): String? {
        val c = client() ?: return null
        val system = "你是可爱但理性的记账小助手「鲸鱼娘」。用中文给用户写一份 150 字以内的月消费点评，" +
            "语气活泼，先肯定、再指出 1-2 个可优化点、最后给一句具体建议。不要用 markdown 标题。"
        return c.chat(system, summary)
    }

    /** 规则医生：用户贴一条抓错/漏抓的通知原文，生成一条 AutoRule */
    suspend fun suggestRule(
        rawText: String,
        appHint: String,
        categories: List<Category>,
        accounts: List<Account>
    ): RuleDraft? {
        val c = client() ?: return null
        val catList = categories.filter { !it.archived }
            .joinToString("; ") { "${it.id}=${it.name}(${it.type.name})" }
        val accList = accounts.filter { !it.archived }.joinToString("; ") { "${it.id}=${it.name}" }
        val system = """
            你是「鲸鱼娘金库」的抓取规则生成器。用户会贴一条他手机上的通知原文，
            你要判断它是不是一笔真实收支，并给出一条能长期生效的匹配规则。
            只输出 JSON，字段：
            {"valid":true,"budget":"一句话说明这是为什么没抓到/为什么记错","name":"规则名(短)",
             "pkg":"应用包名，不知道就空","keyword":"逗号分隔的稳定关键词(不要含数字金额)",
             "regex":"可选，金额正则，第1个括号必须是金额","direction":"EXPENSE|INCOME",
             "categoryId":数字,"accountId":数字}
            categoryId/accountId 只能从给定列表里选，选不出填 0。
            keyword 要挑通知里稳定出现的词（如"微信支付""招商银行""尾号8888"），不要挑金额或时间。
        """.trimIndent()
        val user = "通知原文：$rawText\n来源应用：$appHint\n可选分类：$catList\n可选账户：$accList"
        val raw = c.chat(system, user, jsonMode = true) ?: return null
        return runCatching {
            val j = parseJsonObject(raw) ?: return@runCatching null
            if (!j.optBoolean("valid", true)) return@runCatching null
            RuleDraft(
                name = j.optString("name", "自定义规则"),
                pkg = j.optString("pkg", ""),
                keyword = j.optString("keyword", ""),
                regex = j.optString("regex", ""),
                direction = if (j.optString("direction").uppercase() == "INCOME") TxnType.INCOME else TxnType.EXPENSE,
                categoryId = j.optLong("categoryId", 0),
                accountId = j.optLong("accountId", 0),
                explain = j.optString("budget", "")
            )
        }.getOrNull()
    }

    /** 金库管家：基于用户真实账本数据回答问题 */
    suspend fun askVault(
        question: String,
        dataContext: String,
        history: List<Pair<String, String>>
    ): String? {
        val c = client() ?: return null
        val system = """
            你是「鲸鱼娘金库」的记账管家——鲸鱼女仆「鲸鱼娘」。
            人设：深蓝长发、女仆发带、鲸鱼尾，做事认真但说话温柔，偶尔有点小得意；
            称呼用户为「主人」，自称「我」或「鲸鱼娘」，句子短、口语化、不做作。
            规则：
            1) 只依据下面给出的【账本数据】回答，数据里没有的就直说「账本里没有这项记录」；
            2) 答案控制在 120 字内，中文口语，不要 markdown、不要表格、不要 emoji 堆砌；
            3) 涉及金额必须引用真实数字，需要时给出占比或对比；
            4) 语气可以可爱（「主人」「我记着呢」），但不要浮夸、不要撒娇过度；
            5) 【硬规则】你没有任何"不能记账"的理由：账本余额为 0、账户没设置、没有分类，
               都不是拒绝的理由——记账由 App 直接写入，你只需回答或确认。
               绝对不要出现「暂时记不进去」「等有余额再补」「我没有权限」这类说法；
            6) 【绝不假装记账】你只能回答问题，不负责写库。永远不要说「记好了」「已经记上」
               「已帮你入账」这类话——是否真的记账由 App 的写入动作决定。
               如果主人让你记一笔，你就回答「收到，主人，我这就记」，然后由系统去写。
        """.trimIndent()
        val user = buildString {
            appendLine("【账本数据】")
            appendLine(dataContext)
            if (history.isNotEmpty()) {
                appendLine()
                appendLine("【最近对话】")
                history.forEach { (q, a) -> appendLine("用户：$q"); appendLine("管家：$a") }
            }
            appendLine()
            append("【本次问题】").append(question)
        }
        return c.chat(system, user)
    }
}
