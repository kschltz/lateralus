(ns kschltz.agent.plugins.web
  "Web search tool plugin — adds the :web tool to the agent."
  (:require [kschltz.agent.tools.web :as web]))

(def plugin
  "Plugin: adds the :web search tool (Mojeek/Startpage/Wikipedia)."
  {:plugin/name :web-search
   :plugin/doc  "Adds the :web search tool."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (web/web-search-tool)))})
