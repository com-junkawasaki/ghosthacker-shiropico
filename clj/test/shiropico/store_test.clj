(ns shiropico.store-test
  "Store contract against both backends — proving MemStore ≡ DatomicStore
  makes 'swap the SSoT for kotoba-server' a config change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [shiropico.store :as store]))

(def ^:private ep01
  {:id "ep01" :episode 1 :title "オネムの暴走、ドバイの夜"
   :cuts [{:id "ep01-c001" :high-stakes? false}
          {:id "ep01-c002" :high-stakes? true}]})

(defn- backends []
  (let [mem (store/seed-db) dat (store/datomic-store)]
    (store/commit-episode! mem ep01)
    (store/commit-episode! dat ep01)
    [["MemStore" mem] ["DatomicStore" dat]]))

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "オネムの暴走、ドバイの夜" (:title (store/episode s "ep01"))))
      (is (= 1 (:episode (store/episode s "ep01"))))
      (is (= ["ep01-c001" "ep01-c002"] (mapv :id (store/cuts-of s "ep01"))))
      (is (nil? (store/episode s "missing")))
      (is (nil? (store/committed-cut s "ep01-c001"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/commit-cut! s "ep01-c001" {:image-b64 "QUJD" :source "gateway" :seed 4242})
      (is (= 4242 (:seed (store/committed-cut s "ep01-c001"))))
      (store/append-ledger! s {:op :commit-cut :cut "ep01-c001" :disposition :commit})
      (store/append-ledger! s {:op :hold :cut "ep01-c002" :disposition :hold})
      (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))))

(deftest datomic-empty-store-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/episode s "nope")))
    (is (= [] (store/all-episodes s)))
    (store/commit-episode! s ep01)
    (is (= "オネムの暴走、ドバイの夜" (:title (store/episode s "ep01"))))))
