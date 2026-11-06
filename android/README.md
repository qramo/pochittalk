# ぽちっと通話 Android版 (Kotlin / ネイティブ) — α

Web版 (`../worker.js`) と**同じ部屋で相互に通話できる**ネイティブアプリです。
Web版が使っている [Trystero](https://github.com/dmotz/trystero) 0.20.0 の nostr 方式を Kotlin で再実装しています。

> **状態: 未ビルド・未実機テストです。** この作業環境には Android SDK がなく、APK のビルドも実機での通話も試せていません。
> 何を確認できていて、何が未確認かは下の「検証状況」を読んでください。

## ビルドと起動 (Android Studio)

1. Android Studio (Ladybug 以降) で **このフォルダ (`android/`)** を開く。初回は Gradle 同期が走り、Android SDK 35 のインストールを求められることがあります。
2. JDK は Android Studio 同梱の JDK 17 以上を使う (Gradle 8.9 / AGP 8.7.3)。
3. 実機 (Android 8.0 / API 26 以上) をUSBデバッグで接続して ▶ を押す。エミュレータでも通話の確認はできますが、マイクの設定がやや面倒なので実機推奨。
4. コマンドラインなら:
   ```bash
   ./gradlew :app:testDebugUnitTest   # 署名・暗号・フレーミングの単体テスト (7件)
   ./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
   ```
5. 通信のログは `adb logcat -s Pochitalk` で見られます (リレー接続、ICE状態、`connect` など)。

### 設定したい場所

| 何を | どこを |
|---|---|
| 招待URLのベース (Web版の公開URL) | `engine/PochiEngine.kt` の `INVITE_BASE_URL` と、`AndroidManifest.xml` の `android:host` |
| STUN サーバー | `PochiEngine.startNetwork()` の `iceServers` |
| Nostr リレー | `rtc/TrysteroNetwork.kt` の `DEFAULT_RELAYS` (**Web版の先頭5件と同じにしておくこと**) |
| 「このアプリについて」の本文 | `ui/AboutContent.kt` (保存・送信するデータを変えたら必ず更新) |

## Web版との互換性を決めている定数 (変えると通話できなくなる)

- appId `pochitalk-walkie-talkie-cf-worker`、ロビー部屋 `pochitalk-lobby`、通話部屋 `h-<8桁の16進ハッシュ>`
- アクション名と中身: `pttState` `{isTalking}` / `pttName` 文字列 / `joinAge` `{age}` (入室してからの経過ms。新しく来た側を判定して入室音を鳴らす) / ロビー `presence` `{ch, talking, name}`
- 招待URLは `?r=<ハッシュ>` (Web版と同じ)。ネイティブ側は `pochitalk://join?r=<ハッシュ>` も受け付けます

## 構成

```
app/src/main/java/app/pochitalk/
├─ core/      純Kotlin。Android/WebRTC に依存しない部分 (JVMで単体テスト可能)
│   ├─ Schnorr.kt          BIP340 署名 (Nostrのイベント署名)。外部ライブラリ無し
│   ├─ NostrProtocol.kt    イベント(kind 29333, tag x)の作成・購読メッセージ
│   ├─ NostrClient.kt      リレーとのWebSocket (OkHttp)、再接続と購読の再送
│   ├─ TrysteroCrypto.kt   topic = SHA-1 を1バイトずつ base36、AES-GCM でSDPを暗号化
│   └─ ActionCodec.kt      room.js のデータチャネル上のフレーミング
├─ rtc/       Trystero / simple-peer の移植 (libwebrtc を使う)
│   ├─ RtcPeer.kt          simple-peer: trickle無し、DataChannel、再ネゴシエーション
│   ├─ TrysteroRoom.kt     strategy.js(announce/offer/answer) + room.js(アクション)
│   ├─ TrysteroNetwork.kt  リレー5本の共有、購読の振り分け
│   └─ Runtime.kt          通信は全部この単一スレッドで処理 (JSの前提を保つため)
├─ engine/    アプリのふるまい (Web版のクライアントJSに対応)
│   ├─ PochiEngine.kt      ロビー、入退室、PTT、話中ロック、混線、VOX、効果音
│   └─ UiState.kt
├─ audio/RadioSounds.kt   ガッ(終了音)・話中・混線・ザーッ をPCMで合成
├─ service/CallService.kt  通話を続けるフォアグラウンドサービス (microphone)
└─ ui/ Compose の画面 (ホーム / 無線機 / このアプリについて)
```

### 通信の流れ (ブラウザ版と同じ)

1. 全員が Nostr リレー5本に、部屋ごとの **rootTopic** (`sha1("Trystero@appId@roomId")`) と、自分宛の **selfTopic** を購読する。
2. 5.3秒おきに rootTopic へ `{"peerId": selfId}` を投稿 (announce)。
3. announce を見た側が WebRTC のオファーを作り、**AES-GCM で暗号化したSDP**を相手の selfTopic へ投稿。相手がアンサーを返す。
   同時にオファーを出し合ったら、selfId が大きい側が自分のオファーを優先する。
4. 接続後は DataChannel 上のアクション (12バイトの名前 + nonce + tag + progress + 本文) で `pttState` などをやり取りする。
5. 音声は **接続後に** トラックを追加し、再ネゴシエーションで流す。ノンイニシエータ側は自分でオファーを作れないので、
   データチャネルの `@_signal` で `renegotiate` / `transceiverRequest` をイニシエータに依頼する (simple-peer と同じ)。

## 検証状況

### 確認できたこと (この作業環境で実際に実行)

- **署名・暗号・フレーミングの一致**: BIP340 公式テストベクタ (0〜2) と、@noble/curves・本物の Trystero の `crypto.js` / `room.js` の出力に対して、
  署名・公開鍵・topic・AES-GCM暗号文・フレームのバイト列が**完全一致**。Kotlin→JS の向きも本物の `room.js` と noble で確認。
  (JVM + JUnit の単体テスト `ProtocolTest` 7件が通る)
- **実際のNostrリレー**: 署名したイベントを `relay.nostr.net` / `relay.snort.social` / `relay.piazza.today` が受理し、購読に返ってくることを確認。
- **本物の Trystero(JS) との信号層の相互動作**: JS側が出す announce を Kotlin が検出 → JSが送ってきた暗号化オファーを Kotlin が復号できた
  (topic 計算・AES鍵・JSON形式・リレー経由の配送が一致)。ただしJS側のWebRTCはスタブで、SDPは偽物。
- **型チェック**: アプリ全体のKotlinを、実物の `stream-webrtc-android 1.3.10`・Android 15・OkHttp・coroutines・Compose 1.7.4 のクラスに対して `kotlinc` で通した
  (Compose コンパイラプラグインを使わないフロントエンド解析のみ)。
- 検証に使ったスクリプトは `tools/interop-check/` にあります (手順は同フォルダのファイル冒頭を参照。JS側は Trystero のソースと npm パッケージが別途必要)。

### 未確認のこと (実機でやってみないと分からない)

- **Gradle / AGP / Compose プラグインでのビルド**、リソースXML、マニフェストの検証 (aapt) は未実施。
- **実際のWebRTC接続**: Android の libwebrtc とブラウザの間の ICE、DataChannel、音声の送受信。
- **再ネゴシエーション**: 接続後の音声トラック追加 (`renegotiate` / `transceiverRequest` の往復)。simple-peer のロジックを忠実に移植したが、
  ブラウザ相手で動くかは未確認。Android側が `RtpTransceiver.mid` と `addTransceiver` に依存している。
- **VOX**: libwebrtc は送信トラックが無効の間は録音を止める (実機のログで確認: `webrtc recording start` の直後に `stop`)。そのため待機中の音量は、別の `AudioRecord` (`audio/MicMonitor.kt`、入力は libwebrtc と同じ `VOICE_COMMUNICATION`) で測り、送信が始まったら止めて libwebrtc の録音の音量に切り替える。だれとも接続していなくても動く。
  音量メーターと VOX が実際の声で反応するか、しきい値は、Androidの入力がWeb版より小さいため **Android専用の対数目盛り `0.0015 × 1.44^(値 - 1)` (0.0015〜0.04)、初期値4** にしている (Web版は `0.02 + (値 - 1) × 0.007`、初期値6。実機で、機種ごとに入力レベルが4〜10倍違った: Pixel の話し声は 0.02〜0.09、Galaxy は 0.005〜0.014)。端末には `pochitalk_vox_level` で保存。VOXメーターは音量を dB (-60〜-20dB) の対数で表示する。しきい値が低いほど周囲の雑音に反応しやすいので、環境に合わせて調整する。
- **入力レベル (マイクの増幅 0〜30dB)**: libwebrtc のキャプチャ後処理 (`ExternalAudioProcessingFactory`、`audio/MicGain.kt`) でサンプルに係数を掛ける。\
  Web版に合わせて、ホーム画面の「⚙ 設定」の中にある (入力レベルのスライダー、MIC メーター、マイクテスト)。保存キーも Web版と同じ `pochitalk_input_db`。\
  実機で確認済み: 処理に渡されるバッファは float (48kHz で 480サンプル/10ms)。テスト音を足した状態で +24dB に設定すると、相手側の受信レベルが約24dB上がった。\
  Web版と同じく、**メーターと VOX の判定は入力レベル (増幅) を掛けたあとの音量**で見る。機種ごとにマイクの大きさが違う (Pixel に対して Galaxy は 1/4〜1/10) ための調整用。
- **設定 / 初回案内 / 入室音 (Web版に合わせた)**: 設定ダイアログ (マイクテスト、初回案内の表示、ソース・ライセンス、このアプリについて)、起動時の初回案内 (`pochitalk_welcome` に、オフにしたときだけ `off` を保存)、
  入室音「ピコーン」。入室音は `joinAge` を交換して新しく来た側を判定し、**すでに部屋にいる側だけ**が鳴らす (実機2台のログで、既存側=鳴らす/新規側=鳴らさない を確認)。入室すると VOX はオフに戻る。
- **音量レベルメーター**: TX/MIC はマイク (VOXと同じく録音中だけ取れる)、RX は libwebrtc の統計 (`inbound-rtp` の `audioLevel`) を150msごとに取って表示する。ブラウザ版の表示 (dB、24セグメント、ピークホールド) に合わせたが、RX の値が取れるか・ブラウザ版と同じ見え方かは未確認。
- **フォアグラウンドサービス (microphone)**: Android 14 以降は、アプリが前面にあるとき (入室ボタンを押したとき) に開始する必要がある。
  `RECORD_AUDIO` が未許可だとサービスは開始しない (聞くだけの参加になる)。
- **効果音の音色** (Web版の合成を近似しただけ)、エコー (スピーカーとマイクの回り込み)。
- **Nostrリレーの生存**: Web版の既定リレー5本のうち、この作業環境からは `relay.exit.pub` (名前解決に失敗) と `nostr.lu.ke` (TLSエラー) に接続できなかった。
  3本で動くが、リレーが減るとつながりにくくなる。ブラウザ版もこの5本固定なので、変えるなら両方そろえる。

### 既知の違い (ブラウザ版との差)

- オファーは、ブラウザ版のように事前に作っておくプールを持たず、announce を見てから作る。接続が最大で数秒遅くなることがある。
- 同じ相手へ出す未成立のオファーは最大2本まで (ブラウザ版は最大5本)。モバイルの負荷を抑えるため。
- リレーが切れたら再接続して購読を再送する (ブラウザ版の Trystero 0.20.0 は再送しない)。
- 招待リンクのアプリ起動は `autoVerify` を付けていない。端末の設定で「このアプリで開く」を許可したときだけ起動する。
  (確実にしたいなら、Web版のドメインに `/.well-known/assetlinks.json` を置いて autoVerify を有効にする)

## 実機での相互通話テスト チェックリスト

準備: パソコンのブラウザで Web版 (公開URL) を開き、ニックネームを決めて「新しい部屋を作る」。Android 側にアプリを入れる。

**部屋の発見と接続**
- [ ] Android のホームの「いまある部屋」に、ブラウザで作った部屋が出る (ハッシュ・人数・名前)
- [ ] Android で部屋をタップして入る → ブラウザ側の参加者に Android のニックネームが出る (逆も)
- [ ] Android で「新しい部屋を作る」→「招待URLを送る」→ ブラウザで開いて「入室」→ 互いに見える
- [ ] `adb logcat -s Pochitalk` に `relay open`、`ice CONNECTED`、`connect` が出る

**音声**
- [ ] Android のボタンを押して話す → ブラウザ側で RX が点灯して声が聞こえる
- [ ] ブラウザで話す → Android で RX が点灯して声が聞こえる (**再ネゴシエーションの確認: ブラウザが先に話す/Androidが先に話す、の両方を試す**)
- [ ] 話し終わりに「ガッ」が鳴る (自分が話し終えたとき / 相手が話し終えたとき)

**話中・混線**
- [ ] ブラウザで話している間に Android でボタンを押すと「話中です」とブザーが鳴り、送信されない
- [ ] ほぼ同時に押す → 両方で「混線！」と混線ノイズ、送信が切れる

**VOX**
- [ ] VOX オンで声を出すと自動で送信され、黙って約1.2秒後に終わる (イヤホン使用)
- [ ] 相手が話している間は、声を出しても割り込まない
- [ ] メーターが動かない/反応しすぎる場合は「しきい値」を調整 (右ほど大きな声が必要。動かない場合はログで録音が始まっているか確認)
- [ ] 液晶のレベルメーター: 待機中は MIC (青)、自分が話すと TX (赤)、相手が話すと RX (緑) になり、dB とピーク表示が動く

**画面オフ・バックグラウンド**
- [ ] 入室したまま画面を消す / 別アプリに切り替える → 通知が出て、声が聞こえ続ける
- [ ] 通知の「退出」で抜けられる。アプリに戻るとホーム画面になっている

**権限・例外**
- [ ] マイクを拒否して入室 → 聞くだけで入れる。話そうとすると許可を求められる
- [ ] 機内モード/回線切断 → 復帰後にリレーへ再接続して、部屋一覧が復活する
- [ ] 部屋の移動 (一覧から別の部屋をタップ) → 「ザーッ」が鳴って移動できる。送信中は移動できない
- [ ] 「このアプリについて」の表示、スクロール、閉じる
