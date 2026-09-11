(ns shiropico.store
  "SSoT for the shiropico actor, behind a `Store` protocol so the backend is
  a swap, not a rewrite (MemStore default ‖ DatomicStore via langchain.db,
  itself swappable to real Datomic Local / kotoba-server). Mirrors
  `tsumugu.store`'s shape, ported to animeka's own domain granularity
  (episode → cut, the atom animeka itself uses — see
  `ai-gftd-animeka/clj/src/animeka/domain.cljc`) instead of manga's
  chapter → panel.

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC, e.g. kotobase.net)
  both implement it, so the same `DatomicStore` record runs on either by
  construction (see `shiropico.kotoba/kotoba-store`).

  Domain: episode (read-only source list of cuts to render) → cut (committed
  render payload, one per cut-id). The append-only ledger is the publish
  provenance — every cut commit is a fact in an immutable log, never
  overwritten."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (episode [s id])
  (all-episodes [s])
  (cuts-of [s episode-id])
  (committed-cut [s id] "the committed render payload for a cut, or nil")
  (ledger [s])
  (commit-episode! [s ep] "seed/replace one episode's cut list")
  (commit-cut! [s cut-id payload] "commit a cut's final render payload")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (episode [_ id] (get-in @a [:episodes id]))
  (all-episodes [_] (sort-by :episode (vals (:episodes @a))))
  (cuts-of [_ episode-id] (get-in @a [:episodes episode-id :cuts] []))
  (committed-cut [_ id] (get-in @a [:cuts id]))
  (ledger [_] (:ledger @a))
  (commit-episode! [s ep] (swap! a assoc-in [:episodes (:id ep)] ep) s)
  (commit-cut! [s id payload] (swap! a assoc-in [:cuts id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore (episodes seeded by the caller via `commit-episode!`)."
  []
  (->MemStore (atom {:episodes {} :cuts {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:gh.shiropico.episode/id {:db/unique :db.unique/identity}
   :gh.shiropico.cut/id     {:db/unique :db.unique/identity}
   :gh.shiropico.ledger/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net)
;; both implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (episode [this id]
    ;; q-only (not pull*) — some kotoba-server pods don't implement the
    ;; datomic.pull XRPC op at all (404 MethodNotImplemented).
    (when-let [[number title cuts]
               (first (q* this '[:find ?number ?title ?cuts :in $ ?id
                                 :where [?e :gh.shiropico.episode/id ?id]
                                        [?e :gh.shiropico.episode/number ?number]
                                        [?e :gh.shiropico.episode/title ?title]
                                        [?e :gh.shiropico.episode/cuts ?cuts]]
                         id))]
      {:id id :episode number :title title :cuts (or (dec* cuts) [])}))
  (all-episodes [this]
    (->> (q* this '[:find [?id ...] :where [?e :gh.shiropico.episode/id ?id]])
         (map #(episode this %)) (sort-by :episode)))
  (cuts-of [this id] (:cuts (episode this id)))
  (committed-cut [this id]
    (dec* (q* this '[:find ?p . :in $ ?cid
                     :where [?e :gh.shiropico.cut/id ?cid] [?e :gh.shiropico.cut/payload ?p]]
               id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :gh.shiropico.ledger/seq ?s] [?e :gh.shiropico.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-episode! [s {:keys [id episode title cuts]}]
    (tx* s [(cond-> {:gh.shiropico.episode/id id}
              episode (assoc :gh.shiropico.episode/number episode)
              title   (assoc :gh.shiropico.episode/title title)
              cuts    (assoc :gh.shiropico.episode/cuts (enc cuts)))])
    s)
  (commit-cut! [s id payload]
    (tx* s [{:gh.shiropico.cut/id id :gh.shiropico.cut/payload (enc payload)}])
    s)
  (append-ledger! [s fact]
    (tx* s [{:gh.shiropico.ledger/seq (count (ledger s)) :gh.shiropico.ledger/fact (enc fact)}])
    fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (default Datomic-
  shaped store; verifiable offline, no network). For the kotoba-server pod
  (kotobase.net), see `shiropico.kotoba/kotoba-store` — same record, different
  `:db-api`."
  []
  (->DatomicStore d/api (d/create-conn schema)))
