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

  ## Voice is `:silent`, and that is not a configuration problem

  shiropico is a video channel, so unlike a manga it genuinely HAS a voice leg
  per dialogue line. There is simply no TTS wired: murakumo's `:tts` backend is
  `:via :proc` (CosyVoice2/Kokoro), not an HTTP endpoint, and nothing was
  answering on the fleet head node when this was written. So every voice leg is
  `:silent` and the loop grades the run `:degraded` and holds it.

  That is correct and should stay visible. Reporting anything else — or leaving
  `:voice` empty so the run grades `:thin` — would hide a missing half of the
  pipeline behind a passing verdict. Empty is right for a manga, which has no
  voice leg at all; it is wrong here, where the leg exists and is not being run."
  (:require [clojure.string :as str]))

(defn scene-leg
  [{:keys [status backend]}]
  (case status
    :rendered (or backend :comfy)
    :placeholder))

(defn report
  "Scene outcomes + dialogue lines -> the `:legs` map the loop reads.

  `:video` indexes scenes and `:voice` indexes dialogue lines, so the two
  vectors have different lengths (episode 11: 23 and 61). `loop-ka.evaluate`
  treats them as independent, so that is correct rather than a mismatch — but a
  reader of `silent-shots` for this channel is looking at LINE indices."
  [scene-outcomes lines scenes]
  {:video (mapv scene-leg scene-outcomes)
   ;; No TTS is wired; see the ns docstring. A line with no text would be silent
   ;; regardless, so both reasons land on the same honest value.
   :voice (mapv (constantly :silent) lines)
   :bed false
   :sfx (vec (keep :shot/sfx scenes))
   :overlays (count (keep :shot/fx scenes))})

(defn dry-outcomes
  "Scenes -> outcomes for a run that renders nothing."
  [scenes]
  (mapv (constantly {:status :skipped}) scenes))

(defn counts [outcomes]
  {:rendered (count (filter #(= :rendered (:status %)) outcomes))
   :failed (count (filter #(= :failed (:status %)) outcomes))
   :skipped (count (filter #(= :skipped (:status %)) outcomes))})
