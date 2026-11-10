# ReConnect — パケ止まり自動再接続アプリ (Android)

「アンテナは立っているのに通信できない」「電波が悪くなって戻らない」状態を検知すると、
モバイル回線を自動で**再接続**する Android アプリです。

## しくみ

常駐サービス（通知に表示）が、一定の間隔で次の順に回線をチェックします。

| チェック | 内容 |
|---|---|
| 休止条件 | 機内モード中・モバイルデータOFF・Wi-Fi接続中のときは何もしない |
| モバイル回線 | インターネットに使えるモバイル回線があるか |
| 疎通確認 | モバイル回線経由で `https://www.google.com/generate_204` に HTTP で到達できるか（**パケ止まり検出の本命**） |
| 電波（任意） | RSRP が閾値（初期値 -120 dBm）より弱いか |

異常が **N 回連続**（初期値 2 回 ＝ 約 30 秒）で続くと再接続します。

1. **Step1**: モバイルデータ OFF → ON（`svc data`）
2. **Step2**: それでも直らなければ機内モード ON → OFF（`cmd connectivity airplane-mode`）

### 再接続を連打しないための制御

機内モードの切り替えを繰り返すと、障害時はかえって繋がりにくくなる場合があります。そのため次の制限を入れています。

- 再接続のあとは待ち時間を設ける（初期値 60 秒。**失敗が続くと倍々に延長**、最大 30 分）
- 1 時間あたりの再接続回数に上限を設ける（初期値 6 回）
- 処理の途中で止めても、モバイルデータOFFや機内モードONのまま放置しない

## 必要なもの

Android 10 以上。普通のアプリには機内モードやモバイルデータを切り替える権限がないため、次のどちらかが必要です。

- **[Shizuku](https://shizuku.rikka.app/)**（推奨・root 不要）。Android 11 以降なら「ワイヤレスデバッグ」で端末だけで起動できます。
  ただし端末を**再起動するたびに Shizuku の起動し直し**が必要です（root 端末を除く）。
- **root**（Shizuku が使えないときは `su` で実行します）

## インストール

1. GitHub の **Actions** → 最新の「Build APK」→ **Artifacts** の `ReConnect-apk` をダウンロードして解凍します。
   - `v0.1.0` のようなタグを push すると、**Releases** に APK が添付されます（スマホから直接ダウンロードしやすい）。
2. スマホで APK を開いてインストールします（「提供元不明のアプリ」を許可）。
3. アプリで上から順に設定します。
   1. 「Shizuku の権限を許可」
   2. 「通知・電話状態の権限を許可」
   3. 「電池の最適化から除外」（画面OFF中に監視が止まりにくくなります）
   4. 「自動再接続を有効にする」をON
4. まず「今すぐ再接続」を押して、モバイルデータが一瞬切れて戻ることを確かめてください。

## 設定

| 項目 | 初期値 | 説明 |
|---|---|---|
| チェック間隔 | 15 秒 | 短いほど早く気づくが電池を使う |
| 連続異常回数 | 2 回 | この回数続いたら再接続 |
| 電波弱でも再接続 | OFF | ON にすると疎通 OK でも RSRP が閾値未満なら再接続（電波が本当に弱い場所では意味がないことが多い） |
| RSRP 閾値 | -120 dBm | 目安：-80 以上=強い、-100 前後=普通、-110 以下=弱い |
| 再接続後の待ち時間 | 60 秒 | 失敗するたびに倍々で延長 |
| 1 時間の上限 | 6 回 | |
| 機内モードも試す | ON | |

## ビルド

```sh
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

`app/debug.keystore` は、CI とローカルのどちらでビルドしても同じ署名になるように同梱しているデバッグ用の鍵です（上書きインストールできるようにするため）。秘密の鍵ではありません。

## 構成

```
app/src/main/java/io/github/snowiris57/reconnect/
├── MainActivity.kt    画面（権限・状態・設定・ログ）
├── MonitorService.kt  常駐監視・判定・再接続の手順・待ち時間の制御
├── NetCheck.kt        電波（RSRP/レベル/方式）と疎通確認
├── Shell.kt           Shizuku UserService / su でコマンドを実行・無線操作
├── ShellService.kt    shell 権限プロセス側（Shizuku UserService）
├── MonitorState.kt    状態とログ（files/events.log）
├── Prefs.kt           設定
└── BootReceiver.kt    端末起動時に自動開始
```

## 参考にしたプロジェクト

- [R-Dilipkumar/one-tap-network-reset](https://github.com/R-Dilipkumar/one-tap-network-reset) — Shizuku/root でモバイル回線をリセット
- [lalakii/android_airplane_mode](https://github.com/lalakii/android_airplane_mode) — Shizuku で機内モードを切り替え
- [aunchagaonkar/NetworkSwitch](https://github.com/aunchagaonkar/NetworkSwitch) — Shizuku 連携
- [ishara-madu/4GLTEOnlyApp](https://github.com/ishara-madu/4GLTEOnlyApp) — 電波指標の監視
