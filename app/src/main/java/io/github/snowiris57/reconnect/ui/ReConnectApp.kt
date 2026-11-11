package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.unit.dp
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
    // 以下は「準備」の数には含めない便利機能
    val widgetPlaced: Boolean = false,
    val canPinWidget: Boolean = false,
    val widgetHintDismissed: Boolean = false,
    val canAddTile: Boolean = false,
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
    val pinWidget: () -> Unit = {},
    val dismissWidgetHint: () -> Unit = {},
    val addTile: () -> Unit = {},
    val finishOnboarding: () -> Unit = {},
    val showWelcome: () -> Unit = {},
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
    onboarded: Boolean,
    actions: AppActions,
    initialTab: Int = 0,
    preview: Boolean = false,
    previewTouch: Float? = null,
) {
    AnimatedContent(
        targetState = onboarded,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
        label = "onboarding",
    ) { done ->
        if (!done) {
            WelcomeScreen(onStart = actions.finishOnboarding, animate = !preview)
        } else {
            MainScaffold(prefs, setup, monitoring, actions, initialTab, preview, previewTouch)
        }
    }
}

@Composable
private fun MainScaffold(
    prefs: Prefs,
    setup: SetupState,
    monitoring: Boolean,
    actions: AppActions,
    initialTab: Int,
    preview: Boolean,
    previewTouch: Float?,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val status by MonitorState.status.collectAsStateWithLifecycle()
    val samples by MonitorState.samples.collectAsStateWithLifecycle()
    val events by MonitorState.events.collectAsStateWithLifecycle()

    BoxWithConstraints {
        // タブレット・横向きは左のナビゲーションレール、スマホは下のナビゲーションバー
        val wide = maxWidth >= 840.dp
        Scaffold(
            bottomBar = {
                if (!wide) {
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
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(Modifier.padding(top = 8.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Tab.entries.forEachIndexed { i, t ->
                            NavigationRailItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    Crossfade(targetState = Tab.entries[tab], label = "tab") { t ->
                        when (t) {
                            Tab.HOME -> HomeScreen(
                                status, samples, events, setup, monitoring, actions,
                                onOpenHistory = { tab = 1 },
                                liveSignal = if (preview) status.signal else rememberLiveSignal(),
                                previewTouch = previewTouch,
                            )
                            Tab.HISTORY -> HistoryScreen(events, samples, onClear = actions.clearHistory)
                            Tab.SETTINGS -> SettingsScreen(prefs, setup, actions)
                        }
                    }
                }
            }
        }
    }
}
