(ns narration-test
  "Run: nbb tools/narration_test.cljs"
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [narration :as n]
            [douga.ffmpeg :as ff]))

(deftest sentence-split-keeps-whole-sentences
  (testing "re-seq yields match STRINGS, not groups — (map first …) takes the first character"
    ;; This is the bug the nbb port shipped with for one iteration: mechanically
    ;; transliterating Python's m.group(0) produced cues reading "P" / "प" / "ي".
    ;; Caught by diffing 12 SRT files against the Python's output.
    (is (= ["Pico cuts the power." "Shiro removes the root."]
           (n/split-sentences "Pico cuts the power. Shiro removes the root.")))
    (testing "Devanagari danda terminates a sentence too"
      (is (= ["पीको बिजली बंद करता है।" "शिरो जड़ हटाती है।"]
             (n/split-sentences "पीको बिजली बंद करता है। शिरो जड़ हटाती है।"))))
    (testing "an unterminated tail is still a sentence"
      (is (= ["One." "two"] (n/split-sentences "One. two"))))
    (is (= [] (n/split-sentences "")))))

(deftest narration-table-is-complete
  (let [spec (n/load-narration "shorts/narration.edn")]
    (testing "the mix constants both tools read"
      (is (= 350 (:mix/voice-delay-ms (:mix spec))))
      (is (= 20.2 (:mix/seconds (:mix spec))))
      (is (= 0.32 (:mix/native-gain (:mix spec)))))
    (testing "three voices, four episodes, twelve scripts"
      (is (= #{"en" "hi" "ar"} (set (keys (:voices spec)))))
      (is (= #{2 3 4 5} (set (keys (:clips spec)))))
      (is (= 12 (count (:scripts spec)))))
    (testing "every episode names exactly two source clips"
      (doseq [[ep files] (:clips spec)]
        (is (= 2 (count files)) (str "ep" ep))))
    (testing "plan covers every script and can be narrowed"
      (is (= 12 (count (n/plan spec nil))))
      (is (= 3 (count (n/plan spec #{2}))))
      (is (every? :voice (n/plan spec nil))))))

(deftest assembled-command-uses-the-table-constants
  (let [spec (n/load-narration "shorts/narration.edn")
        mix (:mix spec)
        cmd (ff/narrated-concat-cmd ["a.mp4" "b.mp4"] "v.aiff" "o.mp4"
                                    {:native-gain (:mix/native-gain mix)
                                     :voice-gain (:mix/voice-gain mix)
                                     :voice-delay-ms (:mix/voice-delay-ms mix)
                                     :seconds (:mix/seconds mix)})
        graph (nth cmd (inc (.indexOf cmd "-filter_complex")))]
    (testing "the caption offset and the mix delay are the same number, from one place"
      (is (re-find #"adelay=350\|350" graph))
      (is (= 350 (:mix/voice-delay-ms mix))))
    (is (re-find #"volume=0.32" graph))
    (is (= "20.2" (nth cmd (inc (.indexOf cmd "-t")))))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ (or fail 0) (or error 0))) (js/process.exit 1)))
