package io.github.snowiris57.reconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import io.github.snowiris57.reconnect.Route
import io.github.snowiris57.reconnect.Sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val hm = SimpleDateFormat("HH:mm", Locale.JAPAN)

/**
 * 電波品質の推移グラフ。疎通NGは赤い点、再接続は縦の破線。
 * 指でなぞると、その時点の値を吹き出しで表示する。
 */
@Composable
fun SignalChart(
    samples: List<Sample>,
    recoveryTimes: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    /** スクリーンショット用: 0..1 の位置をなぞっている状態で表示 */
    previewTouch: Float? = null,
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
    val visible = remember(samples, start) { samples.filter { it.time >= start } }

    val line = MaterialTheme.colorScheme.primary
    val wifiLine = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var width by remember { mutableIntStateOf(1) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val activeX = touchX ?: previewTouch?.let { it * width }
    val selected: Sample? = activeX?.let { tx ->
        visible.minByOrNull { abs((it.time - start) / span * width - tx) }
    }
    LaunchedEffect(selected?.time) {
        if (selected != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val okCount = samples.count { it.ok == true }
    val ngCount = samples.count { it.ok == false }

    Column(modifier) {
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
                        contentDescription = "直近${minutes}分の電波品質グラフ。疎通OK ${okCount}回、NG ${ngCount}回。指でなぞると各時点の値を表示します"
                    }
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

                // 欠損（null）と回線の切り替わりで区切った線分ごとに描く（モバイル/Wi-Fi で色を変える）
                val segments = mutableListOf<Pair<Route, MutableList<Offset>>>()
                var cur = mutableListOf<Offset>()
                var curRoute = visible.firstOrNull()?.route ?: Route.MOBILE
                visible.forEach { s ->
                    val q = s.quality
                    if (q == null || s.route != curRoute) {
                        if (cur.isNotEmpty()) segments += curRoute to cur
                        cur = mutableListOf()
                        curRoute = s.route
                    }
                    if (q != null) cur += Offset(x(s.time), y(q))
                }
                if (cur.isNotEmpty()) segments += curRoute to cur

                segments.forEach { (route, pts) ->
                    val line = if (route == Route.WIFI) wifiLine else line
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

                visible.filter { it.ok == false }.forEach {
                    drawCircle(SignalColors.bad, 4.dp.toPx(), Offset(x(it.time), y(it.quality ?: 0f)))
                }

                // なぞっている位置
                selected?.let { s ->
                    val sx = x(s.time)
                    drawLine(cursorColor, Offset(sx, 0f), Offset(sx, h), 1.dp.toPx())
                    val sy = y(s.quality ?: 0f)
                    val c = if (s.ok == false) SignalColors.bad else if (s.route == Route.WIFI) wifiLine else line
                    drawCircle(c.copy(alpha = 0.25f), 10.dp.toPx(), Offset(sx, sy))
                    drawCircle(c, 5.dp.toPx(), Offset(sx, sy))
                }
            }

            selected?.let { s ->
                val bubbleW = with(density) { 132.dp.toPx() }
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
                            hm.format(Date(s.time)) + if (s.route == Route.WIFI) "  Wi-Fi" else "  モバイル",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            when {
                                s.ok == false -> "疎通NG"
                                s.quality != null -> "品質 ${(s.quality * 100).roundToInt()}"
                                else -> "—"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (s.ok == false) SignalColors.bad else MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        Text(
                            listOfNotNull(s.rsrp?.let { "$it dBm" }, s.latencyMs?.let { "${it}ms" }).joinToString(" ・ ").ifEmpty { " " },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(line, "モバイル")
            Legend(wifiLine, "Wi-Fi")
            Legend(SignalColors.bad, "疎通NG")
            Legend(SignalColors.info, "再接続")
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
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            " $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
