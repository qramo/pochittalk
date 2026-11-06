# ぽちっと通話 (α版)

ボタンを押している間だけ話せる、トランシーバー風のグループ通話アプリ。
Cloudflare Workers の単一ファイル (`worker.js`) で動きます。

## デプロイ
1. Cloudflare ダッシュボードで Worker を作成し、「コードを編集」に `worker.js` の全文を貼り付けて Deploy。
2. HTTPS の URL を開くだけで使えます（マイクは HTTPS が必須）。

## 仕組み
- 音声は WebRTC の P2P。接続の仲介は [Trystero](https://github.com/dmotz/trystero)（Nostr リレー）。
- 部屋は 8 桁のハッシュ値。ロビーで各部屋の人数・ニックネームを共有します。
- 詳しいプライバシー情報はアプリ内の「このアプリについて」を参照。

## assets/
ファビコン・OGP 画像の線画 SVG（PNG は `worker.js` に埋め込み済み）。
