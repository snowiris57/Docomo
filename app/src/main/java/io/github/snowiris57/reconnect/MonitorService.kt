package io.github.snowiris57.reconnect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常駐して回線を監視し、パケ止まりや電波劣化を検知したら再接続する。
 *
 * 再接続の手順:
 *   Step1: モバイルデータ OFF → ON（軽い）
 *   Step2: 機内モード ON → OFF（重いが効きやすい。設定で無効化可）
 *
 * 再接続の連打は逆効果になりうるので、クールダウン（失敗時は倍々）と1時間あたりの上限を設ける。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val recoverLock = Mutex()

    private val recoveryTimes = ArrayDeque<Long>()
    private var nextAllowedAt = 0L
    private var backoffSec = 0
    private var lastNotified = ""

    private sealed class Verdict(val text: String) {
        class Ok(text: String) : Verdict(text)
        class Skip(text: String) : Verdict(text)
        class Bad(text: String) : Verdict(text)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("起動中…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).enabled = false
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                if (Prefs(this).enabled) startLoopIfNeeded()
                scope.launch {
                    recover(Prefs(this@MonitorService), "手動", manual = true)
                    if (loopJob?.isActive != true) stopSelf()
                }
            }

            else -> startLoopIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        MonitorState.status.update { it.copy(running = false, state = "停止") }
        super.onDestroy()
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        MonitorState.log("監視を開始")
        MonitorState.status.update { it.copy(running = true) }
        loopJob = scope.launch { loop() }
    }

    private suspend fun loop() {
        var bad = 0
        while (scope.isActive) {
            val p = Prefs(this)
            if (!recoverLock.isLocked) {
                val v = evaluate(p)
                bad = if (v is Verdict.Bad) bad + 1 else 0
                MonitorState.status.update {
                    it.copy(running = true, state = v.text, badCount = bad, failThreshold = p.failThreshold)
                }

                if (bad >= p.failThreshold) {
                    val wait = waitReason(p)
                    if (wait == null) {
                        recover(p, v.text, manual = false)
                        bad = 0
                    } else {
                        MonitorState.status.update { it.copy(state = "${v.text}（$wait）") }
                    }
                }
                updateNotification()
            }
            delay(p.intervalSec * 1000L)
        }
    }

    private suspend fun evaluate(p: Prefs): Verdict {
        if (NetCheck.airplaneModeOn(this)) return Verdict.Skip("機内モード中のため休止")
        if (!NetCheck.mobileDataEnabled(this)) return Verdict.Skip("モバイルデータOFFのため休止")
        if (NetCheck.onWifi(this)) return Verdict.Skip("Wi-Fi 接続中のため休止")

        val sig = NetCheck.signal(this)
        MonitorState.status.update { it.copy(signal = sig.describe()) }

        val cell = NetCheck.cellularNetwork(this)
        if (cell == null) {
            MonitorState.status.update { it.copy(probe = "-") }
            return Verdict.Bad("モバイル回線なし")
        }

        val probe = NetCheck.probe(cell)
        MonitorState.status.update { it.copy(probe = probe.describe()) }
        if (!probe.ok) return Verdict.Bad("疎通NG（パケ止まりの疑い）")

        val rsrp = sig.rsrp
        if (p.signalTrigger && rsrp != null && rsrp < p.rsrpThreshold) {
            return Verdict.Bad("電波弱 (RSRP $rsrp < ${p.rsrpThreshold})")
        }
        return Verdict.Ok("正常")
    }

    /** 再接続を今は控えるべき理由。null なら実行可 */
    private fun waitReason(p: Prefs): String? {
        val now = SystemClock.elapsedRealtime()
        while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > 3_600_000) recoveryTimes.removeFirst()
        if (recoveryTimes.size >= p.maxPerHour) return "1時間の上限 ${p.maxPerHour} 回に到達"
        if (now < nextAllowedAt) return "クールダウン中 残り${(nextAllowedAt - now) / 1000}秒"
        return null
    }

    private suspend fun recover(p: Prefs, reason: String, manual: Boolean): Boolean {
        if (!recoverLock.tryLock()) return false
        try {
            if (NetCheck.airplaneModeOn(this)) {
                MonitorState.log("機内モード中なので再接続しません")
                return false
            }
            MonitorState.log("再接続開始: $reason")
            setState("再接続中…")

            val ok = runRecoverySteps(p)

            val now = SystemClock.elapsedRealtime()
            if (!manual) recoveryTimes.addLast(now)
            backoffSec = if (ok) p.cooldownSec else (backoffSec * 2).coerceIn(p.cooldownSec, 1800)
            nextAllowedAt = now + backoffSec * 1000L

            val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
            MonitorState.status.update {
                it.copy(
                    recoveries = it.recoveries + 1,
                    lastRecovery = "$time ${if (ok) "成功" else "失敗"}",
                    state = if (ok) "正常（再接続済み）" else "再接続失敗",
                    badCount = 0,
                )
            }
            MonitorState.log(if (ok) "復旧しました" else "復旧できませんでした（次回まで ${backoffSec}秒）")
            updateNotification()
            return ok
        } finally {
            recoverLock.unlock()
        }
    }

    private suspend fun runRecoverySteps(p: Prefs): Boolean {
        var dataOff = false
        var airplaneOn = false
        try {
            MonitorState.log("Step1: モバイルデータ OFF → ON")
            val r = Radio.setMobileData(false)
            if (!r.ok) {
                MonitorState.log("コマンド失敗: ${r.output}")
                return false
            }
            dataOff = true
            delay(3_000)
            Radio.setMobileData(true)
            dataOff = false
            if (waitHealthy(25_000)) return true

            if (!p.useAirplane) return false
            MonitorState.log("Step2: 機内モード ON → OFF")
            val r2 = Radio.setAirplaneMode(true)
            if (!r2.ok) {
                MonitorState.log("コマンド失敗: ${r2.output}")
                return false
            }
            airplaneOn = true
            delay(5_000)
            Radio.setAirplaneMode(false)
            airplaneOn = false
            return waitHealthy(40_000)
        } finally {
            // 途中でサービスが止められても、無線を切ったままにしない
            if (dataOff || airplaneOn) {
                withContext(NonCancellable) {
                    if (airplaneOn) Radio.setAirplaneMode(false)
                    if (dataOff) Radio.setMobileData(true)
                }
            }
        }
    }

    private suspend fun waitHealthy(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(5_000)
            if (NetCheck.onWifi(this)) return true
            val cell = NetCheck.cellularNetwork(this) ?: continue
            if (NetCheck.probe(cell).ok) return true
        }
        return false
    }

    private fun setState(s: String) {
        MonitorState.status.update { it.copy(state = s) }
        updateNotification()
    }

    private fun updateNotification() {
        val st = MonitorState.status.value
        val text = "${st.state} ・ ${st.signal}"
        if (text == lastNotified) return
        lastNotified = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("回線監視中")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(0, "今すぐ再接続", servicePendingIntent(ACTION_RECONNECT, 1))
        .addAction(0, "停止", servicePendingIntent(ACTION_STOP, 2))
        .build()

    private fun servicePendingIntent(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, MonitorService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RECONNECT = "reconnect"

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
        )

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        fun reconnectNow(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_RECONNECT)
        )
    }
}
