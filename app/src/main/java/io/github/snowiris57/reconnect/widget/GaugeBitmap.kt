package io.github.snowiris57.reconnect.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ウィジェット用のメーターをビットマップに描く（RemoteViews はカスタム描画できないため）。
 * TetherPulse のメーターと同じく、赤→黄→緑の弧に針で値を指す形。目盛りは通信品質の 0〜100。
 */
object GaugeBitmap {
    const val BAD = 0xFFEF4444.toInt()
    const val WARN = 0xFFF59E0B.toInt()
    const val GOOD = 0xFF22C55E.toInt()

    /** 弧の始まり（左下）と角度の幅。下側が開いた馬てい形 */
    private const val START = 150f
    private const val SWEEP = 240f

    /** 高さ ÷ 幅 */
    const val ASPECT = 0.74f

    data class Palette(val track: Int, val text: Int, val sub: Int, val knob: Int)

    /**
     * @param widthPx ビットマップの幅。高さは [ASPECT] 倍
     * @param quality 0..1。null なら針を出さない（電波なし）
     * @param label 針の軸の下に出す小さな文字（例: "品質"）
     */
    fun render(widthPx: Int, quality: Float?, label: String, palette: Palette): Bitmap {
        val w = widthPx.toFloat()
        val h = (w * ASPECT).roundToInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val stroke = w * 0.032f
        val r = w * 0.40f
        val cx = w / 2
        val cy = stroke + r + w * 0.02f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // 弧（全体を赤→黄→緑。針が今の値を指す）
        val f = SWEEP / 360f
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(BAD, BAD, WARN, GOOD, GOOD, BAD),
            floatArrayOf(0f, 0.04f * f, 0.36f * f, 0.68f * f, f, 1f),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(START, cx, cy) }) }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            this.shader = shader
            // 値が無い（休止中・電波なし）ときは弧を薄くする
            if (quality == null) alpha = 70
        }
        c.drawArc(rect, START, SWEEP, false, arc)

        // 目盛り: 5 ごとに短く、25 ごとに長く
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            color = palette.sub
        }
        for (i in 0..20) {
            val major = i % 5 == 0
            val a = Math.toRadians((START + SWEEP * i / 20).toDouble())
            val r1 = r - stroke * 1.1f
            val r2 = r1 - w * (if (major) 0.07f else 0.035f)
            tick.strokeWidth = w * (if (major) 0.011f else 0.006f)
            tick.alpha = if (major) 230 else 130
            c.drawLine(
                cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick,
            )
        }

        // 目盛りの数字（0・25・50・75・100）を内側に
        val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = w * 0.085f
            color = palette.sub
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        for (k in 0..4) {
            val a = Math.toRadians((START + SWEEP * k / 4).toDouble())
            val rr = r - stroke * 1.1f - w * 0.16f
            c.drawText("${k * 25}", cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat() + scale.textSize * 0.36f, scale)
        }

        // 針と軸
        val hubR = w * 0.035f
        if (quality != null) {
            val q = quality.coerceIn(0f, 1f)
            val a = Math.toRadians((START + SWEEP * q).toDouble())
            val len = r - stroke * 1.4f
            val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = w * 0.022f
                strokeCap = Paint.Cap.ROUND
                color = palette.text
            }
            c.drawLine(
                cx + hubR * cos(a).toFloat(), cy + hubR * sin(a).toFloat(),
                cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), needle,
            )
        }
        c.drawCircle(cx, cy, hubR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.knob })
        c.drawCircle(cx, cy, hubR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.016f
            color = if (quality != null) palette.text else palette.sub
        })

        // 軸の下の小さな文字
        val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = w * 0.07f
            color = palette.sub
        }
        c.drawText(label, cx, cy + hubR + w * 0.1f, lbl)
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
