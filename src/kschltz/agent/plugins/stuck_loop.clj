(ns kschltz.agent.plugins.stuck-loop
  "Stuck-loop detection plugin — contributes the stuck-loop-detector
   as a `:stuck-loop` slot interceptor.

   Plugins that want to override or augment stuck-loop detection can
   include their own `:stuck-loop` slot interceptors. This plugin is
   the default; `plugins/defaults.clj` includes it in the default
   bundle so the detector is always active unless explicitly replaced."
  (:require [kschltz.agent.interceptors :as ix]))

(def plugin
  "Plugin: adds the stuck-loop detector as a `:stuck-loop` slot
   interceptor. Runs after every tool-call iteration when wired into
   the exchange chain via `plugin/assemble-chain`."
  {:plugin/name :stuck-loop-detector
   :plugin/doc  "Detects when the agent is making no forward progress
                 with its tool calls (AND-2-of-3 of: low hash diversity,
                 high args similarity, low result novelty). On detection,
                 terminates the turn and surfaces a stuck-loop event to
                 the user."
   :plugin/slots
   {:stuck-loop [ix/stuck-loop-detector]}})
