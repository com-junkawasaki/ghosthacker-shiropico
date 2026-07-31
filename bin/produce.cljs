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
            [shiropico-produce.shotlist :as shotlist]))

(def ^:private catalog-dir "production-catalog")

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
                                           :req {:prompt (:shot/prompt shot)
                                                 :key (str plan-id "/" (name lang) "/"
                                                           (or (:shot/key shot) idx))}})
                           (.then (fn [{:keys [ok? file reason]}]
                                    (note (if ok? "rendered" "FAILED") "scene" idx
                                          (if ok? file (str reason)))
                                    (assoc acc idx (if ok?
                                                     {:status :rendered :backend :comfy :file file}
                                                     {:status :failed :reason reason}))))))))
          (js/Promise.resolve {})
          indexed))

(defn -main [& argv]
  (let [{:keys [plan-id lang dry-run limit]} (parse-args argv)]
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
        (let [base (comfy/base-url)
              emit (fn [outcomes]
                     (println (pr-str
                               (merge {:plan/id plan-id
                                       :lang lang
                                       :shotlist file
                                       :shots (count scenes)
                                       :lines (count lines)
                                       :backend base
                                       :legs (legs/report outcomes lines scenes)}
                                      (into {} (map (fn [[k v]] [(keyword (str "scenes-" (name k))) v]))
                                            (legs/counts outcomes))))))]
          (if (or dry-run (str/blank? (str base)))
            (do (when-not base (note "no COMFY_URL / MURAKUMO_BACKEND_URL — nothing rendered"))
                (emit (legs/dry-outcomes scenes)))
            (-> (comfy/reachable? base)
                (.then
                 (fn [up]
                   (if-not up
                     ;; Configured but not answering. Never report a served leg
                     ;; for a URL nothing is listening on.
                     (do (note "backend configured but unreachable:" base)
                         (emit (legs/dry-outcomes scenes)))
                     (let [todo (cond->> (map-indexed vector scenes)
                                  true (filter (fn [[_ s]] (shotlist/renderable? s)))
                                  limit (take limit))]
                       (note "rendering" (count todo) "of" (count scenes) "scenes")
                       (-> (render-sequentially base out-dir plan-id lang todo)
                           (.then (fn [rendered]
                                    (emit (vec (map-indexed
                                                (fn [i o] (get rendered i o))
                                                (legs/dry-outcomes scenes))))))))))))))))))

(apply -main *command-line-args*)
