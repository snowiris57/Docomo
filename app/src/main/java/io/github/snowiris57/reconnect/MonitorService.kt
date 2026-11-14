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
import io.github.snowiris57.reconnect.widget.MeterWidget
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

    // 「不安定」の判定に使う直近の疎通結果（回線が変わったらリセット）
    private val window = ArrayDeque<ProbeResult>()
    private var windowRoute: Route? = null

    private data class Check(
        val health: Health,
        val headline: String,
        val detail: String,
        val signal: SignalInfo?,
        val probe: ProbeResult?,
        val route: Route = Route.MOBILE,
        val wifi: WifiReading? = null,
    )

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
            buildNotification("起動しています…"),
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
                    recover(Prefs(this@MonitorService), "手動", manual = true, route = NetCheck.activeRoute(this@MonitorService))
                    if (loopJob?.isActive != true) stopSelf()
                }
            }

            else -> startLoopIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (loopJob != null) MonitorState.event(EventType.STOP, "監視を停止しました")
        MonitorState.status.update {
            it.copy(running = false, health = Health.STOPPED, headline = "監視は停止中です", detail = "", badCount = 0)
        }
        MeterWidget.updateAll(this)
        super.onDestroy()
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return
        MonitorState.event(EventType.START, "監視を開始しました")
        MonitorState.status.update { it.copy(running = true) }
        loopJob = scope.launch { loop() }
        // 通信量のグラフ用。見守りの間隔とは別に 10 秒ごと
        scope.launch {
            while (isActive) {
                Traffic.tick()
                delay(Traffic.STEP_MS)
            }
        }
    }

    private suspend fun loop() {
        var bad = 0
        while (scope.isActive) {
            val p = Prefs(this)
            if (!recoverLock.isLocked) {
                val c = check(p)
                bad = if (c.health == Health.BAD) bad + 1 else 0
                MonitorState.addSample(
                    Sample(
                        time = System.currentTimeMillis(),
                        // dBm（モバイルは RSRP、Wi-Fi は RSSI）
                        rsrp = if (c.route == Route.WIFI) c.wifi?.rssi else c.signal?.rsrp,
                        // 通信品質（電波と応答時間の弱い方）
                        quality = Quality.combined(
                            if (c.route == Route.WIFI) c.wifi?.quality else c.signal?.quality,
                            c.probe,
                        ),
                        latencyMs = c.probe?.takeIf { it.ok }?.ms,
                        ok = c.probe?.ok ?: if (c.health == Health.BAD) false else null,
                        health = c.health,
                        route = c.route,
                        hopMs = c.probe?.hopMs,
                    )
                )

                var detail = c.detail
                var shouldRecover = false
                if (c.health == Health.BAD) {
                    detail = "$detail（${bad}/${p.failThreshold}）"
                    if (bad == p.failThreshold) MonitorState.event(EventType.DETECT, "異常を検知: ${c.detail}")
                    if (bad >= p.failThreshold) {
                        val wait = if (c.route == Route.WIFI && !p.wifiRecover) "自動の入れ直しはオフ" else waitReason(p)
                        if (wait == null) shouldRecover = true else detail = "$detail ・ $wait"
                    }
                }
                publish(c.copy(detail = detail), bad, p)

                if (shouldRecover) {
                    recover(p, c.detail, manual = false, route = c.route)
                    bad = 0
                }
            }
            delay(p.intervalSec * 1000L)
        }
    }

    private suspend fun check(p: Prefs): Check {
        val airplane = NetCheck.airplaneModeOn(this)
        val sig = if (airplane) null else NetCheck.signal(this)

        // Wi-Fi モード（テザリングを含む）
        if (NetCheck.activeRoute(this) == Route.WIFI) {
            val wifi = NetCheck.wifi(this)
            // 「テザリングは」「Wi-Fi は」と自然に読めるように
            val name = if (wifi?.tethering == true) "テザリング" else "Wi-Fi "
            if (!p.wifiMonitor) {
                return Check(Health.PAUSED, "${name}に接続中", "Wi-Fi の見守りはオフです", sig, null, Route.WIFI, wifi)
            }
            val net = NetCheck.wifiNetwork(this)
                ?: return Check(Health.BAD, "${name}に問題があります", "Wi-Fi が見つかりません", sig, null, Route.WIFI, wifi)
            val probe = NetCheck.probe(net, measureHop = true)
            val unstable = unstableReason(p, Route.WIFI, probe)
            if (!probe.ok) {
                val where = if (probe.hopMs == null) "Wi-Fi 区間から応答がありません" else "つながっているのに通信できません"
                return Check(Health.BAD, "${name}に問題があります", where, sig, probe, Route.WIFI, wifi)
            }
            if (unstable != null) {
                return Check(Health.UNSTABLE, "${name}が不安定です", unstable, sig, probe, Route.WIFI, wifi)
            }
            return Check(Health.OK, "${name}は安定しています", "${p.intervalSec}秒ごとに見守っています", sig, probe, Route.WIFI, wifi)
        }

        // モバイルモード
        if (airplane) {
            return Check(Health.PAUSED, "機内モード中", "機内モードの間は監視を休みます", null, null)
        }
        if (!NetCheck.mobileDataEnabled(this)) {
            return Check(Health.PAUSED, "モバイルデータがオフ", "オンになると監視を再開します", sig, null)
        }

        val cell = NetCheck.cellularNetwork(this)
            ?: return Check(Health.BAD, "通信に問題があります", "モバイル回線が見つかりません", sig, null)

        val probe = NetCheck.probe(cell)
        val unstable = unstableReason(p, Route.MOBILE, probe)
        if (!probe.ok) {
            return Check(Health.BAD, "通信に問題があります", "応答なし — パケ止まりの疑い", sig, probe)
        }

        val rsrp = sig?.rsrp
        if (p.signalTrigger && rsrp != null && rsrp < p.rsrpThreshold) {
            return Check(Health.BAD, "電波が弱くなっています", "RSRP $rsrp dBm（閾値 ${p.rsrpThreshold}）", sig, probe)
        }
        if (unstable != null) {
            return Check(Health.UNSTABLE, "通信が不安定です", unstable, sig, probe)
        }
        return Check(Health.OK, "通信は安定しています", "${p.intervalSec}秒ごとに見守っています", sig, probe)
    }

    /** 通じてはいるが「遅い・ときどき途切れる」なら理由を返す */
    private fun unstableReason(p: Prefs, route: Route, probe: ProbeResult): String? {
        if (windowRoute != route) {
            window.clear()
            windowRoute = route
        }
        window.addLast(probe)
        while (window.size > 6) window.removeFirst()
        if (!probe.ok) return null
        if (probe.ms > p.slowMs) return "応答 ${probe.ms}ms（目安は ${p.slowMs}ms 以内）"
        val fails = window.count { !it.ok }
        if (fails >= 2) return "直近で応答なしが${fails}回ありました"
        val oks = window.filter { it.ok }.map { it.ms }.sorted()
        if (oks.size >= 3 && oks[oks.size / 2] > p.slowMs * 3 / 4) return "応答が遅めの状態が続いています"
        return null
    }

    private fun publish(c: Check, bad: Int, p: Prefs) {
        MonitorState.status.update {
            it.copy(
                running = true,
                health = c.health,
                headline = c.headline,
                detail = c.detail,
                route = c.route,
                signal = c.signal ?: it.signal,
                wifi = c.wifi,
                probe = c.probe,
                badCount = bad,
                failThreshold = p.failThreshold,
                updatedAt = System.currentTimeMillis(),
            )
        }
        refreshSurfaces()
    }

    /** 再接続を今は控えるべき理由。null なら実行可 */
    private fun waitReason(p: Prefs): String? {
        val now = SystemClock.elapsedRealtime()
        while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > 3_600_000) recoveryTimes.removeFirst()
        if (recoveryTimes.size >= p.maxPerHour) return "1時間の上限（${p.maxPerHour}回）に到達"
        if (now < nextAllowedAt) return "待機中 あと${(nextAllowedAt - now) / 1000}秒"
        return null
    }

    private suspend fun recover(p: Prefs, reason: String, manual: Boolean, route: Route): Boolean {
        if (!recoverLock.tryLock()) return false
        try {
            if (route == Route.MOBILE && NetCheck.airplaneModeOn(this)) {
                MonitorState.event(EventType.INFO, "機内モード中のため再接続しませんでした")
                return false
            }
            MonitorState.event(
                EventType.RECOVER_START,
                (if (route == Route.WIFI) "[Wi-Fi] " else "") +
                    if (manual) "手動で再接続を開始" else "自動で再接続を開始（$reason）",
            )

            val ok = if (route == Route.WIFI) runWifiRecovery() else runRecoverySteps(p)

            val now = SystemClock.elapsedRealtime()
            if (!manual) recoveryTimes.addLast(now)
            backoffSec = if (ok) p.cooldownSec else (backoffSec * 2).coerceIn(p.cooldownSec, 1800)
            nextAllowedAt = now + backoffSec * 1000L

            if (ok) {
                MonitorState.event(EventType.RECOVER_OK, "通信が復旧しました")
                setHealth(Health.OK, "通信が復旧しました", "再接続に成功")
            } else {
                MonitorState.event(EventType.RECOVER_FAIL, "復旧できませんでした（次の再接続まで${backoffSec}秒）")
                setHealth(Health.BAD, "再接続に失敗しました", "${backoffSec}秒後に再挑戦します")
            }
            return ok
        } finally {
            recoverLock.unlock()
        }
    }

    private suspend fun runRecoverySteps(p: Prefs): Boolean {
        var dataOff = false
        var airplaneOn = false
        try {
            setHealth(Health.RECOVERING, "再接続しています…", "モバイルデータを入れ直しています")
            MonitorState.event(EventType.STEP, "Step 1: モバイルデータ OFF → ON")
            val r = Radio.setMobileData(false)
            if (!r.ok) {
                MonitorState.event(EventType.ERROR, "操作できませんでした: ${r.output}")
                return false
            }
            dataOff = true
            delay(3_000)
            Radio.setMobileData(true)
            dataOff = false
            if (waitHealthy(25_000)) return true

            if (!p.useAirplane) return false
            setHealth(Health.RECOVERING, "再接続しています…", "機内モードで電波をつかみ直しています")
            MonitorState.event(EventType.STEP, "Step 2: 機内モード ON → OFF")
            val r2 = Radio.setAirplaneMode(true)
            if (!r2.ok) {
                MonitorState.event(EventType.ERROR, "操作できませんでした: ${r2.output}")
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

    /** Wi-Fi を OFF→ON して、つなぎ直させる（保存済みの Wi-Fi に自動で戻る） */
    private suspend fun runWifiRecovery(): Boolean {
        var wifiOff = false
        try {
            setHealth(Health.RECOVERING, "再接続しています…", "Wi-Fi を入れ直しています")
            MonitorState.event(EventType.STEP, "Wi-Fi OFF → ON")
            val r = Radio.setWifi(false)
            if (!r.ok) {
                MonitorState.event(EventType.ERROR, "操作できませんでした: ${r.output}")
                return false
            }
            wifiOff = true
            delay(3_000)
            Radio.setWifi(true)
            wifiOff = false
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(4_000)
                val net = NetCheck.wifiNetwork(this) ?: continue
                if (NetCheck.probe(net).ok) return true
            }
            return false
        } finally {
            if (wifiOff) withContext(NonCancellable) { Radio.setWifi(true) }
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

    private fun setHealth(health: Health, headline: String, detail: String) {
        MonitorState.status.update {
            it.copy(health = health, headline = headline, detail = detail, badCount = 0, updatedAt = System.currentTimeMillis())
        }
        refreshSurfaces()
    }

    private fun refreshSurfaces() {
        updateNotification()
        MeterWidget.updateAll(this)
    }

    private fun updateNotification() {
        val st = MonitorState.status.value
        val text = listOfNotNull(
            st.headline,
            if (st.route == Route.WIFI) st.wifi?.short() else st.signal?.short(),
            st.probe?.takeIf { it.ok }?.let { "${it.ms}ms" },
        ).joinToString(" ・ ")
        val busy = st.health == Health.RECOVERING
        val key = "$busy|$text"
        if (key == lastNotified) return
        lastNotified = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, busy))
    }

    private fun buildNotification(text: String, busy: Boolean = false) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (busy) "回線をつなぎ直しています" else "回線を見守っています")
        .setProgress(0, 0, busy)
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(openAppIntent(this))
        .addAction(0, "今すぐ再接続", servicePendingIntent(ACTION_RECONNECT, 1))
        .addAction(0, "停止", servicePendingIntent(ACTION_STOP, 2))
        .build()

    private fun servicePendingIntent(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, MonitorService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "io.github.snowiris57.reconnect.START"
        private const val ACTION_STOP = "io.github.snowiris57.reconnect.STOP"
        private const val ACTION_RECONNECT = "io.github.snowiris57.reconnect.RECONNECT"

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
        )

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        fun reconnectNow(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_RECONNECT)
        )

        /** ウィジェットのボタン用 */
        fun reconnectPendingIntent(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
            ctx, 10, Intent(ctx, MonitorService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** アプリを開くときに表示するタブ（[MainActivity] が読む） */
        const val EXTRA_TAB = "io.github.snowiris57.reconnect.TAB"
        const val TAB_SETTINGS = 2

        /** アプリを開く。[tab] を渡すとそのタブを表示する */
        fun openAppIntent(ctx: Context, tab: Int? = null): PendingIntent = PendingIntent.getActivity(
            ctx, if (tab == null) 0 else 100 + tab,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (tab != null) putExtra(EXTRA_TAB, tab) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
