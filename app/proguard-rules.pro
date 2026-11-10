# Shizuku が UserService をクラス名からリフレクションで生成するため残す
-keep class io.github.snowiris57.reconnect.ShellService { <init>(...); *; }
-keep class io.github.snowiris57.reconnect.IShellService { *; }
-keep class io.github.snowiris57.reconnect.IShellService$* { *; }
