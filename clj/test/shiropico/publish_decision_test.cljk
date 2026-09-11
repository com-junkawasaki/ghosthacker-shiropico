(ns shiropico.publish-decision-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [shiropico.phase :as phase]
            [shiropico.policy :as policy]))

(def source (slurp "src/shiropico/publish_decision.kotoba"))
(def compiled (compiler/compile-source source :wasm32-browser-kotoba-v1))

(def disposition-code {:hold 0 :escalate 1 :commit 2})

(defn legacy-decision [effect confidence high-stakes phase-number]
  (let [proposal {:effect effect
                  :confidence confidence
                  :value {:high-stakes? high-stakes}}
        verdict (policy/check {} {} proposal)
        base (phase/verdict->disposition verdict)]
    (:disposition (phase/gate phase-number {:op :cut/render} base))))

(defn kotoba-decision [effect confidence high-stakes phase-number]
  (ir/execute (:kir compiled) 'decide
              [(if (= effect :noop) 0 1)
               (long (* confidence 1000))
               (if high-stakes 1 0)
               phase-number]))

(deftest kotoba-publish-decision-matches-the-compatibility-oracle
  (doseq [{:keys [effect confidence high-stakes phase-number]}
          [{:effect :noop :confidence 0.0 :high-stakes false :phase-number 2}
           {:effect :propose :confidence 1.0 :high-stakes false :phase-number 0}
           {:effect :propose :confidence 0.3 :high-stakes false :phase-number 2}
           {:effect :propose :confidence 1.0 :high-stakes true :phase-number 2}
           {:effect :propose :confidence 1.0 :high-stakes false :phase-number 1}
           {:effect :propose :confidence 1.0 :high-stakes false :phase-number 2}]]
    (testing (pr-str [effect confidence high-stakes phase-number])
      (is (= (disposition-code
              (legacy-decision effect confidence high-stakes phase-number))
             (kotoba-decision effect confidence high-stakes phase-number))))))

(deftest application-source-compiles-to-real-wasm
  (is (bytes? (:bytes compiled)))
  (is (pos? (alength ^bytes (:bytes compiled))))
  (is (= ['decide] (get-in compiled [:kir :exports]))))
