package io.github.snowiris57.reconnect

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class ShellResult(val code: Int, val output: String) {
    val ok get() = code == 0
}

/** 特権コマンドの実行。Shizuku を優先し、使えなければ root (su) を試す。 */
object Shell {

    @Volatile
    private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService>? = null
    private val mutex = Mutex()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                pending?.complete(s)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = shizukuRunning() && try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    suspend fun exec(cmd: String): ShellResult =
        if (shizukuGranted()) execShizuku(cmd) else execRoot(cmd)

    private suspend fun obtainService(): IShellService = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
        val d = CompletableDeferred<IShellService>()
        pending = d
        withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, connection) }
        withTimeout(10_000) { d.await() }
    }

    private suspend fun execShizuku(cmd: String): ShellResult = try {
        val raw = withContext(Dispatchers.IO) { obtainService().exec(cmd) }
        val code = raw.substringBefore('\n').toIntOrNull() ?: -1
        ShellResult(code, raw.substringAfter('\n', "").trim())
    } catch (e: Exception) {
        service = null
        ShellResult(-1, "Shizuku 実行失敗: ${e.message ?: e}")
    }

    private suspend fun execRoot(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            ShellResult(p.waitFor(), out)
        } catch (e: Exception) {
            ShellResult(-1, "Shizuku 未許可で root も使えません")
        }
    }
}

/** 無線の操作コマンド（shell 権限で実行される） */
object Radio {
    suspend fun setMobileData(enabled: Boolean): ShellResult =
        Shell.exec("svc data ${if (enabled) "enable" else "disable"}")

    suspend fun setAirplaneMode(enabled: Boolean): ShellResult {
        val r = Shell.exec("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
        if (r.ok) return r
        // 古い端末向けのフォールバック
        return Shell.exec(
            "settings put global airplane_mode_on ${if (enabled) 1 else 0} && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
    }
}
