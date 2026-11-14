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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.Sample
import io.github.snowiris57.reconnect.widget.LatencyBitmap

/**
 * 応答時間の棒グラフ（直近 1 時間）。1 回のチェックが 1 本。
 * 緑＝目安以内、黄＝目安より遅い、赤＝応答なし。目安の線を破線で引く。
 */
@Composable
fun LatencyChart(samples: List<Sample>, slowMs: Int, modifier: Modifier = Modifier, height: Dp = 96.dp) {
    val measured = samples.filter { it.latencyMs != null || it.ok == false }
    if (measured.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                "データを集めています…",
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
    val visible = remember(samples, start) { measured.filter { it.time >= start } }
    val maxMs = visible.mapNotNull { it.latencyMs }.maxOrNull() ?: 0L
    val top = LatencyBitmap.scaleTop(maxMs).toFloat()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val guide = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).semantics {
                contentDescription = "直近${minutes}分の応答時間のグラフ。最大 ${maxMs}ミリ秒、応答なし ${visible.count { it.ok == false }}回"
            }
        ) {
            val w = size.width
            val h = size.height
            val barW = (w / visible.size.coerceAtLeast(40) * 0.6f).coerceIn(1.5.dp.toPx(), 6.dp.toPx())
            drawLine(grid, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1.dp.toPx())
            if (slowMs <= top) {
                val y = h - slowMs / top * h
                drawLine(guide, Offset(0f, y), Offset(w, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            visible.forEach { s ->
                val x = ((s.time - start) / span * (w - barW)).coerceIn(0f, w - barW)
                val ms = s.latencyMs
                val frac = if (s.ok == false) 1f else ((ms ?: 0L) / top).coerceIn(0.04f, 1f)
                val color = when {
                    s.ok == false -> SignalColors.bad
                    ms != null && ms > slowMs -> SignalColors.warn
                    else -> SignalColors.good
                }
                drawRoundRect(color, Offset(x, h - h * frac), Size(barW, h * frac), CornerRadius(barW / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${minutes}分前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "上端 ${top.toInt()}ms" + if (slowMs <= top) " ・ 破線は目安 ${slowMs}ms" else " ・ 目安 ${slowMs}ms 以内",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text("いま", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
