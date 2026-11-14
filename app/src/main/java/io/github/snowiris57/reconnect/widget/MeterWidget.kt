package io.github.snowiris57.reconnect.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Health
import io.github.snowiris57.reconnect.MonitorService
import io.github.snowiris57.reconnect.MonitorState
import io.github.snowiris57.reconnect.NetCheck
import io.github.snowiris57.reconnect.Prefs
import io.github.snowiris57.reconnect.ProbeResult
import io.github.snowiris57.reconnect.Quality
import io.github.snowiris57.reconnect.R
import io.github.snowiris57.reconnect.Route
import io.github.snowiris57.reconnect.Sample
import io.github.snowiris57.reconnect.SignalInfo
import io.github.snowiris57.reconnect.WifiReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 通信品質メーターのホーム画面ウィジェット（TetherPulse のメーターと同じ並び）。
 * 標準: 見出し・メーター・品質・Wi-Fi 名・速度・状態と応答。
 * 小さいとき: メーターと品質・状態だけ。横長: 左にメーター、右に数字と文字。
 */
class MeterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = Snapshot.capture(context)
        ids.forEach { update(context, manager, it, snap) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, Snapshot.capture(context))
    }

    /** ウィジェットに表示する内容（画面のテストからも使う） */
    data class Snapshot(
        val route: Route,
        val wifi: WifiReading?,
        val probe: ProbeResult?,
        val signal: SignalInfo?,
        val health: Health,
        val recoveriesToday: Int,
        /** 休止中の理由（「モバイルデータがオフ」など）。休止中でなければ null */
        val pausedReason: String? = null,
        /** 直近のチェック（応答時間の棒グラフ用）と、「不安定」の目安 */
        val recent: List<Sample> = emptyList(),
        val slowMs: Int = 300,
    ) {
        /** 最後に通信できた時刻 */
        val lastOkAt: Long? get() = recent.lastOrNull { it.ok == true }?.time

        /** 休止中（機内モード・モバイルデータがオフなど）は通信を測っていないので、品質を出さない */
        val paused: Boolean get() = pausedReason != null

        /** いま使っている回線の品質と dBm */
        val quality: Float?
            get() = if (paused) null else Quality.combined(if (route == Route.WIFI) wifi?.quality else signal?.quality, probe)
        val dbm: Int? get() = if (route == Route.WIFI) wifi?.rssi else signal?.rsrp

        companion object {
            fun capture(ctx: Context): Snapshot {
                val st = MonitorState.status.value
                // 監視が止まっていてもメーターは動かす
                val sig = (if (st.running) st.signal else null) ?: runCatching { NetCheck.signal(ctx) }.getOrNull()
                val route = runCatching { NetCheck.activeRoute(ctx) }.getOrDefault(st.route)
                val wifi = if (route == Route.WIFI) runCatching { NetCheck.wifi(ctx) }.getOrNull() ?: st.wifi else null
                return Snapshot(
                    route = route,
                    wifi = wifi,
                    probe = st.probe?.takeIf { st.running && st.route == route },
                    signal = sig,
                    health = if (st.running) st.health else Health.STOPPED,
                    recoveriesToday = DayStats.today(MonitorState.events.value).successes,
                    pausedReason = st.headline.takeIf { st.running && st.health == Health.PAUSED && st.route == route },
                    recent = MonitorState.samples.value.takeLast(LatencyBitmap.SLOTS),
                    slowMs = Prefs(ctx).slowMs,
                )
            }
        }
    }

    /** 文字の中身（レイアウトに依らない） */
    data class Texts(
        val value: String,
        val valueColor: Int,
        val side: String,
        val sideColor: Int,
        val line1: String,
        val line2: String,
        /** 横長のとき: 2 行目に足すもの・3 行目 */
        val line2Wide: String,
        val line3: String,
        val status: String,
        val statusColor: Int,
        val latency: String,
        /** グラフつきのとき: 状態の右（最後の成功）と、グラフの左（応答時間） */
        val lastOk: String,
        val graphLabel: String,
    )

    companion object {
        private const val SEP = " · "

        /** これ以上の高さ（dp）があれば、横長のウィジェットに応答時間の棒グラフも出す */
        private const val GRAPH_MIN_HEIGHT = 145f

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MeterWidget::class.java))
            if (ids.isEmpty()) return
            val snap = Snapshot.capture(ctx)
            ids.forEach { update(ctx, manager, it, snap) }
        }

        private fun update(ctx: Context, manager: AppWidgetManager, id: Int, snap: Snapshot) {
            val gauge = gaugeBitmap(ctx, snap)
            val t = texts(ctx, snap)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                // 入る中でいちばん大きいものを Android が選ぶ
                RemoteViews(
                    mapOf(
                        SizeF(100f, 100f) to build(ctx, R.layout.widget_small, snap, t, gauge),
                        SizeF(120f, 135f) to build(ctx, R.layout.widget_meter, snap, t, gauge),
                        SizeF(250f, 110f) to build(ctx, R.layout.widget_wide, snap, t, gauge),
                        SizeF(250f, GRAPH_MIN_HEIGHT) to build(ctx, R.layout.widget_wide_graph, snap, t, gauge),
                    )
                )
            } else {
                val o = manager.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                val layout = when {
                    w >= 250 && h >= GRAPH_MIN_HEIGHT -> R.layout.widget_wide_graph
                    w >= 250 -> R.layout.widget_wide
                    h in 1 until 135 -> R.layout.widget_small
                    else -> R.layout.widget_meter
                }
                build(ctx, layout, snap, t, gauge)
            }
            manager.updateAppWidget(id, views)
        }

        fun gaugeBitmap(ctx: Context, snap: Snapshot) = GaugeBitmap.render(
            widthPx = (150 * ctx.resources.displayMetrics.density).roundToInt().coerceIn(240, 520),
            quality = snap.quality,
            label = "品質",
            palette = palette(ctx),
        )

        fun latencyBitmap(ctx: Context, snap: Snapshot): android.graphics.Bitmap {
            val d = ctx.resources.displayMetrics.density
            return LatencyBitmap.render(
                widthPx = (240 * d).roundToInt().coerceIn(300, 900),
                heightPx = (30 * d).roundToInt().coerceIn(40, 120),
                samples = snap.recent,
                slowMs = snap.slowMs,
                baseline = ContextCompat.getColor(ctx, R.color.widget_track),
            )
        }

        fun palette(ctx: Context) = GaugeBitmap.Palette(
            track = ContextCompat.getColor(ctx, R.color.widget_track),
            text = ContextCompat.getColor(ctx, R.color.widget_text),
            sub = ContextCompat.getColor(ctx, R.color.widget_sub),
            knob = ContextCompat.getColor(ctx, R.color.widget_bg),
        )

        fun texts(ctx: Context, snap: Snapshot): Texts {
            val q = snap.quality
            val wifi = snap.wifi.takeIf { snap.route == Route.WIFI }
            val sub = ContextCompat.getColor(ctx, R.color.widget_sub)
            val net = snap.probe?.let { p -> if (p.ok) "${p.ms}ms" else "応答なし" }
            return Texts(
                value = q?.let { "${(it * 100).roundToInt()}" } ?: "—",
                valueColor = q?.let { GaugeBitmap.colorAt(it) } ?: sub,
                side = snap.dbm?.let { "$it dBm" } ?: "電波なし",
                sideColor = if (snap.paused) sub else q?.let { GaugeBitmap.colorAt(it) } ?: sub,
                line1 = if (wifi != null) {
                    (wifi.ssid ?: if (wifi.tethering) "テザリング" else "Wi-Fi") + SEP + wifi.band
                } else {
                    listOfNotNull(snap.signal?.carrier, snap.signal?.type)
                        .filter { it.isNotBlank() && it != "?" }
                        .joinToString(SEP)
                        .ifEmpty { "モバイル回線" }
                },
                line2 = if (snap.pausedReason != null) {
                    snap.pausedReason
                } else if (wifi != null) {
                    listOfNotNull(wifi.speedText, wifi.standard).joinToString(SEP)
                } else {
                    "今日の復旧 ${snap.recoveriesToday} 回"
                },
                line2Wide = if (snap.pausedReason != null) {
                    snap.pausedReason
                } else if (wifi != null) {
                    listOfNotNull(wifi.speedText, wifi.standard, wifi.channel?.let { "ch $it" }).joinToString(SEP)
                } else {
                    "今日の復旧 ${snap.recoveriesToday} 回"
                },
                line3 = if (wifi != null) snap.probe?.hopMs?.let { "Wi-Fi 区間 ${it}ms" }.orEmpty() else "",
                status = "● ${healthLabel(snap.health)}",
                statusColor = healthColor(ctx, snap.health),
                latency = when {
                    net == null -> ""
                    wifi != null -> "ネット $net"
                    else -> "応答 $net"
                },
                lastOk = snap.lastOkAt?.let { "最後の成功 " + SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(it)) }.orEmpty(),
                graphLabel = when {
                    net == null -> "応答時間"
                    wifi != null -> listOfNotNull(snap.probe?.hopMs?.let { "Wi-Fi ${it}ms" }, "ネット $net").joinToString("\n")
                    else -> "応答\n$net"
                },
            )
        }

        fun background(h: Health): Int = when (h) {
            Health.OK -> R.drawable.widget_bg_ok
            Health.UNSTABLE -> R.drawable.widget_bg_warn
            Health.BAD -> R.drawable.widget_bg_bad
            Health.RECOVERING -> R.drawable.widget_bg_busy
            Health.PAUSED, Health.STOPPED -> R.drawable.widget_bg_idle
        }

        private fun build(ctx: Context, layout: Int, snap: Snapshot, t: Texts, gauge: android.graphics.Bitmap) =
            RemoteViews(ctx.packageName, layout).apply {
                setInt(R.id.widget_root, "setBackgroundResource", background(snap.health))
                setImageViewBitmap(R.id.gauge, gauge)
                setContentDescription(R.id.gauge, describe(snap))
                setTextViewText(R.id.value, t.value)
                setTextColor(R.id.value, t.valueColor)
                setTextViewText(R.id.status, t.status)
                setTextColor(R.id.status, t.statusColor)
                setOnClickPendingIntent(R.id.widget_root, MonitorService.openAppIntent(ctx))
                if (layout == R.layout.widget_small) return@apply

                setTextViewText(R.id.side, t.side)
                setTextColor(R.id.side, t.sideColor)
                setTextViewText(R.id.line1, t.line1)
                val graph = layout == R.layout.widget_wide_graph
                val wide = graph || layout == R.layout.widget_wide
                setTextViewText(R.id.line2, if (wide) t.line2Wide else t.line2)
                // グラフつきでは、応答時間はグラフの左に出す。状態の右は「最後の成功」
                setTextViewText(R.id.line3, t.line3)
                setViewVisibility(R.id.line3, if (wide && !graph && t.line3.isNotEmpty()) View.VISIBLE else View.GONE)
                setTextViewText(R.id.latency, if (graph) t.lastOk else t.latency)
                if (graph) {
                    setTextViewText(R.id.graph_label, t.graphLabel)
                    setImageViewBitmap(R.id.graph, latencyBitmap(ctx, snap))
                }
                setOnClickPendingIntent(R.id.reconnect, MonitorService.reconnectPendingIntent(ctx))
                setOnClickPendingIntent(R.id.settings, MonitorService.openAppIntent(ctx, MonitorService.TAB_SETTINGS))
                // 再接続中は青く光らせる
                if (snap.health == Health.RECOVERING) {
                    setInt(R.id.reconnect, "setColorFilter", ContextCompat.getColor(ctx, R.color.widget_edge_busy))
                }
            }

        private fun describe(snap: Snapshot): String {
            val q = snap.quality ?: return "通信品質メーター: 電波を取得できません"
            val name = if (snap.route == Route.WIFI) "Wi-Fi" else "モバイル回線"
            return "$name の通信品質 ${(q * 100).roundToInt()}" +
                (snap.dbm?.let { "、${it}デシベルミリワット" } ?: "")
        }

        private fun healthLabel(h: Health) = when (h) {
            Health.OK -> "正常"
            Health.BAD -> "異常"
            Health.UNSTABLE -> "不安定"
            Health.PAUSED -> "休止中"
            Health.RECOVERING -> "再接続中"
            Health.STOPPED -> "監視オフ"
        }

        private fun healthColor(ctx: Context, h: Health): Int = when (h) {
            Health.OK -> GaugeBitmap.GOOD
            Health.BAD -> GaugeBitmap.BAD
            Health.UNSTABLE -> GaugeBitmap.WARN
            Health.RECOVERING -> 0xFF3B82F6.toInt()
            Health.PAUSED, Health.STOPPED -> ContextCompat.getColor(ctx, R.color.widget_sub)
        }
    }
}
