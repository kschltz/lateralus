(ns kschltz.agent.plugins.portal
  "Portal data-visualization tool plugin — adds the :visualize tool
   to the agent."
  (:require [kschltz.agent.tools.portal :as portal]))

(def plugin
  "Plugin: adds the :visualize tool (Portal inspector display)."
  {:plugin/name :portal-visualize
   :plugin/doc  "Adds the :visualize tool for Portal inspector display."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (portal/visualize-tool)))})
