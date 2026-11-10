package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 電波品質メーター（270°の弧、赤→黄→緑）。値の変化はばねアニメーションで動く。
 * ウィジェット版（widget/GaugeBitmap.kt）と同じデザイン。
 */
@Composable
fun SignalGauge(
    quality: Float?,
    caption: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val target = quality?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessVeryLow),
        label = "gauge",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val knob = MaterialTheme.colorScheme.surface
    val percent = quality?.let { (it * 100).roundToInt() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clearAndSetSemantics {
                contentDescription = if (percent == null) "電波を取得できません"
                else "電波品質 ${percent}パーセント、$unit、$caption"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke / 2 + size.minDimension * 0.03f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val r = arcSize.width / 2

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
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                val a = Math.toRadians(135.0 + 270.0 * animated)
                val p = center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
                drawCircle(knob, stroke * 0.46f, p)
                drawCircle(SignalColors.at(animated), stroke * 0.27f, p)
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
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
