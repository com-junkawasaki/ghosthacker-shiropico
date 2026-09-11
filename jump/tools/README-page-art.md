# ページ絵の生成

## 作品の切り替え（読切 / 各話）

ネームの md は作品ごとに別ファイル（読切 `oneshot-45p.md`、連載 `name-01.md`〜
`name-15.md`）。**どれを通すかは `--work` で選ぶ**。定義は `jump/tools/works.edn`
——md のパス・ページ EDN の接頭辞・表示名・ページごとの作画スタイルがそこにある。

```bash
nbb jump/tools/md-to-pages.cljk   --work ep01          # → pages/ep01-pNN.edn
nbb jump/tools/generate-page-art.cljk --work ep01 --pages 1,2,3
nbb jump/tools/compose-pages.cljk --work ep01 --art /tmp/art-ep01 --out /tmp/pages-ep01 \
  --classpath "<kami-genko>/src:<kami-mangaka-page>/src:<canvaskit>/src"
```

`--work` 無指定は `:oneshot`（読切）。作品を足すときは **works.edn に 1 エントリ
足すだけ**で、変換器・生成器は触らない。

## 手順

```bash
# 1) 参照画像ホスト（一時インフラ）を立てる ※ 既に立っていれば不要
cd <scratchpad>/refhost && npx wrangler deploy
#    → https://shiropico-refs.<sub>.workers.dev

# 2) トークンを mint（kagi の署名 secret から）
MURAKUMO_TOKEN_SECRET=$(KAGI_HOME=$HOME/.kagi kagi get MURAKUMO_GENERATION_TOKEN_SECRET) \
  clojure -M:token issue shiropico generation 7200   # in orgs/gftdcojp/cloud-murakumo

# 3) 中身を確認してから生成
nbb jump/tools/generate-page-art.cljk --pages 6,7,8 --dry-run
MURAKUMO_GENERATION_TOKEN=... nbb jump/tools/generate-page-art.cljk --pages 6,7,8 --out jump/tools/art

# 4) 原稿に組む
nbb --classpath "<kami-genko>/src:<kami-mangaka-page>/src:<canvaskit>/src" \
  jump/tools/build-page.cljk jump/tools/art/p6 out.svg jump/tools/pages/oneshot-p06.edn
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

## キャラ入れ替わりの再発防止（2026-08-03）

参照画像を渡すだけでは、ショット間で衣装・髪色・持ち物が入れ替わる。実測でシロと
ピコが同じ顔になり、白い帽子がピコに移った。原因は、プロンプトがキャラを
**名前でしか呼んでいない**こと——モデルは「シロ」が何なのかを参照画像から推測する
しかなく、ショットが進むにつれて推測がずれる。

対策は**2つ同時に**打つ。片方だけでは効かない。

1. ページ冒頭に **character lock ブロック**を置き、そのページに出るキャラの外見を
   明文化する（`CHAR-LOCK`）
2. 各ショット本文で、キャラ名の直後に **英字タグ**を差し込む（`シロ` → `シロ(SHIRO)`）
   ——lock ブロックの記述と本文を結びつける

①だけだとショット本文と結びつかず、②だけだとタグの中身が定義されない。
プロンプトが 2000 字を超えるときも **lock ブロックは削らない**（削ると再発する）。

キャラを増やしたら `CHAR-LOCK` に 1 行足すこと。**外見を書かずに名前だけ足さない。**

照合は `:dir` に出てくる**表記そのもの**（`str/includes?`）なので、和名の欄には
md で実際に使われている語を入れる。第1話の「コートの少年」は**話者名にしか出ず
地の文には「黒いコート」としか書かれていない**ので、和名は `黒いコート` にした。
セリフだけに出るキャラは lock に載らない——絵に出るなら地の文に書くこと。

## 番号付きコマが無いページ

見開きだけでなく、**扉**（1コマ・地の文しかない）もここに来る。`md-to-pages.cljs`
は「コマが空なら地の文で大ゴマ1つ」にするので、扉を見開き扱いにしなくても白紙に
ならない。見開き限定にすると `:rows` が空になって白紙が焼ける（実測: 第1話 P.1）。
