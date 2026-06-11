(ns kschltz.agent.plugins.repl
  "REPL tool plugin — adds the :repl-eval tool to the agent. Also
  exposes a `nrepl` variant for callers that want the nREPL tool."
  (:require [kschltz.agent.tools.repl :as repl]))

(def plugin
  "Plugin: adds the :repl-eval tool (local Clojure REPL with timeout +
   delimiter repair)."
  {:plugin/name :repl-eval
   :plugin/doc  "Adds the :repl-eval tool."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (repl/repl-eval-tool)))})

(defn nrepl-plugin
  "Variant of the repl plugin that registers the nREPL tool instead
  of the local REPL eval tool."
  []
  {:plugin/name :repl-nrepl
   :plugin/doc  "Adds the :repl-nrepl tool."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (repl/repl-nrepl-tool)))})
