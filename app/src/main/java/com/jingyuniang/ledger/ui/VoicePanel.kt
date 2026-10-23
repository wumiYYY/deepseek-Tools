package com.jingyuniang.ledger.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 鲸鱼娘聆听面板：金色波纹呼吸 + 表情头像 + 实时音量，说完直接回填。
 * 用 SpeechRecognizer 直连系统识别服务，不再弹小米那个朴素面板。
 */
@Composable
fun VoiceListeningDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    onFallback: (() -> Unit)? = null
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        VoicePanelBody(onResult, onDismiss, onFallback)
    }
}

@Composable
private fun VoicePanelBody(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    onFallback: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val p = WhaleTheme.palette
    var status by remember { mutableStateOf("我在听…") }
    var level by remember { mutableFloatStateOf(0f) }
    var partial by remember { mutableStateOf("") }

    // 波纹动画
    val t = rememberInfiniteTransition(label = "ring")
    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    var gotAny by remember { mutableStateOf(false) }
    // 1 秒内没有识别到任何文字 → 交给系统语音面板（MIUI 只把结果回传给它自己）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1100)
        if (!gotAny) {
            onFallback?.invoke()
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        var recognizer: SpeechRecognizer? = null
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { status = "我在听…" }
            override fun onBeginningOfSpeech() { status = "听到了，继续说" }
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "想想你怎么说的…" }
            override fun onError(error: Int) {
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等太久啦，再点一次麦克风"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络不太好"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
                    else -> "识别失败（$error），可以打字"
                }
                level = 0f
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) { gotAny = true; onResult(text) }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val s = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!s.isNullOrBlank()) { partial = s; status = "听懂中…" }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
        runCatching {
            run {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer?.startListening(intent)
            }
        }.onFailure { status = "启动语音失败：${it.message?.take(20)}" }

        onDispose {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.destroy() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050C1F).copy(alpha = 0.82f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(30.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2E5C), Color(0xFF0A1430))))
                .border(1.4.dp, p.primary.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
                .padding(horizontal = 26.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 波纹 + 头像
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(176.dp)) {
                repeat(3) { i ->
                    val phase = ((pulse + i * 0.33f) % 1f)
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .graphicsLayer {
                                val s = 1f + phase * (0.75f + level * 0.35f)
                                scaleX = s
                                scaleY = s
                                alpha = (1f - phase) * (0.30f + level * 0.35f)
                            }
                            .clip(CircleShape)
                            .border(1.6.dp, p.primary, CircleShape)
                    )
                }
                MoodBadge(size = 104.dp, res = com.jingyuniang.ledger.R.drawable.mood_delighted)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                status,
                color = p.gold,
                fontSize = 14.sp,
                fontFamily = WhaleSerif,
                fontWeight = FontWeight.Bold
            )
            if (partial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "「$partial」",
                    color = p.text.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "试试说：「午饭 25 微信」",
                color = p.sub, fontSize = 10.sp
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 26.dp, vertical = 10.dp)
            ) {
                Text("先不说了", color = Color(0xFF10203F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
