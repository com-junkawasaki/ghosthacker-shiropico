#!/usr/bin/env nbb
;; ネームの md（正本）→ jump/tools/pages/<prefix>-pNN.edn（コマ割りの入力）
;;
;;   nbb jump/tools/md-to-pages.cljs [--work ep01] [--force] [--only 6,7,8]
;;
;; 作品（読切 / 各話）の定義は jump/tools/works.edn。--work 無指定は :oneshot。
;;
;; **なぜ変換器を書くか。** ページ EDN を手書きすると md と二重管理になり、
;; 台詞を md で直したのに EDN が古いまま、という drift が必ず起きる（実際に
;; P.2/P.3 で起きた）。正本は md 一本にして、EDN は生成物として扱う。
;;
;; **この変換器が決めないこと**: コマ割りの幾何。それは kami.mangaka.komawari が
;; :style と beat hint から導く（ADR-2607051530）。ここが出すのは「段の分け方」と
;; 「吹き出しの種類」までで、どちらも後から page EDN を手で上書きできる。
;;
;; **既に手で作り込んだページは上書きしない**（--force で明示的に上書き）。
;; 手で調整した :beat/weight や :beat/intensity を黙って捨てないため。

(ns md-to-pages
  (:require ["fs" :as fs] ["path" :as path]
            [clojure.edn :as edn] [clojure.string :as str]))

(def OUT   "jump/tools/pages")
(def WORKS "jump/tools/works.edn")

;; ---------------------------------------------------------------------------
;; 段の分け方。コマ数だけから決める既定値（手で上書き可）。
;; 1段目を大きく取るのは、ページをめくった直後に視線が最初に落ちる場所だから。
;; ---------------------------------------------------------------------------
(def ROWS
  {1 [1] 2 [1 1] 3 [1 2] 4 [2 2] 5 [1 2 2] 6 [2 2 2] 7 [1 2 2 2]
   8 [2 2 2 2] 9 [1 2 2 2 2] 10 [2 2 2 2 2] 11 [1 2 2 2 2 2] 12 [2 2 2 2 2 2]})

(defn split-rows
  "コマ列 → 段のベクタ。段内は既に読み順（右→左）で並んでいる前提。"
  [ps]
  (let [plan (get ROWS (count ps) (into [] (repeat (quot (inc (count ps)) 2) 2)))]
    (first
     (reduce (fn [[acc rest] n]
               (if (seq rest) [(conj acc (vec (take n rest))) (drop n rest)] [acc rest]))
             [[] ps] plan))))

;; ---------------------------------------------------------------------------
;; 吹き出しの種類。**話者と文末で決める。** 迷ったら oval（既定）。
;; ---------------------------------------------------------------------------
(defn fuki-type [speaker text]
  (cond
    (= speaker "オーリオ")            "square"   ; 几帳面。角で喋る
    (= speaker "ラット")              "wavy"     ; ぬるっと入ってくる
    (= speaker "プロック")            "oval"
    (re-find #"[！!]{1,}\s*$" text)   "jagged"   ; 叫び
    (re-find #"^M[（(]" (str speaker)) "square"
    :else                             "oval"))

(defn tokenize
  "折り返しの最小単位に切る。**「——」や「……」を1文字ずつ扱うと行末で割れる**
   （実測: 「ピコ様、そこは—」／「—」という無残な2行になった）。連続する
   ダッシュ・リーダは1単位として扱い、絶対に分断しない。"
  [s]
  ;; ⚠ (drop n cs) は空でも () を返し nil? にならない。nil? で止めると
  ;; 末尾に空文字トークンが1つ紛れ込み、「孤児1単位」の判定が狂う（実測: 
  ;; 「——」が最終行に取り残されて畳まれなかった）。empty? で止めること。
  (loop [cs (seq s) out []]
    (if (empty? cs)
      out
      (let [c (first cs)]
        (if (#{\— \- \… \‥ \ー \〜} c)
          (let [run (take-while #(= % c) cs)]
            (recur (drop (count run) cs) (conj out (apply str run))))
          (recur (next cs) (conj out (str c))))))))

(defn wrap-lines
  "縦書き1列 ~9字で折る。読点・句点・空白のあとを優先的に折り返し位置にする。
   **禁則処理はしない** — それは組版の仕事で、ネーム段階では過剰。
   ただし**1字だけの行は作らない**（前の行に押し込む）。行頭に落ちた孤児は
   絵として醜く、ネームのレビューを妨げる。"
  [s]
  (let [toks (tokenize (str/trim s))
        maxn 9
        len  (fn [row] (reduce + 0 (map count row)))   ; トークン数でなく字数
        rows (loop [ts toks cur [] out []]
               (cond
                 (empty? ts) (if (seq cur) (conj out cur) out)
                 ;; 読点等のあとで、ある程度たまっていれば切る
                 (and (>= (len cur) 5) (#{"、" "。" " " "\u3000"} (last cur)))
                 (recur ts [] (conj out cur))
                 (and (seq cur) (>= (+ (len cur) (count (first ts))) maxn))
                 (recur ts [] (conj out cur))
                 :else (recur (next ts) (conj cur (first ts)) out)))
        ;; 孤児（1単位だけの最終行）を前の行へ畳む
        rows (if (and (> (count rows) 1) (= 1 (count (last rows))))
               (conj (vec (butlast (butlast rows)))
                     (into (vec (last (butlast rows))) (last rows)))
               rows)]
    (mapv #(str/replace (apply str %) #"\s+$" "") rows)))

;; ---------------------------------------------------------------------------
;; md の解析
;; ---------------------------------------------------------------------------
(def strip-md #(-> % (str/replace #"\*\*" "") (str/replace #"`" "") str/trim))

(defn parse-panel
  "1コマぶんの行群 → beat map。
   **1コマに複数の話者が入ることがある**（掛け合い・ツッコミ）。最初の1つだけ
   拾うと、P.33 の「は?」やプロックのツッコミが黙って消える（実測）ので全部拾う。"
  [idx lines]
  (let [head (strip-md (str/replace (first lines) #"^\s*\d+\.\s*" ""))
        dir  (-> head (str/replace #"^【" "") (str/replace #"】" " ") strip-md)
        tail (rest lines)
        sfx  (some #(second (re-find #"^\s*-\s*SE:\s*(.+)$" %)) tail)
        monos (keep #(second (re-find #"^\s*-\s*M（[^）]*）\s*[（(](.+)[）)]\s*$" %)) tail)
        says  (keep #(re-find #"^\s*>\s*([^「]+)「(.+)」\s*$" %) tail)
        fukis (vec
               (concat
                (map-indexed
                 (fn [i [_ spk txt]]
                   (let [spk (strip-md (str/trim spk)) txt (strip-md txt)]
                     {:type (fuki-type spk txt)
                      :tail (if (even? (+ idx i)) "right" "left")
                      :lines (wrap-lines txt)
                      :who spk}))
                 says)
                (map (fn [m] {:type "square" :tail "top"
                              :lines (wrap-lines (strip-md m)) :who "M"}) monos)))]
    (cond-> {:id (str "p" idx) :dir dir}
      sfx        (assoc :sfx (strip-md sfx))
      (= 1 (count fukis)) (assoc :fuki (first fukis))
      (< 1 (count fukis)) (assoc :fuki fukis))))

(defn parse-page [block]
  (let [lines (str/split-lines block)
        hdr   (first lines)
        [_ a b] (re-find #"^##\s*P\.(\d+)(?:-(\d+))?\s*｜" hdr)
        spread? (boolean (or b (re-find #"見開き" hdr)))
        ;; コマは "N. 【" で始まる。それに続くインデント行はそのコマに属する。
        body  (rest lines)
        groups (->> body
                    (reduce (fn [acc l]
                              (if (re-find #"^\s*\d+\.\s*【" l)
                                (conj acc [l])
                                (if (and (seq acc) (re-find #"^\s+\S" l))
                                  (update acc (dec (count acc)) conj l)
                                  acc)))
                            [])
                    (map-indexed parse-panel)
                    vec)
        ;; **見開きには番号付きコマが無い。** 地の文（【…】と説明行）を拾わないと
        ;; 生成プロンプトが「見開き・大ゴマ。md 本文を参照」という無意味な文字列になり、
        ;; 中身と何の関係も無い絵が返る（実測: 洞窟・巨大な卵。P.25 は content filter で落ちた）。
        ;; ※ で始まる行は担当編集向けの注記（「名前は出さない」等）。絵の指示では
        ;; ないので落とす。残すと生成プロンプトに編集メモがそのまま乗る（実測: 第1話 P.47）。
        prose (->> body
                   (remove #(re-find #"^\s*(>|-|◀|---|\||※|\*\*煽り)" %))
                   (map strip-md)
                   (remove str/blank?)
                   (map #(-> % (str/replace #"^【" "") (str/replace #"】" " ")))
                   (str/join " "))]
    {:n (js/parseInt a 10) :n2 (some-> b (js/parseInt 10))
     :spread? spread? :panels groups :prose prose}))

;; ---------------------------------------------------------------------------
(defn edn-str [{:keys [md label style]} {:keys [n n2 spread? panels prose]}]
  (let [sty (get style n :urasawa)
        lbl (if n2 (str label " P." n "-" n2) (str label " P." n))
        ;; **番号付きコマが無いページは大ゴマ1つとして出す。** 見開きだけでなく
        ;; 扉（1コマ・地の文しかない）もここに来る。見開き限定にすると扉の :rows が
        ;; 空になり、白紙が焼ける（実測: 第1話 P.1）。分割は人が決める。
        big? (or spread? (empty? panels))
        rows (if big?
               [[{:id "s1" :beat/weight :large :beat/breakout true
                  :dir (if (str/blank? prose)
                         (or (:dir (first panels)) "大ゴマ")
                         (subs prose 0 (min (count prose) 420)))}]]
               (split-rows panels))]
    (str ";; " lbl " — " md " から自動生成（md-to-pages.cljs）。\n"
         ";; **手で調整したら、このヘッダを消す**（--force なしでは上書きされない）。\n"
         (when big? ";; ⚠ 大ゴマ1つとして出してある。割り方は人が決める。\n")
         "{:style " sty " :page \"" lbl "\"\n :rows\n ["
         (str/join "\n\n  "
           (for [row rows]
             (str "[" (str/join "\n   " (map pr-str row)) "]")))
         "]}\n")))

(let [argv  (vec (drop-while #(not (str/ends-with? % ".cljs")) (js->clj js/process.argv)))
      opts  (set (rest argv))
      force? (contains? opts "--force")
      only  (when-let [o (second (drop-while #(not= "--only" %) (rest argv)))]
              (set (map #(js/parseInt % 10) (str/split o #","))))
      wid   (keyword (or (second (drop-while #(not= "--work" %) (rest argv))) "oneshot"))
      works (edn/read-string (fs/readFileSync WORKS "utf8"))
      work  (or (get works wid)
                (throw (js/Error. (str "works.edn に " wid " が無い。ある: "
                                       (str/join ", " (map name (keys works)))))))
      md    (fs/readFileSync (:md work) "utf8")
      blocks (->> (str/split md #"(?m)^## ")
                  (filter #(re-find #"^P\.\d" %))
                  (map #(str "## " %)))
      pages (map parse-page blocks)]
  (doseq [{:keys [n panels] :as p} pages
          :let [f (path/join OUT (str (:prefix work) "-p" (if (< n 10) (str "0" n) n) ".edn"))
                exists? (fs/existsSync f)
                gen? (and exists? (str/includes? (fs/readFileSync f "utf8") "自動生成"))
                skip? (or (and only (not (only n)))
                          (and exists? (not gen?) (not force?)))]]
    (if skip?
      (println (str "skip  P." n (when exists? "  ← 手で作り込み済み")))
      (do (fs/writeFileSync f (edn-str work p))
          (println (str "write P." n "  コマ " (count panels))))))
  (println (str "\n" (name wid) ": ページ " (count pages) " 件")))
