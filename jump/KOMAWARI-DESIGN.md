# コマ割りの設計と、読書体験の数値化

制作経路は `jump/PRODUCTION.md`／作画規約は `jump/ART-DIRECTION.md`

---

## 1. 結論から — 作らなくてよかった

「togashi / toriyama を参考にコマ割りを設計する」も「system dynamics でスコア化する」も、
**この workspace に既に実装がある**。新しく作らず、そこに載せた。

| やりたかったこと | 既にあったもの |
|---|---|
| 作家のコマ割り文法 | **`ai-gftd-mangaka/clj/resources/komawari_styles.edn`** — 鳥山・冨樫・荒木・浦沢・井上の5作家、**出典グレード付き**（`:primary` / `:secondary` / `:consensus` / `:inferred` / `:design-value`） |
| beats → 幾何の変換 | **`kotoba-lang/kami-mangaka-page` の `kami.mangaka.komawari/propose-page-layout`** |
| stock-flow の計算 | **`kotoba-lang/dynamics`**（CLAUDE.md が「ゼロから再発明するな」と名指し） |
| 実際に走る SD シミュレータ | **`kotoba-lang/org-oasis-open-xmile`**（OASIS XMILE 1.0）。`dynamics.xmile` の docstring が「軌跡が要るなら one-off simulator を作るな」と明記 |

> **`xsmile` という独立リポは存在しない。** `dynamics/xmile.cljc` が
> `org-oasis-open-xmile` への薄い層として在る、というのが実態。

---

## 2. コマ割り — 幾何を書くのをやめた

### 前（誤り）
page EDN に `:rect [x1 y1 x2 y2]` を手で書いていた。
段組も右綴じ順も自分で計算していたので、**均等割り**になり、φ加重も tilt も inset も無かった。

### 後
page EDN は **「段 × 読み順の beat」と `:style` だけ**を持つ。幾何は書かない。

```clojure
{:style :togashi
 :rows [[{:id "d1" :beat/intensity 0.7 :dir "…" :sfx "タタタタ"}
         {:id "d2" :dir "…"}]
        [{:id "d5" :beat/weight :large :fuki {…}}
         {:id "d6" :beat/weight :small :fuki {…}}]]}
```

`propose-page-layout` が `:style` からコマ矩形・tilt・polygon・z・bleed を導く。

### 分担線
| | 担当 |
|---|---|
| **komawari** | **どこに置くか** — 段の高さ配分（φ加重）、右綴じ順、tilt、inset、character-bleed |
| **genko** | **実寸で何を描くか** — B4 257×364mm、裁ち落とし／基本枠／内枠、吹き出しの輪郭、画像の配置 |

### 効いたこと（実測）
第4話 P.4 の3段目が、`:beat/weight :large` / `:small` を書いただけで
**「笑い転げるピコ＝大コマ／オーリオの真顔＝小コマ」**に自動で振れた。
これは冨樫の観察 `togashi-standoff-register-contrast`（同じ beat の中で register 対比を作る）が
幾何側に現れたもので、手で `:rect` を書いていたときは出せていなかった。

### スタイルの選び分け（本作での方針）
| スタイル | 使いどころ | 根拠（カタログの grading） |
|---|---|---|
| `:urasawa` | 日常・会話・静かな導入 | 定点＋水平バンド＋tilt なし（`:primary` 浦沢チャンネル／小津インタビュー） |
| `:togashi` | 対峙・register 対比・密度差 | 矩形のまま density-contrast と panel-in-panel（提供ページの観察） |
| `:inoue` | 扉・無音・解放の見開き | 大ゴマは「超描き込み」か「余白」の両極（`:secondary`） |
| `:toriyama` | 打撃・ドタバタ | 枠線そのものが攻撃の対角線を継承（force-line） |
| `:araki` | 未使用 | 本作は 7+ で枠の最大主義まで振らない |

---

## 3. 読書体験の数値化 — 点ではなく軌跡

### なぜ点数ではないか
1ページを単体で採点しても「読んでいる体験」は測れない。
**緊張は前のページから持ち越され、疲労は蓄積し、めくる勢いはその差で決まる。**
つまりストックがある。だから stock-and-flow で書いて、実際に走らせる。

```
        ┌─ hook / intensity / sfx / メリハリ ─┐
        ↓                                     │(飽和)
     [ tension ] ──→ [ momentum ] ←── 引き算 ─┤
        ↑ 解放             ↑                  │
     セリフ字数         [ fatigue ] ←── 字数＋コマ密度
                           ↓ 回復
                        無音コマ率
```

### 実測と設計値を混ぜない
- **実測**（数えた値）: コマ数・面積のばらつき・セリフ字数・吹き出し数と種別・
  無音コマ率・SFX 数・めくりコマの重み。すべて page EDN と提案レイアウトから決定論的。
- **設計値**（仮説）: それらを緊張／疲労／勢いに変換する**係数**。
  `jump/tools/reading-dynamics.edn` にあり、`--coeffs` で差し替える。

**人間が回して直す面は係数のほう。** この区別は `komawari_styles.edn` 自身の
grading 規律と同じで、「計算済みと称する数値は実データに基づく」（CLAUDE.md）に従う。

### スコアは3つ。総合点にしない
| | 意味 | 向き |
|---|---|---|
| `pull` | 最後まで引っぱれたか（最終 momentum） | 高い |
| `swing` | 緊張の振れ幅（平坦さの逆） | 高い |
| `fatigue-peak` | 疲労のピーク | 低い |

1つに丸めると**何を直せばいいか分からなくなる**ので丸めない。

### 使い方
```bash
nbb --classpath "<kami-mangaka-page>/src:<org-oasis-open-xmile>/src" \
    jump/tools/score-komawari.cljs jump/tools/pages/oneshot-p0{1,2,3,4,5}.edn \
    [--coeffs jump/tools/reading-dynamics.edn] [--xmile out.edn]
```

---

## 4. 実装中に踏んだ3つの穴（すべて実測で発見）

### ① `xmile.execute` は graphical function を実装していない
時間変化する入力を `gf` で入れる設計にしたが、**`xmile.model` は `:xmile/gf` を持てるのに
`execute` 側に適用箇所が1つも無い**。aux は式だけを評価する。

実測: `ypts` を `[0 1 0 1 0]` と `[9 9 9 9 9]` で切り替えても出力は両方 `[0 1 2 3 4]`＝`TIME` そのまま。

**これに気づかず出した最初の版は、平板化したページと本番でスコアが完全に同一だった**
（＝何も測れていない）。**org-oasis-open-xmile 側の gap** で、上流に報告する価値がある。

回避: gf を使わず、**1ページ＝定数入力の1区間**として区切って回し、
ストックを次ページの初期値へ送る（piecewise-constant forcing）。

### ② 飽和項が無いとストックが発散する
初版は 5ページで tension が 9.3 まで積み上がった。
**点数がページ数に比例するだけの無意味な数**になる。
流入に `(1 - stock)` を掛けて飽和させた。

### ③ 流出を絶対量で書くとストックが負になる
無音率の高いページ（P.1 は無音率 1.0）で fatigue が **-0.37** まで落ちた。
**持っていない疲労は抜けない。** 流出はストックに比例させる。

---

## 5. 現在の測定値（読切 P.1-5）

| ページ | style | コマ | メリハリ | 字 | 吹/種 | 無音 | SFX | めくり |
|---|---|---:|---:|---:|---|---:|---:|---:|
| P.1 扉 | inoue | 1 | 0 | 0 | 0/0 | 1.0 | 0 | 1.0 |
| P.2 | urasawa | 5 | 0.48 | 9 | 1/1 | 0.8 | 3 | 0.6 |
| P.3 | urasawa | 5 | 0.48 | 19 | 2/2 | 0.6 | 2 | 1.0 |
| P.4 | togashi | 6 | 0.42 | 30 | 2/2 | 0.67 | 3 | 0.3 |
| P.5 | urasawa | 5 | 0.48 | 43 | 3/1 | 0.4 | 1 | 1.0 |

軌跡: 緊張 `[0.54 0.66 0.68 0.59 0.59]` ／ 疲労 `[0.06 0.29 0.44 0.54 0.62]` ／ 勢い `[0.31 0.45 0.52 0.54 0.53]`

スコア: **pull 0.53 / swing 0.14 / fatigue-peak 0.62**

### 感度の検証（これをやらないと意味がない）
`:beat/weight` `:beat/intensity` `:sfx` を全部消した平板版と比較:

| | 本番 | 平板版 |
|---|---:|---:|
| pull | **0.53** | 0.49 |
| swing | 0.14 | 0.13 |
| fatigue-peak | 0.62 | 0.62 |

`pull` は差を検出する。`fatigue-peak` が同じなのは、平板化で字数とコマ数を変えていない
（疲労はその2つで駆動される）ためで、一貫している。

### この数値の読み方（現時点の解釈）
`swing 0.14` は低い。ただし **P.1-5 は読切の「つかみ」で、緊張を作るより
主人公の日常と関係を見せる区間**なので、ここが平坦なのは設計どおりとも読める。
**平坦さが悪いのか、この5ページでは正しいのかは、係数を回して人間が決める。**
それが `reading-dynamics.edn` を分離した理由。

---

## 6. まだやっていないこと

- **係数は一度も校正していない。** 現在の値は初期値で、実際の読者反応と突き合わせていない。
  複数の既存作品（同じ形式で beats を起こしたもの）を通して、
  「面白いと分かっている作品が高く出る」ように合わせるのが次の段階
- **`swing` の定義が粗い**。緊張の最大−最小しか見ていないので、
  「山が2つある」と「単調に上がる」を区別できない
- **register 対比が測れていない**。吹き出しの種別数は数えているが、
  冨樫の観察（同じ beat の中でトゲ吹き出しと楕円を並べる）は隣接関係の話で、
  種別の総数では捉えられない
- **`xmile.execute` の gf 未実装を上流へ報告していない**
