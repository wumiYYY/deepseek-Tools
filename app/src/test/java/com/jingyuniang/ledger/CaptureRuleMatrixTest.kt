package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.capture.TxnIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取规则矩阵（定版用例集）。
 *
 * 每条用例 = 一个真实会遇到的场景，断言三件事：
 * 1. `shouldRead` —— 这道闸门放不放行
 * 2. `extractAmount` —— 抠出来的金额（分）；0 表示抠不到
 * 3. 期望归类 —— EXPENSE / INCOME / TRANSFER / DROP
 *
 * 改任何抓取规则前先看这份矩阵；改完必须让它全绿，否则就是回归。
 * 规则说明见 `docs/CAPTURE_RULES_V1.md`。
 */
class CaptureRuleMatrixTest {

    private enum class Expect { EXPENSE, INCOME, TRANSFER, DROP }

    private data class Case(
        val name: String,
        val pkg: String,
        val title: String,
        val text: String,
        val expect: Expect,
        val amountCents: Long = -1,   // -1 = 不校验金额
        val autoBookHint: Boolean? = null  // null = 不校验
    )

    private val cases = listOf(
        // ================= 一、微信 / 支付宝（支付通道，最常出现） =================
        Case(
            "微信支付·已支付", "com.tencent.mm", "微信支付",
            "支付成功，已支付¥25.00元", Expect.EXPENSE, 2500, true
        ),
        Case(
            "微信支付·已扣费（聚合通知）", "com.tencent.mm", "微信支付",
            "[12条]微信支付: 已扣费¥2.00", Expect.EXPENSE, 200, true
        ),
        Case(
            "微信·收款到账", "com.tencent.mm", "微信支付",
            "微信支付收款到账 88.00 元", Expect.INCOME, 8800, true
        ),
        Case(
            "微信红包（只有标题无金额）", "com.tencent.mm", "老婆",
            "[微信红包] 恭喜发财，大吉大利", Expect.DROP, 0
        ),
        Case(
            "微信转账卡片", "com.tencent.mm", "老张",
            "[转账]100.00元 请收款", Expect.INCOME, 10000
        ),
        Case(
            "微信群·别人转发银行短信", "com.tencent.mm", "家人群",
            "老张: 您尾号8888账户于今日消费人民币48.00元，余额1,234.56元", Expect.DROP
        ),
        Case(
            "微信私聊·提到转账", "com.tencent.mm", "小李",
            "我转你 100 元了，收到没", Expect.DROP
        ),
        Case(
            "支付宝·付款成功", "com.eg.android.AlipayGphone", "支付宝",
            "你已成功付款 12.50 元，商户：美团外卖", Expect.EXPENSE, 1250, true
        ),
        Case(
            "支付宝·红包营销（不是动账）", "com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔10.00元的支出，领2元小荷包支付红包", Expect.DROP
        ),

        // ================= 二、银行短信（最可靠来源） =================
        Case(
            "招行短信·消费", "com.android.mms", "95555",
            "【招商银行】您账户尾号8888于10月26日12:30消费人民币48.00元，余额1,234.56元",
            Expect.EXPENSE, 4800, true
        ),
        Case(
            "工行短信·收入", "com.android.mms", "95588",
            "【工商银行】您尾号1234账户于10月26日工资入账人民币8,000.00元",
            Expect.INCOME, 800000, true
        ),
        Case(
            "建行短信·退款", "com.android.mms", "95533",
            "【建设银行】您尾号5678账户退款到账 30.00 元", Expect.INCOME, 3000, true
        ),
        Case(
            "银行短信·贷款广告（带金额）", "com.android.mms", "106835742600999",
            "【工商银行】您在我行可办理328000元综合贷款，点击了解", Expect.DROP
        ),
        Case(
            "验证码短信", "com.android.mms", "95555",
            "验证码 123456，您正在支付 100.00 元，请勿泄露", Expect.DROP
        ),

        // ================= 三、银行 App 推送 =================
        Case(
            "招行推送·快捷支付扣款（有账户）", "cmb.pb", "招商银行",
            "您账户3979于10月26日11:49在【财付通-微信支付-优之膳】发生快捷支付扣款，人民币10.00元",
            Expect.EXPENSE, 1000, true
        ),
        Case(
            "招行推送·本期账单（非动账）", "cmb.pb", "招商银行",
            "您信用卡本期账单金额 1,234.56 元，最后还款日 11月5日", Expect.DROP
        ),
        Case(
            "招行推送·额度提升（非动账）", "cmb.pb", "招商银行",
            "您尾号8888信用卡额度已提升至 50000 元", Expect.DROP
        ),
        Case(
            "招行推送·信用卡还款（资金转移）", "cmb.pb", "招商银行",
            "您尾号8888信用卡还款成功，金额 1000.00 元", Expect.TRANSFER, 100000
        ),
        Case(
            "招行推送·账单分期（重复计风险）", "cmb.pb", "招商银行",
            "您的账单分期成功，本期应还 500.00 元", Expect.DROP
        ),
        Case(
            "银行推送·营销渠道（按渠道拦）", "cmb.pb", "招商银行",
            "消费满100元立减20元，速来领取", Expect.DROP
        ),

        // ================= 四、电商 / 本地生活（只认强支付模板） =================
        Case(
            "淘宝·商品推荐（规格数字）", "com.taobao.taobao", "淘宝",
            "中国地质大学欧泊吊坠胸针 925银精工镶嵌，双面佩戴", Expect.DROP, 0
        ),
        Case(
            "淘宝·直播带货", "com.taobao.taobao", "淘宝",
            "直播间 9.9元包邮！爆款直降", Expect.DROP
        ),
        Case(
            "京东·订单支付成功", "com.jingdong.app.mall", "京东",
            "订单支付成功，实付 199.00 元", Expect.EXPENSE, 19900, true
        ),
        Case(
            "拼多多·百亿补贴", "com.xunmeng.pinduoduo", "拼多多",
            "百亿补贴 限时立减 50 元", Expect.DROP
        ),
        Case(
            "美团·订单已支付", "com.sankuai.meituan", "美团",
            "支付成功 ¥36.50 订单号 12345678", Expect.EXPENSE, 3650, true
        ),

        // ================= 五、内容 / 短视频（默认不抓） =================
        Case(
            "抖音·刷视频推送", "com.ss.android.ugc.aweme", "抖音",
            "9.9元包邮！爆款直降", Expect.DROP
        ),
        Case(
            "抖音·未读消息", "com.ss.android.ugc.aweme", "未读消息提醒",
            "[1条@·4条私信] 快来看看", Expect.DROP, 0
        ),
        Case(
            "抖音·支付成功（真买东西）", "com.ss.android.ugc.aweme", "抖音支付",
            "抖音支付 支付成功 ¥12.90", Expect.EXPENSE, 1290, true
        ),
        Case(
            "快手·直播间优惠", "com.smile.gifmaker", "快手",
            "直播间3元券限时领", Expect.DROP
        ),
        Case(
            "小红书·好物推荐", "com.xingin.xhs", "小红书",
            "好物推荐 39元起，收藏有礼", Expect.DROP
        ),

        // ================= 六、干扰项 =================
        Case(
            "账单列表页（一屏多笔）", "com.eg.android.AlipayGphone", "账单",
            "支付 25.00 元 支付 38.50 元 支付 12.00 元 支付 9.90 元", Expect.DROP
        ),
        Case(
            "积分/权益提醒", "cmb.pb", "招商银行",
            "您有 5000 积分即将过期，可兑换 20 元券", Expect.DROP
        ),
        Case(
            "理财收益", "com.eg.android.AlipayGphone", "支付宝",
            "余额宝昨日收益 1.23 元", Expect.DROP
        ),
        Case(
            "话费充值成功", "com.eg.android.AlipayGphone", "支付宝",
            "充值成功，实付 50.00 元，商户：中国移动", Expect.EXPENSE, 5000, true
        ),
        Case(
            "微信·零钱提现到银行卡", "com.tencent.mm", "微信支付",
            "零钱提现已到账 500.00 元 尾号8888", Expect.TRANSFER, 50000
        ),
        Case(
            "云闪付·扫码支付", "com.unionpay", "云闪付",
            "云闪付支付成功 ¥18.80 商户：全家便利店", Expect.EXPENSE, 1880, true
        ),
        Case(
            "银行短信·大额消费（四位数金额）", "com.android.mms", "95555",
            "【招商银行】您尾号8888账户消费人民币1000.00元，余额50000.00元", Expect.EXPENSE, 100000, true
        )
    )

    @Test
    fun `规则矩阵全部符合预期`() {
        val failures = ArrayList<String>()
        cases.forEach { c ->
            val should = CaptureFilters.shouldRead(c.pkg, c.text, c.title)
            val amount = ParseEngine.extractAmount(c.text)
            val isRepayment = CaptureFilters.isFundTransfer(c.text)

            val actual = when {
                // 放行但抠不出金额时，流水线里 ParseEngine.parse 会返回 null → 最终仍是丢弃
                !should || amount <= 0 -> Expect.DROP
                isRepayment -> Expect.TRANSFER
                else -> {
                    val dir = ParseEngine.detectDirection(c.text)
                    if (dir == com.jingyuniang.ledger.data.TxnType.INCOME) Expect.INCOME else Expect.EXPENSE
                }
            }

            if (actual != c.expect) {
                failures.add("【${c.name}】期望 ${c.expect}，实际 ${actual}（shouldRead=$should isRepayment=$isRepayment）")
            }
            if (c.amountCents >= 0 && amount != c.amountCents) {
                failures.add("【${c.name}】金额期望 ${c.amountCents}，实际 $amount")
            }
            if (c.autoBookHint != null) {
                val eff = com.jingyuniang.ledger.capture.CapturePipeline.effectiveConfidence(
                    com.jingyuniang.ledger.data.TxnSource.NOTIFY, c.pkg, c.title, c.text, 90
                )
                val auto = eff >= 80
                if (auto != c.autoBookHint) {
                    failures.add("【${c.name}】自动入账期望 ${c.autoBookHint}，实际 $auto（置信度 $eff）")
                }
            }
        }
        assertTrue("规则矩阵有 ${failures.size} 条不符：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `用例矩阵至少覆盖 35 个场景`() {
        assertTrue("用例太少：${cases.size}", cases.size >= 35)
    }

    // ================= 交易身份（去重） =================
    @Test
    fun `同一笔被两个渠道各报一次时交易身份相同`() {
        // 招行短信与招行推送，卡尾号 + 金额相同 → 同一身份（同 5 分钟桶）
        val t = 1_700_000_000_000L
        val a = TxnIdentity.of(
            "com.android.mms",
            "【招商银行】您尾号8888于10月26日消费人民币48.00元", 4800, t
        )
        val b = TxnIdentity.of(
            "cmb.pb",
            "您账户8888于10月26日消费人民币48.00元，余额1,234.56元", 4800, t + 30_000
        )
        assertEquals(a, b)
        assertEquals("card:8888|4800|${TxnIdentity.bucket(t)}", a)
    }

    @Test
    fun `同一渠道连买两笔同金额不会被误判为重复`() {
        val t = 1_700_000_000_000L
        val first = TxnIdentity.of("com.tencent.mm", "支付成功 已支付¥25.00", 2500, t)
        // 6 分钟后的第二杯：时间桶不同 → 不同身份
        val second = TxnIdentity.of("com.tencent.mm", "支付成功 已支付¥25.00", 2500, t + 6 * 60_000L)
        assertNotEquals(first, second)
    }

    @Test
    fun `有参考号时优先用参考号`() {
        val t = 1_700_000_000_000L
        val id = TxnIdentity.of("cmb.pb", "交易单号: 202610260001 消费 48.00 元", 4800, t)
        assertEquals("ref:202610260001", id)
        // 参考号相同即同一笔，与时间无关
        val later = TxnIdentity.of("cmb.pb", "交易单号: 202610260001 消费 48.00 元", 4800, t + 3_600_000)
        assertEquals(id, later)
    }

    @Test
    fun `没有卡尾号时退化成包名加金额加时间桶`() {
        val t = 1_700_000_000_000L
        val id = TxnIdentity.of("com.eg.android.AlipayGphone", "你已成功付款 12.50 元", 1250, t)
        assertEquals("app:com.eg.android.AlipayGphone|1250|${TxnIdentity.bucket(t)}", id)
    }

    @Test
    fun `卡尾号提取覆盖常见写法`() {
        assertEquals("8888", TxnIdentity.cardTail("您尾号8888的账户支出"))
        assertEquals("3979", TxnIdentity.cardTail("您账户3979于10月26日发生扣款"))
        assertEquals("6666", TxnIdentity.cardTail("卡号 ****6666 消费"))
        assertEquals("", TxnIdentity.cardTail("你已成功付款 12.50 元"))
    }
}
