(ns shiropico-produce.legs
  "Pure legs reporting — the shape `loop-ka.evaluate` grades.

  The loop's whole reason for existing is that a pipeline can degrade and still
  emit a file that plays. So this namespace's contract is: **a leg names what
  actually ran, never what was intended.** If no image backend is configured,
  every video leg is `:placeholder` and the loop grades the run `:degraded` and
  holds it. That is the correct outcome, not a bug to work around — the wrong
  outcome would be reporting `:murakumo` because a URL was present in the plan.

  Leg vocabularies are fixed by `loop-ka.evaluate`:
    video  :murakumo | :comfy | :placeholder   (:placeholder is degraded)
    voice  :murakumo | :local  | :silent       (:silent is degraded)"
  (:require [clojure.string :as str]
            [shiropico-produce.shotlist :as shotlist]))

(defn image-leg
  "Which image backend actually served this shot.

  `backends` is what the caller could *reach*, already resolved — this fn does
  no probing, so a caller that guesses from env vars alone is the one making
  the claim, and the shape below makes that claim explicit and reviewable."
  [{:keys [murakumo comfy]} shot]
  (cond
    (not (shotlist/renderable? shot)) :placeholder
    murakumo :murakumo
    comfy    :comfy
    :else    :placeholder))

(defn voice-leg
  "A voice leg is per DIALOGUE LINE, not per scene. shiropico shotlists keep the
  two as separate row types (episode 11: 23 scenes, 61 lines), so indexing voice
  by scene would both mis-count and mislabel which thing went silent."
  [{:keys [murakumo local]} line]
  (cond
    (str/blank? (str (:line/text line))) :silent
    murakumo :murakumo
    local    :local
    :else    :silent))

(defn report
  "Scenes + dialogue + reachable backends -> the `:legs` map the loop reads.

  `:video` indexes scenes and `:voice` indexes dialogue lines. `loop-ka.evaluate`
  treats them as two independent vectors (`degraded-shots` / `silent-shots`
  return indices into their own list), so the differing lengths are correct
  rather than a mismatch — but a reader of `silent-shots` for this channel is
  looking at line indices, which is why it is stated here.

  `:sfx` is the cue list rather than a count because `loop-ka.evaluate` counts
  it itself; `:bed` is false and deliberately so — shiropico shotlists carry
  per-shot sfx and fx but no music bed, so claiming one would be the same lie in
  a different leg. A channel with no bed grades `:thin`, which is accurate."
  [scenes lines {:keys [image voice]}]
  {:video    (mapv #(image-leg image %) scenes)
   :voice    (mapv #(voice-leg voice %) lines)
   :bed      false
   :sfx      (vec (keep :shot/sfx scenes))
   :overlays (count (keep :shot/fx scenes))})
