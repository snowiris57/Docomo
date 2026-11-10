package io.github.snowiris57.reconnect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvShizuku: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnPerms: Button
    private lateinit var btnBattery: Button
    private lateinit var swMonitor: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etInterval: EditText
    private lateinit var etFail: EditText
    private lateinit var cbSignal: CheckBox
    private lateinit var etRsrp: EditText
    private lateinit var etCooldown: EditText
    private lateinit var etMaxHour: EditText
    private lateinit var cbAirplane: CheckBox

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSetup() }

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshSetup() } }
    private val shizukuReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshSetup() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshSetup() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvShizuku = findViewById(R.id.tvShizuku)
        btnShizuku = findViewById(R.id.btnShizuku)
        btnPerms = findViewById(R.id.btnPerms)
        btnBattery = findViewById(R.id.btnBattery)
        swMonitor = findViewById(R.id.swMonitor)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        etInterval = findViewById(R.id.etInterval)
        etFail = findViewById(R.id.etFail)
        cbSignal = findViewById(R.id.cbSignal)
        etRsrp = findViewById(R.id.etRsrp)
        etCooldown = findViewById(R.id.etCooldown)
        etMaxHour = findViewById(R.id.etMaxHour)
        cbAirplane = findViewById(R.id.cbAirplane)

        Shizuku.addRequestPermissionResultListener(shizukuResult)
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)

        btnShizuku.setOnClickListener { onShizukuClick() }
        btnPerms.setOnClickListener { permLauncher.launch(runtimePermissions()) }
        btnBattery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        swMonitor.setOnCheckedChangeListener { _, on ->
            if (on == prefs.enabled) return@setOnCheckedChangeListener
            prefs.enabled = on
            if (on) MonitorService.start(this) else MonitorService.stop(this)
        }
        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            if (!Shell.shizukuGranted()) toast("Shizuku が未許可のため root で試みます")
            MonitorService.reconnectNow(this)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { MonitorState.clearLog() }

        loadSettings()
        if (prefs.enabled) MonitorService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MonitorState.status.collect { renderStatus(it) } }
                launch { MonitorState.log.collect { tvLog.text = it.joinToString("\n") } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        swMonitor.isChecked = prefs.enabled
        refreshSetup()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        Shizuku.removeBinderReceivedListener(shizukuReceived)
        Shizuku.removeBinderDeadListener(shizukuDead)
        super.onDestroy()
    }

    private fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun onShizukuClick() {
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

    private fun refreshSetup() {
        val shizukuText = when {
            Shell.shizukuGranted() -> "Shizuku: ✅ 許可済み"
            Shell.shizukuRunning() -> "Shizuku: ⚠️ 起動中・未許可"
            else -> "Shizuku: ❌ 未起動（root 端末なら root で動作）"
        }
        tvShizuku.text = shizukuText
        btnShizuku.isEnabled = !Shell.shizukuGranted()

        val permsOk = runtimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        btnPerms.isEnabled = !permsOk
        btnPerms.text = if (permsOk) "通知・電話状態の権限: 許可済み" else "通知・電話状態の権限を許可"

        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        btnBattery.isEnabled = !ignoring
        btnBattery.text = if (ignoring) "電池の最適化: 除外済み" else "電池の最適化から除外"
    }

    private fun renderStatus(s: Status) {
        tvStatus.text = buildString {
            appendLine("監視    : ${if (s.running) "実行中" else "停止"}")
            appendLine("状態    : ${s.state}")
            appendLine("電波    : ${s.signal}")
            appendLine("疎通    : ${s.probe}")
            appendLine("連続異常: ${s.badCount}/${s.failThreshold}")
            append("再接続  : ${s.recoveries}回（最終 ${s.lastRecovery}）")
        }
        if (swMonitor.isChecked != prefs.enabled) swMonitor.isChecked = prefs.enabled
    }

    private fun loadSettings() {
        etInterval.setText(prefs.intervalSec.toString())
        etFail.setText(prefs.failThreshold.toString())
        cbSignal.isChecked = prefs.signalTrigger
        etRsrp.setText(prefs.rsrpThreshold.toString())
        etCooldown.setText(prefs.cooldownSec.toString())
        etMaxHour.setText(prefs.maxPerHour.toString())
        cbAirplane.isChecked = prefs.useAirplane
    }

    private fun saveSettings() {
        etInterval.text.toString().toIntOrNull()?.let { prefs.intervalSec = it }
        etFail.text.toString().toIntOrNull()?.let { prefs.failThreshold = it }
        prefs.signalTrigger = cbSignal.isChecked
        etRsrp.text.toString().toIntOrNull()?.let { prefs.rsrpThreshold = it }
        etCooldown.text.toString().toIntOrNull()?.let { prefs.cooldownSec = it }
        etMaxHour.text.toString().toIntOrNull()?.let { prefs.maxPerHour = it }
        prefs.useAirplane = cbAirplane.isChecked
        loadSettings() // 範囲外の値を丸めた結果を表示
        toast("保存しました")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_SHIZUKU = 1
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"
    }
}
