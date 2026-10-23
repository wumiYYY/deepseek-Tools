package com.jingyuniang.ledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyuniang.ledger.R
import com.jingyuniang.ledger.util.Fmt

val CardShape = RoundedCornerShape(20.dp)
val SmallShape = RoundedCornerShape(12.dp)

/** 工坊风卡片：纸面 + 香槟金发丝边 */
@Composable
fun WhaleCard(
    modifier: Modifier = Modifier,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = WhaleTheme.palette
    val base = modifier
        .fillMaxWidth()
        .clip(CardShape)
        .background(color ?: p.surface)
        .border(1.dp, p.primary.copy(alpha = if (p.isDark) 0.22f else 0.28f), CardShape)
    val m = if (onClick != null) base.clickable { onClick() } else base
    Column(modifier = m.padding(16.dp), content = content)
}

/** 顶部主卡：夜蓝渐变 + 金线蕾丝边；art=true 时右侧站一位半身立绘 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    art: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = WhaleTheme.palette
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        if (p.isDark) Color(0xFF1D3363) else Color(0xFF1B3B7A),
                        if (p.isDark) Color(0xFF0B1737) else Color(0xFF102A5C)
                    )
                )
            )
            .border(1.dp, p.primary.copy(alpha = 0.55f), CardShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .then(if (art) Modifier.padding(end = 112.dp) else Modifier),
            content = content
        )
        if (art) {
            MaidHalfArt(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 6.dp)
                    .width(112.dp)
                    .height(136.dp)
            )
        }
    }
}

/** 鲸鱼女仆徽章（金环 + 头像） */
@Composable
fun MascotBadge(size: Dp = 44.dp) {
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
            painter = painterResource(R.drawable.ic_mascot),
            contentDescription = "鲸鱼女仆",
            modifier = Modifier.size(size * 0.92f)
        )
    }
}

@Composable
fun EmojiAvatar(emoji: String, color: Color, size: Dp = 42.dp) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        (if (p.isDark) color.copy(alpha = 0.42f) else color.copy(alpha = 0.26f)),
                        (if (p.isDark) color.copy(alpha = 0.16f) else color.copy(alpha = 0.08f))
                    )
                )
            )
            .border(1.2.dp, p.primary.copy(alpha = 0.55f), CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .border(0.6.dp, p.gold.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = (size.value * 0.44f).sp)
    }
}

@Composable
fun AmountText(
    cents: Long,
    color: Color,
    fontSize: Int = 16,
    prefix: String = "",
    bold: Boolean = true,
    modifier: Modifier = Modifier
) {
    Text(
        text = prefix + Fmt.amount(cents),
        color = color,
        fontSize = fontSize.sp,
        fontFamily = WhaleSerif,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** 标题：衬线 + 左侧金色小菱形 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    val p = WhaleTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(p.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = p.text,
            fontSize = 15.sp,
            fontFamily = WhaleSerif,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                text = action,
                color = p.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, p.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/** 空状态：女仆头像 + 文案 */
@Composable
fun EmptyState(emoji: String = "", title: String, sub: String = "") {
    val p = WhaleTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.maid_half),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.size(width = 96.dp, height = 128.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(title, color = p.sub, fontSize = 13.sp, fontFamily = WhaleSerif)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub,
                color = p.sub.copy(alpha = 0.72f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
fun ChipPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = WhaleTheme.palette
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.primary else p.surfaceAlt)
            .border(
                1.dp,
                if (selected) p.primary else p.primary.copy(alpha = 0.25f),
                RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = if (selected) (if (p.isDark) Color(0xFF10203F) else Color.White) else p.sub,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit) = ChipPill(text, selected, onClick = onClick)

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = p.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = p.text,
            fontSize = 18.sp,
            fontFamily = WhaleSerif,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun IconAction(icon: ImageVector? = null, emoji: String = "", text: String = "", onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .clip(SmallShape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = p.text, modifier = Modifier.size(20.dp))
        } else {
            Text(if (emoji.isNotBlank()) emoji else text, color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ProgressBar(ratio: Float, color: Color, height: Dp = 8.dp, track: Color? = null) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track ?: p.surfaceAlt)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.85f), color)
                    )
                )
        )
    }
}

@Composable
fun LabelValueRow(label: String, value: String, valueColor: Color? = null, small: Boolean = false) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = p.sub, fontSize = if (small) 11.sp else 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = valueColor ?: p.text,
            fontSize = if (small) 12.sp else 14.sp,
            fontFamily = WhaleSerif,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
fun DividerLine() {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(p.divider)
    )
}

/** 蕾丝分隔线：金发丝 + 中央菱形 */
@Composable
fun LaceDivider(modifier: Modifier = Modifier) {
    val p = WhaleTheme.palette
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, p.primary.copy(alpha = 0.55f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(6.dp)
                .background(p.primary.copy(alpha = 0.8f), RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(p.primary.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = WhaleTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(title, fontSize = 16.sp, fontFamily = WhaleSerif, fontWeight = FontWeight.Bold) },
        text = { Text(text, fontSize = 13.sp, color = p.sub) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (danger) p.expense else p.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText, color = p.sub) }
        }
    )
}

@Composable
fun SettingRow(
    emoji: String,
    title: String,
    subtitle: String = "",
    trailing: String = "",
    onClick: () -> Unit
) {
    val p = WhaleTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.primarySoft)
                .border(1.dp, p.primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 16.sp) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = p.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing.isNotBlank()) {
            Text(trailing, color = p.sub, fontSize = 12.sp)
        }
        Text("›", color = p.primary.copy(alpha = 0.7f), fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun Tag(text: String, color: Color = WhaleTheme.palette.primary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Color(0xFFD9B871), Color(0xFFB98F3C)))
                else Brush.horizontalGradient(listOf(p.surfaceAlt, p.surfaceAlt))
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF14213F) else p.sub,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RowDivider(inset: Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(WhaleTheme.palette.divider)
    )
}

@Composable
fun HSpacer(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpacer(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun BorderBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = modifier
            .clip(SmallShape)
            .border(1.dp, p.divider, SmallShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun RowOf(vararg content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.Start) { content.forEach { it() } }
}

/** 首页搜索入口：金色蕾丝圆钮 + 放大镜矢量 + 柔光底 */
@Composable
fun SearchPill(onClick: () -> Unit) {
    val p = WhaleTheme.palette
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        p.primary.copy(alpha = 0.22f),
                        p.primary.copy(alpha = 0.06f)
                    )
                )
            )
            .border(1.2.dp, p.primary.copy(alpha = 0.75f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(0.6.dp, p.gold.copy(alpha = 0.35f), CircleShape)
        )
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = "搜索",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(p.gold),
            modifier = Modifier.size(19.dp)
        )
    }
}