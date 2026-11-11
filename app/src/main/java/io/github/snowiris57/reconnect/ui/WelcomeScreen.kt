package io.github.snowiris57.reconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class DemoPhase(val quality: Float, val caption: String, val unit: String, val label: String)

private val demo = listOf(
    DemoPhase(0.82f, "5G NSA", "-91 dBm ・ 良好", "いつもは静かに見守っています"),
    DemoPhase(0.22f, "LTE", "応答なし", "アンテナは立っているのに通信できない…"),
    DemoPhase(0.22f, "LTE", "", "パケ止まりを検知。つなぎ直します"),
    DemoPhase(0.86f, "5G NSA", "-88 dBm ・ とても強い", "復旧しました"),
)

/** 初回起動時の案内。メーターが「安定 → パケ止まり → 再接続 → 復旧」を自動で演じる */
@Composable
fun WelcomeScreen(onStart: () -> Unit, animate: Boolean = true) {
    var phase by remember { mutableIntStateOf(0) }
    var celebrate by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            phase = 0; delay(2600)
            phase = 1; delay(2200)
            phase = 2; delay(2200)
            phase = 3; celebrate = System.nanoTime(); delay(2600)
        }
    }
    val d = demo[phase]
    val bg = Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface)
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).background(bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text("ReConnect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "「つながらない」を、\n自動でつなぎ直す。",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            SignalGauge(
                quality = d.quality,
                caption = d.caption,
                unit = d.unit,
                recovering = phase == 2,
                celebrateKey = celebrate,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
            AnimatedContent(
                targetState = d.label,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 })
                },
                label = "demoLabel",
            ) {
                Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Feature(Icons.Filled.Search, "見張る", "モバイル回線も Wi-Fi（テザリング）も、一定間隔で通り具合を確かめます")
                Feature(Icons.Filled.Refresh, "つなぎ直す", "通信が止まったら、モバイルはデータ → 機内モード、Wi-Fi は OFF → ON で入れ直します")
                Feature(Icons.Filled.Lock, "やりすぎない", "待ち時間と回数の上限で、通信障害のときに悪化させません")
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("はじめる", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "回線の操作には Shizuku（root 不要）を使います",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp).size(22.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
