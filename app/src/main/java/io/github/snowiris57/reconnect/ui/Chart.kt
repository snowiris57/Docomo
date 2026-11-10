package io.github.snowiris57.reconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.Sample

/**
 * 電波品質の推移グラフ。疎通NGは赤い点、再接続は縦の破線で示す。
 */
@Composable
fun SignalChart(
    samples: List<Sample>,
    recoveryTimes: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    val usable = samples.filter { it.quality != null || it.ok == false }
    if (usable.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                "データを集めています…\n監視をオンにすると、ここに推移が描かれます",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val now = samples.last().time
    val start = maxOf(samples.first().time, now - 60 * 60 * 1000L)
    val span = (now - start).coerceAtLeast(60_000L).toFloat()
    val minutes = ((now - start) / 60_000L).coerceAtLeast(1)

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val okCount = samples.count { it.ok == true }
    val ngCount = samples.count { it.ok == false }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "直近${minutes}分の電波品質グラフ。疎通OK ${okCount}回、NG ${ngCount}回" }
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            fun x(t: Long) = (t - start) / span * w
            fun y(q: Float) = h - (q * 0.9f + 0.05f) * h

            for (f in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(grid, Offset(0f, y(f)), Offset(w, y(f)), 1.dp.toPx(), pathEffect = dash)
            }
            recoveryTimes.filter { it in start..now }.forEach {
                drawLine(SignalColors.info.copy(alpha = 0.7f), Offset(x(it), 0f), Offset(x(it), h), 1.5.dp.toPx(), pathEffect = dash)
            }

            // 欠損（null）で区切った線分ごとに描く
            val segments = mutableListOf<MutableList<Offset>>()
            var cur = mutableListOf<Offset>()
            samples.filter { it.time >= start }.forEach { s ->
                val q = s.quality
                if (q == null) {
                    if (cur.isNotEmpty()) segments += cur
                    cur = mutableListOf()
                } else {
                    cur += Offset(x(s.time), y(q))
                }
            }
            if (cur.isNotEmpty()) segments += cur

            segments.forEach { pts ->
                if (pts.size == 1) {
                    drawCircle(line, 2.5.dp.toPx(), pts[0])
                    return@forEach
                }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val p0 = pts[i - 1]
                        val p1 = pts[i]
                        val mx = (p0.x + p1.x) / 2
                        cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                    }
                }
                val fill = Path().apply {
                    addPath(path)
                    lineTo(pts.last().x, h)
                    lineTo(pts.first().x, h)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
                drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            samples.filter { it.ok == false && it.time >= start }.forEach {
                drawCircle(SignalColors.bad, 4.dp.toPx(), Offset(x(it.time), y(it.quality ?: 0f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${minutes}分前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("いま", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
