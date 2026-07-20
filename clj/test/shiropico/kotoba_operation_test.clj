(ns shiropico.kotoba-operation-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [shiropico.kotoba-operation :as kotoba-op]
            [shiropico.store :as store]))

(defn- seed! [s]
  (store/commit-episode! s {:id "ep01" :episode 1
                            :cuts [{:id "ep01-c001" :high-stakes? false}]})
  s)

(deftest canonical-operation-runs-the-kotoba-application
  (is (pos? (alength ^bytes (kotoba-op/artifact-bytes))))
  (is (= :hold (:disposition
                (kotoba-op/decide {} {:phase 2}
                                  {:effect :noop :confidence 1.0}
                                  {:high-stakes? false}))))
  (is (= :commit (:disposition
                  (kotoba-op/decide {} {:phase 2}
                                    {:effect :commit-cut :confidence 1.0}
                                    {:high-stakes? false})))))

(deftest commit-writes-confined-atomic-intent-before-ssot
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "shiropico-checkpoint-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        checkpoint-file (io/file directory "state/commit.edn")]
    (testing "approved commit persists an auditable intent and then mutates SSoT"
      (let [s (seed! (store/seed-db))
            actor (kotoba-op/build s {:checkpoint! (kotoba-op/atomic-checkpoint
                                                     directory "state/commit.edn")})
            request {:op :cut/render :cut-id "ep01-c001"
                     :episode-id "ep01" :prompt "dubai skyline"}]
        (g/run* actor {:request request :context {:actor-id "shiropico" :phase 1}}
                {:thread-id "kotoba-checkpoint"})
        (g/run* actor {:approval {:status :approved :by "reviewer"}}
                {:thread-id "kotoba-checkpoint" :resume? true})
        (is (some? (store/committed-cut s "ep01-c001")))
        (is (= :committed (:t (:fact (edn/read-string (slurp checkpoint-file))))))))
    (testing "path traversal is rejected when authority is constructed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes"
                            (kotoba-op/atomic-checkpoint directory "../escape.edn"))))))

(deftest checkpoint-failure-is-fail-closed
  (let [s (seed! (store/seed-db))
        actor (kotoba-op/build s {:checkpoint! (fn [_] (throw (ex-info "denied" {})))})
        request {:op :cut/render :cut-id "ep01-c001"
                 :episode-id "ep01" :prompt "dubai skyline"}]
    (g/run* actor {:request request :context {:actor-id "shiropico" :phase 1}}
            {:thread-id "kotoba-denied"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denied"
                          (g/run* actor {:approval {:status :approved :by "reviewer"}}
                                  {:thread-id "kotoba-denied" :resume? true})))
    (is (nil? (store/committed-cut s "ep01-c001")))))
