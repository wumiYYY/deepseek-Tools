package com.jingyuniang.ledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyuniang.ledger.util.Fmt
import kotlin.math.max

/** 环形图：分类占比可视化 */
@Composable
fun DonutChart(
    slices: List<Pair<Color, Float>>,
    centerTitle: String,
    centerValue: String,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 16.dp
) {
    val p = WhaleTheme.palette
    Box(modifier = modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = p.surfaceAlt,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke)
            )
            var start = -90f
            slices.forEach { (color, value) ->
                if (value > 0f) {
                    val sweep = value * 360f
                    drawArc(
                        color = color,
                        startAngle = start + 1.2f,
                        sweepAngle = max(0f, sweep - 2.4f),
                        useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTitle, color = p.sub, fontSize = 11.sp)
            Text(centerValue, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 月度柱状图 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp
) {
    val p = WhaleTheme.palette
    val maxV = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((height.value - 24f).dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val h = (v / maxV * (height.value - 28f)).coerceAtLeast(if (v > 0f) 3f else 0f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(h.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (i == values.lastIndex) color else color.copy(alpha = 0.45f))
                    )
                }
                Text(
                    text = labels.getOrElse(i) { "" },
                    color = p.sub,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** 横向排行条 */
@Composable
fun RankBar(
    label: String,
    emoji: String,
    color: Color,
    cents: Long,
    ratio: Float,
    count: Int = 0
) {
    val p = WhaleTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$emoji $label", color = p.text, fontSize = 13.sp)
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text("$count 笔", color = p.sub, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("¥${Fmt.amount(cents)}", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("  ${(ratio * 100).toInt()}%", color = p.sub, fontSize = 10.sp)
        }
        Spacer(Modifier.height(5.dp))
        ProgressBar(ratio = ratio, color = color, height = 6.dp)
    }
}
