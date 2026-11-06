/**
 * worker.js - ぽちっと通話 (Cloudflare Workers 単一ファイル)
 * ダッシュボードの「コードを編集」に全文貼り付けて Deploy するだけで動きます。
 * 注意: 外側がテンプレートリテラルなので、内部の <script> ではバッククォートと ${} を使わないこと。
 */

const HTML = `<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
  <link rel="icon" href="/favicon.png" type="image/png" sizes="96x96">
  <link rel="apple-touch-icon" href="/apple-touch-icon.png">
  <meta name="theme-color" content="#1a1e24">
  <meta name="description" content="ボタンを押している間だけ話せる、トランシーバー風のグループ通話アプリ（α版）">
  <meta property="og:type" content="website">
  <meta property="og:site_name" content="ぽちっと通話">
  <meta property="og:title" content="ぽちっと通話 α版">
  <meta property="og:description" content="ボタンを押している間だけ話せる、トランシーバー風のグループ通話アプリ">
  <meta property="og:url" content="__ORIGIN__/">
  <meta property="og:image" content="__ORIGIN__/og.png">
  <meta property="og:image:width" content="1200">
  <meta property="og:image:height" content="630">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="ぽちっと通話 α版">
  <meta name="twitter:description" content="ボタンを押している間だけ話せる、トランシーバー風のグループ通話アプリ">
  <meta name="twitter:image" content="__ORIGIN__/og.png">
  <title>ぽちっと通話 α版 - ボタンを押して話すトランシーバー</title>
  <style>
    :root {
      --bg-color: #1a1e24;
      --card-bg: #252b36;
      --panel-bg: #14181d;
      --accent-color: #3b82f6;
      --tx-color: #ef4444;
      --rx-color: #10b981;
      --text-color: #f3f4f6;
      --muted-color: #9ca3af;
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      user-select: none;
      -webkit-user-select: none;
      -webkit-touch-callout: none;
    }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background-color: var(--bg-color);
      color: var(--text-color);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 16px;
    }
    .walkie-talkie {
      width: 100%;
      max-width: 400px;
      background: var(--card-bg);
      border-radius: 28px;
      padding: 24px;
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5), inset 0 1px 1px rgba(255, 255, 255, 0.1);
      border: 2px solid #374151;
      display: flex;
      flex-direction: column;
      gap: 20px;
      position: relative;
    }
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 2px solid #374151;
      padding-bottom: 12px;
    }
    .brand {
      font-weight: 800;
      letter-spacing: 1px;
      font-size: 1.1rem;
      color: #93c5fd;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .room-badge {
      font-size: 0.8rem;
      background: #1e293b;
      padding: 4px 10px;
      border-radius: 12px;
      color: var(--muted-color);
      border: 1px solid #334155;
          }
    .display {
      background: var(--panel-bg);
      border-radius: 16px;
      padding: 16px;
      border: 2px solid #22272e;
      box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.6);
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .status-row { display: flex; justify-content: space-between; align-items: center; }
    .indicators { display: flex; gap: 12px; }
    .led-wrapper { display: flex; align-items: center; gap: 4px; }
    .led {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #374151;
      transition: all 0.15s ease;
    }
    .led-label { font-size: 0.7rem; font-weight: 800; color: var(--muted-color); }
    .led.tx.active { background: var(--tx-color); box-shadow: 0 0 12px var(--tx-color); }
    .led.rx.active { background: var(--rx-color); box-shadow: 0 0 12px var(--rx-color); }
    .status-text { font-family: monospace; font-size: 1rem; font-weight: bold; color: #38bdf8; }
    .peers-list {
      min-height: 48px;
      max-height: 84px;
      overflow-y: auto;
      font-size: 0.85rem;
      color: var(--muted-color);
      border-top: 1px dashed #2d3748;
      padding-top: 8px;
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .peers-empty { opacity: 0.5; }
    .peer-tag {
      background: #1e293b;
      padding: 2px 8px;
      border-radius: 6px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 0.8rem;
    }
    .peer-tag.talking {
      background: #064e3b;
      color: #34d399;
      font-weight: bold;
      box-shadow: 0 0 8px rgba(16, 185, 129, 0.3);
    }
    .ptt-container { display: flex; flex-direction: column; align-items: center; gap: 14px; margin: 6px 0; }
    .ptt-btn {
      width: 170px;
      height: 170px;
      border-radius: 50%;
      background: radial-gradient(circle, #3b82f6 0%, #1d4ed8 100%);
      border: 6px solid #1e3a8a;
      box-shadow: 0 8px 20px rgba(0, 0, 0, 0.4), inset 0 4px 6px rgba(255, 255, 255, 0.3);
      color: white;
      font-size: 1.25rem;
      font-weight: 800;
      letter-spacing: 1px;
      cursor: pointer;
      outline: none;
      transition: all 0.1s ease;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 2px;
      touch-action: none;
    }
    .ptt-btn:active, .ptt-btn.transmitting {
      transform: scale(0.96);
      background: radial-gradient(circle, #ef4444 0%, #b91c1c 100%);
      border-color: #7f1d1d;
      box-shadow: 0 0 25px rgba(239, 68, 68, 0.6), inset 0 2px 4px rgba(0, 0, 0, 0.4);
    }
    .ptt-main { font-size: 1.3rem; font-weight: 900; }
    .ptt-sub { font-size: 0.75rem; letter-spacing: 2px; opacity: 0.85; }
    .controls-info { text-align: center; font-size: 0.85rem; color: var(--muted-color); line-height: 1.5; }
    .key-badge {
      background: #374151;
      color: #f3f4f6;
      padding: 2px 8px;
      border-radius: 4px;
      font-family: monospace;
      font-size: 0.8rem;
      border-bottom: 2px solid #1f2937;
    }

    .touch-only { display: none; }
    @media (hover: none) and (pointer: coarse), (max-width: 480px) {
      .pc-only { display: none; }
      .touch-only { display: inline; }
    }
    .alpha-badge { font-size: 0.65rem; font-weight: 800; color: #fbbf24; border: 1px solid #fbbf24; border-radius: 6px; padding: 1px 6px; letter-spacing: 1px; }
    .sticky-bar { position: fixed; top: 0; left: 0; right: 0; z-index: 10; display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 16px; background: rgba(20,24,29,0.95); border-bottom: 1px solid #374151; }
    .sticky-hash { font-family: "Courier New", monospace; font-weight: 800; color: #38bdf8; letter-spacing: 1px; }
    dialog { margin: auto; width: calc(100% - 32px); max-width: 460px; max-height: 85vh; padding: 0; border: 2px solid #374151; border-radius: 20px; background: var(--card-bg); color: var(--text-color); overflow: hidden; }
    dialog[open] { display: flex; flex-direction: column; }
    dialog::backdrop { background: rgba(0,0,0,0.65); }
    .about { display: flex; flex-direction: column; min-height: 0; max-height: 85vh; font-size: 0.85rem; line-height: 1.6; user-select: text; -webkit-user-select: text; }
    .about * { user-select: text; -webkit-user-select: text; }
    .about-head { flex: none; display: flex; justify-content: space-between; align-items: center; padding: 12px 18px; border-bottom: 1px solid #374151; background: var(--card-bg); }
    .about-body { flex: 1; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 4px 18px 20px; }
    .about h3 { margin: 16px 0 6px; color: #93c5fd; font-size: 1rem; border-bottom: 1px solid #374151; padding-bottom: 4px; }
    .about h4 { margin: 12px 0 4px; color: #fbbf24; font-size: 0.88rem; }
    .about ul { padding-left: 1.2em; display: flex; flex-direction: column; gap: 4px; }
    .about p { color: var(--text-color); }
    .about b { color: #fff; }
    .vox-panel { display: flex; flex-direction: column; gap: 8px; }
    .vox-meter { position: relative; height: 10px; background: #111827; border-radius: 5px; overflow: hidden; border: 1px solid #374151; }
    .vox-fill { height: 100%; width: 0; background: var(--rx-color); transition: width .05s linear; }
    .vox-fill.over { background: var(--tx-color); }
    .vox-thr { position: absolute; top: 0; bottom: 0; width: 2px; background: #fbbf24; }
    .vox-sens { display: flex; align-items: center; gap: 8px; font-size: 0.8rem; color: var(--muted-color); }
    .vox-sens input { flex: 1; }
    .vox-hint { font-size: 0.7rem; opacity: .8; white-space: nowrap; }
    .level-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-family: monospace; font-size: 0.65rem; color: var(--muted-color); }
    .level { flex: 1; height: 14px; min-width: 0; }
    .level-db { width: 52px; text-align: right; }
    .hidden { display: none !important; }
    .home { width: 100%; max-width: 400px; display: flex; flex-direction: column; gap: 16px; }
    .lcd { text-align: center; padding: 4px 0 2px; }
    .lcd-ch { font-family: monospace; font-size: 0.8rem; color: var(--muted-color); letter-spacing: 3px; }
    .lcd-freq { font-family: "Courier New", monospace; font-size: 2.3rem; font-weight: 900; color: #38bdf8; text-shadow: 0 0 14px rgba(56,189,248,0.55); letter-spacing: 2px; line-height: 1.1; transition: opacity .1s; }
    .lcd-freq small { font-size: 0.9rem; letter-spacing: 1px; margin-left: 6px; opacity: .8; }
    .lcd-freq.tuning { opacity: .45; animation: blink .35s steps(2) infinite; }
    @keyframes blink { 50% { opacity: .9; } }
    .lcd-sub { font-size: 0.72rem; color: var(--muted-color); min-height: 1em; }
    .tuner { display: flex; flex-direction: column; gap: 10px; }
    .dial-row { display: flex; align-items: center; gap: 8px; }
    .step-btn { width: 40px; height: 40px; border-radius: 10px; border: none; background: #374151; color: #fff; font-size: 1rem; cursor: pointer; flex: none; }
    .step-btn:active { background: #4b5563; transform: scale(.94); }
    .dial { flex: 1; position: relative; height: 40px; border-radius: 8px; background: #0f1419; border: 1px solid #2d3748;
      background-image: repeating-linear-gradient(90deg, #4b5563 0 1px, transparent 1px 10px), repeating-linear-gradient(90deg, #9ca3af 0 2px, transparent 2px 50px);
      background-size: 100% 40%, 100% 70%; background-repeat: no-repeat; background-position: 0 100%, 0 100%; }
    .dial input[type=range] { position: absolute; inset: 0; width: 100%; height: 100%; margin: 0; background: transparent; -webkit-appearance: none; appearance: none; touch-action: pan-y; cursor: ew-resize; }
    .dial input[type=range]::-webkit-slider-runnable-track { background: transparent; height: 40px; }
    .dial input[type=range]::-moz-range-track { background: transparent; height: 40px; }
    .dial input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 6px; height: 40px; background: #ef4444; border-radius: 3px; box-shadow: 0 0 8px #ef4444; }
    .dial input[type=range]::-moz-range-thumb { width: 6px; height: 40px; background: #ef4444; border: none; border-radius: 3px; box-shadow: 0 0 8px #ef4444; }
    .tune-actions { display: flex; gap: 8px; }
    .tune-actions button { flex: 1; font-size: 0.8rem; padding: 8px 6px; }
    .scan-title { font-size: 0.72rem; color: var(--muted-color); letter-spacing: 1px; display: flex; justify-content: space-between; }
    .scan-list { display: flex; flex-wrap: wrap; gap: 6px; min-height: 30px; }
    .chan-chip { background: #1e293b; border: 1px solid #334155; color: var(--text-color); border-radius: 8px; padding: 4px 8px; font-size: 0.78rem; cursor: pointer; font-family: monospace; }
    .chan-chip.me { border-color: var(--accent-color); background: #172554; }
    .chan-chip.air { border-color: var(--tx-color); box-shadow: 0 0 8px rgba(239,68,68,.5); }
    .room-panel { width: 100%; max-width: 400px; margin-top: 16px; background: var(--card-bg); border: 2px solid #374151; border-radius: 20px; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
    .chan-chip { text-align: left; }
    .chip-who { font-family: -apple-system, 'Segoe UI', sans-serif; font-size: 0.72rem; color: var(--muted-color); margin-top: 2px; max-width: 220px; }
    .scan-list { flex-direction: column; }
    .chan-chip:active { transform: scale(.96); }
    .config-panel { display: flex; flex-direction: column; gap: 10px; font-size: 0.85rem; }
    .input-row { display: flex; gap: 8px; }
    input[type="text"] {
      flex: 1;
      min-width: 0;
      background: #111827;
      border: 1px solid #374151;
      color: white;
      padding: 8px 12px;
      border-radius: 8px;
      outline: none;
      font-size: 16px;
      user-select: text;
      -webkit-user-select: text;
    }
    input[type="text"]:focus { border-color: var(--accent-color); }
    button.btn-sub {
      background: #374151;
      color: white;
      border: none;
      padding: 8px 16px;
      border-radius: 8px;
      cursor: pointer;
      font-weight: 600;
    }
    button.btn-sub:hover { background: #4b5563; }
    .sound-toggle { display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--muted-color); font-size: 0.8rem; }
  </style>
</head>
<body>
  <div class="home" id="homeView">
    <section class="room-panel">
      <div class="brand"><span>🔘</span><span>ぽちっと通話</span><span class="alpha-badge">α版</span></div>
      <input type="text" id="nameInput" placeholder="ハンドルネーム" maxlength="12" autocomplete="off">
      <button class="btn-sub" id="emptyBtn" type="button">新しい部屋を作る</button>
      <button class="btn-sub" id="aboutBtn" type="button" style="background:#1e293b">このアプリについて（使い方・プライバシー）</button>
    </section>
  </div>

  <div class="sticky-bar hidden" id="stickyBar">
    <span class="sticky-hash" id="roomDisplay">----·----</span>
    <button class="btn-sub" id="leaveBtn2" type="button" style="padding:4px 10px">退出</button>
  </div>

  <main class="walkie-talkie hidden" id="radioView">
    <header class="header">
      <div class="brand"><span>🔘</span><span>ぽちっと通話</span><span class="alpha-badge">α版</span></div>
      <div style="display:flex;gap:6px;align-items:center">
        <button class="btn-sub" id="leaveBtn" type="button" style="padding:4px 10px">退出</button>
      </div>
    </header>
    <section class="display">
      <div class="lcd">
        <div class="lcd-ch" id="chDisplay">ROOM HASH</div>
        <div class="lcd-freq" id="freqDisplay">----·----</div>
        <div class="lcd-sub" id="lcdSub"></div>
        <div class="level-row"><span class="level-label" id="levelLabel">LEVEL</span><canvas class="level" id="levelCanvas" width="280" height="14"></canvas><span class="level-db" id="levelDb">-∞ dB</span></div>
      </div>
      <div class="status-row">
        <div class="indicators">
          <div class="led-wrapper"><span class="led tx" id="ledTx"></span><span class="led-label">TX</span></div>
          <div class="led-wrapper"><span class="led rx" id="ledRx"></span><span class="led-label">RX</span></div>
        </div>
        <div class="status-text" id="statusLabel">STANDBY</div>
      </div>
      <div class="peers-list" id="peersList"><span class="peers-empty">待機中（参加者なし）</span></div>
    </section>
    <section class="ptt-container">
      <button class="ptt-btn" id="pttBtn" type="button">
        <span class="ptt-main">POCHI</span>
        <span class="ptt-sub">TALK</span>
      </button>
      <div class="controls-info">
        <span class="pc-only"><kbd class="key-badge">Space</kbd> キーを押しながら話す<br>またはボタンを長押し</span><span class="touch-only">ボタンを押している間だけ話せます</span>
      </div>
    </section>
    <footer class="config-panel">
      <div class="tune-actions">
        <button class="btn-sub" id="shareBtn" type="button">招待URLをコピー</button>
      </div>
      <label class="sound-toggle">
        <input type="checkbox" id="voxCheck">
        VOX（声を拾って自動で送信）
      </label>
      <div class="vox-panel hidden" id="voxPanel">
        <div class="vox-meter"><div class="vox-fill" id="voxFill"></div><div class="vox-thr" id="voxThr"></div></div>
        <label class="vox-sens"><span>しきい値</span><input type="range" id="voxSens" min="1" max="10" step="1" value="6"><span class="vox-hint">右ほど大きな声が必要</span></label>
      </div>
      <label class="sound-toggle">
        <input type="checkbox" id="soundEffectCheck" checked>
        無線効果音（スケルチ音など）を鳴らす
      </label>
    </footer>
  </main>

  <section class="room-panel" style="margin-top:16px">
      <div class="scan-title"><span>いまある部屋（タップで入る）</span><span id="onlineCount"></span></div>
      <div class="scan-list" id="scanList"><span class="peers-empty">スキャン中...</span></div>
  </section>


  <dialog id="aboutDialog">
    <div class="about">
      <div class="about-head">
        <strong>ぽちっと通話 <span class="alpha-badge">α版</span></strong>
        <button class="btn-sub" id="aboutClose" type="button" style="padding:4px 12px">閉じる</button>
      </div>
      <div class="about-body">

      <h3>このアプリは？</h3>
      <p>ボタンを押している間だけ話せる、トランシーバー風の音声通話アプリです。アカウント登録やログインは要りません。同じ部屋に入った人どうしで話せます。α版のため、予告なく仕様が変わったり、動作が不安定だったりすることがあります。</p>

      <h3>使い方</h3>
      <ul>
        <li>ハンドルネームを決めて、「新しい部屋を作る」か、一覧の部屋をタップして入ります。</li>
        <li>入室後は、大きなボタンを押している間だけ話せます（PCではスペースキーも使えます）。</li>
        <li>「招待URLをコピー」で友達にURLを送ると、同じ部屋に入れます。</li>
        <li>誰かが話している間は話せません（話中）。同時に押すと混線して、どちらも送信が切れます。</li>
        <li>人数の目安は1部屋あたり6〜8人までです。</li>
      </ul>

      <h3>プライバシー</h3>
      <h4>誰に何が見えるか</h4>
      <ul>
        <li><b>ハンドルネーム・今いる部屋・話し中かどうか</b>は、このアプリを同時に開いている全員に見えます（部屋一覧のため）。本名や個人が特定できる名前は使わないでください。</li>
        <li><b>部屋は公開です。</b>部屋のハッシュ値は一覧に出るので、誰でもタップして入れます。秘密の話をする用途には向いていません。</li>
        <li><b>同じ部屋の人には、あなたのIPアドレスが見える</b>ことがあります。通話が端末どうしの直接通信（P2P）で行われるためです。</li>
        <li>同じ部屋の誰かが、自分の端末で通話を録音する可能性はあります。このアプリには防ぐ仕組みはありません。</li>
      </ul>

      <h4>音声について</h4>
      <ul>
        <li>音声は、サーバーを通らず、端末どうしで直接送られます。通信はWebRTCの標準の暗号化がかかります。</li>
        <li>このアプリの運営側は、音声を受け取らず、録音も保存もしません（そもそも運営側のサーバーに届きません）。</li>
        <li>VOX（声で自動送信）をオンにすると、声を検出するために端末の中でマイクの音量を監視します。この監視用の音は送信も保存もされず、声を検出したときだけ送信が始まります。同じ理由で、マイクが使える状態の間は、画面のレベル表示のためにも端末の中で音量を測っています。VOX自体は、ページを開き直すとオフに戻ります（しきい値の設定だけ、端末に保存されます）。</li>
        <li>マイクは、最初に話すボタンを押したときに許可を求めます。許可後はページを開いている間マイクを確保しますが、ボタンを押していない間は音声を送りません（ブラウザのマイク表示が出たままになることがあります）。</li>
      </ul>

      <h4>あなたの端末に保存されるもの</h4>
      <ul>
        <li><b>ハンドルネーム</b>: ブラウザの localStorage（キー名 pochitalk_ptt_name）に保存されます。次回開いたときに入力済みになります。この端末のブラウザの中だけにあり、運営側には送られません。ブラウザのサイトデータを消すと削除されます。</li>
        <li><b>URLの「?r=…」</b>: 今いる部屋のハッシュ値がアドレスに入ります。ブラウザの履歴に残ることがあります。</li>
        <li><b>VOXのしきい値</b>: 1〜10の数字を、同じく localStorage（キー名 pochitalk_vox_thr）に保存します。この端末のブラウザの中だけにあり、運営側には送られません。</li>
        <li>Cookie、ログイン情報、メールアドレス、位置情報、連絡先などは、保存も取得もしません。</li>
        <li>アクセス解析や広告の仕組みは入れていません。</li>
      </ul>

      <h4>サーバー側に保存されるもの</h4>
      <ul>
        <li>このアプリの運営側には、データベースや保存領域がなく、ユーザーの名前・部屋・音声・通話履歴を保存していません。</li>
        <li>ただし、ページを配信している Cloudflare には、一般的なアクセス情報（IPアドレスなど）が、同社の方針に沿って記録されることがあります。</li>
      </ul>

      <h4>外部のサービスについて</h4>
      <ul>
        <li><b>esm.sh</b>: 通話用のライブラリ（Trystero）の配信元です。ページを開くと、ここにもアクセスが発生します。</li>
        <li><b>Nostr の公開リレー</b>: 通話相手を見つけるための接続情報のやり取りに使います。内容は暗号化されますが、接続元IPなどのアクセス情報はリレーの運営者に見える可能性があります。</li>
        <li><b>STUNサーバー（Cloudflare・Google）</b>: 端末どうしが直接つながるための、公開されたアドレスの確認に使います。IPアドレスがこれらに送られます。</li>
        <li>これらは運営側が管理していない外部のサービスです。</li>
      </ul>

      <h3>ご注意</h3>
      <ul>
        <li>個人が作ったα版です。動作や安全性は保証できません。重要な連絡や緊急時の連絡には使わないでください。</li>
        <li>回線やNATの種類によっては、相手とつながらないことがあります。</li>
        <li>他人への迷惑行為や、他人の会話の録音・公開はしないでください。</li>
      </ul>
      </div>
    </div>
  </dialog>

  <script type="module">
    import { joinRoom } from 'https://esm.sh/trystero@0.20.0/nostr';

    const pttBtn = document.getElementById('pttBtn');
    const ledTx = document.getElementById('ledTx');
    const ledRx = document.getElementById('ledRx');
    const statusLabel = document.getElementById('statusLabel');
    const roomDisplay = document.getElementById('roomDisplay');
    const chDisplay = document.getElementById('chDisplay');
    const freqDisplay = document.getElementById('freqDisplay');
    const lcdSub = document.getElementById('lcdSub');
    const scanList = document.getElementById('scanList');
    const onlineCount = document.getElementById('onlineCount');
    const peersList = document.getElementById('peersList');
    const nameInput = document.getElementById('nameInput');
    const soundEffectCheck = document.getElementById('soundEffectCheck');

    const rxAnalysers = new Map(); // peerId -> AnalyserNode
    let txAnalyser = null, txMonitor = null;
    let audioCtx = null, userMediaStream = null, localAudioTrack = null;
    let room = null, sendPttState = null, sendNickname = null;
    let isTransmitting = false, currentTalkerId = null;
    const peerMap = new Map();

    const urlParams = new URLSearchParams(window.location.search);
    function isKey(k) { return /^[0-9a-f]{8}$/.test(k); }
    function fmt(k) { return k.slice(0, 4).toUpperCase() + '·' + k.slice(4).toUpperCase(); }
    function toHex(bytes) { return Array.from(bytes).map(function (b) { return (b < 16 ? '0' : '') + b.toString(16); }).join(''); }
    async function hashOf(name) {
      const norm = name.trim().toLowerCase().normalize('NFKC');
      const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(norm));
      return toHex(new Uint8Array(buf).slice(0, 4));
    }
    let currentKey = null;
    let savedName = null;
    try { savedName = localStorage.getItem('pochitalk_ptt_name'); } catch (_) {}
    let myNickname = savedName || ('無線機_' + Math.floor(Math.random() * 899 + 100));
    nameInput.value = myNickname;

    function getAudioContext() {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      return audioCtx;
    }

    function playPttStartTone() {
      if (!soundEffectCheck.checked) return;
      try {
        const ctx = getAudioContext();
        const osc = ctx.createOscillator(), gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(880, ctx.currentTime);
        osc.frequency.setValueAtTime(1320, ctx.currentTime + 0.04);
        gain.gain.setValueAtTime(0.12, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.08);
        osc.connect(gain); gain.connect(ctx.destination);
        osc.start(); osc.stop(ctx.currentTime + 0.08);
      } catch (_) {}
    }

    // 発話終了: ザラザラに歪んだノイズを細かく途切れさせた「ガガッ」
    function playPttEndTone() {
      if (!soundEffectCheck.checked) return;
      try {
        const ctx = getAudioContext();
        const sr = ctx.sampleRate;
        const dur = 0.16;
        const buffer = ctx.createBuffer(1, Math.floor(sr * dur), sr);
        const d = buffer.getChannelData(0);
        let held = 0;
        for (let i = 0; i < d.length; i++) {
          const t = i / sr;
          if (i % 5 === 0) held = Math.random() * 2 - 1;      // 粗いサンプルホールド = ザラつき
          const gate = (Math.floor(t * 22) % 2 === 0) ? 1 : 0.15; // ガ・ガ・ガ と細かく途切れる
          const decay = t < 0.09 ? 1 : Math.max(0, 1 - (t - 0.09) / 0.07); // 最後にスッと切れる
          d[i] = held * gate * decay;
        }
        const src = ctx.createBufferSource(); src.buffer = buffer;
        const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 350;
        const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 4200;
        // 歪ませてバリバリ感を出す
        const shaper = ctx.createWaveShaper();
        const curve = new Float32Array(256);
        for (let k = 0; k < 256; k++) { const x = (k / 128) - 1; curve[k] = Math.tanh(x * 5); }
        shaper.curve = curve;
        const g = ctx.createGain(); g.gain.value = 0.14;
        src.connect(hp); hp.connect(lp); lp.connect(shaper); shaper.connect(g); g.connect(ctx.destination);
        src.start();
      } catch (_) {}
    }

    // 話中ブザー（他の人が話しているときに押した）
    function playBusyTone() {
      if (!soundEffectCheck.checked) return;
      try {
        const ctx = getAudioContext();
        [0, 0.12].forEach(function (off) {
          const osc = ctx.createOscillator(), g = ctx.createGain();
          osc.type = 'square'; osc.frequency.value = 330;
          g.gain.setValueAtTime(0.06, ctx.currentTime + off);
          g.gain.setValueAtTime(0.001, ctx.currentTime + off + 0.08);
          osc.connect(g); g.connect(ctx.destination);
          osc.start(ctx.currentTime + off); osc.stop(ctx.currentTime + off + 0.09);
        });
      } catch (_) {}
    }

    // 混線ノイズ（荒く揺れる広帯域ノイズ）
    function playCrosstalk() {
      if (!soundEffectCheck.checked) return;
      try {
        const ctx = getAudioContext();
        const sr = ctx.sampleRate, dur = 0.7;
        const buffer = ctx.createBuffer(1, Math.floor(sr * dur), sr);
        const d = buffer.getChannelData(0);
        let held = 0;
        for (let i = 0; i < d.length; i++) {
          const t = i / sr;
          if (i % 3 === 0) held = Math.random() * 2 - 1;
          const wob = 0.55 + 0.45 * Math.sin(t * 2 * Math.PI * 9);
          d[i] = held * wob * Math.max(0, 1 - t / dur);
        }
        const src = ctx.createBufferSource(); src.buffer = buffer;
        const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 1600; bp.Q.value = 0.5;
        const g = ctx.createGain(); g.gain.value = 0.2;
        src.connect(bp); bp.connect(g); g.connect(ctx.destination);
        src.start();
      } catch (_) {}
    }

    function showCrosstalk() {
      playCrosstalk();
      statusLabel.textContent = '混線！';
      statusLabel.style.color = '#f59e0b';
      clearTimeout(showCrosstalk.t);
      showCrosstalk.t = setTimeout(function () {
        if (!isTransmitting && !currentTalkerId) {
          statusLabel.textContent = 'STANDBY';
          statusLabel.style.color = '#38bdf8';
        }
      }, 1300);
    }

    function othersTalking() {
      let n = 0;
      peerMap.forEach(function (p) { if (p.isTalking) n++; });
      return n;
    }

    function playTuneStatic() {
      if (!soundEffectCheck.checked) return;
      try {
        const ctx = getAudioContext();
        const dur = 0.32;
        const buffer = ctx.createBuffer(1, Math.floor(ctx.sampleRate * dur), ctx.sampleRate);
        const d = buffer.getChannelData(0);
        for (let i = 0; i < buffer.length; i++) d[i] = Math.random() * 2 - 1;
        const src = ctx.createBufferSource(); src.buffer = buffer;
        const f = ctx.createBiquadFilter(); f.type = 'bandpass'; f.Q.value = 2;
        f.frequency.setValueAtTime(500, ctx.currentTime);
        f.frequency.exponentialRampToValueAtTime(3200, ctx.currentTime + dur);
        const g = ctx.createGain();
        g.gain.setValueAtTime(0.12, ctx.currentTime);
        g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + dur);
        src.connect(f); f.connect(g); g.connect(ctx.destination);
        src.start();
      } catch (_) {}
    }

    // 送信トラックは無効化されるため、表示用に複製したトラックで音量を測る
    function setupTxAnalyser() {
      try {
        txMonitor = userMediaStream.clone();
        txMonitor.getAudioTracks().forEach(function (t) { t.enabled = true; }); // 複製は送信トラックの無効状態を引き継ぐため
        const ctx = getAudioContext();
        txAnalyser = ctx.createAnalyser();
        txAnalyser.fftSize = 256;
        txAnalyser.smoothingTimeConstant = 0.6;
        ctx.createMediaStreamSource(txMonitor).connect(txAnalyser);
      } catch (_) { txAnalyser = null; }
    }

    // マイクは初回のみ取得し、トラックを無効化した状態で全員に一度だけ共有する
    async function initMicrophone() {
      if (userMediaStream && localAudioTrack) return true;
      try {
        userMediaStream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
          video: false
        });
        localAudioTrack = userMediaStream.getAudioTracks()[0];
        localAudioTrack.enabled = false;
        setupTxAnalyser();
        if (room) room.addStream(userMediaStream);
        return true;
      } catch (err) {
        alert('マイクの許可が必要です（HTTPS接続が必要）: ' + err.message);
        return false;
      }
    }

    function connectToChannel(key) {
      const roomId = 'h-' + key;
      if (room) {
        room.leave();
        peerMap.clear();
        rxAnalysers.clear();
        currentTalkerId = null;
        document.querySelectorAll('audio[id^="audio-"]').forEach(function (a) { a.remove(); });
        ledRx.classList.remove('active');
        renderPeers();
      }
      room = joinRoom({
        appId: 'pochitalk-walkie-talkie-cf-worker',
        rtcConfig: {
          iceServers: [
            { urls: 'stun:stun.cloudflare.com:3478' },
            { urls: 'stun:stun.l.google.com:19302' }
          ]
        }
      }, roomId);

      // makeAction は同じ名前を1回しか登録できないので、送受信を一度に受け取る
      const pttStateAction = room.makeAction('pttState');
      const nameAction = room.makeAction('pttName');
      sendPttState = pttStateAction[0];
      sendNickname = nameAction[0];

      nameAction[1](function (name, peerId) {
        const p = peerMap.get(peerId);
        if (p) { p.name = name; renderPeers(); }
      });

      pttStateAction[1](function (state, peerId) {
        const peer = peerMap.get(peerId);
        if (peer) peer.isTalking = state.isTalking;
        if (state.isTalking) {
          currentTalkerId = peerId;
          if (isTransmitting) {
            // 同時に話し始めた: 両方とも送信が切れて混線
            pressed = false;
            stopTransmissionSilently();
            showCrosstalk();
            flashSub('混線！ もう一度押してください');
          } else if (othersTalking() >= 2) {
            showCrosstalk();
          } else {
            onStartReceiving((peer && peer.name) || '相手');
          }
        } else if (currentTalkerId === peerId) {
          onStopReceiving();
        }
        renderPeers();
      });

      room.onPeerJoin(function (peerId) {
        peerMap.set(peerId, { name: '接続中...', isTalking: false });
        renderPeers();
        sendNickname(myNickname, peerId);
        if (userMediaStream) room.addStream(userMediaStream, peerId);
      });

      room.onPeerLeave(function (peerId) {
        peerMap.delete(peerId);
        rxAnalysers.delete(peerId);
        if (currentTalkerId === peerId) onStopReceiving();
        const el = document.getElementById('audio-' + peerId);
        if (el) el.remove();
        renderPeers();
      });

      room.onPeerStream(function (stream, peerId) {
        let el = document.getElementById('audio-' + peerId);
        if (!el) {
          el = document.createElement('audio');
          el.id = 'audio-' + peerId;
          el.autoplay = true;
          el.playsInline = true;
          document.body.appendChild(el);
        }
        el.srcObject = stream;
        try {
          const ctx = getAudioContext();
          const an = ctx.createAnalyser();
          an.fftSize = 256;
          an.smoothingTimeConstant = 0.6;
          ctx.createMediaStreamSource(stream).connect(an);
          rxAnalysers.set(peerId, an);
        } catch (_) {}
        el.play().catch(function () {});
      });

      if (userMediaStream) room.addStream(userMediaStream);
    }

    function renderPeers() {
      if (peerMap.size === 0) {
        peersList.innerHTML = '<span class="peers-empty">待機中（参加者なし）</span>';
        return;
      }
      peersList.innerHTML = '';
      peerMap.forEach(function (info, id) {
        const span = document.createElement('span');
        span.className = 'peer-tag' + (info.isTalking ? ' talking' : '');
        span.textContent = (info.isTalking ? '🎙️ ' : '👤 ') + (info.name || id.slice(0, 5));
        peersList.appendChild(span);
      });
    }

    function onStartReceiving(name) {
      ledRx.classList.add('active');
      statusLabel.textContent = 'RX: ' + name;
      statusLabel.style.color = 'var(--rx-color)';
    }

    function onStopReceiving() {
      ledRx.classList.remove('active');
      currentTalkerId = null;
      if (!isTransmitting) {
        statusLabel.textContent = 'STANDBY';
        statusLabel.style.color = '#38bdf8';
        playPttEndTone();
      }
    }

    let startPending = false;
    async function startTransmission(quiet) {
      if (isTransmitting || startPending) return;
      if (!room) { flashSub('先に部屋に入ってください'); return; }
      if (othersTalking() > 0) { if (!quiet) { playBusyTone(); flashSub('話中です'); } return; }
      startPending = true;
      getAudioContext();
      const ok = await initMicrophone();
      startPending = false;
      if (!ok) return;
      if (!pressed) return; // 許可ダイアログ中に指を離した場合
      if (othersTalking() > 0) { if (!quiet) { playBusyTone(); flashSub('話中です'); } return; }
      isTransmitting = true;
      localAudioTrack.enabled = true;
      pttBtn.classList.add('transmitting');
      ledTx.classList.add('active');
      statusLabel.textContent = 'TX (送信中)';
      statusLabel.style.color = 'var(--tx-color)';
      playPttStartTone();
      if (sendPttState) sendPttState({ isTalking: true });
      announce();
    }

    function stopTransmissionSilently() {
      if (!isTransmitting) return;
      isTransmitting = false;
      if (localAudioTrack) localAudioTrack.enabled = false;
      pttBtn.classList.remove('transmitting');
      ledTx.classList.remove('active');
      if (sendPttState) sendPttState({ isTalking: false });
      announce();
    }

    function stopTransmission() {
      if (!isTransmitting) return;
      isTransmitting = false;
      if (localAudioTrack) localAudioTrack.enabled = false;
      pttBtn.classList.remove('transmitting');
      ledTx.classList.remove('active');
      const talker = currentTalkerId && peerMap.get(currentTalkerId);
      if (talker) {
        onStartReceiving(talker.name);
      } else {
        statusLabel.textContent = 'STANDBY';
        statusLabel.style.color = '#38bdf8';
      }
      playPttEndTone();
      if (sendPttState) sendPttState({ isTalking: false });
      announce();
    }

    let pressed = false;
    function press() { pressed = true; startTransmission(); }
    function release() { pressed = false; stopTransmission(); }

    pttBtn.addEventListener('mousedown', function (e) { e.preventDefault(); press(); });
    window.addEventListener('mouseup', function () { if (pressed) release(); });
    pttBtn.addEventListener('touchstart', function (e) { e.preventDefault(); press(); }, { passive: false });
    pttBtn.addEventListener('touchend', function (e) { e.preventDefault(); release(); }, { passive: false });
    pttBtn.addEventListener('touchcancel', function () { release(); });

    window.addEventListener('keydown', function (e) {
      if (e.code === 'Space' && !e.repeat) {
        const tag = document.activeElement && document.activeElement.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA') return;
        e.preventDefault();
        press();
      }
    });

    window.addEventListener('keyup', function (e) {
      if (e.code === 'Space') {
        const tag = document.activeElement && document.activeElement.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA') return;
        e.preventDefault();
        release();
      }
    });

    // ---------- 部屋(ハッシュ周波数)操作 ----------
    let pendingKey = null; // 招待URLで指定された部屋（まだ入室していない）
    function renderFreq(key) {
      document.getElementById('homeView').classList.toggle('hidden', !!key);
      document.getElementById('radioView').classList.toggle('hidden', !key);
      if (!key) document.getElementById('stickyBar').classList.add('hidden');
      if (!key) return;
      roomDisplay.textContent = fmt(key);
      chDisplay.textContent = 'ROOM HASH';
      freqDisplay.textContent = fmt(key);
      freqDisplay.classList.remove('tuning');
      lcdSub.textContent = '';
    }

    function leaveRoom() {
      if (isTransmitting) { pressed = false; stopTransmission(); }
      if (room) { room.leave(); room = null; }
      sendPttState = null; sendNickname = null;
      peerMap.clear();
      currentTalkerId = null;
      document.querySelectorAll('audio[id^="audio-"]').forEach(function (a) { a.remove(); });
      ledRx.classList.remove('active');
      statusLabel.textContent = 'STANDBY';
      statusLabel.style.color = '#38bdf8';
      currentKey = null;
      try { history.replaceState(null, '', location.pathname); } catch (_) {}
      renderPeers();
      renderFreq(null);
      renderScan();
    }

    function flashSub(text) {
      lcdSub.textContent = text;
      clearTimeout(flashSub.t);
      flashSub.t = setTimeout(function () { renderFreq(currentKey); }, 2500);
    }

    function tuneKey(key) {
      if (isTransmitting) { flashSub('送信中は周波数を変えられません'); return; }
      if (key === currentKey) { renderFreq(key); flashSub('すでにこの周波数です'); return; }
      currentKey = key;
      pendingKey = null;
      try { history.replaceState(null, '', '?r=' + key); } catch (_) {}
      playTuneStatic();
      freqDisplay.classList.add('tuning');
      let i = 0;
      const t = setInterval(function () {
        if (++i >= 9) { clearInterval(t); renderFreq(currentKey); return; }
        freqDisplay.textContent = fmt(toHex(crypto.getRandomValues(new Uint8Array(4))));
      }, 45);
      connectToChannel(currentKey);
      announce();
      renderScan();
    }

    // ---------- ロビー: 各部屋の在室状況を共有 ----------
    const lobbyEntries = new Map(); // peerId -> { ch(key), talking, ts }
    let sendPresence = null, lobbyRoom = null;

    function announce(peerId) {
      if (!sendPresence || !currentKey) return;
      const msg = { ch: currentKey, talking: isTransmitting, name: myNickname };
      if (peerId) sendPresence(msg, peerId); else sendPresence(msg);
    }

    function joinLobby() {
      lobbyRoom = joinRoom({ appId: 'pochitalk-walkie-talkie-cf-worker' }, 'pochitalk-lobby');
      const act = lobbyRoom.makeAction('presence');
      sendPresence = act[0];
      act[1](function (data, peerId) {
        if (!data || typeof data.ch !== 'string' || !isKey(data.ch)) return;
        lobbyEntries.set(peerId, { ch: data.ch, talking: !!data.talking, name: String(data.name || '?').slice(0, 12), ts: Date.now() });
        renderScan();
      });
      lobbyRoom.onPeerJoin(function (peerId) { announce(peerId); });
      lobbyRoom.onPeerLeave(function (peerId) { lobbyEntries.delete(peerId); renderScan(); });
      setInterval(function () {
        const now = Date.now();
        lobbyEntries.forEach(function (v, k) { if (now - v.ts > 15000) lobbyEntries.delete(k); });
        announce();
        renderScan();
      }, 4000);
    }

    function roomStats() {
      const stats = new Map();
      lobbyEntries.forEach(function (v) {
        const e = stats.get(v.ch) || { count: 0, air: false, names: [] };
        e.count++; e.names.push(v.name); if (v.talking) e.air = true;
        stats.set(v.ch, e);
      });
      if (currentKey) {
        const me = stats.get(currentKey) || { count: 0, air: false, names: [] };
        me.count++; me.names.unshift(myNickname + '(自分)'); if (isTransmitting) me.air = true;
        stats.set(currentKey, me);
      }
      return stats;
    }

    function renderScan() {
      const stats = roomStats();
      if (pendingKey && !stats.has(pendingKey)) stats.set(pendingKey, { count: 0, air: false, names: [], invited: true });
      const keys = Array.from(stats.keys()).sort();
      let total = 0;
      stats.forEach(function (v) { total += v.count; });
      onlineCount.textContent = '👥 ' + total + '人 / ' + keys.length + '部屋';
      scanList.innerHTML = '';
      keys.forEach(function (key) {
        const st = stats.get(key);
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'chan-chip' + (key === currentKey ? ' me' : '') + (st.air ? ' air' : '');
        const head = document.createElement('div');
        head.textContent = (st.air ? '🔴 ' : '🔵 ') + fmt(key) + ' · 👤' + st.count + (key === pendingKey ? ' · 招待' : '');
        const who = document.createElement('div');
        who.className = 'chip-who';
        who.textContent = st.invited ? '招待された部屋（現在は無人）' : st.names.join('、');
        b.appendChild(head); b.appendChild(who);
        b.addEventListener('click', function () { tuneKey(key); });
        scanList.appendChild(b);
      });
    }

    document.getElementById('emptyBtn').addEventListener('click', function () {
      const key = toHex(crypto.getRandomValues(new Uint8Array(4)));
      tuneKey(key);
      flashSub('新しい部屋を作りました。招待で友達を呼べます');
    });

    const aboutDialog = document.getElementById('aboutDialog');
    document.getElementById('aboutBtn').addEventListener('click', function () {
      if (aboutDialog.showModal) aboutDialog.showModal(); else aboutDialog.setAttribute('open', '');
    });
    document.getElementById('aboutClose').addEventListener('click', function () { aboutDialog.close(); });
    aboutDialog.addEventListener('click', function (e) { if (e.target === aboutDialog) aboutDialog.close(); });

    // ---------- VOX（声を拾って自動で送信）----------
    const voxCheck = document.getElementById('voxCheck');
    const voxPanel = document.getElementById('voxPanel');
    const voxFill = document.getElementById('voxFill');
    const voxThrEl = document.getElementById('voxThr');
    const voxSens = document.getElementById('voxSens');
    const VOX_HOLD_MS = 1200;   // 声が止んでからこの時間で送信終了
    const VOX_COOLDOWN_MS = 700; // 終了音を拾って再送信しないための待ち
    let voxStream = null, voxAnalyser = null, voxTimer = null, voxBuf = null;
    let voxHits = 0, voxLastVoice = 0, voxCooldownUntil = 0, voxInitiated = false;

    function voxThreshold() { return 0.02 + (parseInt(voxSens.value, 10) - 1) * 0.007; }
    function updateVoxThrMarker() { voxThrEl.style.left = Math.min(100, voxThreshold() / 0.2 * 100) + '%'; }

    function voxTick() {
      if (!voxAnalyser) return;
      voxAnalyser.getFloatTimeDomainData(voxBuf);
      let sum = 0;
      for (let i = 0; i < voxBuf.length; i++) sum += voxBuf[i] * voxBuf[i];
      const lvl = Math.sqrt(sum / voxBuf.length);
      const thr = voxThreshold();
      voxFill.style.width = Math.min(100, lvl / 0.2 * 100) + '%';
      voxFill.classList.toggle('over', lvl > thr);
      if (!room) { voxHits = 0; return; }
      const now = Date.now();
      if (!isTransmitting) {
        if (!startPending) voxInitiated = false;
        // 他の人が話している間は割り込まない
        if (lvl > thr && othersTalking() === 0 && now > voxCooldownUntil && !startPending) {
          if (++voxHits >= 3) {
            voxHits = 0;
            voxInitiated = true;
            voxLastVoice = now;
            pressed = true;
            startTransmission(true);
          }
        } else if (lvl <= thr) { voxHits = 0; }
      } else if (voxInitiated) {
        if (lvl > thr) voxLastVoice = now;
        else if (now - voxLastVoice > VOX_HOLD_MS) {
          pressed = false;
          voxInitiated = false;
          stopTransmission();
          voxCooldownUntil = Date.now() + VOX_COOLDOWN_MS;
        }
      }
    }

    async function startVox() {
      getAudioContext();
      const ok = await initMicrophone();
      if (!ok) { voxCheck.checked = false; return; }
      // 送信用トラックは無効化されているので、監視用に複製したトラックを使う
      voxStream = userMediaStream.clone();
      voxStream.getAudioTracks().forEach(function (t) { t.enabled = true; });
      const ctx = getAudioContext();
      voxAnalyser = ctx.createAnalyser();
      voxAnalyser.fftSize = 1024;
      voxBuf = new Float32Array(voxAnalyser.fftSize);
      ctx.createMediaStreamSource(voxStream).connect(voxAnalyser);
      voxPanel.classList.remove('hidden');
      updateVoxThrMarker();
      voxTimer = setInterval(voxTick, 50);
    }

    function stopVox() {
      clearInterval(voxTimer); voxTimer = null;
      if (voxStream) { voxStream.getTracks().forEach(function (t) { t.stop(); }); voxStream = null; }
      voxAnalyser = null;
      voxPanel.classList.add('hidden');
      voxFill.style.width = '0';
      if (voxInitiated && isTransmitting) { pressed = false; stopTransmission(); }
      voxInitiated = false;
    }

    voxCheck.addEventListener('change', function () { if (voxCheck.checked) startVox(); else stopVox(); });
    try {
      const savedSens = parseInt(localStorage.getItem('pochitalk_vox_thr'), 10);
      if (savedSens >= 1 && savedSens <= 10) voxSens.value = savedSens;
    } catch (_) {}
    voxSens.addEventListener('input', function () {
      updateVoxThrMarker();
      try { localStorage.setItem('pochitalk_vox_thr', voxSens.value); } catch (_) {}
    });

    // ---------- 音声レベル ----------
    (function () {
      const lv = document.getElementById('levelCanvas').getContext('2d');
      const lvW = document.getElementById('levelCanvas').width, lvH = document.getElementById('levelCanvas').height;
      const levelLabel = document.getElementById('levelLabel');
      const levelDb = document.getElementById('levelDb');
      const SEGS = 24;
      let level = 0, peak = 0, peakHoldUntil = 0, td = null;

      function pickSource() {
        if (isTransmitting && txAnalyser) return { an: txAnalyser, color: '#ef4444' };
        if (currentTalkerId && rxAnalysers.get(currentTalkerId)) return { an: rxAnalysers.get(currentTalkerId), color: '#10b981' };
        if (txAnalyser) return { an: txAnalyser, color: '#3b82f6', idle: true };
        return null;
      }

      function draw() {
        requestAnimationFrame(draw);
        if (document.getElementById('radioView').classList.contains('hidden')) return;
        const src = pickSource();
        // ---- レベルメーター（音量 dB、ピークホールド付き）----
        let target = 0, db = -Infinity;
        if (src) {
          if (!td || td.length !== src.an.fftSize) td = new Uint8Array(src.an.fftSize);
          src.an.getByteTimeDomainData(td);
          let sum = 0;
          for (let i = 0; i < td.length; i++) { const v = (td[i] - 128) / 128; sum += v * v; }
          const rms = Math.sqrt(sum / td.length);
          db = rms > 0 ? 20 * Math.log10(rms) : -Infinity;
          target = Math.min(1, Math.max(0, (db + 60) / 60));
        }
        level += (target - level) * (target > level ? 0.5 : 0.12);
        const nowMs = performance.now();
        if (level >= peak) { peak = level; peakHoldUntil = nowMs + 800; }
        else if (nowMs > peakHoldUntil) peak = Math.max(level, peak - 0.02);
        lv.clearRect(0, 0, lvW, lvH);
        const sg = 3, sw = (lvW - sg * (SEGS - 1)) / SEGS;
        for (let i = 0; i < SEGS; i++) {
          const pos = (i + 1) / SEGS;
          const on = pos <= level + 0.001;
          const isPeak = Math.abs(pos - peak) < 0.5 / SEGS + 0.001 && peak > 0.02;
          const col = pos > 0.85 ? '#ef4444' : pos > 0.65 ? '#fbbf24' : '#10b981';
          lv.globalAlpha = (on || isPeak) ? 1 : 0.16;
          lv.fillStyle = col;
          lv.fillRect(i * (sw + sg), 0, sw, lvH);
        }
        lv.globalAlpha = 1;
        levelDb.textContent = isFinite(db) ? Math.round(db) + ' dB' : '-∞ dB';
        levelLabel.textContent = !src ? 'LEVEL' : (src.idle ? 'MIC' : (isTransmitting ? 'TX' : 'RX'));
      }
      requestAnimationFrame(draw);
    })();

    document.getElementById('leaveBtn').addEventListener('click', function () { leaveRoom(); });
    document.getElementById('leaveBtn2').addEventListener('click', function () { leaveRoom(); });

    // 液晶が画面外にスクロールしたら、上部にルームハッシュのバーを出す
    (function () {
      const bar = document.getElementById('stickyBar');
      const lcd = document.querySelector('.lcd');
      if (!('IntersectionObserver' in window)) return;
      new IntersectionObserver(function (entries) {
        const visible = entries[0].isIntersecting;
        const inRoom = !document.getElementById('radioView').classList.contains('hidden');
        bar.classList.toggle('hidden', visible || !inRoom);
      }).observe(lcd);
    })();

    document.getElementById('shareBtn').addEventListener('click', function () {
      if (!currentKey) return;
      const url = location.origin + location.pathname + '?r=' + currentKey;
      const done = function () { flashSub('招待URLをコピーしました'); };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(done, function () { prompt('招待URL', url); });
      } else { prompt('招待URL', url); }
    });

    nameInput.addEventListener('input', function () {
      myNickname = nameInput.value.trim() || 'NoName';
    });
    nameInput.addEventListener('change', function () {
      myNickname = nameInput.value.trim() || 'NoName';
      try { localStorage.setItem('pochitalk_ptt_name', myNickname); } catch (_) {}
      if (sendNickname) sendNickname(myNickname);
      announce();
      renderScan();
    });

    (async function init() {
      const p = (urlParams.get('r') || '').trim().toLowerCase();
      pendingKey = isKey(p) ? p : null;
      renderFreq(null);
      joinLobby();
      renderScan();
    })();
  </script>
</body>
</html>`;

// ---- アイコン類（線画）。PNGはbase64で埋め込み ----
const FAVICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">\n  <rect width="64" height="64" rx="14" fill="#1a1e24"/>\n  <g fill="none" stroke="#60a5fa" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round">\n    <rect x="18" y="19" width="24" height="39" rx="5"/>\n    <path d="M25 19V8"/>\n    <path d="M24 28h12"/>\n    <circle cx="30" cy="45" r="5.5"/>\n    <path d="M47 24q4 6 0 12"/>\n    <path d="M53 19q7 11 0 22"/>\n  </g>\n</svg>';
const PNG_FAVICON = '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';
const PNG_APPLE = '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';
const PNG_OG = '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';

function bytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
const CACHE = 'public, max-age=86400';

export default {
  async fetch(request) {
    const url = new URL(request.url);
    switch (url.pathname) {
      case '/favicon.svg':
        return new Response(FAVICON_SVG, { headers: { 'Content-Type': 'image/svg+xml', 'Cache-Control': CACHE } });
      case '/favicon.png':
      case '/favicon.ico':
        return new Response(bytes(PNG_FAVICON), { headers: { 'Content-Type': 'image/png', 'Cache-Control': CACHE } });
      case '/apple-touch-icon.png':
        return new Response(bytes(PNG_APPLE), { headers: { 'Content-Type': 'image/png', 'Cache-Control': CACHE } });
      case '/og.png':
        return new Response(bytes(PNG_OG), { headers: { 'Content-Type': 'image/png', 'Cache-Control': CACHE } });
    }
    return new Response(HTML.split('__ORIGIN__').join(url.origin), {
      headers: {
        'Content-Type': 'text/html;charset=UTF-8',
        'Cache-Control': 'no-cache'
      }
    });
  }
};
