package io.github.snowiris57.reconnect.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Health
import io.github.snowiris57.reconnect.MonitorService
import io.github.snowiris57.reconnect.MonitorState
import io.github.snowiris57.reconnect.NetCheck
import io.github.snowiris57.reconnect.R
import io.github.snowiris57.reconnect.SignalInfo
import kotlin.math.roundToInt

/**
 * 電波メーターのホーム画面ウィジェット。
 * 2×2 ではメーターのみ、横に広げると状態・応答速度・再接続ボタンを表示する。
 */
class MeterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = Snapshot.capture(context)
        ids.forEach { update(context, manager, it, snap) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, Snapshot.capture(context))
    }

    /** ウィジェットに表示する内容 */
    private data class Snapshot(
        val signal: SignalInfo?,
        val health: Health,
        val title: String,
        val latencyMs: Long?,
        val recoveriesToday: Int,
    ) {
        companion object {
            fun capture(ctx: Context): Snapshot {
                val st = MonitorState.status.value
                // 監視が止まっていてもメーターは動かす
                val sig = (if (st.running) st.signal else null) ?: runCatching { NetCheck.signal(ctx) }.getOrNull()
                return Snapshot(
                    signal = sig,
                    health = if (st.running) st.health else Health.STOPPED,
                    title = when {
                        !st.running -> "監視オフ"
                        st.health == Health.OK -> "通信は安定"
                        st.health == Health.BAD -> "通信に問題あり"
                        st.health == Health.RECOVERING -> "再接続中…"
                        else -> st.headline
                    },
                    latencyMs = st.probe?.takeIf { it.ok && st.running }?.ms,
                    recoveriesToday = DayStats.today(MonitorState.events.value).successes,
                )
            }
        }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MeterWidget::class.java))
            if (ids.isEmpty()) return
            val snap = Snapshot.capture(ctx)
            ids.forEach { update(ctx, manager, it, snap) }
        }

        private fun update(ctx: Context, manager: AppWidgetManager, id: Int, snap: Snapshot) {
            val gauge = gaugeBitmap(ctx, snap)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(
                    mapOf(
                        SizeF(100f, 100f) to small(ctx, snap, gauge),
                        SizeF(230f, 100f) to wide(ctx, snap, gauge),
                    )
                )
            } else {
                val minWidth = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                if (minWidth >= 230) wide(ctx, snap, gauge) else small(ctx, snap, gauge)
            }
            manager.updateAppWidget(id, views)
        }

        private fun gaugeBitmap(ctx: Context, snap: Snapshot) = GaugeBitmap.render(
            sizePx = (150 * ctx.resources.displayMetrics.density).roundToInt().coerceIn(200, 480),
            quality = snap.signal?.quality,
            value = snap.signal?.quality?.let { "${(it * 100).roundToInt()}" } ?: "—",
            unit = snap.signal?.rsrp?.let { "$it dBm" } ?: "電波なし",
            caption = snap.signal?.type ?: "",
            palette = GaugeBitmap.Palette(
                track = ContextCompat.getColor(ctx, R.color.widget_track),
                text = ContextCompat.getColor(ctx, R.color.widget_text),
                sub = ContextCompat.getColor(ctx, R.color.widget_sub),
                knob = ContextCompat.getColor(ctx, R.color.widget_bg),
            ),
        )

        private fun small(ctx: Context, snap: Snapshot, gauge: android.graphics.Bitmap) =
            RemoteViews(ctx.packageName, R.layout.widget_small).apply {
                setImageViewBitmap(R.id.gauge, gauge)
                setContentDescription(R.id.gauge, describe(snap))
                setTextViewText(R.id.status, "● ${healthLabel(snap.health)}")
                setTextColor(R.id.status, healthColor(ctx, snap.health))
                setOnClickPendingIntent(R.id.widget_root, MonitorService.openAppIntent(ctx))
            }

        private fun wide(ctx: Context, snap: Snapshot, gauge: android.graphics.Bitmap) =
            RemoteViews(ctx.packageName, R.layout.widget_wide).apply {
                setImageViewBitmap(R.id.gauge, gauge)
                setContentDescription(R.id.gauge, describe(snap))
                setTextViewText(R.id.dot, "●")
                setTextColor(R.id.dot, healthColor(ctx, snap.health))
                setTextViewText(R.id.title, snap.title)
                setTextViewText(
                    R.id.subtitle,
                    listOf(snap.signal?.carrier.orEmpty(), snap.signal?.type.orEmpty())
                        .filter { it.isNotBlank() && it != "?" }.joinToString(" ・ ").ifEmpty { "モバイル回線" }
                )
                setTextViewText(R.id.latency, snap.latencyMs?.let { "$it" } ?: "—")
                setTextViewText(R.id.recoveries, "${snap.recoveriesToday}")
                setOnClickPendingIntent(R.id.widget_root, MonitorService.openAppIntent(ctx))
                setOnClickPendingIntent(R.id.reconnect, MonitorService.reconnectPendingIntent(ctx))
                if (snap.health == Health.RECOVERING) {
                    setTextViewText(R.id.reconnect, "再接続中…")
                } else {
                    setTextViewText(R.id.reconnect, "↻  今すぐ再接続")
                }
            }

        private fun describe(snap: Snapshot): String {
            val q = snap.signal?.quality ?: return "電波メーター: 電波を取得できません"
            return "電波品質 ${(q * 100).roundToInt()}パーセント" +
                (snap.signal.rsrp?.let { "、RSRP ${it}デシベルミリワット" } ?: "")
        }

        private fun healthLabel(h: Health) = when (h) {
            Health.OK -> "正常"
            Health.BAD -> "異常"
            Health.PAUSED -> "休止中"
            Health.RECOVERING -> "再接続中"
            Health.STOPPED -> "監視オフ"
        }

        private fun healthColor(ctx: Context, h: Health): Int = when (h) {
            Health.OK -> GaugeBitmap.GOOD
            Health.BAD -> GaugeBitmap.BAD
            Health.PAUSED -> GaugeBitmap.WARN
            Health.RECOVERING -> 0xFF3B82F6.toInt()
            Health.STOPPED -> ContextCompat.getColor(ctx, R.color.widget_sub)
        }
    }
}
