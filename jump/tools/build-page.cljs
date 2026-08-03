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
            [kami.mangaka.genko-render :as gr]
            [kami.mangaka.komawari :as kw]
            [kami.mangaka.tategaki :as tg]))

;; ---- ネーム P.12（4コマ）------------------------------------------------
;; 正本: jump/oneshot-45p.md の P.12。セリフはそこからそのまま持ってくる。
(defn panel->rect
  "komawari の :panel/rect [x y w h]（基本枠内の正規化）→ world 矩形。
   gutter は komawari 側が既に引いているので、ここでは足さない。"
  [[nx ny nw nh]]
  (let [{:keys [x1 y1 x2 y2]} g/youshi-frame-bounds
        w (- x2 x1) h (- y2 y1)]
    {:x1 (+ x1 (* nx w)) :y1 (+ y1 (* ny h))
     :x2 (+ x1 (* (+ nx nw) w)) :y2 (+ y1 (* (+ ny nh) h))}))

(defn build-doc
  "genko の doc を組む。

   **コマ割りは書かない。** page EDN は {:style :rows} で「段 × 読み順の beat」だけを持ち、
   幾何は kami.mangaka.komawari/propose-page-layout が :style から導く
   （φ加重・右綴じ順・tilt・inset・character-bleed はスタイルカタログの責任。
   komawari_styles.edn / ADR-2607051530 / ADR-2607172200）。
   ここが genko と komawari の分担線: komawari が「どこに置くか」、genko が「実寸で何を描くか」。"
  [shots {:keys [style rows locale]}]
  (let [panels (kw/propose-page-layout rows {:style (or style kw/default-style)})
        nodes
        (vec
         (mapcat
          (fn [pn]
            (let [r   (panel->rect (:panel/rect pn))
                  b64 (get shots (or (:shot pn) (:id pn)))
                  poly (:panel/polygon pn)
                  img (when b64
                        {:id (str "img" (:id pn)) :type "ai-image" :visible true
                         :data (merge r {:_genImage b64})})
                  pan {:id (str "panel" (:id pn)) :type "panel" :visible true
                       :data (merge r {:borderW 3}
                                    (when poly {:_polygon poly})
                                    (when-not b64
                                      {:_dir (:dir pn) :_sfx (:sfx pn)
                                       :_fuki? (some? (:fuki pn))}))}
                  fk  (when-let [f (:fuki pn)]
                        (let [w (- (:x2 r) (:x1 r)) h (- (:y2 r) (:y1 r))
                              fs   14.0
                              loc  (or locale :ja)
                              ;; ギザ/波は輪郭が内側へ食い込むので余白を厚く
                              pad (if (#{"jagged" "wavy"} (:type f)) 2.6 1.6)
                              [bw bh] (tg/box-size (:lines f)
                                                   {:locale loc :font-size fs :pad pad})
                              bx (if (= "left" (:tail f)) (+ (:x1 r) (* w 0.05))
                                     (- (:x2 r) bw (* w 0.05)))
                              by (+ (:y1 r) (* h 0.05))]
                          {:id (str "fuki" (:id pn)) :type "fukidashi" :visible true
                           :data {:fukiType (:type f) :fukiTail (:tail f)
                                  :x1 bx :y1 by :x2 (+ bx bw) :y2 (+ by bh)
                                  :_lines (:lines f) :_fs fs :_locale loc}}))]
              (remove nil? [img pan fk])))
          panels))]
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
  "吹き出しの中身。**行組みは kami.mangaka.tategaki に委譲する。**
   ここは lib が返した :glyphs を SVG にするだけ（グリフを描くのは host の仕事、
   どの字を回すか・どちらへ流すかは lib の仕事、という分担）。
   自前で縦組みを書いていたときは長音符が横に寝ていた。"
  [{:keys [_lines _fs _locale] :as d}]
  (when (seq _lines)
    (let [{:keys [glyphs]} (tg/layout _lines d {:locale (or _locale :ja)
                                                :font-size (or _fs 14.0)})]
      (str/join
       (for [{:keys [ch x y rotate?]} glyphs]
         (str "<text x='" (.toFixed x 1) "' y='" (.toFixed y 1)
              "' font-size='" (.toFixed (or _fs 14.0) 1) "' text-anchor='middle'"
              " dominant-baseline='central'"
              (when rotate?
                (str " transform='rotate(90 " (.toFixed x 1) " " (.toFixed y 1) ")'"))
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

(defn dir-text-svg
  "絵がまだ無いコマに、ト書きと SFX 指示を薄く置く。
   絵が入るまでのあいだ、この出力自体が『実寸のネーム』として読める。"
  [{:keys [x1 y1 x2 y2 _dir _sfx _fuki?]}]
  (let [pad 10 fs 11 w (- x2 x1 (* 2 pad))
        cpl (max 8 (int (/ w (* fs 1.05))))
        lines (loop [t (or _dir "") acc []]
                (if (<= (count t) cpl) (conj acc t)
                    (recur (subs t cpl) (conj acc (subs t 0 cpl)))))
        sfx (when _sfx (str "SFX  " _sfx))]
    (str
     (str/join
      (map-indexed
       (fn [i l]
         (str "<text x='" (.toFixed (+ x1 pad) 1) "' y='" (.toFixed (+ (if _fuki?
                                                                           ;; 吹き出しがあるコマは下寄せ（重ねない）
                                                                           (- y2 pad (* fs 1.5 (count lines)) (if _sfx (* fs 1.8) 0))
                                                                           (+ y1 pad))
                                                                        fs (* i (* fs 1.5))) 1)
              "' font-size='" fs "' fill='#9aa3a0'"
              " font-family=\"'Hiragino Sans','Yu Gothic',sans-serif\">"
              (str/replace l #"[<>&]" "") "</text>"))
       lines))
     (when sfx
       (str "<text x='" (.toFixed (+ x1 pad) 1) "' y='" (.toFixed (- y2 pad) 1)
            "' font-size='" (* fs 1.1) "' fill='#c08a5a' letter-spacing='2'"
            " font-family=\"ui-monospace,monospace\">" (str/replace sfx #"[<>&]" "") "</text>")))))

(defn render [doc]
  (let [page (first (:pages doc))
        nodes (:nodes page)
        p g/youshi-paper-bounds
        body (str/join
              (map (fn [n]
                     (let [ds (gr/node->draws n)
                           fk? (= "fukidashi" (:type n))
                           base (str/join (map #(draw->svg % fk?) ds))]
                       (cond
                         fk? (str base (fuki-text-svg (:data n)))
                         (and (= "panel" (:type n)) (:_dir (:data n)))
                         (str base (dir-text-svg (:data n)))
                         :else base)))
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
      page  (edn/read-string (fs/readFileSync (or (nth argv 2 nil)
                                                  (throw (ex-info "page EDN が要る" {})))
                                              "utf8"))
      beats (mapcat identity (:rows page))
      shots (into {} (for [k (keep #(or (:shot %) (:id %)) beats)
                           :let [f (path/join shots-dir (str k ".jpg"))]
                           :when (fs/existsSync f)]
                       [k (.toString (fs/readFileSync f) "base64")]))]
  (println (str "animeka: 抜いたカット " (count shots) "/" (count beats)))
  (let [doc (build-doc shots page)
        n (count (:nodes (first (:pages doc))))
        svg (render doc)]
    (println (str "komawari: style " (:style page) " / 段 " (count (:rows page)) " / コマ " (count beats)))
    (println (str "genko: node " n " 個（B4 b4manga・内枠 "
                  (int (- (:x2 g/youshi-safe-bounds) (:x1 g/youshi-safe-bounds))) "×"
                  (int (- (:y2 g/youshi-safe-bounds) (:y1 g/youshi-safe-bounds))) "px）"))
    (fs/writeFileSync out svg)
    (println (str "mangaka: " out " (" (Math/round (/ (.-length svg) 1024)) " KB)"))))
