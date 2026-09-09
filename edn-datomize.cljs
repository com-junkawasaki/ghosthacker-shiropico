#!/usr/bin/env nbb
;; edn-datomize.cljs — EDN → Datomic/Datascript tx-data 変換ツール。
;;
;; 2026-08-11 に edn-datomize.bb から移植。babashka は ADR-2607173000 で
;; script host から退役しており、この repo に残っていた最後の 1 本だった。
;; ロジックは 1:1（classify / attr-value / 名前空間付与 / schema マージ / ADR の
;; 汎用変換）。変わったのは host だけ：clojure.java.io → node fs/path、
;; clojure.java.shell → child_process。
;;
;; 「datomic/datascript query 可能」の定義: ファイルのトップレベルが
;; (d/transact conn (edn/read-string (slurp file))) にそのまま渡せる
;; tx-data ベクタ（entity-map のベクタ、各 map は :db/id を持つ）であること。
;;
;; マップ1個のファイルは [{...:db/id -1}] に包み、既存キーはファイル種別ごとの
;; 名前空間を付けた属性名にリネームする。既にベクタ・オブ・マップのファイルは
;; 各要素に :db/id -1,-2,... を振る（wrap-vec）。値が Datomic の scalar
;; valueType に収まらないもの（入れ子 map、map を含む vector 等）は pr-str した
;; 文字列として保持する（valueType=string の "blob" 属性）。属性定義は
;; schema.edn（リポジトリルート）に自動登録する。
;;
;; 使い方:
;;   nbb edn-datomize.cljs wrap-map <path> <ns>     — map 1個のファイルを変換
;;   nbb edn-datomize.cljs wrap-vec <path> <ns>     — vector-of-maps ファイルを変換
;;   nbb edn-datomize.cljs adr-dir  <dir>           — ADR frontmatter/body を変換
;;   nbb edn-datomize.cljs adr-file <path>          — ADR 1ファイルを変換
;;   nbb edn-datomize.cljs schema-from-tx <path>... — 既に tx-data のファイルから
;;                                                    属性を schema.edn へ登録する
;;
;; schema-from-tx は移植で足した。手で書いた tx-data（character-design-spec.edn 等、
;; 2026-08-10 の JSON 移行で入ったもの）は wrap-* が "already tx-data" で正しく
;; skip するため、**その属性が schema.edn に一度も載らない**。生成物である
;; schema.edn が実データより狭いのは、それ自体が静かな嘘なので塞ぐ。

(ns edn-datomize
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]))

;; nbb の process.argv は [node nbb <script>.cljs & args...]。位置引数を使うので
;; script 名の次から取る（"drop 2" だと script 名が第1引数になり、mode が常に
;; 未知になって usage で落ちる — 移植で実際に踏んだ）。
(def argv
  (let [a (vec (js->clj js/process.argv))
        i (first (keep-indexed (fn [i x] (when (str/ends-with? (str x) ".cljs") i)) a))]
    (vec (drop (inc (or i 1)) a))))

(def root
  (str/trim (str (.-stdout (.spawnSync cp "git" #js ["rev-parse" "--show-toplevel"]
                                       #js {:encoding "utf8"})))))

(defn- schema-path [] (path/join root "schema.edn"))
(defn- slurp* [p] (str (fs/readFileSync p "utf8")))
(defn- spit* [p s] (fs/writeFileSync p s))
(defn- slurp-edn [p] (edn/read-string (slurp* p)))

(defn already-tx-data?
  "既に [{...:db/id ...} ...] 形式に変換済みか判定（再実行の冪等性用）。"
  [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

(defn classify
  "値から Datomic :db/valueType + :db/cardinality を推定する。scalar に収まらない
   値は :blob true を返す(pr-str して string 化)。"
  [v]
  (cond
    (string? v)  {:type :db.type/string  :card :db.cardinality/one}
    (boolean? v) {:type :db.type/boolean :card :db.cardinality/one}
    ;; cljs では 1 も 1.0 も number。整数判定を先に置く（bb 版の integer?/double? と同順）
    (and (number? v) (integer? v)) {:type :db.type/long   :card :db.cardinality/one}
    (number? v)  {:type :db.type/double  :card :db.cardinality/one}
    (keyword? v) {:type :db.type/keyword :card :db.cardinality/one}
    (nil? v)     {:type :db.type/string  :card :db.cardinality/one}
    (and (coll? v) (empty? v))          {:type :db.type/string :card :db.cardinality/many}
    (and (coll? v) (every? string? v))  {:type :db.type/string  :card :db.cardinality/many}
    (and (coll? v) (every? keyword? v)) {:type :db.type/keyword :card :db.cardinality/many}
    (and (coll? v) (every? #(and (number? %) (integer? %)) v))
    {:type :db.type/long :card :db.cardinality/many}
    :else {:type :db.type/string :card :db.cardinality/one :blob true}))

(defn attr-value [v]
  (if (:blob (classify v)) (pr-str v) v))

(defn namespaced-key [ns-name k]
  (keyword ns-name (name k)))

(defn entity-from-map [content ns-name]
  (into {:db/id -1}
        (map (fn [[k v]] [(namespaced-key ns-name k) (attr-value v)]))
        content))

(defn schema-attrs [content ns-name]
  (for [[k v] content]
    (let [{:keys [type card]} (classify v)]
      {:db/ident (namespaced-key ns-name k) :db/valueType type :db/cardinality card})))

(defn load-schema []
  (if (fs/existsSync (schema-path)) (slurp-edn (schema-path)) []))

(defn merge-schema! [new-attrs]
  (let [existing (load-schema)
        by-ident (into {} (map (juxt :db/ident identity)) existing)
        merged-by-ident (reduce (fn [acc {:keys [db/ident] :as attr}]
                                  (if (contains? acc ident) acc (assoc acc ident attr)))
                                by-ident new-attrs)
        merged (vec (sort-by (comp str :db/ident) (vals merged-by-ident)))]
    (spit* (schema-path)
           (str ";; schema.edn — Datomic/Datascript 互換スキーマ定義（自動生成 by edn-datomize.cljs）\n"
                ";; :db/ident 属性定義のリスト。Datomic 固有キー(:db.install/_attribute 等)は使わない。\n"
                ";; 手編集禁止 — 再生成すると上書きされる。\n\n"
                (pr-str merged) "\n"))
    merged))

(defn wrap-map! [rel-path ns-name]
  (let [f (path/join root rel-path)
        content (slurp-edn f)]
    (if (already-tx-data? content)
      (println "skip (already tx-data):" rel-path)
      (let [entity (entity-from-map content ns-name)]
        (spit* f (pr-str [entity]))
        (merge-schema! (schema-attrs content ns-name))
        (println "wrapped" rel-path "->" (count entity) "attrs, ns=" ns-name)))))

(defn already-tx-data-vec? [content]
  (and (vector? content) (seq content) (every? map? content)
       (every? #(contains? % :db/id) content)))

(defn entity-from-record [m ns-name idx]
  (into {:db/id (- (inc idx))}
        (map (fn [[k v]] [(namespaced-key ns-name k) (attr-value v)]))
        m))

(defn wrap-vec! [rel-path ns-name]
  (let [f (path/join root rel-path)
        content (slurp-edn f)]
    (cond
      (already-tx-data-vec? content) (println "skip (already tx-data):" rel-path)
      (not (and (vector? content) (every? map? content)))
      (println "SKIP (not a vector-of-maps, needs manual review):" rel-path)
      :else
      (let [entities (vec (map-indexed (fn [i m] (entity-from-record m ns-name i)) content))]
        (spit* f (pr-str entities))
        (merge-schema! (distinct (mapcat #(schema-attrs % ns-name) content)))
        (println "wrapped" rel-path "->" (count entities) "entities, ns=" ns-name)))))

;; ---------- ADR ----------
;; :frontmatter があればトップレベルへマージし、名前空間の無いキーには :adr/ を付与、
;; 既に名前空間付きのキーはそのまま。:related/:supersedes/:superseded_by は ADR-id と
;; 生パスが混在する実データなので lookup-ref 化せず文字列 vector のまま持つ。

(defn adr-key [k] (if (namespace k) k (keyword "adr" (name k))))

(defn transform-adr-generic [content]
  (let [fm (:frontmatter content)
        base (dissoc content :frontmatter)
        entries (concat (when (map? fm) (seq fm)) (seq base))]
    [(into {:db/id -1} (map (fn [[k v]] [(adr-key k) (attr-value v)])) entries)]))

(defn adr-schema-for [entity]
  (for [[k v] (dissoc entity :db/id)]
    (let [{:keys [type card]} (classify v)]
      {:db/ident k :db/valueType type :db/cardinality card})))

(defn adr-file! [f report]
  (try
    (let [content (slurp-edn f)]
      (cond
        (already-tx-data? content)
        (do (println "skip (already tx-data):" (str f)) (swap! report update :skipped conj (str f)))
        (not (map? content))
        (do (println "skip (not a frontmatter map, likely already data payload):" (str f))
            (swap! report update :skipped conj (str f)))
        :else
        (let [tx (transform-adr-generic content)]
          (spit* f (pr-str tx))
          (swap! report update :attrs into (mapcat adr-schema-for tx))
          (swap! report update :ok conj (str f)))))
    (catch :default e
      (println "SKIP (parse/transform error):" (str f) "->" (str e))
      (swap! report update :errors conj [(str f) (str e)]))))

(defn- edn-files-under [dir]
  (letfn [(walk [d]
            (mapcat (fn [n]
                      (let [p (path/join d n)]
                        (if (.isDirectory (fs/statSync p)) (walk p) [p])))
                    (js->clj (fs/readdirSync d))))]
    (sort (filter #(str/ends-with? % ".edn") (walk dir)))))

(defn adr-dir! [dir]
  (let [files (edn-files-under (path/join root dir))
        report (atom {:ok [] :skipped [] :errors [] :attrs []})]
    (doseq [f files] (adr-file! f report))
    (merge-schema! (:attrs @report))
    (println "done." (count files) "files:" (count (:ok @report)) "transformed,"
             (count (:skipped @report)) "skipped," (count (:errors @report)) "errors.")
    (when (seq (:errors @report))
      (println "=== ERRORS (left untouched, pre-existing data issues) ===")
      (doseq [[f m] (:errors @report)] (println " " f "->" m)))
    (when (seq (:skipped @report))
      (println "=== SKIPPED ===")
      (doseq [f (:skipped @report)] (println " " f)))
    @report))

(defn schema-from-tx!
  "既に tx-data のファイルから属性を schema.edn へ登録する（ファイルは書き換えない）。
  wrap-* は already tx-data を skip するので、手で書いた tx-data の属性はこれが無いと
  schema.edn に一生載らない。"
  [rel-paths]
  (let [attrs (mapcat (fn [rp]
                        (let [content (slurp-edn (path/join root rp))]
                          (if-not (already-tx-data? content)
                            (do (println "SKIP (not tx-data):" rp) [])
                            (do (println "read" rp "->" (count content) "entities")
                                (mapcat adr-schema-for content)))))
                      rel-paths)
        before (count (load-schema))
        merged (merge-schema! (distinct attrs))]
    (println (str "schema.edn: " before " -> " (count merged) " attrs"))))

(let [[mode a & more] argv]
  (case mode
    "wrap-map" (wrap-map! a (first more))
    "wrap-vec" (wrap-vec! a (first more))
    "adr-dir"  (adr-dir! a)
    "adr-file" (let [report (atom {:ok [] :skipped [] :errors [] :attrs []})]
                 (adr-file! (path/join root a) report)
                 (merge-schema! (:attrs @report))
                 (println @report))
    "schema-from-tx" (schema-from-tx! (cons a more))
    (do (println (str "usage: nbb edn-datomize.cljs [wrap-map <path> <ns> | wrap-vec <path> <ns>"
                      " | adr-dir <dir> | adr-file <path> | schema-from-tx <path>...]"))
        (js/process.exit 1))))
