(ns shiropico.kotoba
  "Wire a `shiropico.store/KotobaStore` to a kotoba-server pod (e.g.
  kotobase.net) over the ai.gftd.apps.kotobase.datomic.* XRPC namespace. The
  store is unchanged — it only ever calls the `:db-api` map;
  `langchain.kotoba-db/kotoba-api` implements that map against the remote
  pod, so this is purely a constructor. Ported from `itonami.kotoba` /
  `tsumugu.kotoba` (keep in sync).

  I/O is injected (langchain's host-caps contract): an http-fn is provided
  here via a kotoba.lang.http/IHttp host backed by the JDK HTTP client, and
  the JSON pair is passed by the caller (e.g. clojure.data.json) so this
  namespace stays dependency-free. Instant/UUID use the portable kotoba-lang
  time lib and a platform reader-conditional (no java.time).

  shiropico is depth-1 self-sovereign: it always connects with `:identity`
  (never a handed :token), self-minting a `:cap/transact` CACAO scoped to its
  own graph — no owner hand-off, no shared secret."
  (:require [clojure.string :as str]
            [kotoba.lang.http :as http]
            [kotoba.lang.time :as t]
            [langchain.kotoba-db :as kdb]
            [shiropico.cacao :as cacao]
            [shiropico.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security SecureRandom]))

;; ---- host clock (portable now) ----
(defn- clock
  "Current epoch-millis. Injected into kotoba.lang.time/now. JVM host branch
  only (shiropico.kotoba is a .clj application host)."
  []
  (System/currentTimeMillis))

(defn- now-iso
  "UTC ISO-8601 string for the current instant (wire format @ 1s)."
  []
  (t/->iso8601 (t/now clock)))

(defn- expiry-iso
  "UTC ISO-8601 string `hours` from now (default 1)."
  ([] (expiry-iso 1))
  ([h] (t/->iso8601 (t/add (t/now clock) (t/hours h)))))

;; ---- portable uuid v4 (no java.util.UUID) ----
(defn- uuid4
  "RFC-4122 version 4 UUID string (random, 122 bits of entropy). Host-only:
  this is a JVM app (.clj); entropy comes from SecureRandom."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (let [hexfmt (fn [b] (format "%02x" (bit-and b 0xFF)))
          seg    (fn [a b] (apply str (map hexfmt (take (- b a) (drop a (seq bytes))))))]
      (-> (str (seg 0 4) "-" (seg 4 6) "-"
               (format "%02x" (bit-or 0x40 (bit-and (nth bytes 6) 0x0F))) (seg 7 8) "-"
               (format "%02x" (bit-or 0x80 (bit-and (nth bytes 8) 0x3F))) (seg 9 10) "-"
               (seg 10 16))))))

(def ^:private jdk-http
  "kotoba.lang.http/IHttp host: JDK transport."
  (reify http/IHttp
    (send [_ req]
      (let [m (get req :http/method)
            u (get req :http/url)
            h (get req :http/headers)
            bdy (get req :http/body)
            b (HttpRequest/newBuilder (URI/create (str u)))]
        (doseq [[k v] h] (.header b (str k) (str v)))
        (let [r (-> b (.method (str/upper-case (name (or m :post)))
                               (if bdy
                                 (HttpRequest$BodyPublishers/ofString bdy)
                                 (HttpRequest$BodyPublishers/noBody)))
                    (.build))
              resp (.send (HttpClient/newHttpClient) r
                          (HttpResponse$BodyHandlers/ofString))]
          (http/response (.statusCode resp) {} (.body resp)))))))

(defn- langchain-http-fn
  "Adapt a \"normalized\" HTTP call to langchain's host-caps `:http-fn`
  contract: input {:url :method :headers :body}, output {:status :body}.
  Routes through the kotoba.lang.http data model + JDK IHttp host so java
  stays behind the kotoba-lang lib."
  [{:keys [url method headers body]}]
  (let [r (http/send jdk-http
                     (http/request (or method :post) url
                                   (cond-> {:headers (or headers {})}
                                     (some? body) (assoc :body body))))]
    {:status (:http/status r) :body (:http/body r)}))

(defn kotoba-store
  "A `shiropico.store/DatomicStore` backed by a kotoba-server pod (e.g.
   kotobase.net), self-sovereign via `identity` (see `shiropico.cacao/
   load-or-create-identity!`) — shiropico SELF-MINTS a CACAO for `:grant`
   (default a transact grant on its own graph). This is the charter path:
   no handed token, the agent issues its own capability.

   Addresses the tenant database by `db-name` (via `langchain.kotoba-db/
   kotoba-conn*`) — the wire shape the live edge's tenant Datom *write*
   requires (it derives + verifies `kotobase/db/<did>/<db-name>` from the
   CACAO's own DID server-side; a client-supplied raw `graph` is rejected
   for tenant writes). Also passes the precomputed `canonical-graph` CID as
   `:graph` — the live edge's *read* ops (`q`/`pull`/`entid`) need this
   instead. The CACAO's own resource scope is this same `canonical-graph`
   CID (what the edge independently recomputes and checks the signature
   against for both directions).

   opts:
     :url     pod base URL     :db-name target database (default: `default-db-name`)
     :json-write :json-read    injected JSON fns (e.g. data.json)
     :grant   {:cap :cap/read|:cap/transact :scope graph} (default transact on own graph)
     :http-fn optional override (defaults to a kotoba.lang.http IHttp host)"
  [{:keys [url db-name json-write json-read identity grant http-fn]}]
  (let [db-name (or db-name cacao/default-db-name)
        graph   (cacao/canonical-graph (:did identity) db-name)
        g       (or grant {:cap :cap/transact :scope graph})
        mint-cacao (cacao/mint identity g {:aud url :nonce (uuid4)
                                           :issued-at (now-iso)
                                           :expiry (expiry-iso 1)})
        host-caps {:http-fn (or http-fn langchain-http-fn)
                   :json-write json-write :json-read json-read}
        api  (kdb/kotoba-api host-caps)
        conn (kdb/kotoba-conn* url db-name {:cacao mint-cacao :did (:did identity) :graph graph})]
    (store/->DatomicStore api conn)))