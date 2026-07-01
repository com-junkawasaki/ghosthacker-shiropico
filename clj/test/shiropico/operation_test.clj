(ns shiropico.operation-test
  "End-to-end OperationActor contract: shiropico.advisor sealed,
  PolicyGovernor + phase gate earn the right to commit, append-only ledger,
  MemStore ≡ DatomicStore. Mirrors `tsumugu`/`talent`'s operation contract
  test shape.

  No ComfyUI gateway is configured in this test environment, so every render
  degrades to the offline stub (:source \"stub\", confidence 0.3) — below
  the PolicyGovernor's 0.4 confidence floor, so a real cut always escalates
  for human approval here, never auto-commits. That is the correct,
  honestly-tested behavior (a stub render should never silently reach
  production) — see `shiropico.advisor`'s docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [shiropico.operation :as op]
            [shiropico.phase :as phase]
            [shiropico.store :as store]))

(defn- seed! [s]
  (store/commit-episode! s {:id "ep01" :episode 1 :title "オネムの暴走、ドバイの夜"
                            :cuts [{:id "ep01-c001" :high-stakes? false}
                                   {:id "ep01-c002" :high-stakes? true}]})
  s)

(defn- ctx [phase] {:actor-id "shiropico" :phase phase})

(deftest supervised-phase-escalates-to-human-approval
  (testing "phase 1 (default): a stub render still needs a human look"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "ep01" :prompt "dubai skyline"}
          res (g/run* actor {:request req :context (ctx phase/default-phase)} {:thread-id "t1"})]
      (is (= :interrupted (:status res)))
      (is (nil? (store/committed-cut s "ep01-c001")) "no commit before approval"))))

(deftest approval-then-commit
  (testing "resuming with :approved commits the cut + appends the ledger fact"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "ep01" :prompt "dubai skyline"}
          res1 (g/run* actor {:request req :context {:actor-id "shiropico" :phase 1}} {:thread-id "t2"})]
      (is (= :interrupted (:status res1)))
      (let [res2 (g/run* actor {:approval {:status :approved :by "reviewer"}}
                         {:thread-id "t2" :resume? true})]
        (is (= :commit (get-in res2 [:state :disposition])))
        (is (some? (store/committed-cut s "ep01-c001")))
        (is (= [:commit] (mapv :disposition (store/ledger s)))))))
  (testing "resuming with :rejected holds — no SSoT mutation"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "ep01" :prompt "dubai skyline"}
          _ (g/run* actor {:request req :context {:actor-id "shiropico" :phase 1}} {:thread-id "t3"})
          res2 (g/run* actor {:approval {:status :rejected :by "reviewer"}}
                       {:thread-id "t3" :resume? true})]
      (is (= :hold (get-in res2 [:state :disposition])))
      (is (nil? (store/committed-cut s "ep01-c001"))))))

(deftest high-stakes-cut-escalates-even-if-it-were-gateway-confident
  (testing "a :high-stakes? cut (e.g. the henshin-bank sequence) always escalates in phase 1/2"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c002" :episode-id "ep01" :prompt "henshin bank"}
          res (g/run* actor {:request req :context {:actor-id "shiropico" :phase 2}} {:thread-id "t4"})]
      (is (= :interrupted (:status res)))
      (is (nil? (store/committed-cut s "ep01-c002"))))))

(deftest missing-cut-holds
  (testing "a cut-id that doesn't exist in the episode → no proposal → HOLD"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "no-such-cut" :episode-id "ep01"}
          res (g/run* actor {:request req :context {:actor-id "shiropico" :phase 2}} {:thread-id "t5"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/committed-cut s "no-such-cut"))))))

(deftest missing-episode-holds
  (testing "an episode-id that doesn't exist → no proposal → HOLD"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "no-such-episode"}
          res (g/run* actor {:request req :context {:actor-id "shiropico" :phase 2}} {:thread-id "t6"})]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest phase-0-holds-even-a-clean-request
  (testing "phase 0 (read-only): writes are disabled outright, regardless of policy"
    (let [s (seed! (store/seed-db))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "ep01" :prompt "dubai skyline"}
          res (g/run* actor {:request req :context {:actor-id "shiropico" :phase 0}} {:thread-id "t7"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/committed-cut s "ep01-c001"))))))

(deftest datomic-store-same-contract
  (testing "backend swap (MemStore → DatomicStore) is a config change, not a rewrite"
    (let [s (seed! (store/datomic-store))
          actor (op/build s)
          req {:op :cut/render :cut-id "ep01-c001" :episode-id "ep01" :prompt "dubai skyline"}
          res1 (g/run* actor {:request req :context {:actor-id "shiropico" :phase 1}} {:thread-id "d1"})
          res2 (g/run* actor {:approval {:status :approved :by "reviewer"}}
                       {:thread-id "d1" :resume? true})]
      (is (= :interrupted (:status res1)))
      (is (= :commit (get-in res2 [:state :disposition])))
      (is (some? (store/committed-cut s "ep01-c001"))))))
