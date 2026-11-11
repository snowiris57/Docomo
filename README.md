# ReConnect — パケ止まりを見張って、自動で回線を入れ直す

「アンテナは立っているのに通信できない」「電波が悪くなったまま戻らない」。
そんな状態を **ReConnect** が見つけて、モバイル回線を自動で**つなぎ直し**ます。
電波の強さはアプリとホーム画面ウィジェットの**メーター**でひと目でわかります。

<p>
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_welcome_light.png" width="200" alt="はじめての案内">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_home_light.png" width="200" alt="ホーム（ライト）">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_home_dark.png" width="200" alt="ホーム（ダーク）">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_history_light.png" width="200" alt="履歴">
</p>
<p>
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_home_wifi_dark.png" width="200" alt="Wi-Fi（テザリング）モード">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_widget_wide_wifi.png" width="380" alt="ウィジェット（テザリング）">
</p>
<p>
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_home_tablet.png" width="560" alt="タブレット（2列＋ナビゲーションレール）">
</p>

### ホーム画面ウィジェット「通信品質メーター」

<p>
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_widget_meter_wifi.png" width="240" alt="ウィジェット 2×2（テザリング）">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_widget_meter_mobile.png" width="240" alt="ウィジェット 2×2（モバイル）">
<img src="app/src/test/snapshots/images/io.github.snowiris57.reconnect_ScreenshotTest_widget_wide_mobile.png" width="410" alt="ウィジェット 横長">
</p>

- TetherPulse のメーターと同じ並び：針のメーター（通信品質 0〜100）、品質の数字と dBm、Wi-Fi 名・帯域、速度・規格、状態と応答時間
- 枠の色が状態で変わる（緑＝正常、黄＝不安定、赤＝異常、青＝再接続中、灰＝監視オフ）
- 右上の ⟳ で今すぐ再接続、⚙ で設定を開く
- **横に広げる**とメーターが左、文字が右に。チャンネルと Wi-Fi 区間の応答も出ます。小さくするとメーターと状態だけに
- 監視をオフにしていても、メーターは電波を表示します

## できること

| | |
|---|---|
| 📶 **モバイル / Wi-Fi の2モード** | 使っている回線に合わせて表示が自動で切り替わる。モバイルは RSRP・方式・通信会社、Wi-Fi は dBm・帯域・チャンネル・リンク速度・規格。iPhone などのテザリングは「テザリング」と表示。上の切り替えで、もう片方を一時的にのぞける |
| 🎯 **通信品質メーター** | メーターの数値は「電波の強さ」と「応答の速さ」の弱い方。電波が強くても、応答が遅ければ低く出ます |
| 🟡 **不安定の判定** | 通じていても応答が遅い（初期値 300ms 超）、またはときどき途切れると黄色の「不安定」。Wi-Fi では「ネット」と「Wi-Fi 区間（スマホ↔iPhone/ルーター）」の応答を分けて表示 |
| 🛰 **パケ止まりの検知** | モバイル回線経由で `generate_204` に小さな通信を送り、届かない状態が続いたら異常と判断 |
| 🔁 **自動で再接続** | モバイル: ① データ OFF→ON → ② だめなら機内モード ON→OFF。Wi-Fi: つながっているのに通信できなければ Wi-Fi OFF→ON（設定でオフにできます） |
| 🧯 **やりすぎ防止** | 再接続後は待ち時間を入れ、失敗するたびに倍々に延長（最大30分）。1時間あたりの回数にも上限。途中で止めても無線を切ったままにしない |
| 📈 **電波品質の推移** | 直近1時間の推移グラフに、疎通NG（赤い点）と再接続（青い破線）を表示。アプリを閉じても残ります |
| 🗂 **履歴と統計** | 今日の再接続回数・成功率・異常検知・正常率、日ごとのタイムライン |
| ⚙️ **細かな設定** | チェック間隔、判定回数、電波が弱いときも再接続（RSRP 閾値）、待ち時間、上限 |
| 🔔 **通知とタイル** | 通知から「今すぐ再接続」「停止」、再接続中は進捗バー。クイック設定タイルで監視の ON/OFF |
| ✨ **気持ちよい動き** | 値はばねアニメーションで動き、再接続中は弧の上を光が走り、復旧すると緑の波紋が広がる |
| 👆 **触れるグラフ** | 推移グラフを指でなぞると、その時刻の品質・RSRP・応答速度を吹き出しで表示 |
| 🧭 **迷わない導線** | 初回の案内（メーターのデモつき）、準備のチェックリスト、ウィジェットやタイルをアプリから追加、Shizuku が無いときの案内 |
| 📱 **どの画面でも** | タブレット・横向きは2列＋ナビゲーションレール。文字サイズを大きくしても崩れない |
| ♿ **使いやすさ** | ダークモード、Material You、メーターやグラフに読み上げ用の説明、見出しの指定、触覚フィードバック |

Wi-Fi の間は Wi-Fi を、モバイルの間はモバイル回線を見守ります（機内モードやモバイルデータ OFF のときはモバイルの監視を休みます）。Wi-Fi の名前を表示したいときは、設定 → Wi-Fi・テザリング から位置情報を許可してください（位置そのものは使いません）。

## 必要なもの

Android 10 以上。普通のアプリには機内モードやモバイルデータを切り替える権限がないため、次のどちらかが必要です。

- **[Shizuku](https://shizuku.rikka.app/)**（おすすめ・root 不要）— Android 11 以降なら「ワイヤレスデバッグ」で端末だけで起動できます。端末を再起動したら Shizuku の「開始」をもう一度押してください。
- **root**（Shizuku が使えないときは `su` で実行します）

## はじめかた

1. Shizuku をインストールして起動します（Shizuku アプリの案内に沿ってワイヤレスデバッグでペアリング → 開始）。
2. ReConnect の APK をインストールします。
   - GitHub の **Actions** → 最新の「Build APK」→ Artifacts の `ReConnect-apk`
   - または `v1.0.0` などのタグを push すると **Releases** に APK が添付されます
3. 最初の案内のあと、ホームに出る「**はじめに**」の 3 ステップを済ませます（Shizuku・通知と電話の状態・電池の最適化）。
4. Wi-Fi をオフにして「**今すぐ再接続**」を押し、モバイルデータが一瞬切れて戻ることを確かめます。
5. 「**自動再接続**」をオンにすれば完了です。ホームの案内カード（または 設定 → 便利な機能）から「通信品質メーター」ウィジェットを置けます。

## 開発

```sh
./gradlew assembleRelease        # R8 で最適化した APK（約2MB）
./gradlew assembleDebug          # デバッグ版
./gradlew recordPaparazziDebug   # 画面とウィジェットのスクリーンショットを撮り直す（端末不要）
```

- UI は Jetpack Compose + Material 3。ウィジェットは RemoteViews で、メーターは Canvas でビットマップに描いています（TetherPulse と同じ針のメーター）。
- `app/debug.keystore` は、CI とローカルのどちらでビルドしても同じ署名にして上書きインストールできるようにするための鍵です（秘密の鍵ではありません）。

```
app/src/main/java/io/github/snowiris57/reconnect/
├── MainActivity.kt         Compose 画面の入口・権限の要求
├── MonitorService.kt       常駐監視・判定・再接続の手順・やりすぎ防止
├── NetCheck.kt             電波（RSRP / 品質 / 方式 / 通信会社）と疎通確認
├── MonitorState.kt         状態・計測サンプル・イベント履歴・統計
├── Shell.kt / ShellService.kt  Shizuku UserService（または su）で特権コマンドを実行
├── MonitorTileService.kt   クイック設定タイル
├── BootReceiver.kt         起動時の自動開始
├── Prefs.kt                設定
├── widget/                 通信品質メーターのウィジェット（MeterWidget / GaugeBitmap）
└── ui/                     テーマ・メーター・グラフ・ホーム / 履歴 / 設定画面
```

## 参考にしたプロジェクト

- [R-Dilipkumar/one-tap-network-reset](https://github.com/R-Dilipkumar/one-tap-network-reset) — Shizuku/root でモバイル回線をリセット
- [lalakii/android_airplane_mode](https://github.com/lalakii/android_airplane_mode) — Shizuku で機内モードを切り替え
- [aunchagaonkar/NetworkSwitch](https://github.com/aunchagaonkar/NetworkSwitch) — Shizuku 連携
- [ishara-madu/4GLTEOnlyApp](https://github.com/ishara-madu/4GLTEOnlyApp) — 電波指標の監視
