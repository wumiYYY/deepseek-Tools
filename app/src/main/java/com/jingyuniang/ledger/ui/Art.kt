package com.jingyuniang.ledger.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyuniang.ledger.R
import kotlinx.coroutines.delay

/**
 * 半身立绘：轻微呼吸浮动（缩放 1.00 → 1.035，上下浮 6dp）
 * 立在首页主卡右侧，像站在主人旁边记账。
 */
@Composable
fun moodRes(): Int {
    val h = java.time.LocalTime.now().hour
    return if (h >= 23 || h < 6) R.drawable.mood_sleepy else R.drawable.mood_delighted
}

@Composable
fun MaidHalfArt(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "maid-breath")
    val scale by t.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val dy by t.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy"
    )
    Image(
        painter = painterResource(R.drawable.maid_half),
        contentDescription = "鲸鱼女仆",
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = dy
        }
    )
}

/** 全身立绘（作为页面头部背景，左侧压暗保证文字可读） */
@Composable
fun MaidFullBackground(modifier: Modifier = Modifier, dim: Float = 0.45f) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.maid_full),
            contentDescription = "鲸鱼女仆立绘",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.95f
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF0A1430).copy(alpha = 0.96f),
                            Color(0xFF0A1430).copy(alpha = 0.80f),
                            Color(0xFF0A1430).copy(alpha = dim)
                        )
                    )
                )
        )
    }
}

/**
 * 开屏动画：两片金边夜蓝幕布从中间向两侧拉开 → 女仆徽章与「鲸鱼娘金库」浮出。
 * 约 1.5 秒后自动结束，可点击立即跳过。
 */
@Composable
fun SplashCurtain(onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(320)
        open = true
        delay(1150)
        gone = true
        onDone()
    }
    if (gone) return
    val p = WhaleTheme.palette
    val shift by animateDpAsState(
        targetValue = if (open) 420.dp else 0.dp,
        animationSpec = tween(780, easing = FastOutSlowInEasing),
        label = "curtain"
    )
    val fade = animateFloatTransition(if (open) 1f else 0f)

    Box(Modifier.fillMaxSize().background(Color(0xFF070F26))) {
        // 金粉
        GoldParticles(20)
        // 中央徽章 + 标题
        Column(
            modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = fade },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(moodRes()),
                contentDescription = "鲸鱼女仆",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(178.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "鲸鱼娘金库",
                color = p.gold,
                fontSize = 24.sp,
                fontFamily = WhaleSerif,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("欢迎回来，主人", color = p.sub, fontSize = 11.sp)
        }

        // 左幕布
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(540.dp)
                .offset(x = -shift)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF16274F), Color(0xFF0A1430))
                    )
                )
        ) {
            // 金线饰边
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(p.primary.copy(alpha = 0.85f))
            )
        }
        // 右幕布
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(540.dp)
                .offset(x = shift)
                .background(Brush.horizontalGradient(listOf(Color(0xFF0A1430), Color(0xFF16274F))))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(p.primary.copy(alpha = 0.85f))
            )
        }
    }
}

/** 简单的淡入（0→1）辅助，避免引入额外 API */
@Composable
private fun animateFloatTransition(target: Float): Float {
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(target) {
        if (target > 0f) anim.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    return anim.value
}

/** 首页的「问问金库管家」条：真人立绘头像 + 一句话记账 + 问账入口 */
@Composable
fun MascotAskBar(onQuickEntry: () -> Unit, onChat: () -> Unit) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF1A2B54), Color(0xFF0D1A3A))
                )
            )
            .border(1.dp, p.primary.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable { onQuickEntry() }
            .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(moodRes()),
            contentDescription = "鲸鱼女仆",
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .border(1.2.dp, p.primary, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "跟鲸鱼娘说一句",
                color = p.text,
                fontSize = 13.sp,
                fontFamily = WhaleSerif,
                fontWeight = FontWeight.Bold
            )
            val aiOn = runCatching { com.jingyuniang.ledger.ai.AiService.available() }.getOrDefault(false)
            Text(
                if (aiOn) "🟢 AI 在线 · 「午饭 25 微信」→ 直接落账"
                else "🔴 AI 未连接 · 用本地规则记账",
                color = if (aiOn) p.sub else p.sub.copy(alpha = 0.85f),
                fontSize = 10.sp
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFFE3C68A), Color(0xFFB98F3C))))
                .clickable { onChat() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("问 账", color = Color(0xFF10203F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
/** 其它页面的装饰头带：金发丝线 + 右侧 Q 版头像（不依赖裁切，任何屏幕都不会出错） */
@Composable
fun HeaderArtBanner(height: androidx.compose.ui.unit.Dp = 64.dp, mood: Int? = null) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, p.primary.copy(alpha = 0.65f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .size(7.dp)
                .background(p.primary.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
        Image(
            painter = painterResource(mood ?: moodRes()),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(height - 6.dp)
        )
    }
}
/** 开屏金粉粒子 */
@Composable
fun GoldParticles(count: Int = 18) {
    val t = rememberInfiniteTransition(label = "dust")
    val rise by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "rise"
    )
    val p = WhaleTheme.palette
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        for (i in 0 until count) {
            val seedX = ((i * 37) % 100) / 100f
            val speed = 0.6f + ((i * 13) % 40) / 100f
            val prog = ((rise * speed) + i * 0.07f) % 1f
            val y = size.height * (1f - prog)
            val x = size.width * seedX + (if (i % 2 == 0) 1 else -1) * prog * 18f
            val alpha = (1f - prog) * 0.7f
            val r = 1.2f + ((i * 7) % 5) * 0.6f
            drawCircle(
                color = p.primary.copy(alpha = alpha.coerceIn(0f, 0.8f)),
                radius = r * density,
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
    }
}
/** 表情徽章（Q 版切图 + 金环），用于状态卡 */
@Composable
fun MoodBadge(size: androidx.compose.ui.unit.Dp = 44.dp, res: Int = moodRes()) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (p.isDark) Color(0xFF14234A) else Color(0xFFFFF6E6))
            .border(1.5.dp, p.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = "鲸鱼女仆",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size * 0.96f)
        )
    }
}