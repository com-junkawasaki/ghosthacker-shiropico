(ns shiropico-produce.legs
  "Pure legs reporting — the shape `loop-ka.evaluate` grades.

  A leg answers the question `evaluate` is actually asking: **did this leg's
  generative step run, or did the pipeline fall back?**

  | scene | video leg |
  |---|---|
  | this run rendered it | the backend that served it |
  | failed, or never attempted | `:placeholder` |

  An earlier version derived legs from *whether an env var was set*. That is a
  claim about configuration, not about work — it reported a served leg for a URL
  nothing was listening on.

  ## Voice

  shiropico is a video channel, so unlike a manga it genuinely HAS a voice leg
  per dialogue line — and as of 2026-07-31 a speech backend exists
  (`kokoro-http` on the fleet head node), so the leg is a result rather than a
  standing `:silent`.

  | line | voice leg |
  |---|---|
  | this run spoke it | the backend that served it |
  | failed, or no text, or no backend | `:silent` |

  Empty was never right here — that is a manga's shape. A line that could not be
  spoken should be visible as degraded, not absent."
  (:require [clojure.string :as str]))

(defn leg
  "One outcome -> its leg. `outcome` is
  `{:status :rendered|:spoken|:failed|:skipped, :backend kw}`."
  [{:keys [status backend]} fallback]
  (case status
    (:rendered :spoken) (or backend fallback)
    fallback))

(defn scene-leg [o] (leg o :placeholder))

(defn report
  "Scene outcomes + line outcomes -> the `:legs` map the loop reads.

  `:video` indexes scenes and `:voice` indexes dialogue lines, so the two
  vectors have different lengths (episode 11: 23 and 61). `loop-ka.evaluate`
  treats them as independent, so that is correct rather than a mismatch — but a
  reader of `silent-shots` for this channel is looking at LINE indices."
  [scene-outcomes line-outcomes scenes]
  {:video (mapv scene-leg scene-outcomes)
   :voice (mapv #(leg % :silent) line-outcomes)
   :bed false
   :sfx (vec (keep :shot/sfx scenes))
   :overlays (count (keep :shot/fx scenes))})

(defn dry-outcomes
  "Units -> outcomes for a run that produced nothing."
  [units]
  (mapv (constantly {:status :skipped}) units))

(defn counts [outcomes]
  {:rendered (count (filter #(= :rendered (:status %)) outcomes))
   :spoken (count (filter #(= :spoken (:status %)) outcomes))
   :failed (count (filter #(= :failed (:status %)) outcomes))
   :skipped (count (filter #(= :skipped (:status %)) outcomes))})
