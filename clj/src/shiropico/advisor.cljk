(ns shiropico.advisor
  "The *contained intelligence node*: it renders a cut's keyframe and returns
  a PROPOSAL, never a committed record. Wraps `shiropico.render/render-cut`;
  every output is censored downstream by `shiropico.policy` before anything
  touches the SSoT. Mirrors `tsumugu.mangallm`'s Advisor protocol shape,
  simplified: tsumugu's coscientist ran a multi-strategy bonus-tag tournament
  over manga storyboard panel text fields (`:colorNote`/`:narration`/
  `:description`) — animeka's cut domain has no equivalent free-text
  strategy space to tournament over (a cut's render spec is a direct
  prompt/negative/seed, not derived from several competing text sources), so
  this advisor does one real render and derives confidence from whether it
  actually reached a gateway (`:source \"gateway\"`) or degraded to the
  offline placeholder (`:source \"stub\"`) — an honest signal grounded in
  what actually happened, not an invented tournament score.

  Proposal shape:
    {:summary    str   ; human-facing description of the render outcome
     :rationale  str   ; why this confidence
     :cites      [kw…] ; request fields the render drew from
     :effect     :commit-cut | :noop
     :value      {…}   ; the render result (:image-b64 :mime :source :seed)
     :cut-id     str
     :confidence 0..1} ; 1.0 = real gateway render, 0.3 = stub, 0.0 = no such cut"
  (:require [shiropico.render :as render]
            [shiropico.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(def ^:private stub-confidence
  "A stub (offline placeholder) render is never mistaken for a real one —
  fixed below the PolicyGovernor's confidence floor so it always escalates,
  never silently auto-commits."
  0.3)

(defn- advise* [st {:keys [cut-id episode-id prompt negative seed model]}]
  (let [ep (store/episode st episode-id)
        cut (and ep (first (filter #(= cut-id (:id %)) (:cuts ep))))]
    (cond
      (nil? ep)
      {:summary (str "episode " episode-id " not found") :rationale "no such episode"
       :cites [] :effect :noop :cut-id cut-id :confidence 0.0}

      (nil? cut)
      {:summary (str "cut " cut-id " not found in episode " episode-id)
       :rationale "no such cut in the episode's cut list" :cites [] :effect :noop
       :cut-id cut-id :confidence 0.0}

      :else
      (let [result (render/render-cut (render/fresh-scratch)
                                       {:prompt prompt :negative negative :seed seed :model model})
            gateway? (= "gateway" (:source result))]
        {:summary (str "cut " cut-id (if gateway? " rendered via gateway" " rendered as offline stub"))
         :rationale (if gateway?
                      "reached a configured ComfyUI gateway — real pixels"
                      "no ComfyUI gateway configured (or it errored) — placeholder only, needs review")
         :cites (vec (remove nil? [:prompt (when negative :negative) (when seed :seed)]))
         :effect :commit-cut
         :value (assoc result :high-stakes? (boolean (:high-stakes? cut)))
         :cut-id cut-id
         :confidence (if gateway? 1.0 stub-confidence)}))))

(defn render-advisor
  "The render-backed advisor. Default everywhere — there is no free-write
  LLM in this pipeline, only a deterministic render call + an honest
  source-grounded confidence."
  []
  (reify Advisor (-advise [_ st req] (advise* st req))))

(defn trace
  "Decision-grounded audit record — why this confidence, for evaluation
  appeals / publish audits."
  [request proposal]
  {:t          :shiropico-proposal
   :op         (:op request)
   :cut        (:cut-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
