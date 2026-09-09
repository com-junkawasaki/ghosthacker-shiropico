(ns yt.common
  "What the SHIRO & PICO YouTube tools share: a synchronous transport,
  credential loading, and the upload ledger.

  The YouTube API work itself is not here — it is `kotoba-lang/com-youtube`
  (see nbb.edn). Before 2026-08-11 these tools were Python and each carried
  its own copy of the channel guard; the guard now lives in the library as
  `youtube.channels/assert-channel!` and this namespace only supplies the
  host bits nbb needs."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]))

;; ---------------------------------------------------------------------------
;; transport
;;
;; com-youtube is written against a *synchronous* http-fn, and node has no
;; synchronous fetch. curl through execFileSync is the honest way to satisfy
;; that contract from nbb: the request/response shaping, retry staging and
;; error messages stay in the shared library, and only the socket is local.
;;
;; Headers go through `--config`, never `-H` on the command line. An
;; Authorization header on argv is readable by any process that can run `ps`,
;; and these tokens can publish to a live channel.

(defn- write-temp! [name-hint data]
  (let [p (path/join (os/tmpdir) (str "yt-" name-hint "-" (.toString (js/Math.floor (* 1e9 (js/Date.now))) 36)))]
    (fs/writeFileSync p data #js {:mode 0600})
    p))

(defn curl-http-fn
  "{:url :method :headers :body} -> {:status :body :response-headers}.
  `:body` may be a string or a Uint8Array; both are written to a temp file
  and sent with --data-binary so bytes survive exactly."
  []
  (fn [{:keys [url method headers body]}]
    (let [hdr-file (write-temp! "hdr" (str/join "\n" (for [[k v] headers] (str "header = \"" k ": " v "\""))))
          body-file (when (some? body) (write-temp! "body" body))
          out-hdr (write-temp! "resp" "")
          args (cond-> ["--silent" "--show-error" "--config" hdr-file
                        "--dump-header" out-hdr
                        "--write-out" "\n%{http_code}"
                        "--max-time" "900"
                        "--request" (str/upper (name method))]
                 body-file (into ["--data-binary" (str "@" body-file)])
                 true (conj url))]
      (try
        (let [r (.execFileSync cp "curl" (clj->js args) #js {:encoding "buffer" :maxBuffer (* 64 1024 1024)})
              text (.toString r "utf8")
              nl (.lastIndexOf text "\n")
              status (js/parseInt (subs text (inc nl)))
              resp-body (subs text 0 (max 0 nl))
              raw-headers (str (fs/readFileSync out-hdr "utf8"))
              hmap (into {} (for [line (str/split-lines raw-headers)
                                  :let [i (str/index-of line ":")]
                                  :when (and i (pos? i))]
                              [(str/lower (str/trim (subs line 0 i)))
                               (str/trim (subs line (inc i)))]))]
          {:status status :body resp-body :response-headers hmap})
        (finally
          (doseq [f (remove nil? [hdr-file body-file out-hdr])]
            (try (fs/unlinkSync f) (catch :default _ nil))))))))

;; ---------------------------------------------------------------------------
;; credentials
;;
;; Never held in this repo. The operator passes a path. Google's own tooling
;; writes an authorized-user JSON; the oauth tool here writes EDN. Both are
;; accepted so an existing token file keeps working.

(defn load-credentials
  "-> {:client-id :client-secret :refresh-token}"
  [p]
  (when-not (fs/existsSync p)
    (throw (ex-info (str "authorized-user file not found: " p) {})))
  (let [raw (str (fs/readFileSync p "utf8"))
        m (if (str/starts-with? (str/triml raw) "{:")
            (edn/read-string raw)
            (js->clj (js/JSON.parse raw) :keywordize-keys true))
        creds {:client-id (or (:client-id m) (:client_id m))
               :client-secret (or (:client-secret m) (:client_secret m))
               :refresh-token (or (:refresh-token m) (:refresh_token m))}]
    (when-not (every? some? (vals creds))
      (throw (ex-info "authorized-user file is missing client_id / client_secret / refresh_token"
                      {:path p :have (vec (keep key (filter val creds)))})))
    creds))

;; ---------------------------------------------------------------------------
;; releases + ledger (both EDN tx-data)

(defn- read-tx [p]
  (if (fs/existsSync p) (edn/read-string (str (fs/readFileSync p "utf8"))) []))

(defn releases
  "shorts/releases.edn -> {:defaults m :by [episode lang] -> release}"
  [p]
  (let [tx (read-tx p)]
    {:defaults (first (filter #(= "defaults" (:release/kind %)) tx))
     :by (into {} (for [e tx :when (= "episode-lang" (:release/kind e))]
                    [[(:release/episode e) (:release/lang e)] e]))}))

(defn ledger
  "The upload record. Returns {:channel m :uploads {[ep lang] entry}}."
  [p]
  (let [tx (read-tx p)]
    {:channel (first (filter #(= "channel" (:upload/kind %)) tx))
     :uploads (into {} (for [e tx :when (= "short" (:upload/kind e))]
                         [[(:upload/episode e) (:upload/lang e)] e]))}))

(defn write-ledger!
  "Rewrite the ledger from {:channel :uploads}. Called after **every** upload,
  not at the end: an interrupted run must not lose the ids of videos that are
  already live, or the next run re-uploads them as duplicates."
  [p {:keys [channel uploads]}]
  (let [id (atom 0)
        nid #(swap! id dec)
        tx (into [(assoc channel :db/id (nid))]
                 (for [[[ep lang] e] (sort-by key uploads)]
                   (assoc e :db/id (nid) :upload/episode ep :upload/lang lang)))]
    (fs/mkdirSync (path/dirname p) #js {:recursive true})
    (fs/writeFileSync p (str ";; 生成物 — tools/youtube-publish-*.cljs が書く。手で編集しない。\n"
                             ";; 1 本 = 1 entity。\n\n"
                             "[" (str/join "\n " (map pr-str tx)) "]\n"))))

;; ---------------------------------------------------------------------------
;; argv

(def argv
  (let [a (vec (js->clj js/process.argv))
        i (first (keep-indexed (fn [i x] (when (str/ends-with? (str x) ".cljs") i)) a))]
    (vec (drop (inc (or i 1)) a))))

(defn arg
  ([flag] (arg flag nil))
  ([flag default]
   (let [i (.indexOf argv flag)] (if (neg? i) default (nth argv (inc i) default)))))

(defn flag? [f] (boolean (some #{f} argv)))

(defn require-arg [flag]
  (or (arg flag)
      (do (println (str "missing required argument: " flag)) (js/process.exit 2))))

(defn die! [& msgs]
  (binding [*print-fn* *print-err-fn*] (println (str/join "\n" msgs)))
  (js/process.exit 1))
