package io.github.snowiris57.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

data class SignalInfo(
    /** 0(圏外/最弱)〜4 */
    val level: Int?,
    /** LTE の RSRP（NSA 5G でもアンカーの LTE を優先）。無ければ NR の SS-RSRP */
    val rsrp: Int?,
    val type: String,
    val carrier: String = "",
) {
    /** 0.0〜1.0 の電波品質。RSRP -140dBm → 0、-80dBm → 1 */
    val quality: Float?
        get() = rsrp?.let { ((it + 140) / 60f).coerceIn(0f, 1f) } ?: level?.let { it / 4f }

    fun short(): String = buildString {
        append(type)
        rsrp?.let { append(" $it dBm") }
    }

    fun describe(): String = buildString {
        append(type)
        level?.let { append(" / レベル $it/4") }
        rsrp?.let { append(" / RSRP $it dBm") }
    }

    companion object {
        /** RSRP の目安を言葉で */
        fun rsrpLabel(rsrp: Int): String = when {
            rsrp >= -85 -> "とても強い"
            rsrp >= -100 -> "良好"
            rsrp >= -110 -> "普通"
            rsrp >= -120 -> "弱い"
            else -> "とても弱い"
        }
    }
}

/** いま使っている回線 */
enum class Route { MOBILE, WIFI }

/** Wi-Fi 7 の MLO のリンク 1 本 */
data class MloInfo(val band: Int, val channel: Int, val active: Boolean, val mbps: Int) {
    val bandName: String
        get() = when (band) {
            1 -> "2.4"
            2 -> "5"
            8 -> "6"
            16 -> "60"
            else -> "?"
        }

    fun describe(): String = "${bandName}GHz ch$channel ${if (active) "使用中" else "待機"}" + (if (mbps > 0) " ${mbps}Mbps" else "")

    companion object {
        /** 例: "5+6GHz" */
        fun bands(links: List<MloInfo>): String =
            links.sortedBy { it.band }.map { it.bandName }.distinct().joinToString("+") + "GHz"
    }
}

/** Wi-Fi の状態（テザリングを含む） */
data class WifiReading(
    val rssi: Int,
    val linkMbps: Int?,
    val freqMhz: Int,
    /** "Wi-Fi 6" など。取れなければ null */
    val standard: String?,
    /** 位置情報の許可が無いと null */
    val ssid: String?,
    /** 従量制の Wi-Fi（iPhone などのテザリング）と判断できたか */
    val tethering: Boolean,
    /** Wi-Fi 7 の MLO で複数の帯域を同時に使っているとき（例: "5+6GHz"） */
    val mloBands: String? = null,
) {
    /** 0.0〜1.0。RSSI -90dBm → 0、-40dBm → 1 */
    val quality: Float get() = ((rssi + 90) / 50f).coerceIn(0f, 1f)

    val band: String
        get() = mloBands ?: when {
            freqMhz < 3000 -> "2.4GHz"
            freqMhz < 5925 -> "5GHz"
            else -> "6GHz"
        }

    val channel: Int?
        get() = when {
            freqMhz == 2484 -> 14
            freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
            freqMhz in 5000..5900 -> (freqMhz - 5000) / 5
            freqMhz in 5925..7125 -> (freqMhz - 5950) / 5
            else -> null
        }

    /** メーター上の小見出し（例: "テザリング ・ 5GHz"） */
    val caption: String get() = "${if (tethering) "テザリング" else "Wi-Fi"} ・ $band"

    fun short(): String = "Wi-Fi $band $rssi dBm" + (linkMbps?.let { " ${formatSpeed(it)}" } ?: "")

    /** 例: "77 Mbps"、"1.2 Gbps" */
    val speedText: String? get() = linkMbps?.let { formatSpeed(it) }

    companion object {
        /** 1000 Mbps 以上は Gbps で表す */
        fun formatSpeed(mbps: Int): String = speedParts(mbps).let { (v, u) -> "$v $u" }

        /** 数値と単位に分ける（例: "1.2" と "Gbps"） */
        fun speedParts(mbps: Int): Pair<String, String> =
            if (mbps >= 1000) String.format(java.util.Locale.US, "%.1f", mbps / 1000.0).removeSuffix(".0") to "Gbps"
            else "$mbps" to "Mbps"

        fun rssiLabel(rssi: Int): String = when {
            rssi >= -50 -> "とても強い"
            rssi >= -60 -> "良好"
            rssi >= -70 -> "普通"
            rssi >= -80 -> "弱い"
            else -> "とても弱い"
        }
    }
}

/**
 * 疎通チェックの結果。
 * [ms] はインターネットまでの往復時間（ping に近い値。TCP の接続にかかった時間）。
 * [hopMs] は Wi-Fi 区間（スマホ ↔ ルーター/iPhone）の往復時間。Wi-Fi のときだけ。
 */
data class ProbeResult(
    val ok: Boolean,
    val ms: Long,
    val error: String? = null,
    val hopMs: Long? = null,
) {
    fun describe(): String = if (ok) "OK (${ms}ms)" else "NG (${error ?: "不明"})"
}

/** 電波と応答時間から「通信品質」（0〜1）を出す */
object Quality {
    /** 応答時間の点数。60ms 以下で満点、800ms で ほぼ 0 */
    fun latency(ms: Long): Float {
        if (ms <= 60) return 1f
        val q = 1f - (kotlin.math.ln(ms / 60.0) / kotlin.math.ln(800 / 60.0)).toFloat() * 0.95f
        return q.coerceIn(0.05f, 1f)
    }

    /** 電波が強くても、応答が遅ければ低くなる（弱い方に合わせる） */
    fun combined(signal: Float?, probe: ProbeResult?): Float? = when {
        probe == null -> signal
        !probe.ok -> 0.03f
        signal == null -> latency(probe.ms)
        else -> minOf(signal, latency(probe.ms))
    }
}

object NetCheck {
    private const val PROBE_HOST = "www.google.com"
    private const val PROBE_URL = "https://$PROBE_HOST/generate_204"
    private const val TIMEOUT_MS = 5_000

    fun airplaneModeOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun mobileDataEnabled(ctx: Context): Boolean {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        return try {
            tm.isDataEnabled
        } catch (_: SecurityException) {
            Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
        }
    }

    /** 既定のネットワークが Wi-Fi か */
    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** いま主に使っている回線。Wi-Fi が既定ならWi-Fi、それ以外はモバイル */
    fun activeRoute(ctx: Context): Route = if (onWifi(ctx)) Route.WIFI else Route.MOBILE

    /** つながっている Wi-Fi の Network を探す */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /** Wi-Fi の電波・帯域・速度。つながっていなければ null */
    @Suppress("DEPRECATION")
    fun wifi(ctx: Context): WifiReading? {
        val net = wifiNetwork(ctx) ?: return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wm = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val info = try {
            wm.connectionInfo
        } catch (_: Exception) {
            null
        } ?: return null
        if (info.rssi <= -127 || info.frequency <= 0) return null
        val caps = cm.getNetworkCapabilities(net)
        // 接続中の回線そのものの情報。古い connectionInfo と規格がずれる機種があるので両方見る
        val netInfo = if (android.os.Build.VERSION.SDK_INT >= 31) caps?.transportInfo as? android.net.wifi.WifiInfo else null
        val code = if (android.os.Build.VERSION.SDK_INT >= 30) {
            maxOf(info.wifiStandard, netInfo?.wifiStandard ?: 0)
        } else 0
        val mlo = mloLinks(info, netInfo)
        // Wi-Fi 7 の MLO で 2 本以上つながっていれば Wi-Fi 7（規格の番号が 6 のままの機種がある）
        val isMlo = mlo.size >= 2
        // Android が報告する規格（WifiInfo.getWifiStandard）。7 は 60GHz の 11ad なので出さない
        val standard = when {
            code == 8 || isMlo -> "Wi-Fi 7"
            code == 6 -> if (info.frequency >= 5925) "Wi-Fi 6E" else "Wi-Fi 6" // 6GHz 帯の 11ax は 6E
            code == 5 -> "Wi-Fi 5"
            code == 4 -> "Wi-Fi 4"
            else -> null
        }
        logWifiDiag(code, info, netInfo, mlo)
        val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return WifiReading(
            rssi = info.rssi,
            // MLO では各リンクの速さの合計（取れなければ従来の値）
            linkMbps = (if (isMlo) mlo.sumOf { it.mbps }.takeIf { it > 0 } else null) ?: info.linkSpeed.takeIf { it > 0 },
            freqMhz = info.frequency,
            standard = standard,
            mloBands = if (isMlo) MloInfo.bands(mlo) else null,
            ssid = rememberSsid(ctx, net, ssid),
            tethering = metered,
        )
    }

    /** Wi-Fi 7 の MLO で同時につながっているリンク（Android 14 以降）。取れなければ空 */
    private fun mloLinks(vararg infos: android.net.wifi.WifiInfo?): List<MloInfo> {
        if (android.os.Build.VERSION.SDK_INT < 34) return emptyList()
        return infos.filterNotNull()
            .map { i ->
                runCatching { i.associatedMloLinks }.getOrDefault(emptyList())
                    .filter { it.state == android.net.wifi.MloLink.MLO_LINK_STATE_ACTIVE || it.state == android.net.wifi.MloLink.MLO_LINK_STATE_IDLE }
                    .map { l ->
                        MloInfo(
                            band = l.band,
                            channel = l.channel,
                            active = l.state == android.net.wifi.MloLink.MLO_LINK_STATE_ACTIVE,
                            mbps = maxOf(l.rxLinkSpeedMbps, l.txLinkSpeedMbps, 0),
                        )
                    }
            }
            .maxByOrNull { it.size } ?: emptyList()
    }

    private var lastDiag: String? = null

    /** 規格の生の値を、変わったときだけ履歴に残す（実機での確認用） */
    private fun logWifiDiag(code: Int, info: android.net.wifi.WifiInfo, netInfo: android.net.wifi.WifiInfo?, mlo: List<MloInfo>) {
        val d = buildString {
            append("Wi-Fi の規格: 番号 ").append(code)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                append("（connectionInfo ").append(info.wifiStandard)
                netInfo?.let { append("・回線 ").append(it.wifiStandard) }
                append("）")
            }
            append("・").append(info.frequency).append("MHz")
            append("・MLO ")
            if (mlo.isEmpty()) append("なし") else append(mlo.joinToString(" / ") { it.describe() })
        }
        if (d == lastDiag) return
        lastDiag = d
        MonitorState.event(EventType.INFO, d)
    }

    /**
     * アプリを開いている間に分かった Wi-Fi 名を、同じ接続が続く間だけ覚えておく。
     * 位置情報が「アプリの使用中のみ」だと、アプリを閉じている間（ウィジェット・通知）は
     * Android が名前を隠すため。つなぎ直すと Network が変わるので、別の Wi-Fi の名前は出さない。
     */
    private fun rememberSsid(ctx: Context, net: Network, ssid: String?): String? {
        val sp = ctx.getSharedPreferences("wifi_name", Context.MODE_PRIVATE)
        val handle = net.networkHandle
        if (ssid == null) {
            return if (sp.getLong("handle", 0L) == handle) sp.getString("ssid", null) else null
        }
        if (sp.getLong("handle", 0L) != handle || sp.getString("ssid", null) != ssid) {
            sp.edit().putLong("handle", handle).putString("ssid", ssid).apply()
        }
        return ssid
    }

    /** インターネット可能なモバイル回線の Network を探す */
    @Suppress("DEPRECATION")
    fun cellularNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun signal(ctx: Context): SignalInfo {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val ss = try {
            tm.signalStrength
        } catch (_: Exception) {
            null
        }
        var lte: Int? = null
        var nr: Int? = null
        ss?.cellSignalStrengths?.forEach { c ->
            when (c) {
                is CellSignalStrengthLte -> if (c.rsrp != CellInfo.UNAVAILABLE) lte = c.rsrp
                is CellSignalStrengthNr -> if (c.ssRsrp != CellInfo.UNAVAILABLE) nr = c.ssRsrp
            }
        }
        val netType = try {
            tm.dataNetworkType
        } catch (_: SecurityException) {
            -1
        }
        val type = when (netType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            TelephonyManager.NETWORK_TYPE_LTE -> if (nr != null) "5G NSA" else "LTE"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "データ無し"
            -1 -> if (nr != null) "5G" else if (lte != null) "LTE" else "?"
            else -> "3G/その他"
        }
        val carrier = try {
            tm.networkOperatorName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return SignalInfo(ss?.level, lte ?: nr, type, carrier)
    }

    /** 指定ネットワーク経由で実際に HTTP が通るか（パケ止まり検出の本命） */
    suspend fun probe(network: Network, measureHop: Boolean = false): ProbeResult = withContext(Dispatchers.IO) {
        val hop = if (measureHop) hopRtt(network) else null
        val start = System.nanoTime()
        try {
            // 1) 往復時間: DNS を引いたあと、TCP の接続だけにかかった時間を測る（ping に近い）
            val addr = network.getAllByName(PROBE_HOST).first()
            val t = System.nanoTime()
            network.socketFactory.createSocket().use { it.connect(InetSocketAddress(addr, 443), TIMEOUT_MS) }
            val rtt = (System.nanoTime() - t) / 1_000_000

            // 2) 本当にインターネットまで通じるか（HTTP の応答が返るか）
            val conn = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                ProbeResult(code in 100..599, rtt, hopMs = hop)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            ProbeResult(false, ms, e.javaClass.simpleName, hopMs = hop)
        }
    }

    /**
     * Wi-Fi 区間の往復時間。ゲートウェイ（ルーターや iPhone）の 53 番に TCP で接続を試み、
     * 「つながった」でも「拒否された」でも返事が来るまでの時間を測る。返事が無ければ null。
     */
    private fun hopRtt(network: Network): Long? {
        val gw = gateway(network) ?: return null
        val t = System.nanoTime()
        return try {
            network.socketFactory.createSocket().use { it.connect(InetSocketAddress(gw, 53), 2_000) }
            (System.nanoTime() - t) / 1_000_000
        } catch (_: java.net.ConnectException) {
            (System.nanoTime() - t) / 1_000_000 // 拒否 = 返事は来た
        } catch (_: Exception) {
            null
        }
    }

    private var appCtx: Context? = null

    /** ゲートウェイの取得に使う（App で一度だけ設定） */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** Wi-Fi のゲートウェイ（ルーターや iPhone）の IP アドレス。例: "192.168.11.1" */
    fun wifiGateway(ctx: Context): String? =
        runCatching { wifiNetwork(ctx)?.let { gateway(it) }?.hostAddress }.getOrNull()

    private fun gateway(network: Network): InetAddress? {
        val cm = appCtx?.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null && it.gateway !is java.net.Inet6Address }
            ?.gateway
    }

    /** 位置情報（GPS）がオンか。オフだと Wi-Fi 名が取れない */
    fun locationEnabled(ctx: Context): Boolean =
        ctx.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
}
