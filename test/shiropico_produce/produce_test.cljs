;; nbb --classpath src:test test/shiropico_produce/produce_test.cljs
;;
;; Every case here is one of the three bugs the first working version shipped
;; with, all of which were invisible until the producer was actually run:
;;   1. `str/replace` substituting both %s holes with the episode number
;;   2. counting every shotlist row as a shot (84 vs the real 23)
;;   3. voice legs indexed by scene instead of by dialogue line
;; A producer whose failure mode is "reports a clean run that did nothing" needs
;; its honesty pinned, not just its happy path.
(ns shiropico-produce.produce-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [shiropico-produce.legs :as legs]
            [shiropico-produce.shotlist :as shotlist]))

(def ^:private tmpl "shotlist.episode%s.%s")

(deftest attr-ns-substitutes-positionally
  (testing "both holes get their own value"
    (is (= "shotlist.episode11.en" (shotlist/attr-ns tmpl 11 :en)))
    (is (= "shotlist.episode12.zh" (shotlist/attr-ns tmpl 12 :zh))))
  (testing "the episode number does not leak into the language hole"
    (is (not= "shotlist.episode11.11" (shotlist/attr-ns tmpl 11 :en)))))

(def ^:private rows
  [{:shotlist.episode11.en/type "scene"
    :shotlist.episode11.en/scene_key "a"
    :shotlist.episode11.en/scene_prompt "wide shot"
    :shotlist.episode11.en/sfx "ambient"
    :shotlist.episode11.en/fx "flash"}
   {:shotlist.episode11.en/type "dialogue"
    :shotlist.episode11.en/speaker "shiro"
    :shotlist.episode11.en/text "...There it is."}
   {:shotlist.episode11.en/type "dialogue"
    :shotlist.episode11.en/speaker "pico"
    :shotlist.episode11.en/text nil}
   {:shotlist.episode11.en/type "scene"
    :shotlist.episode11.en/scene_key "b"
    :shotlist.episode11.en/scene_prompt ""}])

(deftest scenes-and-dialogue-are-separate-row-types
  (let [ns-str (shotlist/attr-ns tmpl 11 :en)]
    (is (= 2 (count (shotlist/shots rows ns-str))) "only scene rows are shots")
    (is (= 2 (count (shotlist/dialogue rows ns-str))) "only dialogue rows are lines")
    (is (= 4 (count rows)) "and the row total is neither of those")))

(deftest legs-come-from-results-not-configuration
  (testing "a video leg names what served the scene"
    (is (= :comfy (legs/scene-leg {:status :rendered :backend :comfy})))
    (is (= :placeholder (legs/scene-leg {:status :failed})))
    (is (= :placeholder (legs/scene-leg {:status :skipped})))))

(deftest voice-is-silent-and-that-is-not-configuration
  ;; shiropico is a VIDEO channel, so unlike a manga it genuinely has a voice
  ;; leg per dialogue line. No TTS is wired (murakumo's :tts is :via :proc, and
  ;; nothing answered on the fleet head node), so every line is :silent and the
  ;; run is graded :degraded. Leaving :voice empty would grade it :thin and hide
  ;; a missing half of the pipeline behind a passing verdict.
  (let [ns-str (shotlist/attr-ns tmpl 11 :en)
        scenes (shotlist/shots rows ns-str)
        lines (shotlist/dialogue rows ns-str)
        {:keys [voice video]} (legs/report (legs/dry-outcomes scenes) lines scenes)]
    (is (= (count lines) (count voice)) "one voice leg per dialogue line")
    (is (every? #(= :silent %) voice))
    (is (seq voice) "NOT empty — empty is right for a manga, wrong here")
    (is (= (count scenes) (count video)) "and video indexes scenes, a different length")))

(deftest cues-and-overlays-come-from-scenes
  (let [ns-str (shotlist/attr-ns tmpl 11 :en)
        scenes (shotlist/shots rows ns-str)
        lines (shotlist/dialogue rows ns-str)
        {:keys [bed sfx overlays]} (legs/report (legs/dry-outcomes scenes) lines scenes)]
    (is (false? bed) "shiropico shotlists carry no music bed")
    (is (= ["ambient"] sfx))
    (is (= 1 overlays))))

(defn -main [& _] (run-tests 'shiropico-produce.produce-test))
(-main)
