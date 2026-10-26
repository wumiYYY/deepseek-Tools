package com.jingyuniang.ledger

import com.jingyuniang.ledger.capture.CaptureFilters
import com.jingyuniang.ledger.capture.ParseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景扩展集（第二轮，80 条）——覆盖**真实银行短信模板 / 微信支付宝各种收付款形态 / 银行 App 推送 /
 * 电商内容 App / 资金转移 / 各类干扰**。
 *
 * 与前一份 `CaptureRuleMatrixTest` 互补：那份是「主路径」，这份是「长尾」。
 * 银行业务模板参考公开的各行动账短信格式；排除类型参考开源实现 `bank-sms-parser` 的
 * `parser_config.json`（DECLINED / FUTURE_AUTO_DEBIT / BALANCE_ALERT / EMI / CARD_PAYMENT…）。
 */
class CaptureScenariosExtraTest {

    private enum class Expect { EXPENSE, INCOME, TRANSFER, DROP }

    private data class Case(
        val name: String,
        val pkg: String,
        val title: String,
        val text: String,
        val expect: Expect,
        val amountCents: Long = -1
    )

    private val cases = listOf(
        // ========== A. 银行短信（公开的真实模板） ==========
        Case("农行·转出", "com.android.mms", "95599",
            "【中国农业银行】尊敬的客户：您的农行账户（尾号1234）于10月26日12:00完成转出交易，金额为500.00，余额为8000.00",
            Expect.EXPENSE, 50000),
        Case("工行·收入(CFT)", "com.android.mms", "95588",
            "您尾号1234卡10月26日12:00工商银行收入（CFT）100.00元，余额5000.00元",
            Expect.INCOME, 10000),
        Case("招行·贷款到账", "com.android.mms", "95555",
            "您尾号为6666的招商银行账户于10月26日收到贷款金额人民币50000.00元，账户余额为50000.00元",
            Expect.INCOME, 5000000),
        Case("中行·他人转账", "com.android.mms", "95566",
            "尊敬的客户，张三向您转账人民币200.00元，请在5日内查收",
            Expect.INCOME, 20000),
        Case("建行·退款到账", "com.android.mms", "95533",
            "您尾号5678账户退款到账 30.00 元", Expect.INCOME, 3000),
        Case("中信·信用卡消费", "com.android.mms", "95558",
            "您尾号8888信用卡消费人民币1,280.00元", Expect.EXPENSE, 128000),
        Case("交行·ATM取现", "com.android.mms", "95559",
            "您尾号9999账户ATM取现500.00元，余额1,000.00元", Expect.TRANSFER, 50000),
        Case("邮储·年费扣收", "com.android.mms", "95580",
            "您的账户收取年费10.00元，余额2,000.00元", Expect.EXPENSE, 1000),
        Case("招行·余额提醒（不是交易）", "com.android.mms", "95555",
            "您尾号8888账户可用余额为1,234.56元", Expect.DROP),
        Case("工行·未来自动扣款", "com.android.mms", "95588",
            "您尾号1234账户将于11月1日自动扣款200.00元，请保持余额充足", Expect.DROP),
        Case("招行·验证码", "com.android.mms", "95555",
            "您的验证码123456，您正在支付100.00元，请勿泄露", Expect.DROP),
        Case("银行·理财申购", "cmb.pb", "招商银行",
            "您尾号1234账户申购基金1,000.00元，余额5,000.00元", Expect.TRANSFER, 100000),

        // ========== B. 微信（各种收付款形态） ==========
        Case("微信·已支付", "com.tencent.mm", "微信支付",
            "支付成功，已支付¥25.00元", Expect.EXPENSE, 2500),
        Case("微信·已扣费(聚合)", "com.tencent.mm", "微信支付",
            "[12条]微信支付: 已扣费¥2.00", Expect.EXPENSE, 200),
        Case("微信·收款到账", "com.tencent.mm", "微信支付",
            "微信支付收款到账 88.00 元", Expect.INCOME, 8800),
        Case("微信·亲属卡消费", "com.tencent.mm", "微信支付",
            "亲属卡消费 25.00 元", Expect.EXPENSE, 2500),
        Case("微信·红包退回零钱", "com.tencent.mm", "微信支付",
            "红包已退回零钱 8.88 元", Expect.INCOME, 888),
        Case("微信·转账已被接收", "com.tencent.mm", "老张",
            "老张已接收你的转账 100.00 元", Expect.EXPENSE, 10000),
        Case("微信·零钱提现到卡", "com.tencent.mm", "微信支付",
            "零钱提现已到账 500.00 元 尾号8888", Expect.TRANSFER, 50000),
        Case("微信·赞赏码", "com.tencent.mm", "微信支付",
            "收到赞赏 5.00 元", Expect.INCOME, 500),
        Case("微信·乘车码扣费", "com.tencent.mm", "微信支付",
            "乘车码扣费 2.00 元", Expect.EXPENSE, 200),
        Case("微信·免密支付", "com.tencent.mm", "微信支付",
            "免密支付扣款 15.00 元，商户：罗森便利店", Expect.EXPENSE, 1500),
        Case("微信·群收款发起", "com.tencent.mm", "群收款",
            "张三发起群收款 每人25.00元", Expect.DROP),
        Case("微信·红包封面营销", "com.tencent.mm", "微信支付",
            "红包封面限时免费领，1元起", Expect.DROP),
        Case("微信·服务通知还款提醒", "com.tencent.mm", "微信支付",
            "信用卡还款提醒：本期应还 1,000.00 元", Expect.DROP),
        Case("微信·群消息银行转发", "com.tencent.mm", "家人群",
            "老张: 您尾号8888账户于今日消费人民币48.00元，余额1,234.56元", Expect.DROP),
        Case("微信·私聊提到转账", "com.tencent.mm", "小李",
            "我转你 100 元了，收到没", Expect.DROP),
        Case("微信·转账卡片", "com.tencent.mm", "老张",
            "[转账]100.00元 请收款", Expect.INCOME, 10000),

        // ========== C. 支付宝 ==========
        Case("支付宝·付款成功", "com.eg.android.AlipayGphone", "支付宝",
            "你已成功付款 12.50 元，商户：美团外卖", Expect.EXPENSE, 1250),
        Case("支付宝·收款到账", "com.eg.android.AlipayGphone", "支付宝",
            "支付宝收款到账 66.00 元", Expect.INCOME, 6600),
        Case("支付宝·花呗还款", "com.eg.android.AlipayGphone", "支付宝",
            "花呗还款成功 500.00 元", Expect.TRANSFER, 50000),
        Case("支付宝·花呗消费", "com.eg.android.AlipayGphone", "支付宝",
            "花呗消费 88.00 元，商户：肯德基", Expect.EXPENSE, 8800),
        Case("支付宝·借呗到账", "com.eg.android.AlipayGphone", "支付宝",
            "借呗借款到账 5,000.00 元", Expect.INCOME, 500000),
        Case("支付宝·转入余额宝", "com.eg.android.AlipayGphone", "支付宝",
            "转入余额宝 1,000.00 元", Expect.TRANSFER, 100000),
        Case("支付宝·余额宝收益", "com.eg.android.AlipayGphone", "支付宝",
            "余额宝昨日收益 1.23 元", Expect.DROP),
        Case("支付宝·退款到账", "com.eg.android.AlipayGphone", "支付宝",
            "退款到账 30.00 元", Expect.INCOME, 3000),
        Case("支付宝·缴费成功", "com.eg.android.AlipayGphone", "支付宝",
            "缴费成功，实付 50.00 元，商户：中国移动", Expect.EXPENSE, 5000),
        Case("支付宝·待缴费提醒", "com.eg.android.AlipayGphone", "支付宝",
            "您有一笔水电费待缴费 120.00 元", Expect.DROP),
        Case("支付宝·小荷包红包", "com.eg.android.AlipayGphone", "交易提醒",
            "你有一笔10.00元的支出，领2元小荷包支付红包", Expect.DROP),
        Case("支付宝·账单列表页", "com.eg.android.AlipayGphone", "账单",
            "支付 25.00 元 支付 38.50 元 支付 12.00 元", Expect.DROP),

        // ========== D. 银行 App 推送 ==========
        Case("招行·快捷支付扣款", "cmb.pb", "招商银行",
            "您账户3979于10月26日11:49在【财付通-微信支付-优之膳】发生快捷支付扣款，人民币10.00元",
            Expect.EXPENSE, 1000),
        Case("招行·本期账单", "cmb.pb", "招商银行",
            "您信用卡本期账单金额 1,234.56 元，最后还款日 11月5日", Expect.DROP),
        Case("招行·额度提升", "cmb.pb", "招商银行",
            "您尾号8888信用卡额度已提升至 50,000 元", Expect.DROP),
        Case("招行·还款成功", "cmb.pb", "招商银行",
            "您尾号8888信用卡还款成功，金额 1,000.00 元", Expect.TRANSFER, 100000),
        Case("招行·账单分期", "cmb.pb", "招商银行",
            "您的账单分期成功，本期应还 500.00 元", Expect.DROP),
        Case("招行·自动扣款预告", "cmb.pb", "招商银行",
            "您的信用卡将于11月5日自动扣款 1,000.00 元", Expect.DROP),
        Case("招行·余额提醒", "cmb.pb", "招商银行",
            "您尾号8888账户余额提醒：可用余额 1,234.56 元", Expect.DROP),
        Case("工行·消费推送", "com.icbc", "工商银行",
            "您尾号1234账户支出 66.00 元，余额 5,000.00 元", Expect.EXPENSE, 6600),
        Case("建行·入账推送", "com.chinamworld.main", "建设银行",
            "您尾号5678账户入账 2,000.00 元，余额 8,000.00 元", Expect.INCOME, 200000),
        Case("平安·保费扣款", "com.pingan.paces.ccms", "平安银行",
            "您投保的保险保费 300.00 元已扣款，保单号 PA123456", Expect.EXPENSE, 30000),
        Case("中信·信用卡年费", "com.ecitic.bank.mobile", "中信银行",
            "扣除您尾号8888信用卡年费 200.00 元", Expect.EXPENSE, 20000),
        Case("银行·ATM取现推送", "cmb.pb", "招商银行",
            "您尾号8888账户ATM取款 500.00 元，余额 1,000.00 元", Expect.TRANSFER, 50000),

        // ========== E. 电商 / 本地生活 / 内容 ==========
        Case("淘宝·商品推荐", "com.taobao.taobao", "淘宝",
            "中国地质大学欧泊吊坠胸针 925银精工镶嵌，双面佩戴", Expect.DROP),
        Case("淘宝·直播带货", "com.taobao.taobao", "淘宝",
            "直播间 9.9元包邮！爆款直降", Expect.DROP),
        Case("京东·订单支付成功", "com.jingdong.app.mall", "京东",
            "订单支付成功，实付 199.00 元", Expect.EXPENSE, 19900),
        Case("京东·白条还款", "com.jd.jrapp", "京东金融",
            "白条还款成功 300.00 元", Expect.TRANSFER, 30000),
        Case("拼多多·百亿补贴", "com.xunmeng.pinduoduo", "拼多多",
            "百亿补贴 限时立减 50 元", Expect.DROP),
        Case("美团·支付成功", "com.sankuai.meituan", "美团",
            "支付成功 ¥36.50 订单号 12345678", Expect.EXPENSE, 3650),
        Case("饿了么·订单已支付", "me.ele", "饿了么",
            "订单支付成功，实付 28.00 元", Expect.EXPENSE, 2800),
        Case("滴滴·行程支付", "com.sdu.didi.psnger", "滴滴出行",
            "支付成功 ¥23.50，行程已结束", Expect.EXPENSE, 2350),
        Case("抖音·刷视频推送", "com.ss.android.ugc.aweme", "抖音",
            "9.9元包邮！爆款直降", Expect.DROP),
        Case("抖音·支付成功", "com.ss.android.ugc.aweme", "抖音支付",
            "抖音支付 支付成功 ¥12.90", Expect.EXPENSE, 1290),
        Case("快手·直播券", "com.smile.gifmaker", "快手",
            "直播间3元券限时领", Expect.DROP),
        Case("小红书·好物推荐", "com.xingin.xhs", "小红书",
            "好物推荐 39元起，收藏有礼", Expect.DROP),

        // ========== F. 干扰项 ==========
        Case("积分过期", "cmb.pb", "招商银行",
            "您有 5000 积分即将过期，可兑换 20 元券", Expect.DROP),
        Case("物流签收", "com.taobao.taobao", "淘宝",
            "您的包裹已签收，快递单号 SF123456", Expect.DROP),
        Case("中奖通知", "com.android.mms", "1069000000",
            "恭喜您中奖 888.00 元，请点击领取", Expect.DROP),
        Case("邀请好友", "cmb.pb", "招商银行",
            "邀请好友办卡，双方各得 50 元奖励", Expect.DROP),
        Case("会员自动续费提醒", "com.eg.android.AlipayGphone", "支付宝",
            "您的会员将于3天后自动续费 15.00 元", Expect.DROP),
        Case("工资入账短信", "com.android.mms", "95588",
            "【工商银行】您尾号1234账户工资入账人民币8,000.00元，余额12,000.00元",
            Expect.INCOME, 800000),
        Case("报销到账", "com.eg.android.AlipayGphone", "支付宝",
            "报销到账 260.00 元", Expect.INCOME, 26000),
        Case("话费充值成功", "com.eg.android.AlipayGphone", "支付宝",
            "充值成功，实付 100.00 元，商户：中国联通", Expect.EXPENSE, 10000),

        // ========== G. 资金转移（净资产不变） ==========
        Case("信用卡还款", "cmb.pb", "招商银行",
            "您尾号8888信用卡还款成功，金额 1,000.00 元", Expect.TRANSFER, 100000),
        Case("零钱提现到卡", "com.tencent.mm", "微信支付",
            "零钱提现到银行卡 500.00 元", Expect.TRANSFER, 50000),
        Case("ATM取现", "cmb.pb", "招商银行",
            "您尾号8888账户取现 500.00 元", Expect.TRANSFER, 50000),
        Case("理财申购", "cmb.pb", "招商银行",
            "您尾号1234账户申购基金 1,000.00 元", Expect.TRANSFER, 100000),
        Case("基金赎回", "cmb.pb", "招商银行",
            "您尾号1234账户基金赎回 2,000.00 元，已到账", Expect.TRANSFER, 200000),
        Case("转到银行卡", "com.eg.android.AlipayGphone", "支付宝",
            "向银行卡转账 300.00 元，尾号8888", Expect.TRANSFER, 30000),
        Case("云闪付·扫码支付", "com.unionpay", "云闪付",
            "云闪付支付成功 ¥18.80 商户：全家便利店", Expect.EXPENSE, 1880),
        Case("数字人民币·消费", "com.ecny.noemie.pay", "数字人民币",
            "数字人民币支付成功 12.00 元，商户：地铁", Expect.EXPENSE, 1200)
    )

    @Test
    fun `扩展场景全部符合预期`() {
        val failures = ArrayList<String>()
        cases.forEach { c ->
            val should = CaptureFilters.shouldRead(c.pkg, c.text, c.title)
            val amount = ParseEngine.extractAmount(c.text)
            val isTransfer = CaptureFilters.isFundTransfer(c.text)
            val actual = when {
                !should || amount <= 0 -> Expect.DROP
                isTransfer -> Expect.TRANSFER
                else -> if (ParseEngine.detectDirection(c.text) == com.jingyuniang.ledger.data.TxnType.INCOME)
                    Expect.INCOME else Expect.EXPENSE
            }
            if (actual != c.expect) {
                failures.add(
                    "【${c.name}】期望 ${c.expect}，实际 $actual " +
                        "(shouldRead=$should amount=$amount transfer=$isTransfer)"
                )
            }
            if (c.amountCents >= 0 && amount != c.amountCents) {
                failures.add("【${c.name}】金额期望 ${c.amountCents}，实际 $amount")
            }
        }
        assertTrue("扩展场景有 ${failures.size}/${cases.size} 条不符：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `扩展场景数量不少于 80`() {
        assertTrue("只有 ${cases.size} 条", cases.size >= 80)
    }
    // ================= 多渠道同一笔：渠道识别 =================
    @Test
    fun `识别银行通知属于哪个第三方支付渠道`() {
        assertEquals("wechat", CaptureFilters.payChannelOf("财付通-微信支付-瑞幸咖啡"))
        assertEquals("wechat", CaptureFilters.payChannelOf("微信支付扣款 25.00"))
        assertEquals("alipay", CaptureFilters.payChannelOf("支付宝-美团外卖"))
        assertEquals("unionpay", CaptureFilters.payChannelOf("云闪付扫码支付"))
        assertEquals("jd", CaptureFilters.payChannelOf("京东支付"))
        assertEquals(null, CaptureFilters.payChannelOf("您账户3979在沃尔玛消费 48.00 元"))
        // 渠道 → App 包名
        assertEquals("com.tencent.mm", CaptureFilters.payChannelPackage("wechat"))
        assertEquals("com.eg.android.AlipayGphone", CaptureFilters.payChannelPackage("alipay"))
        assertEquals(null, CaptureFilters.payChannelPackage("unknown"))
    }

    @Test
    fun `微信付款扣银行卡的两种通知都能识别`() {
        // 微信侧：只有金额，没有渠道字样
        assertTrue(CaptureFilters.shouldRead("com.tencent.mm", "支付成功，已支付¥25.00元", "微信支付"))
        // 银行侧：写明财付通 → 可判定是微信那笔
        val bankText = "您账户3979于10月26日14:05在【财付通-微信支付-瑞幸咖啡】发生快捷支付扣款，人民币25.00元"
        assertTrue(CaptureFilters.shouldRead("cmb.pb", bankText, "招商银行"))
        assertEquals("wechat", CaptureFilters.payChannelOf(bankText))
        // 纯线下刷卡（无第三方渠道字样）→ 不会被误判成微信那笔
        val offline = "您账户3979于10月26日14:05在【沃尔玛】消费人民币25.00元"
        assertEquals(null, CaptureFilters.payChannelOf(offline))
    }
}
