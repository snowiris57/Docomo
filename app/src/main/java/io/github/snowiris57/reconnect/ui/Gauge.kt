package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 電波品質メーター（270°の弧、赤→黄→緑）。
 * - 値の変化はばねアニメーション
 * - 背後に品質の色でほのかに光る
 * - 再接続中は弧の上を光が走る
 * - [celebrateKey] が変わると（復旧時）緑の波紋が広がる
 * ウィジェット版（widget/GaugeBitmap.kt）と同じデザイン。
 */
@Composable
fun SignalGauge(
    quality: Float?,
    caption: String,
    unit: String,
    modifier: Modifier = Modifier,
    recovering: Boolean = false,
    celebrateKey: Long = 0L,
) {
    val target = quality?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessVeryLow),
        label = "gauge",
    )
    val dim by animateFloatAsState(if (recovering) 0.35f else 1f, tween(500), label = "dim")

    val sweep = rememberInfiniteTransition(label = "sweep")
    val sweepPos by sweep.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepPos",
    )

    // 復旧時の波紋
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(celebrateKey) {
        if (celebrateKey != 0L) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        }
    }

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val knob = MaterialTheme.colorScheme.surface
    val glow = if (quality == null) MaterialTheme.colorScheme.outline else SignalColors.at(animated)
    val percent = quality?.let { (it * 100).roundToInt() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = when {
                    recovering -> "再接続中"
                    percent == null -> "電波を取得できません"
                    else -> "電波品質 ${percent}パーセント、$unit、$caption"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke / 2 + size.minDimension * 0.03f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val r = arcSize.width / 2

            // 背後の光
            drawCircle(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.16f * dim), Color.Transparent),
                    center = center, radius = size.minDimension * 0.5f,
                ),
                radius = size.minDimension * 0.5f,
            )

            // 復旧の波紋
            if (ripple.value < 1f) {
                val p = ripple.value
                drawCircle(
                    SignalColors.good.copy(alpha = (1f - p) * 0.55f),
                    radius = r * (0.55f + 0.55f * p),
                    style = Stroke(stroke * (1.2f - p)),
                )
            }

            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            for (i in 0..8) {
                val a = Math.toRadians(135.0 + 270.0 * i / 8)
                val r1 = r - stroke * 1.0f
                val r2 = r - stroke * 1.45f
                drawLine(
                    tick,
                    center + Offset((r1 * cos(a)).toFloat(), (r1 * sin(a)).toFloat()),
                    center + Offset((r2 * cos(a)).toFloat(), (r2 * sin(a)).toFloat()),
                    strokeWidth = size.minDimension * 0.008f,
                    cap = StrokeCap.Round,
                )
            }

            if (quality != null) {
                val brush = Brush.sweepGradient(
                    0f to SignalColors.bad,
                    0.22f to SignalColors.warn,
                    0.48f to SignalColors.good,
                    0.875f to SignalColors.good,
                    0.876f to SignalColors.bad,
                    1f to SignalColors.bad,
                )
                rotate(135f) {
                    drawArc(
                        brush, 0f, maxOf(270f * animated, 0.5f), false, topLeft, arcSize,
                        alpha = dim,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                val a = Math.toRadians(135.0 + 270.0 * animated)
                val p = center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
                drawCircle(knob, stroke * 0.46f, p, alpha = dim)
                drawCircle(SignalColors.at(animated), stroke * 0.27f, p, alpha = dim)
            }

            // 再接続中: 弧の上を光が走る
            if (recovering) {
                val head = 270f * sweepPos
                val len = 70f
                val start = (head - len).coerceAtLeast(0f)
                val bright = Brush.sweepGradient(
                    0f to Color.Transparent,
                    ((start) / 360f) to Color.Transparent,
                    (head / 360f).coerceAtLeast(start / 360f + 0.001f) to SignalColors.info,
                    1f to Color.Transparent,
                )
                rotate(135f) {
                    drawArc(
                        bright, start, (head - start).coerceAtLeast(0.5f), false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                caption,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = percent?.let { "${(animated * 100).roundToInt()}" } ?: "—",
                fontSize = 64.sp,
                lineHeight = 64.sp,
                fontWeight = FontWeight.Bold,
                // 数字が変わっても幅が揺れないよう等幅数字
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f + 0.6f * dim),
            )
            Text(
                if (recovering) "つなぎ直しています" else unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
