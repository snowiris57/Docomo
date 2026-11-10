package io.github.snowiris57.reconnect.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * ウィジェット用のメーターをビットマップに描く（RemoteViews はカスタム描画できないため）。
 * アプリ内の Compose 版メーター（ui/Gauge.kt）と同じデザインにしている。
 */
object GaugeBitmap {
    const val BAD = 0xFFEF4444.toInt()
    const val WARN = 0xFFF59E0B.toInt()
    const val GOOD = 0xFF22C55E.toInt()

    data class Palette(val track: Int, val text: Int, val sub: Int, val knob: Int)

    fun render(
        sizePx: Int,
        quality: Float?,
        value: String,
        unit: String,
        caption: String,
        palette: Palette,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val cx = s / 2
        val cy = s / 2
        val stroke = s * 0.085f
        val inset = stroke / 2 + s * 0.04f
        val rect = RectF(inset, inset, s - inset, s - inset)
        val r = rect.width() / 2

        // 背景トラック
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = palette.track
        }
        c.drawArc(rect, 135f, 270f, false, track)

        // 目盛り
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = s * 0.008f
            strokeCap = Paint.Cap.ROUND
            color = palette.sub
            alpha = 90
        }
        for (i in 0..8) {
            val a = Math.toRadians(135.0 + 270.0 * i / 8)
            val r1 = r - stroke * 0.95f
            val r2 = r - stroke * 1.35f
            c.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick,
            )
        }

        // 値の弧（赤→黄→緑のグラデーション）
        if (quality != null) {
            val q = quality.coerceIn(0f, 1f)
            val shader = SweepGradient(
                cx, cy,
                intArrayOf(BAD, WARN, GOOD, GOOD, BAD, BAD),
                floatArrayOf(0f, 0.22f, 0.48f, 0.875f, 0.876f, 1f),
            ).apply { setLocalMatrix(Matrix().apply { setRotate(135f, cx, cy) }) }
            val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                this.shader = shader
            }
            c.drawArc(rect, 135f, maxOf(270f * q, 0.5f), false, prog)

            val a = Math.toRadians(135.0 + 270.0 * q)
            val kx = cx + r * cos(a).toFloat()
            val ky = cy + r * sin(a).toFloat()
            c.drawCircle(kx, ky, stroke * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.knob })
            c.drawCircle(kx, ky, stroke * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAt(q) })
        }

        // 中央のテキスト
        val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        center.apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = s * 0.075f
            color = palette.sub
        }
        c.drawText(caption, cx, cy - s * 0.15f, center)

        center.apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = s * 0.25f
            color = palette.text
        }
        val valueWidth = center.measureText(value)
        center.textSize.let { full ->
            // 長い文字は縮める
            val maxW = s * 0.5f
            if (valueWidth > maxW) center.textSize = full * maxW / valueWidth
        }
        c.drawText(value, cx, cy + s * 0.075f, center)

        center.apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = s * 0.072f
            color = palette.sub
        }
        c.drawText(unit, cx, cy + s * 0.19f, center)
        return bmp
    }

    /** 0..1 → 赤・黄・緑の補間色 */
    fun colorAt(q: Float): Int = when {
        q < 0.3f -> lerp(BAD, WARN, q / 0.3f)
        q < 0.64f -> lerp(WARN, GOOD, (q - 0.3f) / 0.34f)
        else -> GOOD
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
