package io.github.snowiris57.reconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.BuildConfig
import io.github.snowiris57.reconnect.Prefs
import io.github.snowiris57.reconnect.SignalInfo
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(prefs: Prefs, setup: SetupState, actions: AppActions) {
    var interval by remember { mutableFloatStateOf(prefs.intervalSec.toFloat()) }
    var fail by remember { mutableFloatStateOf(prefs.failThreshold.toFloat()) }
    var signalTrigger by remember { mutableStateOf(prefs.signalTrigger) }
    var rsrp by remember { mutableFloatStateOf(prefs.rsrpThreshold.toFloat()) }
    var airplane by remember { mutableStateOf(prefs.useAirplane) }
    var cooldown by remember { mutableFloatStateOf(prefs.cooldownSec.toFloat()) }
    var maxHour by remember { mutableFloatStateOf(prefs.maxPerHour.toFloat()) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("設定", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Group("検知") {
                SliderSetting(
                    title = "チェック間隔",
                    value = interval,
                    valueText = "${interval.roundToInt()}秒",
                    help = "短いほど早く気づけますが、電池を少し多く使います",
                    range = 5f..120f, step = 5f,
                    onChange = { interval = it },
                    onDone = { prefs.intervalSec = interval.roundToInt() },
                )
                Divider()
                SliderSetting(
                    title = "異常と判定するまで",
                    value = fail,
                    valueText = "${fail.roundToInt()}回連続",
                    help = "およそ ${(fail * interval).roundToInt()} 秒つながらなければ再接続します",
                    range = 1f..10f, step = 1f,
                    onChange = { fail = it },
                    onDone = { prefs.failThreshold = fail.roundToInt() },
                )
                Divider()
                SwitchSetting(
                    title = "電波が弱いときも再接続",
                    help = "通信できていても、電波が下の値より弱ければ再接続します",
                    checked = signalTrigger,
                    onChange = { signalTrigger = it; prefs.signalTrigger = it },
                )
                SliderSetting(
                    title = "電波が弱いとみなす値",
                    value = rsrp,
                    valueText = "${rsrp.roundToInt()} dBm",
                    help = "これより弱い（${SignalInfo.rsrpLabel(rsrp.roundToInt() - 1)}）とき",
                    range = -140f..-90f, step = 1f,
                    enabled = signalTrigger,
                    onChange = { rsrp = it },
                    onDone = { prefs.rsrpThreshold = rsrp.roundToInt() },
                )
            }
        }
        item {
            Group("再接続") {
                SwitchSetting(
                    title = "機内モードも使う",
                    help = "モバイルデータの入れ直しで直らないとき、機内モードを ON → OFF して電波をつかみ直します",
                    checked = airplane,
                    onChange = { airplane = it; prefs.useAirplane = it },
                )
                Divider()
                SliderSetting(
                    title = "再接続のあとの待ち時間",
                    value = cooldown,
                    valueText = "${cooldown.roundToInt()}秒",
                    help = "失敗が続くと倍々に延ばします（最大30分）。障害時に連打して悪化させないためです",
                    range = 10f..600f, step = 10f,
                    onChange = { cooldown = it },
                    onDone = { prefs.cooldownSec = cooldown.roundToInt() },
                )
                Divider()
                SliderSetting(
                    title = "1時間あたりの上限",
                    value = maxHour,
                    valueText = "${maxHour.roundToInt()}回",
                    help = "これを超えたら、その1時間は自動の再接続を控えます",
                    range = 1f..20f, step = 1f,
                    onChange = { maxHour = it },
                    onDone = { prefs.maxPerHour = maxHour.roundToInt() },
                )
            }
        }
        item {
            Group("便利な機能") {
                if (setup.canPinWidget) {
                    LinkItem(
                        if (setup.widgetPlaced) "電波メーターをもう1つ追加" else "ホーム画面に電波メーターを置く",
                        "2×2 はメーター、横に広げると再接続ボタンも出ます",
                    ) { actions.pinWidget() }
                    Divider()
                }
                if (setup.canAddTile) {
                    LinkItem("クイック設定にタイルを追加", "通知パネルから見守りを ON/OFF できます") { actions.addTile() }
                    Divider()
                }
                LinkItem("使い方をもう一度見る", "最初の案内画面を表示します") { actions.showWelcome() }
            }
        }
        item {
            Group("このアプリについて") {
                ListItem(
                    headlineContent = { Text("しくみ") },
                    supportingContent = {
                        Text(
                            "一定間隔でモバイル回線から google.com に小さな通信を送り、届かない状態が続いたら" +
                                "「モバイルデータ OFF→ON」、それでもだめなら「機内モード ON→OFF」で回線を入れ直します。" +
                                "Wi-Fi・機内モード・モバイルデータ OFF の間は休みます。"
                        )
                    },
                    colors = itemColors(),
                )
                Divider()
                LinkItem("Shizuku について", "回線の操作に使う、root 不要の仕組み") { actions.openUrl("https://shizuku.rikka.app/") }
                Divider()
                ListItem(
                    headlineContent = { Text("バージョン") },
                    trailingContent = { Text(BuildConfig.VERSION_NAME) },
                    colors = itemColors(),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        SectionCard { Column(Modifier.padding(vertical = 6.dp)) { content() } }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun itemColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueText: String,
    help: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = { onChange((it / step).roundToInt() * step) },
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
        )
    }
}

@Composable
private fun SwitchSetting(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(help) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = itemColors(),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun LinkItem(title: String, body: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = itemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
