(ns shiropico-produce.shotlist
  "Pure shotlist reading. Nothing here touches a filesystem or an env var, so
  the whole shot derivation is testable without a render backend.

  A shotlist in this repo is datomize-style tx-data whose attributes are
  namespaced per episode AND language:

      {:db/id -1
       :shotlist.episode11.en/type        \"scene\"
       :shotlist.episode11.en/scene_key   \"geothermal_plant_dawn\"
       :shotlist.episode11.en/scene_prompt \"Aerial wide shot of ...\"
       :shotlist.episode11.en/sfx         \"ambient\"
       :shotlist.episode11.en/fx          nil
       :shotlist.episode11.en/dur         3.5}

  The namespace is per-file rather than shared, so a reader cannot use one
  fixed keyword set. `attr-ns` reconstructs it from the episode number and the
  language, which is why the catalog plan carries the template instead of every
  plan repeating ten literal keyword sets."
  (:require [clojure.string :as str]))

(defn attr-ns
  "Episode number + language -> the attribute namespace those shotlist entries
  use. `template` comes from the plan (`:plan/attr-namespace-template`) so a
  future episode that names its attributes differently does not need a code
  change here.

  Substitution is positional over the `%s` holes, NOT `str/replace` per value:
  `str/replace` with a string match replaces every occurrence, so
  substituting the episode first turned `shotlist.episode%s.%s` into
  `shotlist.episode11.11` and every shot silently read as a non-scene. The
  shotlist parsed to zero rows and the producer would have reported an empty
  episode as a fact about the content."
  [template episode lang]
  (let [parts (str/split (str template) #"%s" -1)
        vals [(str episode) (name lang)]]
    (apply str (interleave parts (concat (take (dec (count parts)) vals)
                                         (repeat ""))))))

(defn- attr [ns-str k]
  (keyword ns-str (name k)))

(defn row-type
  "A shotlist carries two kinds of row and they are NOT interchangeable:
  `scene` rows drive the image legs, `dialogue` rows drive the voice legs.
  Episode 11 is 23 scenes and 61 dialogue lines. Counting rows without reading
  :type reports 84 shots for an episode that has 23 — which is how the first
  version of the catalog plan came to claim a fabricated shot count."
  [ns-str row]
  (get row (attr ns-str :type)))

(defn shots
  "Shotlist tx-data + attribute namespace -> ordered vector of scene shots.

  Keys are de-namespaced here so everything downstream works on a stable shape
  regardless of which episode or language it came from."
  [rows ns-str]
  (->> rows
       (filter map?)
       (filter #(= "scene" (row-type ns-str %)))
       (mapv (fn [row]
               {:shot/key    (get row (attr ns-str :scene_key))
                :shot/prompt (get row (attr ns-str :scene_prompt))
                :shot/sfx    (get row (attr ns-str :sfx))
                :shot/fx     (get row (attr ns-str :fx))
                :shot/dur    (get row (attr ns-str :dur))}))))

(defn dialogue
  "Shotlist tx-data -> ordered vector of dialogue lines (the voice legs).

  Attribute names are the ones the files actually use, read out of
  `episode-11-shotlist-v2.en.edn` rather than guessed: `speaker`, `text`,
  `shiro_emo`, `pico_emo`, `dur`. A line kept with nil text still counts, so
  its voice leg is honestly `:silent` instead of the line vanishing from the
  total."
  [rows ns-str]
  (->> rows
       (filter map?)
       (filter #(= "dialogue" (row-type ns-str %)))
       (mapv (fn [row]
               {:line/speaker (get row (attr ns-str :speaker))
                :line/text (get row (attr ns-str :text))
                :line/emo {:shiro (get row (attr ns-str :shiro_emo))
                           :pico (get row (attr ns-str :pico_emo))}
                :line/dur (get row (attr ns-str :dur))}))))

(defn renderable?
  "A shot with no prompt cannot produce an image. Reporting it as attempted
  would be the exact failure loop-ka-production exists to catch, so the caller
  needs to distinguish it before assigning a leg."
  [shot]
  (not (str/blank? (str (:shot/prompt shot)))))
