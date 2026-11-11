package io.github.snowiris57.reconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.Traffic
import io.github.snowiris57.reconnect.TrafficSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 受信は青、送信はオレンジ（TetherPulse と同じ） */
object TrafficColors {
    val rx = Color(0xFF3B82F6)
    val tx = Color(0xFFF97316)
}

private val hms = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

/** グラフの上端。きりのよい値に切り上げる（最低 10 KB/s。少ない通信が大きく見えないように） */
fun trafficScale(max: Long): Long {
    val floor = 10L * 1024
    if (max <= floor) return floor
    var step = 1024L
    while (true) {
        for (m in longArrayOf(1, 2, 5)) {
            if (step * m * 10 >= max) return ((max + step * m - 1) / (step * m)) * step * m
        }
        step *= 10
    }
}

/**
 * 直近 1 時間の受信・送信の推移（10 秒ごと）。受信は塗りつぶし、送信は線。
 * 指でなぞると、その時点の値を吹き出しで表示する。
 */
@Composable
fun TrafficChart(
    samples: List<TrafficSample>,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    previewTouch: Float? = null,
) {
    if (samples.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                "データを集めています…\n10 秒ごとに、受信と送信の量が描かれます",
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
    val visible = remember(samples, start) { samples.filter { it.time >= start } }
    val peak = remember(visible) { visible.maxOf { maxOf(it.rx, it.tx) } }
    val top = trafficScale(peak).toFloat()

    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var width by remember { mutableIntStateOf(1) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val activeX = touchX ?: previewTouch?.let { it * width }
    val selected: TrafficSample? = activeX?.let { tx ->
        visible.minByOrNull { abs((it.time - start) / span * width - tx) }
    }
    LaunchedEffect(selected?.time) {
        if (selected != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最大 ${Traffic.formatRate(peak)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Legend(TrafficColors.rx, "受信")
            Spacer(Modifier.size(12.dp))
            Legend(TrafficColors.tx, "送信")
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .pointerInput(visible) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            touchX = down.position.x
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                touchX = c.position.x.coerceIn(0f, size.width.toFloat())
                                c.consume()
                            }
                            touchX = null
                        }
                    }
                    .semantics {
                        contentDescription = "直近${minutes}分の通信量グラフ。最大 ${Traffic.formatRate(peak)}。指でなぞると各時点の値を表示します"
                    }
            ) {
                val w = size.width
                val h = size.height
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                fun x(t: Long) = (t - start) / span * w
                fun y(v: Long) = h - (v / top).coerceIn(0f, 1f) * h * 0.95f

                drawLine(grid, Offset(0f, h * 0.525f), Offset(w, h * 0.525f), 1.dp.toPx(), pathEffect = dash)
                drawLine(grid, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1.dp.toPx())

                // 記録の間が空いたところで線を切る
                val runs = mutableListOf<MutableList<TrafficSample>>()
                visible.forEach { s ->
                    val last = runs.lastOrNull()?.lastOrNull()
                    if (last == null || s.time - last.time > Traffic.STEP_MS * 3) runs += mutableListOf(s) else runs.last() += s
                }

                fun line(run: List<TrafficSample>, v: (TrafficSample) -> Long) = Path().apply {
                    moveTo(x(run[0].time), y(v(run[0])))
                    for (i in 1 until run.size) lineTo(x(run[i].time), y(v(run[i])))
                }

                runs.filter { it.size >= 2 }.forEach { run ->
                    val rx = line(run) { it.rx }
                    val fill = Path().apply {
                        addPath(rx)
                        lineTo(x(run.last().time), h)
                        lineTo(x(run.first().time), h)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(TrafficColors.rx.copy(alpha = 0.35f), TrafficColors.rx.copy(alpha = 0.08f))))
                    drawPath(rx, TrafficColors.rx, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(line(run) { it.tx }, TrafficColors.tx, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                selected?.let { s ->
                    val sx = x(s.time)
                    drawLine(cursorColor, Offset(sx, 0f), Offset(sx, h), 1.dp.toPx())
                    drawCircle(TrafficColors.rx, 4.dp.toPx(), Offset(sx, y(s.rx)))
                    drawCircle(TrafficColors.tx, 4.dp.toPx(), Offset(sx, y(s.tx)))
                }
            }

            selected?.let { s ->
                val bubbleW = with(density) { 140.dp.toPx() }
                val sx = (s.time - start) / span * width
                val bx = (sx - bubbleW / 2).coerceIn(0f, (width - bubbleW).coerceAtLeast(0f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.offset { IntOffset(bx.roundToInt(), 0) },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            hms.format(Date(s.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            "↓ ${Traffic.formatRate(s.rx)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        Text(
                            "↑ ${Traffic.formatRate(s.tx)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${minutes}分前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (activeX == null) "なぞると詳しく表示" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text("いま", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
