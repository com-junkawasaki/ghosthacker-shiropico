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

(deftest legs-name-what-ran
  (let [ns-str (shotlist/attr-ns tmpl 11 :en)
        scenes (shotlist/shots rows ns-str)
        lines (shotlist/dialogue rows ns-str)]
    (testing "no backend reachable -> every leg degraded"
      (let [{:keys [video voice]} (legs/report scenes lines {:image {} :voice {}})]
        (is (= [:placeholder :placeholder] video))
        (is (= [:silent :silent] voice))))
    (testing "murakumo reachable -> served, except what cannot be served"
      (let [{:keys [video voice]}
            (legs/report scenes lines {:image {:murakumo true} :voice {:murakumo true}})]
        ;; scene "b" has an empty prompt: reachable backend or not, there is
        ;; nothing to render, so it stays :placeholder.
        (is (= [:murakumo :placeholder] video))
        ;; the second line has nil text: nothing to speak.
        (is (= [:murakumo :silent] voice))))
    (testing "comfy only -> images served, voice still silent"
      (let [{:keys [video voice]}
            (legs/report scenes lines {:image {:comfy true} :voice {}})]
        (is (= [:comfy :placeholder] video))
        (is (= [:silent :silent] voice))))
    (testing "cues and overlays come from scenes, and no bed is claimed"
      (let [{:keys [bed sfx overlays]} (legs/report scenes lines {:image {} :voice {}})]
        (is (false? bed) "shiropico shotlists carry no music bed")
        (is (= ["ambient"] sfx))
        (is (= 1 overlays))))))

(defn -main [& _] (run-tests 'shiropico-produce.produce-test))
(-main)
