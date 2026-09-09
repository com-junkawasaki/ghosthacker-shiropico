#!/usr/bin/env nbb
;; b2-catalog — ai-gftd-datasets バケットにある SHIRO & PICO の制作資産の
;; 在庫と drift 検査。2026-08-11 に tools/b2_catalog.py から移植（Python を
;; 新規に置かない / 運用 script は nbb、ADR-2607173000）。出力も JSON から
;; EDN の datom 面へ移した — 在庫を「引ける」ようにするため。
;;
;; git-annex でこのバイトを抱えられない理由（元の Python の説明をそのまま引き継ぐ）:
;;   - `git annex import --from <s3remote> --no-content` は拒否される
;;     （"This remote does not support importing without downloading content"）。
;;     import すると 9.9GB 全部を引いて hash することになる。
;;   - listing の SHA1 を使って annex key を作るのは不健全。**全オブジェクトの
;;     contentSha1 が `unverified:` 接頭辞**（S3 API 経由のアップロードで B2 が
;;     hash を検証していない）。信じて annex entry を作れば、`get` で検証できない
;;     content を主張する **偽のバックアップ**になる —— この作業全体が防ごうと
;;     しているものそのもの。
;;
;; だから custody は content addressing ではなく **在庫 + drift 検出**。何が
;; バケットに在るかを記録し、いつでも転送ほぼゼロで「全部まだ在って変わっていない」
;; ことを示せる。取り出しは普通のダウンロードのまま。
;;
;; 資格情報は環境変数 B2_KEY_ID / B2_APP_KEY か、Keychain service
;; `b2:ai-gftd-datasets`（account = key id、password = app key）。鍵はこのバケット
;; だけにスコープされている。**service 名を狙い撃ちで 1 件引く**（vault を総当たり
;; しない — CLAUDE.md 安全床⑦）。
;;
;;   nbb tools/b2-catalog.cljs --generate   # catalog/ai-gftd-datasets.edn を更新
;;   nbb tools/b2-catalog.cljs --verify     # drift があれば exit 1

(ns b2-catalog
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(defn- arg [flag default]
  (let [i (.indexOf argv flag)] (if (neg? i) default (nth argv (inc i) default))))

;; repo root は cwd 既定（この repo の他の nbb tool と同じ約束。nbb は ESM なので
;; __filename が無く、script の位置からは辿れない）。--root で明示もできる。
(def root (path/resolve (arg "--root" (.cwd js/process))))
(def catalog-path (path/join root "catalog" "ai-gftd-datasets.edn"))
(def bucket "ai-gftd-datasets")
(def prefix "ghosthacker-shiropico/")
(def keychain-service "b2:ai-gftd-datasets")
(def dataset "shiropico-b2-catalog")

(defn- die! [& msgs]
  (binding [*print-fn* *print-err-fn*] (println (str/join "\n" msgs)))
  (js/process.exit 1))

(defn- credentials []
  (let [kid (.. js/process -env -B2_KEY_ID)
        key (.. js/process -env -B2_APP_KEY)]
    (if (and kid key)
      [kid key]
      ;; `security -g` は account を stdout に、password を stderr に出す。
      (try
        (let [r (.spawnSync cp "security"
                            (clj->js ["find-generic-password" "-s" keychain-service "-g"])
                            #js {:encoding "utf8"})
              out (or (.-stdout r) "") err (or (.-stderr r) "")
              acct (some->> (str/split-lines out)
                            (filter #(str/includes? % "\"acct\""))
                            first (#(nth (str/split % #"\"") 3 nil)))
              pw (some-> (second (str/split err #"password: \"")) (str/split #"\"") first)]
          (if (and acct pw)
            [acct pw]
            (throw (js/Error. "keychain entry incomplete"))))
        (catch :default e
          (die! (str "B2 credentials unavailable (env B2_KEY_ID/B2_APP_KEY or Keychain "
                     keychain-service "): " e)))))))

(defn- api-call [url token payload]
  (-> (js/fetch url
        (clj->js (cond-> {:headers (cond-> {"Content-Type" "application/json"}
                                     token (assoc "Authorization" token))}
                   payload (assoc :method "POST" :body (js/JSON.stringify (clj->js payload))))))
      (.then (fn [^js res]
               (if (.-ok res)
                 (.json res)
                 (js/Promise.reject (js/Error. (str "POST " url " -> " (.-status res)))))))
      (.then #(js->clj % :keywordize-keys true))))

(defn- list-objects []
  (let [[kid key] (credentials)
        basic (.toString (js/Buffer.from (str kid ":" key)) "base64")]
    (-> (js/fetch "https://api.backblazeb2.com/b2api/v3/b2_authorize_account"
                  #js {:headers #js {"Authorization" (str "Basic " basic)}})
        (.then (fn [^js res]
                 (if (.-ok res) (.json res)
                     (js/Promise.reject (js/Error. (str "b2_authorize_account -> " (.-status res)))))))
        (.then #(js->clj % :keywordize-keys true))
        (.then (fn [auth]
                 (let [api (get-in auth [:apiInfo :storageApi :apiUrl])
                       token (:authorizationToken auth)]
                   (-> (api-call (str api "/b2api/v3/b2_list_buckets") token
                                 {:accountId (:accountId auth) :bucketName bucket})
                       (.then (fn [bs]
                                (let [bid (:bucketId (first (:buckets bs)))]
                                  ;; 1000 件ずつ、nextFileName が尽きるまで
                                  (letfn [(page [start acc]
                                            (-> (api-call (str api "/b2api/v3/b2_list_file_names") token
                                                          (cond-> {:bucketId bid :prefix prefix :maxFileCount 1000}
                                                            start (assoc :startFileName start)))
                                                (.then (fn [p]
                                                         (let [acc (into acc (:files p))]
                                                           (if-let [nxt (:nextFileName p)]
                                                             (page nxt acc)
                                                             acc))))))]
                                    (page nil [])))))))))
        (.then (fn [files]
                 (->> files
                      (sort-by :fileName)
                      (mapv (fn [f]
                              {:path (subs (:fileName f) (count prefix))
                               :size (:contentLength f)
                               :sha1 (:contentSha1 f)
                               :uploaded (:uploadTimestamp f)}))))))))

;; ---- EDN 面 --------------------------------------------------------------
;; 1 オブジェクト = 1 entity。要約が 1 entity。tx-data なのでそのまま transact でき、
;; 「どのファイルが在るか」「合計何バイトか」を query で引ける（JSON では引けなかった）。

(defn- ->tx [objects]
  (let [total (reduce + 0 (map :size objects))]
    (into [{:db/id -1
            :source/dataset dataset
            :catalog/kind "summary"
            :catalog/bucket bucket
            :catalog/prefix prefix
            :catalog/files (count objects)
            :catalog/bytes total
            :catalog/note "在庫のみ — バイトは annex 管理下に無い。生成は tools/b2-catalog.cljs。"}]
          (map-indexed (fn [i o]
                         (cond-> {:db/id (- (+ i 2))
                                  :source/dataset dataset
                                  :catalog/kind "object"
                                  :object/path (:path o)
                                  :object/size (:size o)}
                           (:sha1 o) (assoc :object/sha1 (:sha1 o))
                           (:uploaded o) (assoc :object/uploaded (:uploaded o))))
                       objects))))

(defn- tx->objects [tx]
  (->> tx (filter #(= "object" (:catalog/kind %)))
       (mapv (fn [e] {:path (:object/path e) :size (:object/size e) :sha1 (:object/sha1 e)}))))

(defn- write-catalog! [objects]
  (let [tx (->tx objects)
        body (str ";; catalog/ai-gftd-datasets.edn — 生成物。手で編集しない。\n"
                  ";;   nbb tools/b2-catalog.cljs --generate\n"
                  ";; 1 オブジェクト = 1 entity。トップレベルは tx-data。\n\n"
                  "[" (str/join "\n " (map pr-str tx)) "]\n")]
    (fs/mkdirSync (path/dirname catalog-path) #js {:recursive true})
    (fs/writeFileSync catalog-path body)
    (println (str "catalog written: " (count objects) " files, "
                  (.toFixed (/ (reduce + 0 (map :size objects)) 1048576) 1) " MB -> " catalog-path))))

(defn- verify! [live]
  (when-not (fs/existsSync catalog-path)
    (die! (str "no catalog at " catalog-path "; run --generate first")))
  (let [recorded (tx->objects (edn/read-string (str (fs/readFileSync catalog-path "utf8"))))
        was (into {} (map (juxt :path identity) recorded))
        now (into {} (map (juxt :path identity) live))
        missing (sort (remove now (keys was)))
        added   (sort (remove was (keys now)))
        changed (sort (for [k (keys was) :when (now k)
                            :let [a (was k) b (now k)]
                            :when (or (not= (:size a) (:size b)) (not= (:sha1 a) (:sha1 b)))]
                        k))]
    (println (str "b2-catalog verify: recorded=" (count was) " live=" (count now)
                  " missing=" (count missing) " changed=" (count changed) " new=" (count added)))
    (doseq [p (take 20 missing)] (println (str "  MISSING: " p " (" (:size (was p)) " bytes)")))
    (doseq [p (take 20 changed)] (println (str "  CHANGED: " p " " (:size (was p)) "->" (:size (now p)))))
    (doseq [p (take 20 added)]   (println (str "  new (not yet catalogued): " p)))
    (if (or (seq missing) (seq changed))
      (die! "b2-catalog verify: FAIL")
      (println (str "b2-catalog verify: OK"
                    (when (seq added) " (new objects present; re-run --generate)"))))))

(let [gen? (some #{"--generate"} argv)
      ver? (some #{"--verify"} argv)]
  (when-not (or gen? ver?)
    (die! "one of --generate / --verify is required"))
  (-> (list-objects)
      (.then (fn [live] (if gen? (write-catalog! live) (verify! live))))
      (.catch (fn [e] (die! (str "b2-catalog: " e))))))
