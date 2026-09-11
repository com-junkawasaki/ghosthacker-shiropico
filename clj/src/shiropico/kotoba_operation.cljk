(ns shiropico.kotoba-operation
  "JVM application host for OperationActor.

  The publish decision is authored in Kotoba and compiled once to a checked
  application artifact. Effects remain explicit host capabilities: the only
  filesystem authority here is one preconfigured checkpoint file beneath one
  preconfigured root; guest-derived paths are never accepted."
  (:require [clojure.java.io :as io]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [shiropico.operation :as operation])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private source-resource "shiropico/publish_decision.kotoba")

(defonce ^:private artifact
  (delay
    (let [resource (or (io/resource source-resource)
                       (throw (ex-info "Kotoba decision source not found"
                                       {:resource source-resource})))]
      (compiler/compile-source (slurp resource)
                               :wasm32-browser-kotoba-v1))))

(def ^:private code->disposition {0 :hold, 1 :escalate, 2 :commit})

(defn artifact-bytes
  "The emitted Wasm bytes, useful for deployment and artifact attestation."
  []
  (:bytes @artifact))

(defn decide
  "Authoritative Kotoba application decision adapter for OperationActor."
  [_request context proposal verdict]
  (let [phase-number (:phase context 1)
        code (ir/execute (:kir @artifact) 'decide
                         [(if (= :noop (:effect proposal)) 0 1)
                          (long (Math/round (* 1000.0
                                               (double (:confidence proposal 0.0)))))
                          (if (:high-stakes? verdict) 1 0)
                          phase-number])
        disposition (or (code->disposition code)
                        (throw (ex-info "Kotoba returned an invalid disposition"
                                        {:code code})))]
    {:disposition disposition
     :reason (cond
               (and (= :hold disposition) (zero? phase-number)) :phase-disabled
               (and (= :escalate disposition) (= 1 phase-number)) :phase-approval
               :else nil)}))

(defn atomic-checkpoint
  "Returns a least-authority checkpoint capability fixed to `relative-file`.
  The target must stay beneath `root`; writes use temp+atomic replace."
  [root relative-file]
  (let [^Path root-path (.normalize (.toAbsolutePath (.toPath (io/file root))))
        ^Path target (.normalize (.resolve root-path (str relative-file)))]
    (when (or (= root-path target) (not (.startsWith target root-path)))
      (throw (ex-info "Checkpoint target escapes its capability root"
                      {:root (str root-path) :target (str target)})))
    (fn [checkpoint]
      (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
      (let [^Path temporary
            (Files/createTempFile (.getParent target) ".shiropico-" ".tmp"
                                  (make-array FileAttribute 0))]
        (try
          (Files/write temporary
                       (.getBytes (pr-str checkpoint) StandardCharsets/UTF_8)
                       (make-array java.nio.file.OpenOption 0))
          (try
            (Files/move temporary target
                        (into-array StandardCopyOption
                                    [StandardCopyOption/ATOMIC_MOVE
                                     StandardCopyOption/REPLACE_EXISTING]))
            (catch java.nio.file.AtomicMoveNotSupportedException _
              (Files/move temporary target
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))))
          nil
          (finally
            (Files/deleteIfExists temporary)))))))

(defn build
  "Builds the canonical JVM actor with Kotoba as the decision authority.
  Pass `:checkpoint!` from `atomic-checkpoint` for durable commit intent."
  [store & [opts]]
  (operation/build store (assoc (or opts {}) :decision decide)))
