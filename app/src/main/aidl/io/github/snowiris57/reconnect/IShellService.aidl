package io.github.snowiris57.reconnect;

// Shizuku の UserService として shell 権限 (uid 2000) で動くプロセスとの IPC。
interface IShellService {
    // Shizuku が予約しているトランザクションコード。変更しないこと。
    void destroy() = 16777114;

    // コマンドを実行し「終了コード\n出力」を返す
    String exec(String cmd) = 1;
}
