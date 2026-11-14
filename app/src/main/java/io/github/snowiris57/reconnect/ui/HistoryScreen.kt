package io.github.snowiris57.reconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import io.github.snowiris57.reconnect.Place
import io.github.snowiris57.reconnect.PlaceKind
import io.github.snowiris57.reconnect.PlaceLog
import io.github.snowiris57.reconnect.PlaceMode
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Event
import io.github.snowiris57.reconnect.Sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    events: List<Event>,
    samples: List<Sample>,
    onClear: () -> Unit,
    places: List<Place> = emptyList(),
    placeMode: Int = PlaceMode.OFF,
    onOpenPlace: (Place) -> Unit = {},
    onClearPlaces: () -> Unit = {},
    initialPage: Int = 0,
) {
    val stats = remember(events) { DayStats.today(events) }
    val okRatio = remember(samples) { okRatio(samples) }
    val groups = remember(events) { events.groupBy { dayLabel(it.time) } }
    var confirm by rememberSaveable { mutableStateOf(false) }
    // 0 = できごと（検知・再接続）、1 = 場所（モバイル通信が悪かった場所）
    var page by rememberSaveable { mutableIntStateOf(initialPage) }
    var showGood by rememberSaveable { mutableStateOf(false) }
    val hasGood = remember(places) { places.any { !it.bad } }
    val shownPlaces = remember(places, showGood) { places.filter { showGood || it.bad }.reversed() }
    val placeGroups = remember(shownPlaces) { shownPlaces.groupBy { dayLabel(it.end) } }
    val hotspots = remember(places) { PlaceLog.hotspots(places) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("履歴", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (if (page == 0) events.isNotEmpty() else places.isNotEmpty()) {
                    IconButton(onClick = { confirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = if (page == 0) "履歴を消去" else "場所の記録を消去")
                    }
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("できごと", "場所").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = page == i,
                        onClick = { page = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
        }
        if (page == 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("今日の再接続", "${stats.attempts}", "回", Modifier.weight(1f))
                        MetricTile("成功率", stats.successRate?.toString() ?: "—", if (stats.successRate != null) "%" else "", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("今日の異常検知", "${stats.detections}", "回", Modifier.weight(1f))
                        MetricTile("直近の正常率", okRatio?.toString() ?: "—", if (okRatio != null) "%" else "", Modifier.weight(1f))
                    }
                }
            }
            if (events.isEmpty()) {
                item {
                    SectionCard {
                        EmptyState("まだ記録はありません", "監視をオンにすると、異常の検知や再接続の結果がここに残ります")
                    }
                }
            }
            groups.forEach { (day, list) ->
                item(key = day) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        SectionCard {
                            Column(Modifier.padding(vertical = 6.dp)) { list.forEach { EventRow(it) } }
                        }
                    }
                }
            }
        } else {
            if (places.isEmpty()) {
                item {
                    SectionCard {
                        if (placeMode == PlaceMode.OFF) {
                            EmptyState("場所の記録はオフです", "設定 →「場所の記録」でオンにすると、モバイル通信が悪かった場所がここに残ります")
                        } else {
                            EmptyState("まだ記録はありません", "モバイル回線で電波が弱い・不安定・応答なしになると、その場所がここに残ります")
                        }
                    }
                }
            }
            if (hasGood) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showGood, onClick = { showGood = false }, label = { Text("悪いところ") })
                        FilterChip(selected = showGood, onClick = { showGood = true }, label = { Text("すべて") })
                    }
                }
            }
            if (hotspots.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "くり返し悪くなる場所",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        SectionCard {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                hotspots.forEach { (p, n) -> PlaceRow(p, onOpenPlace, title = "${n}回  最後は ${dayLabel(p.end)} ${hm.format(Date(p.end))}") }
                            }
                        }
                    }
                }
            }
            placeGroups.forEach { (day, list) ->
                item(key = "place-$day") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        SectionCard {
                            Column(Modifier.padding(vertical = 6.dp)) { list.forEach { PlaceRow(it, onOpenPlace) } }
                        }
                    }
                }
            }
            if (places.isNotEmpty()) {
                item {
                    Text(
                        "行をタップすると地図アプリで開きます。記録はこのスマホの中だけに保存し、30日たつと消えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    if (confirm) {
        val placesPage = page == 1
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (placesPage) "場所の記録を消去しますか？" else "履歴を消去しますか？") },
            text = { Text(if (placesPage) "記録した場所がすべて削除されます。" else "検知と再接続の記録がすべて削除されます。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; if (placesPage) onClearPlaces() else onClear() }) { Text("消去") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
    }
}

private val hm = SimpleDateFormat("HH:mm", Locale.JAPAN)

fun placeColor(kind: PlaceKind): Color = when (kind) {
    PlaceKind.GOOD -> SignalColors.good
    PlaceKind.WEAK, PlaceKind.UNSTABLE -> SignalColors.warn
    PlaceKind.BAD -> SignalColors.bad
}

/** 場所の記録の 1 行。タップで地図アプリを開く */
@Composable
private fun PlaceRow(p: Place, onOpen: (Place) -> Unit, title: String? = null) {
    val from = hm.format(Date(p.start))
    val to = hm.format(Date(p.end))
    val detail = listOfNotNull(
        p.type.takeIf { it.isNotBlank() && it != "?" },
        p.minRsrp?.let { "$it dBm" },
        p.fails.takeIf { it > 0 }?.let { "応答なし ${it}回" },
        p.maxMs?.let { "最大 ${it}ms" },
        "誤差 約${p.accuracy.roundToInt()}m",
    ).joinToString(" ・ ")
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "地図で開く") { onOpen(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(placeColor(p.kind), CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title?.let { "${p.kind.label}  $it" } ?: ("${p.kind.label}  " + if (from == to) from else "$from〜$to"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dayLabel(time: Long): String {
    val today = DayStats.startOfToday()
    val yesterday = today - 24 * 60 * 60 * 1000L
    return when {
        time >= today -> "今日"
        time >= yesterday -> "昨日"
        else -> SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(Date(time))
    }
}

