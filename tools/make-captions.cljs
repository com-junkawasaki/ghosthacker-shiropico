#!/usr/bin/env nbb
;; make-captions — ep02-05 のローカライズ Shorts 用 SRT を作る。
;;
;; 2026-08-11 に tools/make_captions.py から移植。SRT の組み立ては
;; `kotoba-lang/srt`（`[:cue {:from :to} text]` → 番号付き SubRip）。
;;
;; **タイミングは推測ではなく実測。** `say` は語やセンテンスの時刻を返さないので、
;; センテンス境界 i ごとに「1..i の累積プレフィックス」を master と同じ voice/rate で
;; 合成し、そのクリップの長さを i の終了時刻として使う。個別に合成した秒数を足すのは
;; 誤り —— クリップごとの前後の無音を二重に数え、`say` が 1 発話の内側に入れる
;; センテンス間の間を落とす。
;;
;; ナレーションは master に adelay で遅らせて混ぜてあるので、全 cue をその分ずらし、
;; master の cutoff で切る。どちらの数値も shorts/narration.edn の :mix から読む
;; （assemble 側と同じ 1 箇所）。
;;
;; 自己検査: 最後の累積長が、既に焼いてある ep{NN}-{lang}.aiff に近いこと。
;; 大きくずれていたらプレフィックス法が `say` の実挙動と合わなくなっており、
;; タイミングを信用してはいけない。
;;
;;   nbb tools/make-captions.cljs [--out-dir shorts/captions] [--tolerance 0.6]

(ns make-captions
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]
            [yt.common :as c]
            [narration :as n]
            [douga.ffmpeg :as ff]
            [srt.core :as srt]))

(def out-dir (c/arg "--out-dir" "shorts/captions"))
(def tolerance (js/parseFloat (c/arg "--tolerance" "0.6")))
(def masters (c/arg "--masters" "shorts/masters"))
(def episodes
  (if-let [s (c/arg "--episodes")]
    (set (map js/parseInt (clojure.string/split s #",")))
    nil))

(defn- sh [argv]
  (str (.execFileSync cp (first argv) (clj->js (rest argv)) #js {:encoding "utf8"})))

(defn- probe-duration [p]
  (js/parseFloat (str/trim (sh (ff/ffprobe-duration-cmd p)))))

(defn spoken-duration
  "text を master と同じ voice/rate で合成し、その長さを秒で返す。"
  [text {:keys [name rate]}]
  (let [tmp (path/join (os/tmpdir) (str "cap-" (js/Date.now) "-" (rand-int 1e6) ".aiff"))]
    (try
      (sh (ff/say-cmd {:voice name :rate rate :out tmp :text text}))
      (probe-duration tmp)
      (finally (try (fs/unlinkSync tmp) (catch :default _ nil))))))

(defn build-srt [sentences ends offset cutoff]
  (loop [[s & more-s] sentences [e & more-e] ends start offset cues []]
    (if (nil? s)
      (apply srt/srt cues)
      (let [cue-end (min (+ e offset) cutoff)]
        (if (<= cue-end start)
          (recur more-s more-e start cues)
          (recur more-s more-e cue-end (conj cues [:cue {:from start :to cue-end} s])))))))

(defn -main []
  (let [spec (n/load-narration "shorts/narration.edn")
        mix (:mix spec)
        offset (/ (:mix/voice-delay-ms mix) 1000.0)
        cutoff (:mix/seconds mix)
        failures (atom [])]
    (fs/mkdirSync out-dir #js {:recursive true})
    (doseq [{:keys [episode lang text voice]} (n/plan spec episodes)]
      (let [sentences (n/split-sentences text)
            ;; 累積プレフィックスの長さ = そのセンテンスの終了時刻
            ends (vec (for [i (range 1 (inc (count sentences)))]
                        (spoken-duration (str/join " " (take i sentences)) voice)))
            pad (n/ep2 episode)
            rendered (path/join masters (str "ep" pad "-" lang ".aiff"))
            drift (when (fs/existsSync rendered) (- (last ends) (probe-duration rendered)))
            out (path/join out-dir (str "shiropico-ep" pad "-" lang ".srt"))]
        (fs/writeFileSync out (build-srt sentences ends offset cutoff))
        (let [over? (and drift (> (js/Math.abs drift) tolerance))]
          (when over? (swap! failures conj [(path/basename out) drift]))
          (println (str (path/basename out) ": " (count sentences) " cues, ends "
                        (.toFixed (last ends) 2) "s"
                        (when drift (str ", drift " (.toFixed drift 2) "s"))
                        (when over? "  <-- DRIFT over tolerance"))))))
    (when (seq @failures)
      (binding [*print-fn* *print-err-fn*]
        (println "\nTiming did not match the rendered narration for:")
        (doseq [[nm d] @failures] (println (str "  " nm ": " (.toFixed d 2) "s"))))
      (js/process.exit 1))))


(-main)
