package io.github.snowiris57.reconnect

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.snowiris57.reconnect.ui.AppActions
import io.github.snowiris57.reconnect.ui.ReConnectApp
import io.github.snowiris57.reconnect.ui.ReConnectTheme
import io.github.snowiris57.reconnect.ui.SetupState
import io.github.snowiris57.reconnect.ui.ShizukuStatus
import io.github.snowiris57.reconnect.widget.MeterWidget
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var setup by mutableStateOf(SetupState())
    private var monitoring by mutableStateOf(false)
    private var onboarded by mutableStateOf(true)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val shizukuReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        monitoring = prefs.enabled
        onboarded = prefs.onboarded

        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)

        if (prefs.enabled) MonitorService.start(this)

        val actions = AppActions(
            requestShizuku = ::onShizuku,
            requestPermissions = { permLauncher.launch(runtimePermissions()) },
            requestBattery = {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
            setMonitoring = { on ->
                prefs.enabled = on
                monitoring = on
                if (on) MonitorService.start(this) else MonitorService.stop(this)
            },
            reconnectNow = {
                MonitorService.reconnectNow(this)
            },
            clearHistory = {
                MonitorState.clearEvents()
                MeterWidget.updateAll(this)
            },
            openUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
            pinWidget = ::pinWidget,
            dismissWidgetHint = {
                prefs.widgetHintDismissed = true
                refresh()
            },
            addTile = ::addTile,
            finishOnboarding = {
                prefs.onboarded = true
                onboarded = true
            },
            showWelcome = { onboarded = false },
        )

        setContent {
            ReConnectTheme {
                ReConnectApp(prefs = prefs, setup = setup, monitoring = monitoring, onboarded = onboarded, actions = actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        Shizuku.removeBinderReceivedListener(shizukuReceived)
        Shizuku.removeBinderDeadListener(shizukuDead)
        super.onDestroy()
    }

    private fun refresh() {
        monitoring = prefs.enabled
        onboarded = prefs.onboarded
        setup = SetupState(
            shizuku = when {
                Shell.shizukuGranted() -> ShizukuStatus.GRANTED
                Shell.shizukuRunning() -> ShizukuStatus.NOT_GRANTED
                else -> ShizukuStatus.NOT_RUNNING
            },
            permissions = runtimePermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            },
            battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            widgetPlaced = widgetManager().getAppWidgetIds(widgetComponent()).isNotEmpty(),
            canPinWidget = widgetManager().isRequestPinAppWidgetSupported,
            widgetHintDismissed = prefs.widgetHintDismissed,
            canAddTile = Build.VERSION.SDK_INT >= 33,
        )
        MeterWidget.updateAll(this)
    }

    private fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun onShizuku() {
        when {
            !Shell.shizukuRunning() -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launch != null) {
                    toast("Shizuku アプリでサービスを開始してください")
                    startActivity(launch)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
                }
            }

            Shizuku.isPreV11() -> toast("Shizuku が古すぎます。更新してください")
            !Shell.shizukuGranted() -> Shizuku.requestPermission(REQ_SHIZUKU)
            else -> toast("許可済みです")
        }
    }

    private fun widgetManager() = AppWidgetManager.getInstance(this)
    private fun widgetComponent() = ComponentName(this, MeterWidget::class.java)

    private fun pinWidget() {
        if (!widgetManager().requestPinAppWidget(widgetComponent(), null, null)) {
            toast("ホーム画面を長押しして「ウィジェット」から ReConnect を追加してください")
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, MonitorTileService::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_notification),
            mainExecutor,
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> toast("タイルを追加しました")
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> toast("タイルはすでに追加されています")
                else -> Unit
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_SHIZUKU = 1
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
    }
}
