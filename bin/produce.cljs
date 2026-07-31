#!/usr/bin/env nbb
;; shiropico producer — the command `loop-ka-production` invokes for this
;; channel. The loop owns cadence, admission and verdict; this owns producing
;; one episode and reporting honestly what its legs actually did.
;;
;;   nbb --classpath src:../../kotoba-lang/comfyui/src bin/produce.cljs \
;;       episode-11 [--lang en] [--dry-run] [--limit N]
;;
;; Image rendering goes to ComfyUI's NATIVE protocol via kotoba-lang/comfyui's
;; `comfyui.native-client` — not the OpenAI-images gateway, whose bridge was
;; down while ComfyUI itself was up.
;;
;; Exit 0 produced (whatever the legs say), 1 could not even plan. An
;; unreachable backend is NOT exit 1: it is a run whose legs are :placeholder,
;; which the loop grades and holds. Crashing would hide the degradation.
(ns produce
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [comfyui.native-client :as comfy]
            [shiropico-produce.legs :as legs]
            [shiropico-produce.prompt :as prompt]
            [shiropico-produce.tts :as tts]
            [shiropico-produce.shotlist :as shotlist]))

(def ^:private catalog-dir "production-catalog")

(def ^:private scene-config
  "shiropico's shots are establishing/scene shots — plants, control rooms,
  corridors — not character panels.

  `animagine-xl-4.0` rather than the library default `Illustrious-XL-v2.0`,
  chosen by rendering the SAME prompt through all four checkpoints the server
  has (2026-07-31, geothermal_plant_dawn):

  | checkpoint | result |
  |---|---|
  | Illustrious-XL-v2.0 | landscape and neon lines, **no plant** |
  | **animagine-xl-4.0** | **pipework, plant structure, teal data lines, aerial** |
  | noobai-XL-1.1 | atmospheric abstract towers, not a plant |
  | waiREALCN_v150 | photoreal single tower — wrong style for the series |

  Illustrious is a character model; that is why it kept dropping industrial
  subjects. ghosthacker deliberately keeps it: its panels are character-heavy
  manga and 255 of arc0-1's 257 are already drawn with that look, so switching
  would make one episode inconsistent with itself."
  {:checkpoint "animagine-xl-4.0.safetensors"})

(defn- die [msg data]
  (binding [*out* *err*]
    (println (str "produce: " msg))
    (when (seq data) (println (pr-str data))))
  (js/process.exit 1))

(defn- note [& xs]
  (binding [*out* *err*] (println (str "produce: " (str/join " " xs)))))

(defn- read-edn [file]
  (when-not (fs/existsSync file) (die "file not found" {:file file}))
  (try (edn/read-string (fs/readFileSync file "utf8"))
       (catch :default e (die "could not parse EDN" {:file file :error (ex-message e)}))))

(defn- parse-args [argv]
  ;; Args come from *command-line-args* (see the call at the bottom). Dropping a
  ;; fixed prefix off process.argv leaves nbb's own flags in the list, which put
  ;; "src" in the plan-id slot when the same shape was written for ghosthacker.
  (loop [[a & more] argv acc {}]
    (cond
      (nil? a) acc
      (= a "--lang") (recur (rest more) (assoc acc :lang (first more)))
      (= a "--limit") (recur (rest more) (assoc acc :limit (js/parseInt (first more))))
      (= a "--dry-run") (recur more (assoc acc :dry-run true))
      (str/starts-with? a "--") (recur more acc)
      :else (recur more (assoc acc :plan-id a)))))

(defn- render-sequentially
  "One scene at a time: the fleet head node runs ONE ComfyUI on one GPU, so
  firing every scene at once would queue them all server-side and lose the
  ability to stop early. `--limit` is what bounds a run."
  [base out-dir plan-id lang indexed]
  (reduce (fn [p [idx shot]]
            (.then p (fn [acc]
                       (-> (comfy/render! {:base base
                                           :out-dir out-dir
                                           :req {;; Tag-form, not the raw prose: SDXL weights early short tokens and
                                                 ;; the subject was being lost mid-clause. See shiropico-produce.prompt.
                                                 :prompt (prompt/positive shot)
                                                 :key (str plan-id "/" (name lang) "/"
                                                           (or (:shot/key shot) idx))}
                                           :config scene-config})
                           (.then (fn [{:keys [ok? file reason]}]
                                    (note (if ok? "rendered" "FAILED") "scene" idx
                                          (if ok? file (str reason)))
                                    (assoc acc idx (if ok?
                                                     {:status :rendered :backend :comfy :file file}
                                                     {:status :failed :reason reason}))))))))
          (js/Promise.resolve {})
          indexed))

(defn- speak-sequentially
  "One line at a time. kokoro-http holds a single onnxruntime session behind a
  lock, so concurrency would only queue inside the service."
  [base out-dir indexed]
  (reduce (fn [p [idx line]]
            (.then p (fn [acc]
                       (-> (tts/speak! {:base base :out-dir out-dir :line line :idx idx})
                           (.then (fn [{:keys [ok? file reason]}]
                                    (assoc acc idx (if ok?
                                                     {:status :spoken :backend :kokoro :file file}
                                                     {:status :failed :reason reason}))))))))
          (js/Promise.resolve {})
          indexed))

(defn- produce-images
  "Promise of scene outcomes. Unreachable or unconfigured -> everything skipped,
  never a served leg for a URL nothing is listening on."
  [{:keys [base out-dir plan-id lang scenes limit]}]
  (if (str/blank? (str base))
    (do (note "no COMFY_URL / MURAKUMO_BACKEND_URL — nothing rendered")
        (js/Promise.resolve (legs/dry-outcomes scenes)))
    (-> (comfy/reachable? base)
        (.then (fn [up]
                 (if-not up
                   (do (note "image backend configured but unreachable:" base)
                       (legs/dry-outcomes scenes))
                   (let [todo (cond->> (map-indexed vector scenes)
                                true (filter (fn [[_ s]] (shotlist/renderable? s)))
                                limit (take limit))]
                     (note "rendering" (count todo) "of" (count scenes) "scenes")
                     (-> (render-sequentially base out-dir plan-id lang todo)
                         (.then (fn [done]
                                  (vec (map-indexed (fn [i o] (get done i o))
                                                    (legs/dry-outcomes scenes)))))))))))))

(defn- produce-voice
  "Promise of line outcomes. Same discipline as images."
  [{:keys [out-dir lines limit]}]
  (let [base (tts/base-url)]
    (if (str/blank? (str base))
      (do (note "no TTS_URL — nothing spoken")
          (js/Promise.resolve (legs/dry-outcomes lines)))
      (-> (tts/reachable? base)
          (.then (fn [up]
                   (if-not up
                     (do (note "TTS configured but unreachable:" base)
                         (legs/dry-outcomes lines))
                     (let [todo (cond->> (map-indexed vector lines) limit (take limit))]
                       (note "speaking" (count todo) "of" (count lines) "lines")
                       (-> (speak-sequentially base (path/join out-dir "voice") todo)
                           (.then (fn [done]
                                    (vec (map-indexed (fn [i o] (get done i o))
                                                      (legs/dry-outcomes lines)))))))))))))) 

(defn -main [& argv]
  (let [{:keys [plan-id lang dry-run] :as a} (parse-args argv)
        ;; A per-run cap the SCHEDULER can set without editing the channel
        ;; registry. One episode is 23 scenes / 61 lines and the fleet has one
        ;; shared GPU, so a nightly tick produces a slice.
        limit (or (:limit a)
                  (let [v (aget js/process.env "LOOP_KA_PANEL_LIMIT")]
                    (when-not (str/blank? (str v)) (js/parseInt v))))]
    (when (str/blank? (str plan-id)) (die "usage: produce.cljs <plan-id> [--lang en]" {}))
    (let [plan (read-edn (path/join catalog-dir (str plan-id ".edn")))
          lang (keyword (or lang (name (:plan/primary-language plan :en))))
          file (get-in plan [:plan/shotlists lang])]
      (when-not file
        (die "plan has no shotlist for language"
             {:plan/id plan-id :lang lang
              :available (vec (sort (keys (:plan/shotlists plan))))}))
      (let [ns-str (shotlist/attr-ns (:plan/attr-namespace-template plan)
                                     (:plan/episode plan) lang)
            rows (read-edn file)
            scenes (shotlist/shots rows ns-str)
            lines (shotlist/dialogue rows ns-str)
            out-dir (path/join "production-out" plan-id (name lang))]
        (when (and (pos? (or (:plan/shots plan) 0)) (empty? scenes))
          (die "shotlist parsed to zero scenes"
               {:file file :attr-ns ns-str :expected (:plan/shots plan)}))
        (doseq [[label expected actual] [["shots" (:plan/shots plan) (count scenes)]
                                         ["dialogue" (:plan/dialogue plan) (count lines)]]]
          (when (and expected (not= expected actual))
            (note "warning —" (str "plan/" label) "says" expected "but the shotlist has" actual)))
        (let [emit (fn [{:keys [scenes-out lines-out]}]
                     (println (pr-str
                               (merge {:plan/id plan-id
                                       :lang lang
                                       :shotlist file
                                       :shots (count scenes)
                                       :lines (count lines)
                                       :image-backend (comfy/base-url)
                                       :tts-backend (tts/base-url)
                                       :legs (legs/report scenes-out lines-out scenes)}
                                      (into {} (map (fn [[k v]] [(keyword (str "scenes-" (name k))) v]))
                                            (legs/counts scenes-out))
                                      (into {} (map (fn [[k v]] [(keyword (str "lines-" (name k))) v]))
                                            (legs/counts lines-out))))))]
          (if dry-run
            (emit {:scenes-out (legs/dry-outcomes scenes)
                   :lines-out (legs/dry-outcomes lines)})
            (-> (produce-images {:base (comfy/base-url) :out-dir out-dir
                                 :plan-id plan-id :lang lang :scenes scenes :limit limit})
                (.then (fn [scenes-out]
                         (-> (produce-voice {:out-dir out-dir :lines lines :limit limit})
                             (.then (fn [lines-out]
                                      (emit {:scenes-out scenes-out
                                             :lines-out lines-out}))))))))))))) 

(apply -main *command-line-args*)
