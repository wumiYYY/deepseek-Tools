package com.jingyuniang.ledger.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.jingyuniang.ledger.capture.ParseEngine
import com.jingyuniang.ledger.data.TxnType
import com.jingyuniang.ledger.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 截图 / 票据 OCR（ML Kit 中文识别，模型内置在 APK 里，离线可用） */
object OcrHelper {

    data class OcrResult(val text: String, val amountCents: Long, val merchant: String, val income: Boolean)

    suspend fun recognize(context: Context, uri: Uri): OcrResult? = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val raw = text.text
                    val amount = ParseEngine.extractAmount(raw)
                    val merchant = ParseEngine.extractMerchant(raw, "截图记账")
                    val income = ParseEngine.detectDirection(raw) == TxnType.INCOME
                    cont.resume(OcrResult(raw, amount, merchant, income))
                }
                .addOnFailureListener { e ->
                    AppLog.e("OCR 失败", e)
                    cont.resume(null)
                }
        } catch (t: Throwable) {
            AppLog.e("OCR 异常", t)
            cont.resume(null)
        }
    }
}
