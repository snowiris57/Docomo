package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Event
import io.github.snowiris57.reconnect.EventType
import io.github.snowiris57.reconnect.Health
import io.github.snowiris57.reconnect.Quality
import io.github.snowiris57.reconnect.R
import io.github.snowiris57.reconnect.Route
import io.github.snowiris57.reconnect.Sample
import io.github.snowiris57.reconnect.SignalInfo
import io.github.snowiris57.reconnect.Status
import io.github.snowiris57.reconnect.WifiReading

@Composable
fun HomeScreen(
    status: Status,
    samples: List<Sample>,
    events: List<Event>,
    setup: SetupState,
    monitoring: Boolean,
    actions: AppActions,
    onOpenHistory: () -> Unit,
    live: LiveReadings = rememberLive(),
    previewTouch: Float? = null,
) {
    val stats = remember(events) { DayStats.today(events) }
    val ratio = remember(samples) { okRatio(samples) }
    val cellNow = live.cell ?: status.signal
    val wifiNow = live.wifi ?: status.wifi
    // 表示するモード。ふだんは使っている回線に自動で合わせ、タップで一時的にもう片方を見られる
    var override by rememberSaveable { mutableStateOf<Route?>(null) }
    LaunchedEffect(live.route) { override = null }
    val shown = override ?: live.route
    // 直近30秒以内の復旧だけ波紋で祝う（アプリを開くたびに出ないように）
    val lastRecovery = remember(events) {
        events.firstOrNull { it.type == EventType.RECOVER_OK }?.time
            ?.takeIf { System.currentTimeMillis() - it < 30_000 } ?: 0L
    }
    val recoveryTimes = remember(events) { events.filter { it.type == EventType.RECOVER_START }.map { it.time } }
    var askShizuku by rememberSaveable { mutableStateOf(false) }

    val reconnect = {
        if (setup.shizuku == ShizukuStatus.GRANTED) actions.reconnectNow() else askShizuku = true
    }

    // 上段: 状態の把握、下段: 操作と記録
    val primary: LazyListScope.() -> Unit = {
        item { Header(status, shown, cellNow, wifiNow, setup, actions) }
        item {
            ModeSwitch(active = live.route, shown = shown) { r ->
                override = if (r == live.route) null else r
            }
        }
        if (!setup.complete) item { SetupCard(setup, actions) }
        item { HeroCard(status, shown, live.route, cellNow, wifiNow, stats, ratio, lastRecovery) }
    }
    val secondary: LazyListScope.() -> Unit = {
        item { ControlCard(status, monitoring, actions, reconnect) }
        if (setup.complete && setup.canPinWidget && !setup.widgetPlaced && !setup.widgetHintDismissed) {
            item { WidgetHintCard(actions) }
        }
        item {
            SectionCard {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "通信品質の推移",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.weight(1f))
                        Text("最大1時間", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    SignalChart(samples, recoveryTimes, previewTouch = previewTouch)
                }
            }
        }
        item { RecentEvents(events.take(4), onOpenHistory) }
    }

    BoxWithConstraints {
        val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        val spacing = Arrangement.spacedBy(16.dp)
        if (maxWidth >= 720.dp) {
            // タブレット・横向き: 2列
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LazyColumn(Modifier.weight(1f).widthIn(max = 560.dp), contentPadding = padding, verticalArrangement = spacing, content = primary)
                LazyColumn(
                    Modifier.weight(1f).widthIn(max = 560.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 24.dp),
                    verticalArrangement = spacing,
                    content = secondary,
                )
            }
        } else {
            LazyColumn(contentPadding = padding, verticalArrangement = spacing) {
                primary()
                secondary()
            }
        }
    }

    if (askShizuku) {
        AlertDialog(
            onDismissRequest = { askShizuku = false },
            title = { Text("Shizuku が必要です") },
            text = {
                Text(
                    "回線の入れ直しには Shizuku の許可が必要です。" +
                        "root 化した端末なら、このまま root で試すこともできます。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askShizuku = false; actions.requestShizuku() }) { Text("Shizuku を設定") }
            },
            dismissButton = {
                TextButton(onClick = { askShizuku = false; actions.reconnectNow() }) { Text("root で試す") }
            },
        )
    }
}

@Composable
private fun Header(status: Status, shown: Route, cell: SignalInfo?, wifi: WifiReading?, setup: SetupState, actions: AppActions) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ReConnect", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(
                when (shown) {
                    Route.MOBILE -> cell?.carrier?.takeIf { it.isNotBlank() } ?: "モバイル回線の見張り番"
                    Route.WIFI -> listOfNotNull(
                        wifi?.ssid ?: if (wifi?.tethering == true) "テザリング" else "Wi-Fi",
                        wifi?.standard,
                        wifi?.channel?.let { "ch$it" },
                    ).joinToString(" ・ ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Wi-Fi 名が取れていないときの案内
            if (shown == Route.WIFI && wifi != null && wifi.ssid == null) {
                when {
                    !setup.locationGranted -> TextButton(
                        onClick = actions.requestLocation,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) { Text("Wi-Fi 名を表示する") }
                    !setup.locationOn -> TextButton(
                        onClick = actions.openLocationSettings,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) { Text("位置情報をオンにすると Wi-Fi 名が出ます") }
                }
            }
        }
        StatusPill(status.health)
    }
}

/** モバイル / Wi-Fi の切り替え。使っている回線には「●」が付く */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(active: Route, shown: Route, onSelect: (Route) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(Route.MOBILE to "モバイル", Route.WIFI to "Wi-Fi").forEachIndexed { i, (r, label) ->
            SegmentedButton(
                selected = shown == r,
                onClick = { onSelect(r) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {
                    Icon(
                        painterResource(if (r == Route.WIFI) R.drawable.ic_wifi else R.drawable.ic_cell),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) {
                Text(if (r == active) "$label ・ 使用中" else label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroCard(
    status: Status,
    shown: Route,
    active: Route,
    cell: SignalInfo?,
    wifi: WifiReading?,
    stats: DayStats,
    okRatio: Int?,
    lastRecovery: Long,
) {
    val recovering = status.health == Health.RECOVERING && shown == active
    val peeking = shown != active
    // いま表示している回線の、直近の疎通結果（監視中だけ）
    val probe = status.probe?.takeIf { status.running && status.route == shown && !peeking }
    val probeMs = probe?.takeIf { it.ok }?.ms?.toString() ?: "—"
    val latencyText = probe?.let { if (it.ok) "応答 ${it.ms}ms" else "応答なし" }

    SectionCard {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (shown) {
                Route.MOBILE -> SignalGauge(
                    quality = Quality.combined(cell?.quality, probe),
                    caption = cell?.type ?: "モバイル",
                    unit = cell?.rsrp?.let { "$it dBm ・ ${latencyText ?: SignalInfo.rsrpLabel(it)}" } ?: "電波を取得できません",
                    recovering = recovering,
                    celebrateKey = lastRecovery,
                    modifier = Modifier.fillMaxWidth(0.78f).widthIn(max = 340.dp),
                )
                Route.WIFI -> SignalGauge(
                    quality = Quality.combined(wifi?.quality, probe),
                    caption = wifi?.caption ?: "Wi-Fi",
                    unit = wifi?.let { "${it.rssi} dBm ・ ${latencyText ?: WifiReading.rssiLabel(it.rssi)}" } ?: "Wi-Fi に未接続",
                    extra = wifi?.speedText,
                    recovering = recovering,
                    celebrateKey = lastRecovery,
                    modifier = Modifier.fillMaxWidth(0.78f).widthIn(max = 340.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            val headline = if (peeking) {
                if (shown == Route.WIFI) "Wi-Fi の電波" else "モバイル回線の電波"
            } else status.headline
            val detail = if (peeking) {
                "いまは${if (active == Route.WIFI) " Wi-Fi " else "モバイル回線"}を使っています（タップで戻ります）"
            } else status.detail
            AnimatedContent(
                targetState = headline to detail,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 4 }) togetherWith (fadeOut() + slideOutVertically { -it / 4 })
                },
                label = "headline",
            ) { (h, d) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    if (d.isNotEmpty()) {
                        Text(
                            d,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            AnimatedVisibility(recovering) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(0.6f).padding(top = 12.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.height(20.dp))
            Crossfade(targetState = shown, label = "tiles") { mode ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(if (mode == Route.WIFI) "ネット" else "応答", probeMs, "ms", Modifier.weight(1f))
                    when (mode) {
                        Route.MOBILE -> {
                            MetricTile("今日の復旧", "${stats.successes}", "回", Modifier.weight(1f))
                            MetricTile("正常率", okRatio?.toString() ?: "—", if (okRatio != null) "%" else "", Modifier.weight(1f))
                        }
                        Route.WIFI -> {
                            MetricTile(
                                "Wi-Fi 区間",
                                probe?.hopMs?.toString() ?: "—",
                                "ms",
                                Modifier.weight(1f),
                            )
                            val speed = wifi?.linkMbps?.let { WifiReading.speedParts(it) }
                            MetricTile(
                                "リンク速度",
                                speed?.first ?: "—",
                                speed?.second ?: "",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(status: Status, monitoring: Boolean, actions: AppActions, onReconnect: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val recovering = status.health == Health.RECOVERING
    SectionCard {
        Column {
            ListItem(
                headlineContent = { Text("自動再接続", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(
                        if (monitoring) "パケ止まりを見つけたら、自動で回線を入れ直します"
                        else "オフ — 回線を監視していません"
                    )
                },
                trailingContent = {
                    Switch(checked = monitoring, onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.setMonitoring(it)
                    })
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.padding(top = 6.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReconnect()
                },
                enabled = !recovering,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            ) {
                if (recovering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(if (recovering) "再接続しています…" else "今すぐ再接続", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun WidgetHintCard(actions: AppActions) {
    SectionCard {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ホーム画面にメーターを", style = MaterialTheme.typography.titleMedium)
                Text(
                    "アプリを開かなくても電波と状態がわかり、ワンタップで再接続できます",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = actions.pinWidget) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ウィジェットを追加")
                    }
                    TextButton(onClick = actions.dismissWidgetHint) { Text("あとで") }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState, actions: AppActions) {
    SectionCard {
        Column(Modifier.padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("はじめに", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "あと ${3 - setup.doneCount} ステップで準備完了です",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { setup.doneCount / 3f },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.height(8.dp))
            SetupRow(
                done = setup.shizuku == ShizukuStatus.GRANTED,
                title = "Shizuku を許可",
                body = when (setup.shizuku) {
                    ShizukuStatus.NOT_RUNNING -> "回線を操作するために必要です（root 端末は不要）"
                    ShizukuStatus.NOT_GRANTED -> "Shizuku は起動中です。許可をお願いします"
                    ShizukuStatus.GRANTED -> "許可済み"
                },
                action = if (setup.shizuku == ShizukuStatus.NOT_RUNNING) "開く" else "許可",
                onClick = actions.requestShizuku,
            )
            SetupRow(
                done = setup.permissions,
                title = "通知・電話・位置情報",
                body = "見守り中の表示、回線の種類、Wi-Fi 名の表示に使います（位置そのものは使いません）",
                action = "許可",
                onClick = actions.requestPermissions,
            )
            SetupRow(
                done = setup.battery,
                title = "電池の最適化から除外",
                body = "画面オフ中も見張りを止めないために必要です",
                action = "設定",
                onClick = actions.requestBattery,
            )
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, body: String, action: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            if (done) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "完了", tint = SignalColors.good)
            } else {
                Box(
                    Modifier
                        .size(24.dp)
                        .padding(2.dp)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "未完了" }
                )
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { if (!done) TextButton(onClick = onClick) { Text(action) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun RecentEvents(events: List<Event>, onOpenHistory: () -> Unit) {
    SectionCard {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近のできごと",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onOpenHistory) { Text("すべて見る") }
            }
            if (events.isEmpty()) {
                EmptyState("まだ記録はありません", "検知や再接続があると、ここに表示されます")
            } else {
                events.forEach { EventRow(it) }
            }
        }
    }
}

/** 判定できたサンプルのうち正常だった割合（%） */
fun okRatio(samples: List<Sample>): Int? {
    val judged = samples.filter { it.health == Health.OK || it.health == Health.UNSTABLE || it.health == Health.BAD }
    return if (judged.isEmpty()) null else judged.count { it.health == Health.OK } * 100 / judged.size
}
