# 制作経路（ネーム → 原稿）

作画規約は `jump/ART-DIRECTION.md`／キャラ設定は `jump/character-design-spec.edn`

---

## 決定（2026-08-03、オーナー判断）

**動画生成は seedance-2.0-fast を使う。** 自前フリートの無料経路は実際に1ページ通したが、
品質が採用水準に届かなかった。

計測の正本は **`network-awai/cloud-murakumo` の `resources/video-engine-comparison.edn`**
（`:rerun-2026-08-03`）。以下はその要約。

| | 無料 `wan2.2-ti2v-5b` | **採用 `seedance-2.0-fast`** |
|---|---|---|
| 1ページ | $0.00 | **$0.22** |
| 1ページの所要 | 約12分（4クリップ） | **約2分（1クリップ）** |
| multi-shot | 不可（1クリップ1ショット） | **可（1クリップに4〜5ショット）** |
| 出力比率 | 768×448 横長固定 → **縦コマで主体が切れる** | 960×960 |
| 構図 | i2v なので**参照が1フレーム目**になり寄り絵に固定 | 参照は identity のみ。構図は別に指示できる |
| 参照なしのキャラ | **出ない**（背景だけ返る） | 出る |

`ltx-2.3` は **使わない** — ジョブは `done` を返すのに**出力が全フレーム真っ黒**（実測 2026-08-03）。

### 費用の目安

| | 尺 | 理想 | 撮り直し込み（×1.8） |
|---|---:|---:|---:|
| 読切「かぞえる」 | 45P | $9.90 | **$18 前後** |
| 連載 第1話 | 48P | $10.56 | **$19 前後** |
| 連載 各話 | 19P | $4.18 | **$8 前後** |
| Arc 1 全15話 | 314P | $69.08 | **$124 前後** |

> **1ページ＝1クリップで焼くこと。** 1コマ1クリップにすると4倍払うことになる
> （実際に1ページ $0.88 払った）。seedance は multi-shot なので、
> 「SHOT 1: … SHOT 2: …」と1つのプロンプトに並べて1本で撮る。

---

## 工程

```
seedance  →  animeka  →  genko  →  mangaka
（撮る）     （抜く）    （組む）   （焼く）
```

| 層 | 担当 | 実体 |
|---|---|---|
| **seedance** | カットの映像 | `POST generation.murakumo.cloud/api/v1/generation` |
| **animeka** | 映像 → 静止カット | ffmpeg で代表フレームを抜く（尺の 60% 付近） |
| **genko** | 原稿の幾何 | **`kotoba-lang/kami-genko`** が SSoT（B4実寸・コマ割り・吹き出し） |
| **mangaka** | draw op → 画 | `jump/tools/build-page.cljk`（SVG を焼く。host の仕事） |

**genko はグリフを描かない。** `node->draws` は text ノードに 8×8 のマーカ矩形しか出さない。
文字を置くのは host の責任なので、縦書きは1文字ずつ座標を出している。ここを混ぜない。

---

## 参照画像の契約（ここで3回つまずいた）

1. **キーは `input.images`**（配列・最大9）。fleet の i2v なら `input.image`（単一）
2. **http(s) URL 必須。** data URI は明示的に拒否される（fal が自分で取りに行く設計）
3. **HEAD に答えるホストであること。**
   `kotobase.net/ipfs/<cid>` は **GET 200 だが HEAD 404** で、fal が `file_download_error` になる。
   **B2 の presigned GET URL は通る**
4. **参照は「そのまま1コマになる絵」を渡す。**
   キャラシート（複数ポーズ）を渡すと、i2v ではシートごと再生される
5. **参照なしではキャラが出ない**（背景だけ返る）

---

## 使い方

```bash
# 1) 参照画像を HEAD の通るホストへ置く（B2 presigned など）
# 2) seedance に 1ページ＝1クリップで投げる（SHOT 1..4 を1プロンプトに）
# 3) ffmpeg で4フレーム抜いて <shots-dir>/{k1..k4}.jpg に置く
# 4) 原稿に組む
kbb --backend sci --classpath "<kami-genko>/src:<canvaskit>/src" \
    jump/tools/build-page.cljk <shots-dir> out.svg jump/tools/pages/oneshot-p12.edn
rsvg-convert -w 1000 out.svg -o out.png
```

ページ構成（コマの向き・セリフ・吹き出しの種別）は **`jump/tools/pages/*.edn`** に置く。
スクリプトには埋め込まない。

### 既存のページ定義
| ファイル | コマ | 中身 |
|---|---:|---|
| `pages/oneshot-p01.edn` | 1 | 読切 P.1 扉 — 給水塔の上のピコ |
| `pages/oneshot-p02.edn` | 5 | 読切 P.2 — 朝の商店街／たけうちベーカリー／パンをもらう |
| `pages/oneshot-p03.edn` | 5 | 読切 P.3 — 「お代は」／オーリオだけが振り返る |
| `pages/oneshot-p04.edn` | 6 | 読切 P.4 — 看板を全品0円に／「それは犯罪です」 |
| `pages/oneshot-p05.edn` | 5 | 読切 P.5 — 駅の掲示板／テーマ文①「なんにも こわしてないのだ」 |
| `pages/oneshot-p12.edn` | 4 | 読切 P.12 — ゾンビの行進／ラット登場 |
| `pages/oneshot-p21.edn` | 4 | 読切 P.21 — シロ初登場・《カウント・シープ》 |

### コマ割りは `:rect` で明示する
genko の `panel-presets` は `1 / 2h / 2v / 3h / 2x2` しか持たないが、実際の原稿は
5コマ・6コマ・変則が普通に出る。**preset を母数にせず、基本枠内の正規化
`[x1 y1 x2 y2]` を `:rect` に書く**（省略時だけ `2x2` に落ちる）。
**右綴じなので、コマ1 は右**（x1 が大きいほう）に置く。

### SFX は動画側、吹き出しは genko 側
- **SFX（描き文字）は絵の一部**なので、seedance のプロンプトに書き込ませる。
  page EDN の `:sfx` はその指示の記録であって、genko は描かない
- **吹き出しとセリフは genko 側**。`:fuki {:type :tail :lines}` で置く

### 絵が無いコマは、そのままネームになる
`:shot` に対応する画像が無いコマには、`:dir`（ト書き）と `:sfx` が薄く描かれる。
つまり**絵を入れる前でも、この出力自体が実寸のネーム**として読める。
絵ができたら同じコマに差し込むだけで原稿になる。

---

## 吹き出しの行組みは共通lib（2026-08-03）

**自前で縦組みを書かない。** `kami.mangaka.tategaki`（kami-genko）に委譲する。

```clojure
(tg/layout lines box {:locale :ja :font-size 14.0})
;; => {:mode :vertical-rtl :glyphs [{:ch \見 :x .. :y .. :rotate? false} ...]}
(tg/box-size lines {:locale :ja :pad 2.6})   ; 字から箱を起こす
```

- **日本語だけが既定で縦組み。** 中国語・韓国語は縦組み可能だが現代出版は横組みが主流なので既定にしない
- **ar/he/fa/ur は横組みの RTL**
- ページ EDN に `:locale` を書けば切り替わる（省略時 `:ja`）
- 縦組みで回る字（長音符・ダッシュ・三点リーダ・括弧類）は lib が `:rotate?` で返す

**なぜ lib か**: 自前で書いていたとき、**長音符が横に寝ていた**（「見ろオ ー リオ！」）。
フォントにもキャンバスにも依存しない純粋な規則を host ごとに実装させると、必ずこうなる。

⚠ **ラテン文字は等幅で置かれる。** lib は字送りを `font-size` 固定で計算するので、
プロポーショナルなフォントメトリクスが要る言語では字間が空く。
実フォントの advance を使うのは host の仕事で、まだやっていない。

---

## 安く上げる道（2026-08-03 調査）

**今すぐ使える、seedance-2.0-fast より安い経路は無い。**
（※ `seedance-2.5` は存在しない。live にあるのは `seedance-2.0` と `-fast` の2つで、
`-fast` のほうが安い＝既に安いほうを使っている。`seedance-2.0` は最大4kで**高い**。）

### ただし、構造的に無駄がある
**動画を買って、静止画だけ使っている。** 15秒クリップは 24fps で約360フレームあるのに、
コマに使うのは4〜6枚。**残り 98% は捨てている。**

### いちばん効く手（購入ではなくコード変更）
フリートの画像経路 `animagine-xl-4.0` は **$0** だが、
**参照画像に対応していない**（`comfy_image_graph` に ref_image 引数が無く、`denoise 1.0` の純 t2i）。

→ **ここに IP-Adapter 等の参照条件付けを足せば、$0 で canon キャラの静止画が出る。**
`kotoba-lang/murakumo` の `scripts/hunyuan3d-generation-api` への変更で、支払いは発生しない。
video 経路（`ltx_video_graph` / `wan_video_graph`）は既に `ref_image` を取っているので、
**画像側だけが対応していない**という非対称。

### 参考: アグリゲータは未実装
ADR-2608026000（fal / Modal / ModelsLab / Higgsfield を1つの面で売る）は
**`:adr/status "proposed"`** で、live には fal しか繋がっていない。

---

## 既知の未了

- **コマの比率に合わせて生成していない。** いまは 960×960 や 768×448 で撮って
  縦長のコマに `slice` で入れているため、端が切れる。比率を合わせれば改善する見込み（未検証）
- **ネーム P.21 に矛盾が残っている。** 「シロの**フード**からプロックが飛び出す」と書いてあるが、
  シロは**キャップ**（→ `ART-DIRECTION.md` §0.5）。絵では背後から出ているので、そちらに合わせて直す
- **吹き出しのしっぽが話者を指していない**コマがある。genko の `fukiTail` は
  4方向しか持たないので、コマ内の話者位置に応じて選ぶロジックが要る
