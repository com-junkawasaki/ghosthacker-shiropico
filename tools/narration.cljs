(ns narration
  "shorts/narration.edn を読む。原稿・音声設定・素材クリップ・ミックス定数は
  ここだけが知っている。

  assemble-localized-shorts と make-captions が**同じ voice/rate と同じ
  voice-delay-ms を読む**ことが要点 —— master を焼いた声と、そこから測った
  caption のタイミングが別々に drift したら、字幕は静かにずれる。"
  (:require ["fs" :as fs]
            [clojure.string]
            [clojure.edn :as edn]))

(defn ep2 [n] (if (< n 10) (str "0" n) (str n)))

(defn load-narration [p]
  (when-not (fs/existsSync p)
    (throw (ex-info (str "narration table not found: " p) {})))
  (let [tx (edn/read-string (str (fs/readFileSync p "utf8")))
        of (fn [k] (filter #(= k (:narration/kind %)) tx))]
    {:mix (first (of "mix"))
     :voices (into {} (map (juxt :voice/lang #(hash-map :name (:voice/name %) :rate (:voice/rate %))) (of "voice")))
     :clips (into {} (map (juxt :clips/episode :clips/files) (of "clips")))
     :scripts (into {} (map (juxt (juxt :script/episode :script/lang) :script/text) (of "script")))}))

(defn plan
  "-> [{:episode :lang :text :voice :clips}] in a stable order.
  `episodes` nil means every episode in the table."
  [{:keys [voices clips scripts]} episodes]
  (vec (for [[[ep lang] text] (sort-by key scripts)
             :when (or (nil? episodes) (contains? episodes ep))
             :let [v (get voices lang)
                   cs (get clips ep)]
             :when (and v cs)]
         {:episode ep :lang lang :text text :voice v :clips cs})))

;; センテンス終端: ASCII のピリオド（en/ar）と Devanagari の danda（hi）。
(def sentence-re #"[^.।]+[.।]")

(defn split-sentences
  "原稿を cue 単位のセンテンスへ。終端記号の無い末尾も 1 つとして拾う。

  ⚠ `re-seq` は**マッチ文字列**の seq を返す（捕捉群が無いので vector ではない）。
  ここで `(map first …)` と書くと各マッチの 1 文字目を取る —— Python の
  `m.group(0)` を機械的に写すと踏む。移植時に実際に踏み、SRT 12 本を Python の
  出力と diff して捕まえた。"
  [text]
  (let [ms (vec (re-seq sentence-re text))
        consumed (reduce + 0 (map count ms))
        tail (clojure.string/trim (subs text (min consumed (count text))))]
    (cond-> (mapv clojure.string/trim ms)
      (not (clojure.string/blank? tail)) (conj tail))))
