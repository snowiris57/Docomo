package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.snowiris57.reconnect.MonitorState
import io.github.snowiris57.reconnect.Prefs

enum class ShizukuStatus { NOT_RUNNING, NOT_GRANTED, GRANTED }

data class SetupState(
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val permissions: Boolean = false,
    val battery: Boolean = false,
) {
    val doneCount get() = listOf(shizuku == ShizukuStatus.GRANTED, permissions, battery).count { it }
    val complete get() = doneCount == 3
}

class AppActions(
    val requestShizuku: () -> Unit = {},
    val requestPermissions: () -> Unit = {},
    val requestBattery: () -> Unit = {},
    val setMonitoring: (Boolean) -> Unit = {},
    val reconnectNow: () -> Unit = {},
    val clearHistory: () -> Unit = {},
    val openUrl: (String) -> Unit = {},
)

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("ホーム", Icons.Filled.Home),
    HISTORY("履歴", Icons.AutoMirrored.Filled.List),
    SETTINGS("設定", Icons.Filled.Settings),
}

@Composable
fun ReConnectApp(
    prefs: Prefs,
    setup: SetupState,
    monitoring: Boolean,
    actions: AppActions,
    initialTab: Int = 0,
    preview: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val status by MonitorState.status.collectAsStateWithLifecycle()
    val samples by MonitorState.samples.collectAsStateWithLifecycle()
    val events by MonitorState.events.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Crossfade(targetState = Tab.entries[tab], label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(
                        status, samples, events, setup, monitoring, actions,
                        onOpenHistory = { tab = 1 },
                        liveSignal = if (preview) status.signal else rememberLiveSignal(),
                    )
                    Tab.HISTORY -> HistoryScreen(events, samples, onClear = actions.clearHistory)
                    Tab.SETTINGS -> SettingsScreen(prefs, actions)
                }
            }
        }
    }
}
