(ns shiropico.cacao
  "Agent-side CACAO issuance. The shiropico actor mints its OWN
  server-verifiable CACAO to authenticate to a kotoba-server pod (kotobase.net)
  — no human-handed token. Ported from `itonami.cacao` / `tsumugu.cacao` (keep
  in sync); the SIWE/wire builders below are a faithful copy of the proven
  byte-exact pure functions in `kotoba.cacao` (kotoba-auth / kotoba-wasm), and
  the crypto is kotoba-lang's portable Ed25519/SHA-256/bytes libs plus a
  minimal CBOR encoder (all pure byte-vector code, no JCA).

  Per-actor key model: shiropico generates + persists its OWN Ed25519 key
  (self-sovereign, no owner hand-off), and its graph is the deterministic
  `canonical-graph(did, db-name)` CID the kotobase.net edge itself recomputes
  from the DID + db-name on every write (ported from the CLJS `kotobase.cid`
  client used elsewhere in this workspace's app-aozora — see that ns's
  docstring: 'byte-identical to the kotobase.net edge'). A depth-1
  self-minted CACAO (iss = the actor's own DID) is still authorized by
  construction — only the DID holder can mint a valid CACAO for it — this
  just changes which bytes get hashed into the graph CID (name-based, not
  raw-pubkey-based), so one actor can own several named graphs instead of
  exactly one. Use `load-or-create-identity!` to bootstrap/persist the
  actor's key.

  Naming: the original JDK port persisted **PKCS8/X.509-wrapped** key material
  and kept JCA opaque key handles (`:private-key`/`:public-key`). This port
  switched to the kotoba-lang ed25519 lib's **raw 32-byte** convention: the
  private `seed` is the 32 raw bytes that expand to the signing state
  (RFC 8032 §5.1.5), the public key is the 32 raw bytes. `:private-b64` is
  base64(seed), `:public-b64` is base64(raw pubkey) — different bytes than the
  old PKCS8/X509 encodings, so any previously persisted identity must be
  regenerated. The SIWE message, CBOR envelope shape, signature bytes, and
  did:key derivation (`ed25519.core/did-key-from-pub`) are byte-identical to
  the kotoba.cacao original, so a freshly minted cacao_b64 still verifies on
  the PDS exactly as before."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ed25519.core :as ed25519]
            [ed25519.sign :as ni]
            [sha2.core :as sha2]
            [kotoba.bytes :as bytes])
  (:import [java.security SecureRandom]))

;; ───────── pure CACAO builders (mirror of kotoba.cacao) ─────────

(def ^:private cap->op {:cap/read "datom:read" :cap/transact "datom:transact" :cap/admin "tx:create"})

(defn grant->resources [{:keys [cap scope]}]
  [(str "kotoba://op/" (cap->op cap)) (str "kotoba://graph/" scope)])

(defn grant->payload [grant {:keys [iss aud nonce issued-at expiry domain version statement]
                             :or {domain "gftd.office" version "1"}}]
  {:iss iss :aud aud :issued-at issued-at :expiry expiry :nonce nonce
   :domain domain :statement statement :version version
   :resources (grant->resources grant)})

(defn- iss-address [iss] (last (str/split iss #":")))
(defn- iss-chain-id [iss]
  (if (str/starts-with? iss "did:key:") "1"
      (let [segs (str/split iss #":")] (if (>= (count segs) 2) (nth segs (- (count segs) 2)) "1"))))

(defn siwe-message [{:keys [iss aud issued-at expiry nonce domain statement version resources]}]
  (->> (concat
        [(str domain " wants you to sign in with your Ethereum account:") (iss-address iss) ""]
        (when statement [statement ""])
        [(str "URI: " aud) (str "Version: " version) (str "Chain ID: " (iss-chain-id iss))
         (str "Nonce: " nonce) (str "Issued At: " issued-at)]
        (when expiry [(str "Expiration Time: " expiry)])
        (when (seq resources) (cons "Resources:" (map #(str "- " %) resources))))
       (str/join "\n")))

(defn ->wire [payload sig-b64]
  {"h" {"t" "eip4361"}
   "p" (cond-> {"iss" (:iss payload) "aud" (:aud payload) "iat" (:issued-at payload)
                "nonce" (:nonce payload) "domain" (:domain payload)
                "version" (:version payload) "resources" (:resources payload)}
         (:expiry payload)    (assoc "exp" (:expiry payload))
         (:statement payload) (assoc "statement" (:statement payload)))
   "s" {"t" "EdDSA" "s" (or sig-b64 "")}})

;; ───────── minimal CBOR (definite-length; serde-deserializable) ─────────
;; Pure byte-vector (ints 0..255) encoder — matches kotoba.bytes convention,
;; no ByteArrayOutputStream. Output is byte-identical to the JDK port's stream
;; builder (same major/head rules, same UTF-8 string bytes).

(defn- cbor-head [major n]
  (cond (< n 24)    [(bit-or (bit-shift-left major 5) n)]
        (< n 256)   [(bit-or (bit-shift-left major 5) 24) n]
        (< n 65536) [(bit-or (bit-shift-left major 5) 25)
                     (bit-and (unsigned-bit-shift-right n 8) 0xff)
                     (bit-and n 0xff)]
        :else (throw (ex-info "cbor len too big" {:n n}))))

(declare ^:private cbor-val)

(defn- cbor-val [v]
  (cond
    (string? v)     (let [b (bytes/utf8-encode v)]
                      (into (cbor-head 3 (count b)) b))
    (map? v)        (into (cbor-head 5 (count v))
                          (mapcat (fn [[k vv]] (concat (cbor-val (name k)) (cbor-val vv))) v))
    (sequential? v) (into (cbor-head 4 (count v)) (mapcat cbor-val v))
    :else           (cbor-val (str v))))

(defn- cbor-bytes [v]
  (vec (cbor-val v)))

;; ───────── Ed25519 + did:key ─────────
;; did:key derivation lives in the ed25519 lib (`ed25519.core/did-key-from-pub`),
;; which has its own base58btc — no local b58 needed.

;; ───────── canonical graph CID (per-actor, per-database) ─────────
;; The graph handle is the CIDv1/dag-cbor/sha2-256 of the name
;; "kotobase/db/<did>/<db-name>" — byte-identical to the kotobase.net edge and
;; to `kotobase.cid/canonical-graph` (the CLJS client this workspace's
;; app-aozora already uses live). Porting the SAME derivation here (rather
;; than a parallel raw-pubkey scheme) means shiropico's graph is exactly what
;; any kotobase.net-speaking client independently recomputes from the DID
;; alone, with no shared secret.

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- sha256 [data]
  (sha2/sha256 data))

(defn- base32-lower-no-pad
  "CIDv1 base32-lower, no padding (multibase 'b' payload) — 8-bit input drained
  as 5-bit groups, MSB-first. Ported from `kotobase.cid/base32-lower-no-pad`."
  [data]
  (let [sb (StringBuilder.)
        {:keys [bits value]}
        (reduce
         (fn [{:keys [bits value]} b]
           (let [b (bit-and (int b) 0xff)
                 value (bit-or (bit-shift-left value 8) b)
                 bits (+ bits 8)]
             (loop [bits bits value value]
               (if (>= bits 5)
                 (do (.append sb (.charAt b32 (bit-and (unsigned-bit-shift-right value (- bits 5)) 31)))
                     (recur (- bits 5) value))
                 {:bits bits :value value}))))
         {:bits 0 :value 0}
         data)]
    (when (pos? bits)
      (.append sb (.charAt b32 (bit-and (bit-shift-left value (- 5 bits)) 31))))
    (.toString sb)))

(defn graph-cid-from-name
  "KotobaCid::from_bytes(name).to_multibase(): SHA-256(name) behind a
  CIDv1/dag-cbor/sha2-256 header (0x01 0x71 0x12 0x20), base32-lower 'b'.
  Ported from `kotobase.cid/graph-cid-from-name`."
  [^String name]
  (let [hash (sha256 (bytes/utf8-encode name))
        cid  (into [0x01 0x71 0x12 0x20] hash)]
    (str "b" (base32-lower-no-pad cid))))

(defn canonical-graph
  "The deterministic graph CID for one of shiropico's databases. The edge
  recomputes exactly this from the DID + db-name and pins it into every
  write. Ported from `kotobase.cid/canonical-graph`."
  [did db-name]
  (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

(def default-db-name
  "shiropico's primary content database — the SHIRO & PICO / animeka cut
  publish ledger."
  "anime")

;; ───────── raw 32-byte Ed25519 (kotoba-lang ed25519) ─────────
;; The ed25519 lib works on raw 32-byte seeds/pubkeys (RFC 8032 §5.1.5), not
;; JCA PKCS8/X509 wrapped keys. The private representation is `seed` (the 32
;; raw bytes that expand to the signing state); `ed25519.sign/public-key`
;; derives the 32-byte pubkey from it, and `ed25519.sign/sign` signs a message
;; with the expanded `:secret-key` map (deterministic — RFC 8032). Only the
;; host CSPRNG (SecureRandom) draws the 32 seed bytes; all crypto sits in the
;; portable libs.

(def ^:private default-seed-bytes 32)

(defn- random-seed ^bytes []
  (let [sr (SecureRandom.) b (byte-array default-seed-bytes)] (.nextBytes sr b) b))

(defn- did-key [raw-pub]
  (ed25519/did-key-from-pub raw-pub))

;; ───────── identity ─────────

(defn generate-identity
  "A fresh Ed25519 identity {:private-key :public-key :did :graph}. For
  owner/test bootstrap — a provisioned agent persists and reloads its key
  instead. `:private-b64` = base64(raw seed), `:public-b64` = base64(raw pub)."
  []
  (let [seed (random-seed)
        sk   (ni/secret-key! seed)        ; {:status :ok :seed :scalar :prefix :public}
        pub  (:public sk)
        did  (did-key pub)]
    {:private-key seed :public-key pub :did did
     :secret-key sk
     :graph (canonical-graph did default-db-name)
     ;; ->bytes normalises the signed JVM byte[] to unsigned before base64 —
     ;; kotoba.bytes/base64-encode assumes unsigned bytes.
     :private-b64 (bytes/base64-encode (bytes/->bytes seed))
     :public-b64  (bytes/base64-encode (bytes/->bytes pub))}))

(defn load-identity
  "Reload a persisted identity from base64 raw seed + raw pubkey (see
  `generate-identity`). Cross-checks the stored pubkey against the one derived
  from the seed (guard against a corrupted persisted record)."
  [{:keys [private-b64 public-b64]}]
  (let [seed (vec (bytes/base64-decode private-b64))
        stored-pub (vec (bytes/base64-decode public-b64))
        sk     (ni/secret-key! seed)
        pub    (:public sk)]
    (when (and stored-pub (not= stored-pub (vec pub)))
      (throw (ex-info "persisted Ed25519 pubkey does not match seed" {})))
    {:private-key seed :public-key pub :did (did-key pub)
     :secret-key sk
     :graph (canonical-graph (did-key pub) default-db-name)
     :private-b64 private-b64 :public-b64 public-b64}))

(defn load-or-create-identity!
  "Per-actor key: load shiropico's persisted Ed25519 identity at `path`, or
  generate + persist one on first run (only the b64 key material is stored).
  Returns {:private-key :public-key :did :graph …}. This is the 'each actor
  issues its own key' bootstrap — the actor's graph is `canonical-graph(did,
  default-db-name)`.
  ⚠ NOTE: the persisted key material is raw seed/pubkey b64, NOT the old
  PKCS8/X.509 encodings — a pre-port identity file must be regenerated."
  [path]
  (let [f (java.io.File. ^String path)]
    (if (.exists f)
      (load-identity (edn/read-string (slurp f)))
      (let [id (generate-identity)
            parent (.getParentFile (.getAbsoluteFile f))]
        (when parent (.mkdirs parent))
        (spit f (pr-str (select-keys id [:private-b64 :public-b64])))
        id))))

;; ───────── sign / verify (portable ed25519.sign) ─────────

(defn- ed-sign [sk ^bytes msg]
  (ni/sign sk msg))

(defn verify? [pub ^bytes msg ^bytes sig]
  (ni/verify pub msg sig))

;; ───────── mint ─────────

(defn mint
  "Mint a base64 cacao_b64 the agent signs itself.
   identity: {:private-key :public-key :did :secret-key}
   grant:    {:cap :cap/read|:cap/transact|:cap/admin :scope <graph>}
   opts:     {:aud <server did/uri> :nonce :issued-at :expiry}"
  [{:keys [secret-key did]} grant {:keys [aud nonce issued-at expiry]}]
  (let [payload (grant->payload grant {:iss did :aud aud :nonce nonce
                                       :issued-at issued-at :expiry expiry})
        msg     (siwe-message payload)
        sig     (ed-sign secret-key (bytes/utf8-encode msg))
        ;; CACAO signs the EdDSA `s` base64url (no padding) — same as the
        ;; original `Base64/getUrlEncoder` `.withoutPadding`.
        sig-b64 (-> (bytes/base64-encode (seq sig))
                    (.replace "+" "-")
                    (.replace "/" "_")
                    (.replace "=" ""))
        wire    (->wire payload sig-b64)]
    (bytes/base64-encode (cbor-bytes wire))))