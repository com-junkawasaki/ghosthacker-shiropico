#!/usr/bin/env nbb
;; seedance-fast → animeka → genko → mangaka
;;
;; 読切ネーム P.12（4コマ）を1枚の原稿に組む。
;;
;;   nbb --classpath "<kami-genko>/src:<canvaskit>/src" jump/tools/build-page.cljs <shots-dir> <out.svg> [page.edn]
;;
;; 各層の役割（勝手に混ぜない）:
;;   seedance : カットの映像を作る（murakumo generation、既に生成済みの mp4 を読む）
;;   animeka  : 映像 → 静止カット（タイムラインから代表フレームを抜く）
;;   genko    : 原稿の幾何（B4 実寸・コマ割り・吹き出し）を決める ← kami-genko の cljc がSSoT
;;   mangaka  : genko の draw op を実際に焼く（ここでは SVG。host 側の仕事）
;;
;; genko は純データ層で、グリフも画像デコードも描かない（node->draws は
;; text node に 8x8 のマーカ矩形しか出さない）。文字を実際に置くのは host＝この
;; スクリプトの責任。そこを混ぜると genko が汚れるので分けてある。

(ns pipeline
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kami.mangaka.genko :as g]
            [kami.mangaka.genko-render :as gr]))

;; ---- ネーム P.12（4コマ）------------------------------------------------
;; 正本: jump/oneshot-45p.md の P.12。セリフはそこからそのまま持ってくる。
(def DEFAULT-KOMA
  [{:shot "k1" :dir "路上。眠った機械の行進のまんなかに、ちいさなピコ。"
    :fuki nil}
   {:shot "k2" :dir "ピコ、目がキラキラ"
    :fuki {:type "jagged" :tail "bottom"
           :lines ["うっわ、街ぜんぶ" "お寝坊なのだ！" "ウケる！"]}}
   {:shot "k3" :dir "オーリオ"
    :fuki {:type "square" :tail "left"
           :lines ["笑い事では" "ありません"]}}
   {:shot "k4" :dir "頭上から、声"
    :fuki {:type "wavy" :tail "top"
           :lines ["めっけたチュウ"]}}])

;; 右綴じ: コマは右上→左上→右下→左下。preset "2x2" は左上起点なので入れ替える。
(def KOMA->PRESET-IDX "右綴じ: 右上→左上→右下→左下" [1 0 3 2])
(def ^:dynamic *koma* nil)

(defn norm->rect
  "正規化 [x1 y1 x2 y2] を基本枠(frame=印刷領域)の world 矩形へ。gutter は mm 実寸で削る。
   内枠(safe)は「重要な絵と文字を置いてよい安全域」であってコマ割りの母数ではない
   ——ここを取り違えると、紙の真ん中に小さくコマが浮く（実際に1回やった）。"
  [[nx1 ny1 nx2 ny2] gutter]
  (let [{:keys [x1 y1 x2 y2]} g/youshi-frame-bounds
        w (- x2 x1) h (- y2 y1)]
    {:x1 (+ x1 (* nx1 w) gutter) :y1 (+ y1 (* ny1 h) gutter)
     :x2 (+ x1 (* nx2 w) (- gutter)) :y2 (+ y1 (* ny2 h) (- gutter))}))

(defn build-doc
  "genko の doc を組む。{:pages [{:youshi {...} :nodes [...]}]}"
  [shots koma]
  (let [preset (get g/panel-presets "2x2")
        gutter (* 3.0 g/youshi-px-per-mm)          ; コマ間 3mm
        nodes
        (vec
         (mapcat
          (fn [i k]
            (let [r (norm->rect (nth preset (nth KOMA->PRESET-IDX i)) gutter)
                  b64 (get shots (:shot k))
                  img (when b64
                        {:id (str "img" i) :type "ai-image" :visible true
                         :data (merge r {:_genImage b64})})
                  pan {:id (str "panel" i) :type "panel" :visible true
                       :data (merge r {:borderW 3})}
                  ;; 吹き出しはコマ内の上寄せ。tail の向きでコマ内の位置を決める。
                  fk  (when-let [f (:fuki k)]
                        ;; 縦書きなので 列数=行の本数、列の長さ=最長行の文字数。
                        ;; 文字寸法から箱を起こす（箱を先に決めて文字を詰めると溢れる）。
                        (let [w (- (:x2 r) (:x1 r)) h (- (:y2 r) (:y1 r))
                              fs   14.0
                              colw (* fs 1.45)
                              cols (count (:lines f))
                              rows (apply max (map count (:lines f)))
                              ;; jagged/wavy は輪郭が内側へ食い込むので余白を厚くする
                              pad (if (#{"jagged" "wavy"} (:type f)) 2.6 1.6)
                              bw (+ (* cols colw) (* fs pad))
                              bh (+ (* rows fs)   (* fs (+ pad 0.4)))
                              bx (if (= "left" (:tail f)) (+ (:x1 r) (* w 0.05))
                                     (- (:x2 r) bw (* w 0.05)))
                              by (+ (:y1 r) (* h 0.05))]
                          {:id (str "fuki" i) :type "fukidashi" :visible true
                           :data {:fukiType (:type f) :fukiTail (:tail f)
                                  :x1 bx :y1 by :x2 (+ bx bw) :y2 (+ by bh)
                                  :_lines (:lines f) :_fs fs :_colw colw}}))]
              (remove nil? [img pan fk])))
          (range) koma))]
    {:pages [{:youshi {:type "b4manga"} :nodes nodes}]}))

;; ---- mangaka: draw op → SVG ---------------------------------------------
(defn rgba->css [c]
  (if (vector? c)
    (let [[r gg b a] c]
      (str "rgba(" (int (* 255 r)) "," (int (* 255 gg)) "," (int (* 255 b)) "," (or a 1) ")"))
    "rgb(20,20,20)"))

(defn pts->str [pts]
  (str/join " " (map (fn [[x y]] (str (.toFixed x 1) "," (.toFixed y 1))) pts)))

(defn draw->svg
  "draw op → SVG。bg? は吹き出し用（輪郭の内側を白で塗り潰す。塗らないと
   下の絵に文字が重なって読めない）。"
  ([d] (draw->svg d false))
  ([{:keys [op mode x1 y1 x2 y2 points color width image-b64]} bg?]
  (case op
    :rect (str "<rect x='" (.toFixed x1 1) "' y='" (.toFixed y1 1)
               "' width='" (.toFixed (- x2 x1) 1) "' height='" (.toFixed (- y2 y1) 1)
               "' fill='" (if bg? "white" "none") "' stroke='" (rgba->css color) "' stroke-width='" (or width 2) "'/>")
    :poly (if (= mode :fan)
            (str "<polygon points='" (pts->str points) "' fill='" (rgba->css color) "'/>")
            (str "<polygon points='" (pts->str points) "' fill='white' stroke='"
                 (rgba->css color) "' stroke-width='" (or width 2) "'/>"))
    :image (str "<image x='" (.toFixed x1 1) "' y='" (.toFixed y1 1)
                "' width='" (.toFixed (- x2 x1) 1) "' height='" (.toFixed (- y2 y1) 1)
                "' preserveAspectRatio='xMidYMid slice'"
                " href='data:image/jpeg;base64," image-b64 "'/>")
    "")))

(defn fuki-text-svg
  "吹き出しの中身。genko は glyph を描かない（text node は 8x8 のマーカ矩形だけ）ので
   ここが host の責任。writing-mode='tb' は rsvg 等で位置が揃わなかったため、
   1文字ずつ座標を出す。列は右から左へ。"
  [{:keys [x1 y1 x2 y2 _lines _fs _colw]}]
  (when (seq _lines)
    (let [fs (or _fs 14.0) colw (or _colw (* fs 1.45))
          cx (/ (+ x1 x2) 2) cy (/ (+ y1 y2) 2)
          cols (count _lines)
          x0 (+ cx (* colw (/ (dec cols) 2.0)))]
      (str/join
       (for [[i line] (map-indexed vector _lines)
             :let [x (- x0 (* i colw))
                   n (count line)
                   ytop (- cy (* (/ (dec n) 2.0) fs))]
             [j ch] (map-indexed vector (seq line))]
         (str "<text x='" (.toFixed x 1) "' y='" (.toFixed (+ ytop (* j fs)) 1)
              "' font-size='" (.toFixed fs 1) "' text-anchor='middle'"
              " dominant-baseline='central'"
              " font-family=\"'Hiragino Mincho ProN','Yu Mincho','Noto Serif JP',serif\""
              " fill='#141414'>" (str/replace (str ch) #"[<>&]" "") "</text>"))))))

(defn youshi-svg []
  (let [p g/youshi-paper-bounds t g/youshi-trim-bounds
        f g/youshi-frame-bounds s g/youshi-safe-bounds
        guide "rgba(140,199,235,0.85)"]
    (str "<rect x='" (:x1 p) "' y='" (:y1 p) "' width='" (- (:x2 p) (:x1 p))
         "' height='" (- (:y2 p) (:y1 p)) "' fill='#fbfbf9'/>"
         (str/join
          (map (fn [[r w]]
                 (str "<rect x='" (.toFixed (:x1 r) 1) "' y='" (.toFixed (:y1 r) 1)
                      "' width='" (.toFixed (- (:x2 r) (:x1 r)) 1)
                      "' height='" (.toFixed (- (:y2 r) (:y1 r)) 1)
                      "' fill='none' stroke='" guide "' stroke-width='" w "'/>"))
               [[t 1.2] [f 1.0] [s 0.8]])))))

(defn render [doc]
  (let [page (first (:pages doc))
        nodes (:nodes page)
        p g/youshi-paper-bounds
        body (str/join
              (map (fn [n]
                     (let [ds (gr/node->draws n)
                           fk? (= "fukidashi" (:type n))
                           base (str/join (map #(draw->svg % fk?) ds))]
                       (if fk?
                         (str base (fuki-text-svg (:data n)))
                         base)))
                   nodes))]
    (str "<svg xmlns='http://www.w3.org/2000/svg' "
         "viewBox='" (.toFixed (:x1 p) 1) " " (.toFixed (:y1 p) 1) " "
         (.toFixed (- (:x2 p) (:x1 p)) 1) " " (.toFixed (- (:y2 p) (:y1 p)) 1) "' "
         "width='" (.toFixed (* 2 (- (:x2 p) (:x1 p))) 0) "'>"
         (youshi-svg) body "</svg>")))

;; ---- main ----------------------------------------------------------------
(let [;; nbb の process.argv は [node nbb <script> ...] だが要素数が環境で変わる。
      ;; スクリプト名を決め打ちすると、ファイルをリネームした瞬間に黙って
      ;; 「引数ゼロ」になる（実際に build-page.cljs へ改名して踏んだ）。
      ;; .cljs で終わる最後の要素＝自分、その後ろが引数、と解決する。
      argv (let [a (vec (js->clj js/process.argv))
                 i (last (keep-indexed #(when (str/ends-with? %2 ".cljs") %1) a))]
             (vec (drop (inc (or i 1)) a)))
      shots-dir (or (first argv) "shots")
      out (or (second argv) "page.svg")
      koma  (if-let [f (nth argv 2 nil)]
              (edn/read-string (fs/readFileSync f "utf8"))
              DEFAULT-KOMA)
      _     (set! *koma* koma)
      shots (into {} (for [k (map :shot koma)
                           :let [f (path/join shots-dir (str k ".jpg"))]
                           :when (fs/existsSync f)]
                       [k (.toString (fs/readFileSync f) "base64")]))]
  (println (str "animeka: 抜いたカット " (count shots) "/" (count koma)))
  (let [doc (build-doc shots koma)
        n (count (:nodes (first (:pages doc))))
        svg (render doc)]
    (println (str "genko: node " n " 個（B4 b4manga・内枠 "
                  (int (- (:x2 g/youshi-safe-bounds) (:x1 g/youshi-safe-bounds))) "×"
                  (int (- (:y2 g/youshi-safe-bounds) (:y1 g/youshi-safe-bounds))) "px）"))
    (fs/writeFileSync out svg)
    (println (str "mangaka: " out " (" (Math/round (/ (.-length svg) 1024)) " KB)"))))
