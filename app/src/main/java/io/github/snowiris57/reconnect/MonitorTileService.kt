package io.github.snowiris57.reconnect

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** クイック設定タイル: 監視の ON/OFF */
class MonitorTileService : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        val prefs = Prefs(this)
        val on = !prefs.enabled
        prefs.enabled = on
        try {
            if (on) MonitorService.start(this) else MonitorService.stop(this)
        } catch (_: Exception) {
            // バックグラウンドからの起動が制限された場合はアプリを開く
            prefs.enabled = !on
            openApp()
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = Prefs(this).enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (on) MonitorState.status.value.headline.take(12) else "オフ"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
