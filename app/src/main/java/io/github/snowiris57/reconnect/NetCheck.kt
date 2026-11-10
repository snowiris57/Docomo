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

data class ProbeResult(val ok: Boolean, val ms: Long, val error: String? = null) {
    fun describe(): String = if (ok) "OK (${ms}ms)" else "NG (${error ?: "不明"})"
}

object NetCheck {
    private const val PROBE_URL = "https://www.google.com/generate_204"
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
    suspend fun probe(network: Network): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val conn = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                val ms = (System.nanoTime() - start) / 1_000_000
                // 何らかの HTTP 応答が返れば回線は生きている
                ProbeResult(code in 100..599, ms)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            ProbeResult(false, ms, e.javaClass.simpleName)
        }
    }
}
