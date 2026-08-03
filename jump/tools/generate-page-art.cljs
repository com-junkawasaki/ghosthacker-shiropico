#!/usr/bin/env nbb
;; ページ EDN → seedance マルチショット動画 → コマ画像
;;
;;   MURAKUMO_GENERATION_TOKEN=... nbb jump/tools/generate-page-art.cljs \
;;     --pages 6,7,8 [--dry-run] [--out <dir>] [--refs <base-url>]
;;
;; **1ページ = 1クリップ。** seedance はマルチショット生成なので、コマごとに
;; 別クリップを頼むと同じ金額を コマ数ぶん 払うことになる（実測: 4倍払っていた）。
;; 1回の生成でページ全部のコマを撮り、ffmpeg で切り出す。
;;
;; 参照画像の契約（守らないと file_download_error になる。実測）:
;;   - input.images は **http(s) URL のみ**。data URI は拒否される
;;   - ホストは **HEAD にも 200 を返す**必要がある（kotobase.net は GET 200 / HEAD 404 で落ちた）
;;   - **キャラ表をそのまま渡さない**。表ごと複製される。単体被写体に切り出したものを渡す
;;   - 最大 9 枚
;;
;; 出力: <out>/pNN.mp4（原クリップ）と <out>/pNN/<beat-id>.jpg（コマ画像）。
;; 後者を build-page.cljs の shots-dir に渡すと原稿が焼ける。

(ns generate-page-art
  (:require ["fs" :as fs] ["path" :as path] ["child_process" :as cp]
            [clojure.edn :as edn] [clojure.string :as str]))

(def API   "https://generation.murakumo.cloud/api/v1/generation")
(def MODEL "seedance-2.0-fast")
(def PAGES "jump/tools/pages")

;; 参照画像。**却下済みの gen2（ティールのパーカー＋獣耳のシロ／オレンジのピコ）は
;; 入れない** — ART-DIRECTION §0.5。
(def REF-DEFAULT "https://shiropico-refs.04-feasts-minded.workers.dev")
(def CHAR-REFS
  [["シロ"     ["shiro-normal" "shiro-happy" "shiro-smug"]]
   ["ピコ"     ["pico-normal" "pico-happy" "pico-surprised"]]
   ["オーリオ" ["olio"]]
   ["プロック" ["proc"]]
   ["ラット"   ["rat"]]])

(def STYLE
  (str "Japanese shonen manga panel art, black and white, clean ink linework, "
       "screentone shading, no text, no speech bubbles, no watermark, no signature, "
       "for children, not scary, no blood."))

(defn refs-for
  "そのページに出るキャラだけを参照に渡す。全部渡すと 9 枚枠を食い潰し、
   出てこないキャラの特徴が絵に混ざる。"
  [base beats]
  (let [txt (str/join " " (map :dir beats))]
    (->> CHAR-REFS
         (filter (fn [[n _]] (str/includes? txt n)))
         (mapcat second)
         (take 9)
         (mapv #(str base "/" % ".jpg")))))

(defn prompt-for [page beats]
  (let [shots (map-indexed
               (fn [i b] (str "Shot " (inc i) ": " (str/replace (:dir b) #"\s+" " ")))
               beats)
        head (str STYLE "\n" (count beats)
                  " consecutive shots, one manga panel each, same characters throughout.\n")
        p (str head (str/join "\n" shots))]
    ;; API は 1-2000 字。溢れたら後ろから削るのではなく **ショット数を減らさず各行を詰める**
    (if (<= (count p) 1990)
      p
      (let [budget (quot (- 1990 (count head)) (max 1 (count shots)))]
        (str head (str/join "\n" (map #(subs % 0 (min (count %) budget)) shots)))))))

(defn sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8"})]
    {:code (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn post-json [url token body]
  (-> (js/fetch url #js {:method "POST"
                         :headers #js {"Authorization" (str "Bearer " token)
                                       "Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js body))})
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn get-json [url token]
  (-> (js/fetch url #js {:headers #js {"Authorization" (str "Bearer " token)}})
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn fix-host
  "API が返す URL は murakumo.cloud を指すが、実体は generation.murakumo.cloud
   にしかない（同じ token は murakumo.cloud では 401）。ホストを差し替える。"
  [u] (str/replace u "https://murakumo.cloud/" "https://generation.murakumo.cloud/"))

(defn extract-frames!
  "クリップ → コマ画像。各ショットの**中央**を抜く（切り替わり際は
   ブレンド中のフレームを掴むことがある）。

   **モノクロ化してから出す。** 漫画原稿は白黒で、seedance はカラーで返す。
   色は mp4 のほうに残しておくので、あとから彩色版が要ればそこから採れる
   ——コマ画像だけを白黒にして、原本は捨てない。"
  [mp4 outdir ids]
  (fs/mkdirSync outdir #js {:recursive true})
  (let [dur (-> (sh "ffprobe" ["-v" "error" "-show_entries" "format=duration"
                               "-of" "default=nw=1:nk=1" mp4])
                :out str str/trim js/parseFloat)
        n   (count ids)]
    (if (or (js/isNaN dur) (zero? n))
      (println "  ffprobe: 長さが取れなかった")
      (doseq [[i id] (map-indexed vector ids)
              :let [t (* dur (/ (+ i 0.5) n))
                    o (path/join outdir (str id ".jpg"))
                    r (sh "ffmpeg" ["-y" "-loglevel" "error" "-ss" (str t) "-i" mp4
                                    "-frames:v" "1" "-q:v" "3"
                                    ;; 単純な desaturate だと眠い灰色になる。
                                    ;; 少しコントラストを立てるとスクリーントーンに近づく。
                                    "-vf" "format=gray,eq=contrast=1.18:brightness=0.02" o])]]
        (println (str "  " id ".jpg  t=" (.toFixed t 2) "s"
                      (when-not (zero? (:code r)) (str "  ← ffmpeg 失敗: " (:err r)))))))))

(defn page-file [n] (path/join PAGES (str "oneshot-p" (if (< n 10) (str "0" n) n) ".edn")))

(defn run-page! [token base out n dry?]
  (let [f (page-file n)]
    (if-not (fs/existsSync f)
      (do (println (str "P." n ": " f " が無い")) (js/Promise.resolve nil))
      (let [d (edn/read-string (fs/readFileSync f "utf8"))
            beats (vec (mapcat identity (:rows d)))
            ids   (mapv :id beats)
            refs  (refs-for base beats)
            p     (prompt-for n beats)]
        (println (str "\n=== P." n "  コマ " (count beats) " / 参照 " (count refs) " 枚 / prompt " (count p) " 字"))
        (when (and (not dry?) (fs/existsSync (path/join out (str "p" n) (str (first ids) ".jpg"))))
          (println "  既に生成済み。skip"))
        (when dry?
          (println (str/join "\n" (map #(str "    ref " %) refs)))
          (println (str "    ---\n    " (str/replace p "\n" "\n    "))))
        (if (or dry?
                (fs/existsSync (path/join out (str "p" n) (str (first ids) ".jpg"))))
          (js/Promise.resolve nil)
          (-> (post-json API token
                         {:type "video" :model MODEL
                          :input (cond-> {:prompt p} (seq refs) (assoc :images refs))})
              (.then (fn [r]
                       (if-let [jid (:jobId r)]
                         (do (println (str "  job " jid))
                             ((fn poll [i]
                                (if (> i 90)
                                  (js/Promise.resolve {:status "timeout"})
                                  (-> (get-json (fix-host (:statusUrl r)) token)
                                      (.then (fn [s]
                                               ;; ⚠ 終端ステータスは **"done"**。
                                               ;; "succeeded" だけ見ると永久に回り続ける（実測: 15分回して timeout、
                                               ;; ジョブ自体は4分で done になっていた）。
                                               (if (#{"done" "succeeded" "failed" "error" "cancelled"} (:status s))
                                                 s
                                                 (-> (sleep 10000) (.then #(poll (inc i))))))))))
                              0))
                         (do (println (str "  失敗: " (pr-str r))) nil))))
              (.then (fn [s]
                       (when s
                         (if (#{"done" "succeeded"} (:status s))
                           (let [url (fix-host (or (:url (first (:artifacts s)))
                                                   (str (fix-host (:statusUrl s)) "/artifact")))
                                 mp4 (path/join out (str "p" n ".mp4"))]
                             (fs/mkdirSync out #js {:recursive true})
                             (-> (js/fetch url #js {:headers #js {"Authorization" (str "Bearer " token)}})
                                 (.then #(.arrayBuffer %))
                                 (.then (fn [ab]
                                          (fs/writeFileSync mp4 (js/Buffer.from ab))
                                          (println (str "  mp4 " (Math/round (/ (.-size (fs/statSync mp4)) 1024)) " KB"))
                                          (extract-frames! mp4 (path/join out (str "p" n)) ids)))))
                           (println (str "  " (:status s) " " (or (:error s) "")))))))))))))

(let [argv (vec (drop-while #(not (str/ends-with? % ".cljs")) (js->clj js/process.argv)))
      a    (rest argv)
      opt  (fn [k d] (or (second (drop-while #(not= k %) a)) d))
      dry? (some #{"--dry-run"} a)
      base (opt "--refs" REF-DEFAULT)
      out  (opt "--out" "jump/tools/art")
      ns*  (map #(js/parseInt % 10) (str/split (opt "--pages" "6") #","))
      job  (opt "--job" nil)
      tok  (or (.-MURAKUMO_GENERATION_TOKEN js/process.env) "")]
  (cond
    (and (not dry?) (str/blank? tok))
    (println "MURAKUMO_GENERATION_TOKEN が無い")

    ;; 既に done になっているジョブから回収する（poll のバグで取り逃した分の救済）
    job
    (let [n (first ns*)
          d (edn/read-string (fs/readFileSync (page-file n) "utf8"))
          ids (mapv :id (mapcat identity (:rows d)))
          mp4 (path/join out (str "p" n ".mp4"))]
      (fs/mkdirSync out #js {:recursive true})
      (-> (js/fetch (str "https://generation.murakumo.cloud/api/v1/generation/jobs/" job "/artifact")
                    #js {:headers #js {"Authorization" (str "Bearer " tok)}})
          (.then #(.arrayBuffer %))
          (.then (fn [ab]
                   (fs/writeFileSync mp4 (js/Buffer.from ab))
                   (println (str "P." n " mp4 " (Math/round (/ (.-size (fs/statSync mp4)) 1024)) " KB"))
                   (extract-frames! mp4 (path/join out (str "p" n)) ids)))))

    :else
    ((fn step [xs]
       (when (seq xs)
         (let [p (run-page! tok base out (first xs) dry?)]
           (if p (.then p (fn [_] (step (rest xs)))) (step (rest xs))))))
     ns*)))
