(ns shiropico-produce.tts
  "The IO boundary to a speech backend.

  `kokoro-http` on the murakumo fleet head node speaks an OpenAI-shaped subset:

      POST /v1/audio/speech  {input, voice, speed, lang}  -> audio/wav

  Its job beyond moving bytes is to be honest about failure: every path that
  does not end with a wav on disk returns `{:ok? false}` with a reason, so the
  caller reports a `:silent` leg rather than a spoken one."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(defn base-url
  "Env -> the speech root, or nil.

  `aget` rather than `js->clj`: under nbb the latter yields a Function and every
  lookup comes back nil, which would report every leg degraded no matter how the
  node is configured."
  []
  (let [v (aget js/process.env "TTS_URL")]
    (when-not (str/blank? (str v)) (str/replace (str v) #"/+$" ""))))

(defn reachable?
  [base]
  (-> (js/fetch (str base "/health"))
      (.then #(.-ok %))
      (.catch (fn [_] false))))

(def voices
  "Speaker -> Kokoro voice. SHIRO and PICO are the two leads; anything else
  (narration, incidental) gets the narration voice rather than silently
  borrowing a lead's."
  {"shiro" "af_heart" "pico" "af_bella"})

(def narration-voice "af_nicole")

(defn voice-for [speaker]
  (get voices (str/lower-case (str speaker)) narration-voice))

(defn speak!
  "A line -> a promise of {:ok? bool :file path :reason ...}."
  [{:keys [base out-dir line idx lang]}]
  (let [text (str (:line/text line))]
    (if (str/blank? text)
      ;; Nothing to say is not a backend failure — but it is still not a spoken
      ;; leg, so it comes back as not-ok with a reason that says which.
      (js/Promise.resolve {:ok? false :reason :no-text})
      (-> (js/fetch (str base "/v1/audio/speech")
                    #js {:method "POST"
                         :headers #js {"content-type" "application/json"}
                         :body (js/JSON.stringify
                                (clj->js {:input text
                                          :voice (voice-for (:line/speaker line))
                                          :lang (or lang "en-us")}))})
          (.then (fn [r]
                   (if-not (.-ok r)
                     (.then (.text r) (fn [t] (throw (ex-info "speech failed"
                                                              {:status (.-status r)
                                                               :body (subs (str t) 0 200)}))))
                     (.arrayBuffer r))))
          (.then (fn [buf]
                   (fs/mkdirSync out-dir #js {:recursive true})
                   (let [file (path/join out-dir (str (inc idx) "-"
                                                      (or (:line/speaker line) "x") ".wav"))]
                     (fs/writeFileSync file (js/Buffer.from buf))
                     {:ok? true :file file :bytes (.-byteLength buf)})))
          (.catch (fn [e] {:ok? false :reason :error :error (ex-message e)}))))))
