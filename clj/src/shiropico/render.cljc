(ns shiropico.render
  "Cut keyframe rendering for the shiropico actor, built directly on
  `genapp.comfy` (the shared LangGraph-app scaffolding extracted from
  `ai-gftd-mangaka`/`ai-gftd-animeka`, see
  90-docs/adr/2607011900-genapp-clj-mangaka-animeka-commons.md). shiropico
  does not call into `ai-gftd-animeka` itself (an actor should not depend on
  another app's deploy artifact) — it is a third, independent consumer of
  the same generic engine, with its own baked-in config, exactly the pattern
  that ADR anticipated.

  With no configured gateway (COMFYUI_URL/COMFYUI_API_KEY) this degrades to
  a placeholder SVG, same as mangaka/animeka — the actor's containment node
  (`shiropico.advisor`) reads the render result's `:source` field
  (\"gateway\" vs \"stub\") as its confidence signal for the PolicyGovernor."
  (:require [genapp.comfy :as comfy]))

(def default-ckpt #?(:clj (or (System/getenv "SHIROPICO_DEFAULT_CKPT") "animagine-xl-4.0.safetensors")
                     :cljs "animagine-xl-4.0.safetensors"))

(def ^:private placeholder {:width 1024 :height 1024 :label "shiropico cut (stub)"})

(def ^:private cfg
  {:node-type "ShiropicoKSampler" :default-ckpt default-ckpt
   :default-width 1024 :default-height 1024})

(defn- gateway-render [spec]
  (comfy/gateway-render-from-env
   {:url-vars ["SHIROPICO_COMFY_URL" "COMFYUI_URL"] :key-vars ["SHIROPICO_COMFY_API_KEY" "COMFYUI_API_KEY"]
    :require-api-key? false :placeholder placeholder}
   spec))

(def ^:private ksampler-node (comfy/ksampler-node (assoc cfg :gateway-render gateway-render)))
(defn- registry [] (comfy/registry ksampler-node))

(defn render-cut
  "Renders one cut's keyframe for `spec` ({:prompt :negative :seed :model}).
  Returns the sampler output map {:image-b64 :mime :source :seed} plus
  {:run-id :cached}. `:source` is \"gateway\" for a real render, \"stub\" for
  the offline placeholder — the caller (`shiropico.advisor`) treats that
  distinction as a confidence signal, never silently as equivalent."
  [scratch-conn spec]
  (comfy/render (registry) cfg scratch-conn spec))

(defn fresh-scratch [] (comfy/fresh-scratch))
