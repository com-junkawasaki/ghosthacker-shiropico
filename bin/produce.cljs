#!/usr/bin/env nbb
;; shiropico producer — the command `loop-ka-production` invokes for this
;; channel. The loop owns cadence, admission and verdict; this owns producing
;; one episode and reporting honestly what its legs actually did.
;;
;;   nbb --classpath src bin/produce.cljs <plan-id> [--lang en]
;;
;; Prints one EDN map on stdout:
;;   {:plan/id "episode-11" :lang :en :shots 84 :legs {...}}
;;
;; Exit codes: 0 produced (whatever the legs say), 1 could not even plan.
;; A missing render backend is NOT exit 1 — it is a run whose legs are
;; :placeholder/:silent, which the loop grades :degraded and holds. Failing
;; hard there would hide the degradation behind a crash instead of recording it.
(ns produce
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [shiropico-produce.legs :as legs]
            [shiropico-produce.shotlist :as shotlist]))

(def ^:private catalog-dir "production-catalog")

(defn- die [msg data]
  (binding [*out* *err*]
    (println (str "produce: " msg))
    (when (seq data) (println (pr-str data))))
  (js/process.exit 1))

(defn- parse-args [argv]
  (loop [[a & more] argv acc {}]
    (cond
      (nil? a) acc
      (= a "--lang") (recur (rest more) (assoc acc :lang (first more)))
      (str/starts-with? a "--") (recur more acc)
      :else (recur more (assoc acc :plan-id a)))))

(defn- read-edn [file]
  (when-not (fs/existsSync file)
    (die "file not found" {:file file}))
  (try
    (edn/read-string (fs/readFileSync file "utf8"))
    (catch :default e
      (die "could not parse EDN" {:file file :error (ex-message e)}))))

(defn- backends
  "Env -> what we can actually reach.

  Presence of a URL is treated as reachability. That is an assumption, and it
  is the weakest link in this producer's honesty: an unreachable URL would be
  reported as a served leg. It is written this way because probing needs a
  network call this producer otherwise does not make; when the murakumo task
  plane runs it on a fleet node, the node's own `:requires` gate is what
  establishes the capability. Tightening this to a real probe is the obvious
  next step and is why the assumption is named here rather than hidden."
  []
  ;; `(js->clj js/process.env)` does NOT work here — under nbb it yields a
  ;; Function, every lookup is nil, and the producer silently reports every leg
  ;; degraded no matter how the node is configured. Read the property directly.
  (let [got (fn [k] (not (str/blank? (str (aget js/process.env k)))))]
    {:image {:murakumo (got "MURAKUMO_BACKEND_URL") :comfy (got "COMFY_URL")}
     :voice {:murakumo (got "MURAKUMO_BACKEND_URL") :local (got "VOICE_LOCAL_CMD")}}))

(defn -main [& argv]
  (let [{:keys [plan-id lang]} (parse-args argv)]
    (when (str/blank? (str plan-id))
      (die "usage: produce.cljs <plan-id> [--lang en]" {}))
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
            b (backends)]
        ;; A plan that claims scenes and yields none is a broken shotlist, not an
        ;; empty episode — say so instead of emitting a clean-looking empty run.
        (when (and (pos? (or (:plan/shots plan) 0)) (empty? scenes))
          (die "shotlist parsed to zero scenes"
               {:file file :attr-ns ns-str :expected (:plan/shots plan)}))
        ;; The plan's counts are derived from the shotlists, so a drift means one
        ;; of the two was edited alone. Report it rather than trusting either.
        (doseq [[label expected actual]
                [["scenes" (:plan/shots plan) (count scenes)]
                 ["dialogue" (:plan/dialogue plan) (count lines)]]]
          (when (and expected (not= expected actual))
            (binding [*out* *err*]
              (println (str "produce: warning — plan/" label " says " expected
                            " but shotlist has " actual " (" file ")")))))
        (println (pr-str {:plan/id plan-id
                          :lang lang
                          :shotlist file
                          :shots (count scenes)
                          :lines (count lines)
                          :legs (legs/report scenes lines b)}))))))

(apply -main (drop 2 (js->clj js/process.argv)))
