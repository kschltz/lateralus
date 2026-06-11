(ns kschltz.agent.plugins.remember
  "Remember tool plugin — adds the :remember tool wired to the
   agent's memory store. Wires up the search fn dynamically from
   the agent's memory-store at registration time."
  (:require [kschltz.agent.memory :as memory]
            [kschltz.agent.tools.remember :as remember]))

(defn plugin
  "Build a remember plugin bound to the agent's memory store. The
   returned plugin's :plugin/register fn reads the agent state and
   wires the search fn to the live memory-store."
  []
  {:plugin/name :remember
   :plugin/doc  "Adds the :remember tool, wired to memory recall."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (let [search-fn (when (:memory-store state)
                       (fn [{:keys [query limit]}]
                         (memory/retrieve-relevant
                          {:backend     (:memory-backend state)
                           :store (:memory-store state)
                           :session-id  (:session-id state)
                           :query       query
                           :limit       (or limit 5)})))]
       (update state :tools (fnil conj [])
               (remember/remember-tool {:search-fn search-fn}))))})
