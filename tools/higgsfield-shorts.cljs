#!/usr/bin/env nbb
;; higgsfield-shorts — checked-in の Higgsfield manifest から SHIRO & PICO の
;; ショートクリップを生成する。
;;
;; 2026-08-11 に tools/higgsfield_shorts.py から移植。**元は Python が EDN を
;; 読むために `bb` を起動して JSON に変換し、それを Python が parse していた** ——
;; 往復 2 プロセス。しかも bb は ADR-2607173000 で退役済みなので、この道具は
;; 方針上すでに壊れていた。nbb では EDN がそのまま読めるので、その全部が消える。
;;
;;   nbb tools/higgsfield-shorts.cljs keyframes|clips \
;;       [--manifest shorts/episodes-02-05-higgsfield.edn] \
;;       [--ledger shorts/higgsfield-jobs.edn] [--dry-run]

(ns higgsfield-shorts
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [yt.common :as c]))

(def stage (first (remove #(str/starts-with? % "--") c/argv)))
(def manifest-path (c/arg "--manifest" "shorts/episodes-02-05-higgsfield.edn"))
(def ledger-path (c/arg "--ledger" "shorts/higgsfield-jobs.edn"))
(def renders (c/arg "--renders" "shorts/renders"))
(def dry-run? (c/flag? "--dry-run"))

(defn- read-edn [p] (when (fs/existsSync p) (edn/read-string (str (fs/readFileSync p "utf8")))))

(defn- write-ledger! [m]
  (fs/writeFileSync ledger-path
                    (str ";; 生成物 — tools/higgsfield-shorts.cljs が書く。手で編集しない。\n\n"
                         (with-out-str (cljs.pprint/pprint m)))))

(defn- run-json
  "higgsfield CLI を --json で叩き、1 件の job を返す。"
  [argv]
  (let [out (str (.execFileSync cp (first argv) (clj->js (concat (rest argv) ["--json"]))
                                #js {:encoding "utf8"}))
        payload (js->clj (js/JSON.parse out) :keywordize-keys true)]
    (if (sequential? payload)
      (if (= 1 (count payload))
        (first payload)
        (c/die! (str "expected one Higgsfield job, got " (count payload))))
      payload)))

(defn- download! [url dest]
  ;; curl。node に同期 fetch は無く、ここは順番に進む道具なので同期でよい。
  (.execFileSync cp "curl" (clj->js ["--silent" "--show-error" "--fail" "--location"
                                     "--output" dest url])
                 #js {:stdio "inherit"}))

(defn -main []
  (when-not (#{"keyframes" "clips"} stage)
    (c/die! "usage: nbb tools/higgsfield-shorts.cljs keyframes|clips [--manifest ...] [--ledger ...]"))
  (let [spec (or (read-edn manifest-path) (c/die! (str "manifest not found: " manifest-path)))
        ledger (atom (or (read-edn ledger-path) {:jobs {}}))]
    (fs/mkdirSync renders #js {:recursive true})
    (doseq [episode (:episodes spec)
            shot (:shots episode)]
      (let [ep (:episode episode)
            key (keyword (str "ep" (if (< ep 10) (str "0" ep) ep) "-" (name (:id shot))))
            record (get-in @ledger [:jobs key] {})]
        (cond
          (and (= stage "keyframes") (:keyframe-url record))
          nil

          (= stage "keyframes")
          (let [argv ["higgsfield" "generate" "create" "nano_banana_2_lite"
                      "--prompt" (:keyframe shot) "--aspect-ratio" "9:16"
                      "--thinking" "HIGH" "--image-references" (:characterReference spec)
                      "--wait" "--wait-timeout" "20m" "--wait-interval" "5s"]]
            (if dry-run?
              (println (str "  $ " (str/join " " argv)))
              (let [job (run-json argv)
                    dest (path/join renders (str (name key) ".png"))]
                (download! (:result_url job) dest)
                (swap! ledger update-in [:jobs key] merge
                       {:keyframe-job (:id job) :keyframe-url (:result_url job) :keyframe-path dest})
                (write-ledger! @ledger)
                (println (str "completed keyframes: " (name key))))))

          (:clip-url record) nil

          (not (:keyframe-path record))
          (c/die! (str "missing keyframe for " (name key) " — run the keyframes stage first"))

          :else
          (let [argv ["higgsfield" "generate" "create" "seedance_2_0"
                      "--prompt" (:motion shot) "--aspect-ratio" "9:16"
                      "--duration" (str (get-in spec [:format :clipSeconds]))
                      "--generate-audio" "true" "--mode" "fast" "--resolution" "480p"
                      "--start-image" (:keyframe-path record)
                      "--wait" "--wait-timeout" "30m" "--wait-interval" "10s"]]
            (if dry-run?
              (println (str "  $ " (str/join " " argv)))
              (let [job (run-json argv)
                    dest (path/join renders (str (name key) ".mp4"))]
                (download! (:result_url job) dest)
                (swap! ledger update-in [:jobs key] merge
                       {:clip-job (:id job) :clip-url (:result_url job) :clip-path dest})
                (write-ledger! @ledger)
                (println (str "completed clips: " (name key)))))))))))

(-main)
