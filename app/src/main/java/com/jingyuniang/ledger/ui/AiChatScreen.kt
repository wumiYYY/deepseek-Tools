package com.jingyuniang.ledger.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jingyuniang.ledger.util.Fmt

/**
 * 金库管家：对话式问账。
 * 有 DeepSeek Key 时把账本摘要喂给模型；没有 Key 时用本地规则兜底，永远能回答基础问题。
 */
@Composable
fun AiChatScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    val chat = vm.chat
    val settings by vm.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }

    var showVoice by remember { mutableStateOf(false) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            input = text
            pending = true
            vm.askVault(text) { pending = false }
            input = ""
        }
    }

    if (showVoice) {
        VoiceListeningDialog(
            onResult = { spoken ->
                showVoice = false
                input = spoken
                pending = true
                vm.askVault(spoken) { pending = false }
                input = ""
            },
            onDismiss = { showVoice = false },
            onFallback = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                }
                runCatching { voiceLauncher.launch(i) }
            }
        )
    }

        fun send(text: String) {
        if (text.isBlank() || pending) return
        pending = true
        input = ""
        // 交给 AI 中枢判断：该记账还是该回答（不再靠关键词正则）
        vm.aiAgent(text) { pending = false }
    }

    LaunchedEffect(chat.size, pending) {
        val target = chat.size + if (pending) 1 else 0
        if (target > 0) runCatching { listState.animateScrollToItem(target - 1) }
    }

    Column(modifier = Modifier.fillMaxSize().background(p.bg).imePadding()) {
        TopBar(title = "金库管家", onBack = onBack, actions = {
            IconAction(emoji = "清空") { vm.clearChat() }
        })

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (chat.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MascotBadge(72.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "主人的金库，我都记着呢",
                            color = p.gold, fontSize = 16.sp,
                            fontFamily = WhaleSerif, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (settings.aiEnabled && settings.apiKey.isNotBlank()) "已接入 AI，帮你分析真实账目"
                            else "没填 API Key 也能问基础账目（本地规则回答）",
                            color = p.sub, fontSize = 11.sp
                        )
                    }
                }
                item {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        listOf(
                            "这个月花了多少？",
                            "哪个分类超预算了？",
                            "跟上月比怎么样？",
                            "我最近都把钱花哪了？"
                        ).forEach { q ->
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 8.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(p.surface)
                                    .border(1.dp, p.primary.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                    .clickable { send(q) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Text(q, color = p.text, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            items(chat.size) { i ->
                val m = chat[i]
                ChatBubble(m, vm)
            }

            if (pending) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        MascotBadge(30.dp)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                                .background(p.surface)
                                .border(1.dp, p.primary.copy(alpha = 0.22f), RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text("让我翻翻账本…", color = p.sub, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 输入区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.surface)
                .navigationBarsPadding()
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surfaceAlt)
                        .border(1.dp, p.primary.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { if (it.length <= 120) input = it },
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                        maxLines = 3,
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text("问点什么，比如「上个月奶茶花了多少」", color = p.sub, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                        .clickable { send(input) },
                    contentAlignment = Alignment.Center
                ) { Text("↑", color = Color(0xFF10203F), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, vm: AppViewModel) {
    val p = WhaleTheme.palette
    val isUser = m.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            MascotBadge(30.dp)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                )
                .background(if (isUser) p.primary.copy(alpha = 0.9f) else p.surface)
                .border(
                    1.dp,
                    if (isUser) p.primary else p.primary.copy(alpha = 0.22f),
                    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = m.text,
                color = if (isUser) (if (p.isDark) Color(0xFF10203F) else Color.White) else p.text,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

/** 首页的「一句话出账」气泡：说一句或打一句，直接落账，可撤销 */
@Composable
fun QuickAiDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val p = WhaleTheme.palette
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var undoId by remember { mutableStateOf(0L) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showVoice by remember { mutableStateOf(false) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            busy = true
            vm.quickAiBook(spoken) { success, msg, id ->
                busy = false; ok = success; result = msg; undoId = id
            }
        }
    }

    if (showVoice) {
        VoiceListeningDialog(
            onResult = { spoken ->
                showVoice = false
                text = spoken
                busy = true
                vm.quickAiBook(spoken) { success, msg, id ->
                    busy = false; ok = success; result = msg; undoId = id
                }
            },
            onDismiss = { showVoice = false },
            onFallback = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                }
                runCatching { voiceLauncher.launch(i) }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotBadge(34.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("跟鲸鱼娘说一句", color = p.text, fontSize = 15.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold)
                    Text(
                        if (settings.aiEnabled && settings.apiKey.isNotBlank()) "AI 解析，直接落账" else "本地规则解析，直接落账",
                        color = p.sub, fontSize = 10.sp
                    )
                }
            }
        },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.surfaceAlt)
                        .border(1.dp, p.primary.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 80) text = it },
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                        maxLines = 3,
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text("午饭 25 微信 / 打车 18 支付宝 / 昨天买菜 46", color = p.sub, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("午饭 25 微信", "打车 18 支付宝").forEach { s ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(p.surfaceAlt)
                                .clickable { text = s }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text(s, color = p.sub, fontSize = 11.sp) }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text("正在解析…", color = p.primary, fontSize = 12.sp)
                } else if (result.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(result, color = if (ok) p.income else p.expense, fontSize = 12.sp)
                    if (ok && undoId > 0) {
                        Text(
                            "撤销这笔",
                            color = p.primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    vm.undoQuickBook(undoId)
                                    undoId = 0
                                    result = "好，这笔已经删掉了"
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                vm.quickAiBook(text) { success, msg, id ->
                    busy = false; ok = success; result = msg; undoId = id
                }
            }) { Text(if (ok) "再记一笔" else "记账", color = p.primary) }
        },
        dismissButton = {
            Row {

                TextButton(onClick = onDismiss) { Text("关闭", color = p.sub) }
            }
        }
    )
}
