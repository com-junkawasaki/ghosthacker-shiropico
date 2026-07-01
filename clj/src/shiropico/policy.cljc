(ns shiropico.policy
  "PolicyGovernor — the independent censor that earns shiropico.advisor's
  render proposal the right to commit. Ported from `tsumugu.policy` /
  `talent.policy`'s shape (confidence-floor/high-stakes → HOLD or ESCALATE,
  never auto-override); the checks themselves are adapted to this actor's
  domain (a real ComfyUI gateway render vs an offline stub, not a
  coscientist tournament survivor count).

  Two checks, in priority order. The first is HARD (a human approver cannot
  override it — there is nothing to approve, no cut/episode to render for).
  The second is SOFT (asks a human to look; they may approve).

    1. No such cut/episode      — `shiropico.advisor` returned :effect :noop
                                  → HOLD (nothing to commit).
    2. Confidence / high-stakes — a stub (non-gateway) render, or a cut
                                  flagged `:high-stakes?` (e.g. the SHIRO &
                                  PICO henshin-bank transformation sequence,
                                  reused across every episode — SERIES-BIBLE.md
                                  §henshin-bank.json) → ESCALATE for human
                                  review even when the render itself
                                  succeeded.")

(def confidence-floor 0.4)

(defn check
  "Censors a shiropico.advisor proposal. Returns {:ok? bool :violations [..]
  :confidence c :escalate? bool :hard? bool :high-stakes? bool}."
  [_request _context proposal]
  (let [hard (cond-> []
               (= :noop (:effect proposal))
               (conj {:rule :no-such-cut
                      :detail "no cut/episode to render for -- nothing to commit"}))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (get-in proposal [:value :high-stakes?]))
        hard? (boolean (seq hard))]
    {:ok?         (and (not hard?) (not low?) (not stakes?))
     :violations  hard
     :confidence  conf
     :hard?       hard?
     :escalate?   (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :cut        (:cut-id request)
   :actor      (:actor-id context)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
