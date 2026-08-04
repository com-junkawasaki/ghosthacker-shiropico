#!/usr/bin/env nbb
;; 組んだページ PNG → 右綴じで読める1枚の HTML（Artifact 用）
;;
;;   nbb jump/tools/build-gallery.cljs --work ep01 --pages /tmp/pages-ep01 \
;;     --out /tmp/ep01-gallery.html --eyebrow "連載第1話ネーム原稿"
;;
;; **右綴じの見開きの組み方。** 奇数＝右ページ、偶数＝左ページで、1つの見開きは
;; (奇数, 奇数+1)。画面は左→右に並ぶので **[偶数][奇数] の順に置く**。ここを
;; 逆にすると、読者はページをめくる向きを間違えたまま最後まで読む。
;; ◆見開き（2ページぶち抜き）はそれ自体が1つの見開きなので、横長1枚で出す。
;;
;; めくり・煽りは**ネームの md から拾う**（ページ EDN には無い情報）。手で
;; 打ち直すと md と食い違うので、正本から読む。

(ns build-gallery
  (:require ["fs" :as fs] ["path" :as path] ["child_process" :as cp]
            [clojure.edn :as edn] [clojure.string :as str]))

(def PAGES "jump/tools/pages")
(def WORKS "jump/tools/works.edn")

(defn esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn jpeg-b64
  "PNG → JPEG に落として base64。原稿はモノクロなので JPEG で十分小さくなる。
   PNG のまま base64 すると 1 ページ 1 MB 級になり、45 ページで開けなくなる。"
  [png]
  (when (fs/existsSync png)
    ;; 1000px は表示幅（2段組で1ページ約 600px）の実質 2 倍。これ以上大きくしても
    ;; 見た目は変わらず、45 ページぶんの base64 だけが膨らんで開くのが重くなる。
    (let [r (cp/spawnSync "magick" #js [png "-resize" "1000x" "-quality" "78" "jpg:-"])]
      (when (zero? (.-status r)) (.toString (.-stdout r) "base64")))))

(defn parse-md
  "ネームの md → ページ番号 → {:mekuri? :aori}。"
  [md]
  (into {}
        (for [b (->> (str/split (fs/readFileSync md "utf8") #"(?m)^## ")
                     (filter #(re-find #"^P\.\d" %)))
              :let [n (js/parseInt (second (re-find #"^P\.(\d+)" b)) 10)]]
          [n {:mekuri? (boolean (re-find #"◀\s*めくり" b))
              :aori    (some-> (re-find #"\*\*煽り\*\*[^「]*「([^」]+)」" b) second)}])))

(let [argv (vec (drop-while #(not (str/ends-with? % ".cljs")) (js->clj js/process.argv)))
      a    (rest argv)
      opt  (fn [k d] (or (second (drop-while #(not= k %) a)) d))
      wid  (keyword (opt "--work" "oneshot"))
      work (or (get (edn/read-string (fs/readFileSync WORKS "utf8")) wid)
               (throw (js/Error. (str "works.edn に " wid " が無い"))))
      pdir (opt "--pages" "/tmp/pages")
      out  (opt "--out" "/tmp/gallery.html")
      brow (opt "--eyebrow" "ネーム原稿")
      sub  (opt "--sub" "")
      lede (opt "--lede" "")
      meta* (parse-md (:md work))
      ;; ページ EDN からページの一覧を作る（見開きは :page の "P.13-14" で判る）
      pages (->> (fs/readdirSync PAGES)
                 (filter #(re-find (re-pattern (str "^" (:prefix work) "-p\\d+\\.edn$")) %))
                 sort
                 (mapv (fn [f]
                         (let [d (edn/read-string (fs/readFileSync (path/join PAGES f) "utf8"))
                               n (js/parseInt (second (re-find #"-p(\d+)\.edn$" f)) 10)
                               n2 (some-> (re-find #"P\.\d+-(\d+)" (str (:page d))) second
                                          (js/parseInt 10))
                               beats (vec (mapcat identity (:rows d)))]
                           {:n n :n2 n2 :beats (count beats)
                            :fuki (reduce + 0 (map #(let [f (:fuki %)]
                                                      (cond (map? f) 1 (sequential? f) (count f) :else 0))
                                                   beats))
                            :img (path/join pdir (str "p" (if (< n 10) (str "0" n) n) ".png"))}))))
      by-n  (into {} (map (juxt :n identity) pages))
      last-n (apply max (map #(or (:n2 %) (:n %)) pages))
      ;; 見開き単位に畳む: ◆見開きは単独、それ以外は (奇数, 奇数+1)
      opens (loop [ps pages out* []]
              (if-not (seq ps) out*
                (let [p (first ps)]
                  (if (:n2 p)
                    (recur (rest ps) (conj out* {:wide p}))
                    (recur (remove #(= (:n %) (inc (:n p))) (rest ps))
                           (conj out* {:right p :left (get by-n (inc (:n p)))}))))))
      cell (fn [p]
             (if-not p
               "<div class=\"sheet empty\"><div class=\"endmark\">裏表紙</div></div>"
               (let [b (jpeg-b64 (:img p))
                     mk (when (:mekuri? (get meta* (:n p)))
                          " <span class=\"mk\">◀ めくり</span>")]
                 (str "<div class=\"sheet\">"
                      (if b (str "<img src=\"data:image/jpeg;base64," b "\" alt=\"P." (:n p) "\" loading=\"lazy\">")
                          "<div class=\"missing\">未生成</div>")
                      "<div class=\"cap\"><span class=\"pn\">" (:n p) "</span>"
                      "<span class=\"side\">" (if (odd? (:n p)) "右" "左") "</span>" mk "</div></div>"))))
      section (fn [{:keys [wide right left]}]
                (if wide
                  (let [b (jpeg-b64 (:img wide))
                        m (get meta* (:n wide))]
                    (str "<section class=\"spread wide\" id=\"p" (:n wide) "\"><div class=\"sheet full\">"
                         (if b (str "<img src=\"data:image/jpeg;base64," b "\" alt=\"P." (:n wide) "\" loading=\"lazy\">")
                             "<div class=\"missing\">未生成</div>")
                         "</div><div class=\"meta\"><span class=\"range\">P." (:n wide) "–" (:n2 wide) "</span>"
                         "<span class=\"tag\">見開き</span>"
                         (when (:aori m) (str "<span class=\"ttl\">" (esc (:aori m)) "</span>"))
                         (when (:mekuri? m) " <span class=\"mk\">◀ めくり</span>")
                         "</div></section>"))
                  (let [m (or (:aori (get meta* (:n right))) (:aori (get meta* (:n left))))]
                    (str "<section class=\"spread\" id=\"p" (:n right) "\">"
                         (cell left) (cell right)
                         "<div class=\"meta\"><span class=\"range\">P." (:n right)
                         (when left (str "–" (:n left))) "</span>"
                         (when m (str "<span class=\"ttl\">" (esc m) "</span>"))
                         "</div></section>"))))
      head (fs/readFileSync "jump/tools/gallery-head.html" "utf8")
      nav  (str "<nav>" (str/join (for [o opens
                                        :let [p (or (:wide o) (:right o))]]
                                    (str "<a href=\"#p" (:n p) "\">" (:n p)
                                         (when (:n2 p) (str "–" (:n2 p))) "</a>")))
                "</nav>")
      html (str (-> head
                    (str/replace "{{TITLE}}" (esc (str "GHOST HACKER ― シロとピコ ｜ " (:label work))))
                    (str/replace "{{EYEBROW}}" (esc brow))
                    (str/replace "{{SUB}}" (esc sub))
                    (str/replace "{{LEDE}}" lede)
                    (str/replace "{{PAGES}}" (str last-n))
                    (str/replace "{{SPREADS}}" (str (count (filter :n2 pages))))
                    (str/replace "{{BEATS}}" (str (reduce + 0 (map :beats pages))))
                    (str/replace "{{FUKI}}" (str (reduce + 0 (map :fuki pages))))
                    (str/replace "{{MEKURI}}" (str (count (filter :mekuri? (vals meta*))))))
                nav
                (str/join "\n" (map section opens))
                "\n<footer>\n"
                "com-junkawasaki/ghosthacker-shiropico ／ <code>" (:md work) "</code>・"
                "<code>jump/tools/pages/" (:prefix work) "-*.edn</code><br><br>\n"
                "絵はネーム段階の当たりで、決定稿ではありません。\n"
                "</footer>\n</div>\n")]
  (fs/writeFileSync out html)
  (println (str (name wid) ": 見開き " (count opens) " 組 / " last-n " ページ / "
                (Math/round (/ (.-size (fs/statSync out)) 1024)) " KB → " out)))
