package io.github.snowiris57.reconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末起動時・アプリ更新時に、監視が有効なら自動で開始する */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Prefs(context).enabled) MonitorService.start(context)
        }
    }
}
