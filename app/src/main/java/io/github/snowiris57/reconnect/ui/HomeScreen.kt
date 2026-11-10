package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Event
import io.github.snowiris57.reconnect.EventType
import io.github.snowiris57.reconnect.Health
import io.github.snowiris57.reconnect.NetCheck
import io.github.snowiris57.reconnect.Sample
import io.github.snowiris57.reconnect.SignalInfo
import io.github.snowiris57.reconnect.Status
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    status: Status,
    samples: List<Sample>,
    events: List<Event>,
    setup: SetupState,
    monitoring: Boolean,
    actions: AppActions,
    onOpenHistory: () -> Unit,
    liveSignal: SignalInfo? = rememberLiveSignal(),
) {
    val stats = remember(events) { DayStats.today(events) }
    val okRatio = remember(samples) { okRatio(samples) }
    val signal = liveSignal ?: status.signal

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header(status, signal) }
        if (!setup.complete) item { SetupCard(setup, actions) }
        item { HeroCard(status, signal, stats, okRatio) }
        item { ControlCard(status, monitoring, actions) }
        item {
            SectionCard {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("電波品質の推移", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("最大1時間", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    SignalChart(samples, events.filter { it.type == EventType.RECOVER_START }.map { it.time })
                }
            }
        }
        item { RecentEvents(events.take(4), onOpenHistory) }
    }
}

/** 画面表示中は 2 秒ごとに電波を読み、メーターを生き生きと動かす */
@Composable
fun rememberLiveSignal(): SignalInfo? {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sig by produceState<SignalInfo?>(null) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = runCatching { NetCheck.signal(ctx) }.getOrNull()
                delay(2_000)
            }
        }
    }
    return sig
}

@Composable
private fun Header(status: Status, signal: SignalInfo?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ReConnect", style = MaterialTheme.typography.headlineMedium)
            Text(
                signal?.carrier?.takeIf { it.isNotBlank() } ?: "モバイル回線の見張り番",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(status.health)
    }
}

@Composable
private fun HeroCard(status: Status, signal: SignalInfo?, stats: DayStats, okRatio: Int?) {
    SectionCard {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SignalGauge(
                quality = signal?.quality,
                caption = signal?.type ?: "—",
                unit = signal?.rsrp?.let { "$it dBm ・ ${SignalInfo.rsrpLabel(it)}" } ?: "電波を取得できません",
                modifier = Modifier.fillMaxWidth(0.78f),
            )
            Spacer(Modifier.height(4.dp))
            Text(status.headline, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (status.detail.isNotEmpty()) {
                Text(
                    status.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedVisibility(status.health == Health.RECOVERING) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(0.6f).padding(top = 12.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    "応答",
                    status.probe?.takeIf { it.ok && status.running }?.ms?.toString() ?: "—",
                    "ms",
                    Modifier.weight(1f),
                )
                MetricTile("今日の復旧", "${stats.successes}", "回", Modifier.weight(1f))
                MetricTile("正常率", okRatio?.toString() ?: "—", if (okRatio != null) "%" else "", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ControlCard(status: Status, monitoring: Boolean, actions: AppActions) {
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
                    actions.reconnectNow()
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
private fun SetupCard(setup: SetupState, actions: AppActions) {
    SectionCard {
        Column(Modifier.padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("はじめに", style = MaterialTheme.typography.titleMedium)
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
                title = "通知と電話の状態",
                body = "見守り中の表示と、回線の種類の取得に使います",
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
                Text("最近のできごと", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
    val judged = samples.filter { it.health == Health.OK || it.health == Health.BAD }
    return if (judged.isEmpty()) null else judged.count { it.health == Health.OK } * 100 / judged.size
}
