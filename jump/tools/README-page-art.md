# ページ絵の生成

```bash
# 1) 参照画像ホスト（一時インフラ）を立てる ※ 既に立っていれば不要
cd <scratchpad>/refhost && npx wrangler deploy
#    → https://shiropico-refs.<sub>.workers.dev

# 2) トークンを mint（kagi の署名 secret から）
MURAKUMO_TOKEN_SECRET=$(KAGI_HOME=$HOME/.kagi kagi get MURAKUMO_GENERATION_TOKEN_SECRET) \
  clojure -M:token issue shiropico generation 7200   # in orgs/gftdcojp/cloud-murakumo

# 3) 中身を確認してから生成
nbb jump/tools/generate-page-art.cljs --pages 6,7,8 --dry-run
MURAKUMO_GENERATION_TOKEN=... nbb jump/tools/generate-page-art.cljs --pages 6,7,8 --out jump/tools/art

# 4) 原稿に組む
nbb --classpath "<kami-genko>/src:<kami-mangaka-page>/src:<canvaskit>/src" \
  jump/tools/build-page.cljs jump/tools/art/p6 out.svg jump/tools/pages/oneshot-p06.edn
```

## 踏んだ落とし穴（繰り返さないために）

| | |
|---|---|
| **終端ステータスは `"done"`** | `"succeeded"` を待つと永久に回る。実測: 15分 poll して timeout、ジョブ自体は4分で done になっていた |
| **参照ホストは HEAD にも 200 を返す必要がある** | kotobase.net は GET 200 / HEAD 404 で `file_download_error` になった |
| **data URI は拒否される** | `input.images` は http(s) URL のみ |
| **キャラ表をそのまま参照に渡さない** | 表ごと複製される。単体被写体に切り出す |
| **細長い切り出しを正方形に潰さない** | 被写体が歪んで参照として害になる（rat が横に潰れた） |
| **1ページ = 1クリップ** | seedance はマルチショット。コマごとに頼むとコマ数ぶん払う（実測で4倍払っていた） |
| **返ってくる URL は `murakumo.cloud`** | 実体は `generation.murakumo.cloud` にしかない。ホストを差し替える |
| **出力はカラー** | 漫画原稿は白黒。コマ画像だけ desaturate し、**mp4 は色付きで残す** |
| **却下済み gen2 を参照に混ぜない** | ティールのパーカー＋獣耳のシロ／オレンジのピコ。ART-DIRECTION §0.5 |

## コスト

seedance-2.0-fast は **1クリップ $0.22**。1ページ1クリップなので 45P で約 $10。
`--dry-run` でプロンプトと参照を確認してから回すこと。生成済みのページは
コマ画像の存在で判定して自動 skip する（中断しても再開できる）。
