package io.github.snowiris57.reconnect.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.snowiris57.reconnect.Sample

/**
 * ウィジェット用の「応答時間の棒グラフ」（TetherPulse のウィジェットと同じ見せ方）。
 * 1 回のチェックが 1 本。右端がいちばん新しい。応答なしは赤い棒、遅い（目安より上）は黄。
 */
object LatencyBitmap {
    /** 並べる本数 */
    const val SLOTS = 40

    /** グラフの上端（ms）。小さな値が大きく見えすぎないよう最低 100ms、きりのよい値に切り上げ */
    fun scaleTop(maxMs: Long): Long {
        var top = 100L
        for (step in longArrayOf(100, 150, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000)) {
            top = step
            if (maxMs <= step) break
        }
        return top
    }

    fun render(widthPx: Int, heightPx: Int, samples: List<Sample>, slowMs: Int, baseline: Int): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val base = h - h * 0.06f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseline; strokeWidth = h * 0.03f }
        c.drawLine(0f, base, w, base, line)

        val recent = samples.takeLast(SLOTS)
        val top = scaleTop(recent.mapNotNull { it.latencyMs }.maxOrNull() ?: 0L).toFloat()
        val slot = w / SLOTS
        val barW = slot * 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val offset = SLOTS - recent.size
        recent.forEachIndexed { i, s ->
            val cx = (offset + i + 0.5f) * slot
            val ms = s.latencyMs
            val frac = when {
                s.ok == false -> 1f
                ms != null -> (ms / top).coerceIn(0.08f, 1f)
                else -> return@forEachIndexed // 休止中など、測っていない
            }
            paint.color = when {
                s.ok == false -> GaugeBitmap.BAD
                ms != null && ms > slowMs -> GaugeBitmap.WARN
                else -> GaugeBitmap.GOOD
            }
            val barH = base * frac
            c.drawRoundRect(RectF(cx - barW / 2, base - barH, cx + barW / 2, base), barW / 2, barW / 2, paint)
            // 下の角は丸めない
            c.drawRect(cx - barW / 2, base - minOf(barH, barW / 2), cx + barW / 2, base, paint)
        }
        return bmp
    }
}
