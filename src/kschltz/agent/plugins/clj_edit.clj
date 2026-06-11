(ns kschltz.agent.plugins.clj-edit
  "clj-edit tool plugin — adds the :clj-edit tool for structured
   Clojure/EDN source editing via rewrite-clj."
  (:require [kschltz.agent.tools.rewrite :as rewrite]))

(def plugin
  "Plugin: adds the :clj-edit tool."
  {:plugin/name :clj-edit
   :plugin/doc  "Adds the :clj-edit tool for structural Clojure editing."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (rewrite/clj-edit-tool)))})
