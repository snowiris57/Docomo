package io.github.snowiris57.reconnect.ui

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
fun HistoryScreen(events: List<Event>, samples: List<Sample>, onClear: () -> Unit) {
    val stats = remember(events) { DayStats.today(events) }
    val okRatio = remember(samples) { okRatio(samples) }
    val groups = remember(events) { events.groupBy { dayLabel(it.time) } }
    var confirm by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("履歴", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (events.isNotEmpty()) {
                    IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "履歴を消去") }
                }
            }
        }
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
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("履歴を消去しますか？") },
            text = { Text("検知と再接続の記録がすべて削除されます。") },
            confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
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

