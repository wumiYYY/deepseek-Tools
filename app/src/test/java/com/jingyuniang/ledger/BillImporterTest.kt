package com.jingyuniang.ledger

import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.io.BillImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信 / 支付宝账单导入的纯 JVM 单测：
 * 覆盖表头定位、GBK 编码识别、千分位、收入/支出判定、「不计收支」与页脚跳过。
 */
class BillImporterTest {

    private val wechatCsv = """
        微信支付账单明细,,,,,,,,,,
        微信昵称：[张三],,,,,,,,,,
        起始时间：[2024-09-01 00:00:00] 终止时间：[2024-09-30 23:59:59],,,,,,,,,,
        导出类型：[全部],,,,,,,,,,
        导出时间：[2024-10-01 10:00:00],,,,,,,,,,
        ,,,,,,,,,,
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2024-09-30 12:30:00,商户消费,星巴克咖啡,拿铁,支出,¥35.00,零钱,支付成功,4200001,10001,/
        2024-09-30 18:00:00,转账,张三,转账,收入,¥100.00,零钱,已存入零钱,4200002,10002,/
        2024-09-29 19:00:00,商户消费,美团,外卖订单,支出,¥1,234.56,招商银行(8888),支付成功,4200003,10003,/
        ----------------------以上是全部账单---------------------
    """.trimIndent()

    private val alipayCsv = """
        支付宝交易记录明细查询
        账号:[zhang@example.com]
        起始日期:[2024-09-01 00:00:00]    终止日期:[2024-09-30 23:59:59]
        ---------------------------------交易记录明细列表------------------------------------
        交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
        2024-09-29 08:12:00,餐饮美食,星巴克,咖啡,支出,28.00,余额宝,交易成功,20240929001,,
        2024-09-28 20:00:00,转账红包,李四,转账,收入,50.00,余额,交易成功,20240928001,,
        2024-09-27 10:00:00,其他,某商户,退款,不计收支,10.00,余额,退款成功,20240927001,,
    """.trimIndent()

    @Test
    fun `微信账单能定位表头并解析出行`() {
        val rows = BillImporter.parseRows(wechatCsv)
        assertEquals(3, rows.size)

        assertEquals(3500L, rows[0].amountCents)
        assertEquals(TxnType.EXPENSE, rows[0].direction)
        assertEquals("星巴克咖啡", rows[0].merchant)
        assertEquals("拿铁", rows[0].note)
        assertEquals("零钱", rows[0].payMethod)

        assertEquals(10000L, rows[1].amountCents)
        assertEquals(TxnType.INCOME, rows[1].direction)
    }

    @Test
    fun `千分位金额不会解析错`() {
        val rows = BillImporter.parseRows(wechatCsv)
        assertEquals(123456L, rows[2].amountCents)
        assertEquals("招商银行(8888)", rows[2].payMethod)
    }

    @Test
    fun `支付宝账单跳过不计收支`() {
        val rows = BillImporter.parseRows(alipayCsv)
        assertEquals(2, rows.size)
        assertEquals(2800L, rows[0].amountCents)
        assertEquals(TxnType.EXPENSE, rows[0].direction)
        assertEquals(5000L, rows[1].amountCents)
        assertEquals(TxnType.INCOME, rows[1].direction)
    }

    @Test
    fun `GBK 编码的支付宝账单能正确解码`() {
        val gbkBytes = alipayCsv.toByteArray(charset("GBK"))
        val decoded = BillImporter.decode(gbkBytes)
        assertTrue(decoded.contains("支付宝交易记录明细查询"))
        assertTrue(decoded.contains("星巴克"))
        assertEquals(2, BillImporter.parseRows(decoded).size)
    }

    @Test
    fun `UTF8 带 BOM 也能解码`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + alipayCsv.toByteArray(Charsets.UTF_8)
        val decoded = BillImporter.decode(bytes)
        assertTrue(decoded.startsWith("支付宝"))
    }

    @Test
    fun `没有表头的文本不会崩且返回空`() {
        assertEquals(0, BillImporter.parseRows("这是一段随便的文本\n没有任何表头").size)
    }

    @Test
    fun `CSV 引号内的逗号不会被切开`() {
        val cols = BillImporter.splitCsv("a,\"b,c\",d")
        assertEquals(3, cols.size)
        assertEquals("b,c", cols[1])
    }

    @Test
    fun `zip 魔数识别`() {
        assertTrue(BillImporter.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
        assertTrue(!BillImporter.isZip("微信支付账单".toByteArray()))
    }
}
