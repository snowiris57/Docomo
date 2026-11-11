package io.github.snowiris57.reconnect

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorState.init(this)
        NetCheck.init(this)
    }
}
