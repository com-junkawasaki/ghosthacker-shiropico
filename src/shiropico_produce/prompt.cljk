(ns shiropico-produce.prompt
  "Prose scene_prompt -> the tag list an SDXL checkpoint responds to.

  These shotlists carry prose:

      \"Aerial wide shot of a massive geothermal power plant on a black lava
       plateau at near-arctic dawn, pale sky, quiet steam columns rising, ...\"

  Fed verbatim, the first render produced the plateau, the dawn and the teal
  data lines but **no power plant** — the subject was buried mid-clause in a
  long leading fragment and lost. SDXL weights early, short tokens.

  So: split on commas (the prose is already comma-delimited descriptive
  fragments), then split the long fragments again at the prepositions that
  separate a subject from its setting. Nothing is invented and nothing is
  dropped — this only re-segments what the author wrote, which is why it is a
  pure function with a test rather than a model call."
  (:require [kotoba.lang.text :as str]))

(def ^:private clause-split
  "Prepositional joints that separate a subject from where/when it sits.
  Longest first so ` on a ` wins before ` on `."
  [" of a " " of the " " on a " " on the " " at a " " at the "
   " in a " " in the " " of " " on " " at " " in "])

(defn- split-clause
  "One prose fragment -> shorter fragments, cut at the first joint only.

  First only, deliberately: cutting at every joint shreds
  `a black lava plateau at near-arctic dawn` into three fragments that each
  lose their referent. One cut moves the subject to the front, which is the
  problem being solved."
  [frag]
  (if-let [j (->> clause-split
                  (filter #(str/includes? frag %))
                  (sort-by #(str/index-of frag %))
                  first)]
    (let [i (str/index-of frag j)]
      [(subs frag 0 i) (subs frag (+ i (count j)))])
    [frag]))

(def ^:private noise
  "Leading articles carry no weight as a tag on their own."
  #{"a" "an" "the"})

(defn- clean [s]
  (-> (str s) str/trim (str/replace #"\s+" " ")))

(defn tags
  "Prose -> ordered tag vector.

  Long fragments are re-segmented; short ones are left alone, because
  `pale sky` is already a tag and cutting it would only make it worse."
  ([prose] (tags prose {}))
  ([prose {:keys [long-fragment] :or {long-fragment 40}}]
   (->> (str/split (str prose) #",")
        (map clean)
        (remove str/blank?)
        (mapcat (fn [frag]
                  (if (> (count frag) long-fragment) (split-clause frag) [frag])))
        (map clean)
        (remove str/blank?)
        (remove #(contains? noise (str/lower %)))
        vec)))

(def style-tags
  "Appended to every scene. The series is animation, and saying so is what keeps
  a photoreal checkpoint interpretation from creeping in. Not derived from the
  shotlist because it is a channel-wide craft decision, not a per-shot one."
  ["anime style" "cinematic" "detailed background"])

(defn positive
  "Scene -> the prompt to send."
  [shot]
  (into (tags (:shot/prompt shot)) style-tags))
