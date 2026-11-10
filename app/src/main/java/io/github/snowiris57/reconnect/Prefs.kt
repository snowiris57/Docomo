package io.github.snowiris57.reconnect

import android.content.Context
import androidx.core.content.edit

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)

    /** 監視を有効にしているか（起動時の自動開始にも使う） */
    var enabled: Boolean
        get() = bool("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    /** チェック間隔（秒） */
    var intervalSec: Int
        get() = int("interval", 15)
        set(v) = sp.edit { putInt("interval", v.coerceIn(5, 600)) }

    /** 何回連続で異常なら再接続するか */
    var failThreshold: Int
        get() = int("fail_threshold", 2)
        set(v) = sp.edit { putInt("fail_threshold", v.coerceIn(1, 20)) }

    /** 疎通OKでも電波が閾値より弱ければ異常とみなすか */
    var signalTrigger: Boolean
        get() = bool("signal_trigger", false)
        set(v) = sp.edit { putBoolean("signal_trigger", v) }

    /** RSRP の閾値（dBm）。これ未満で「電波弱」 */
    var rsrpThreshold: Int
        get() = int("rsrp_threshold", -120)
        set(v) = sp.edit { putInt("rsrp_threshold", v.coerceIn(-140, -60)) }

    /** 再接続後、次の再接続までの最短待ち時間（秒）。失敗が続くと倍々に延びる */
    var cooldownSec: Int
        get() = int("cooldown", 60)
        set(v) = sp.edit { putInt("cooldown", v.coerceIn(10, 3600)) }

    /** 1時間あたりの再接続回数の上限 */
    var maxPerHour: Int
        get() = int("max_per_hour", 6)
        set(v) = sp.edit { putInt("max_per_hour", v.coerceIn(1, 60)) }

    /** モバイルデータ OFF/ON で直らないとき機内モード ON/OFF も試すか */
    var useAirplane: Boolean
        get() = bool("use_airplane", true)
        set(v) = sp.edit { putBoolean("use_airplane", v) }
}
