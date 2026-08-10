#!/usr/bin/env nbb
;; verify-spec-edn.cljs — 設定 EDN が datom 面に載る形のままであることを検査する。
;;
;;   nbb tools/verify-spec-edn.cljs
;;
;; 見るのは「EDN として読める」ではなく「transact できる」。この 2 つは違い、
;; 差は静かに出る — nil を 1 個書くと読めるが transact で落ちる（移行時に実際に
;; 踏んだ: JSON の "earCuff": null をそのまま持ってきて datascript が
;; "Cannot store nil as a value" で停止した）。
;;
;; 検査項目:
;;   1. トップレベルが entity map のベクタで、全てに :db/id がある
;;   2. :db/id が一意で負（tempid）
;;   3. nil 値の属性が無い（datom 面に欠測は「属性が無い」で表す）
;;   4. 全 entity に :source/dataset と :spec/kind がある（query の入口）
;;
;; 移行時（2026-08-10）の JSON→EDN は、JSON 側の scalar leaf が全て EDN 側に
;; 現れることを機械で確認してから .json を削除した（character-design 82/82、
;; henshin-bank 33/33、pipeline-specs 14/15 + 意図的変更 1）。その検査は入力の
;; .json が消えた時点で再実行できないので、ここには残していない。

(ns verify-spec-edn
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def specs ["character-design-spec.edn"
            "henshin-bank.edn"
            "pipeline-specs.edn"
            "jump/character-design-spec.edn"])

(defn nil-attrs [e] (keep (fn [[k v]] (when (nil? v) k)) e))

(defn check [path]
  (let [tx     (edn/read-string (fs/readFileSync path "utf8"))
        shape? (and (vector? tx) (seq tx) (every? map? tx) (every? #(contains? % :db/id) tx))
        ids    (map :db/id tx)
        uniq?  (and (= (count ids) (count (distinct ids))) (every? neg? ids))
        nils   (mapcat nil-attrs tx)
        no-ds  (remove :source/dataset tx)
        no-kind (remove :spec/kind tx)
        problems (cond-> []
                   (not shape?)   (conj "not tx-data (entity maps with :db/id)")
                   (not uniq?)    (conj "…:db/id が一意な負値でない")
                   (seq nils)     (conj (str "nil 値の属性: " (str/join ", " (map pr-str nils))))
                   (seq no-ds)    (conj (str ":source/dataset の無い entity " (count no-ds) " 件"))
                   (seq no-kind)  (conj (str ":spec/kind の無い entity " (count no-kind) " 件")))]
    (println (str (if (seq problems) "FAIL " "ok   ") path
                  "  (" (count tx) " entities, kinds: "
                  (str/join "/" (sort (distinct (keep :spec/kind tx)))) ")"))
    (doseq [p problems] (println (str "       " p)))
    (empty? problems)))

(let [results (doall (map check specs))]
  (println)
  (if (every? true? results)
    (println (str "PASS — " (count specs) " spec files are valid tx-data"))
    (do (println "FAIL") (js/process.exit 1))))
