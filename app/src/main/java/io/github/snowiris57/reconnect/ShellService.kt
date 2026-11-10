package io.github.snowiris57.reconnect

import kotlin.system.exitProcess

/**
 * Shizuku の UserService として shell 権限 (uid 2000) のプロセス内で動く。
 * 通常アプリでは触れない `svc data` や `cmd connectivity airplane-mode` を実行できる。
 */
class ShellService : IShellService.Stub() {

    override fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        "$code\n$out"
    } catch (e: Exception) {
        "-1\n$e"
    }

    override fun destroy() {
        exitProcess(0)
    }
}
