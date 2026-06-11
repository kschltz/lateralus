(ns kschltz.agent.plugins.defaults
  "Default plugin bundle. Used by `make-agent` when no `:plugins` are
   supplied. Equivalent to the legacy `add-*-tool!` installers."
  (:require [kschltz.agent.plugins.clj-edit :as clj-edit]
            [kschltz.agent.plugins.file-edit :as file-edit]
            [kschltz.agent.plugins.portal :as portal]
            [kschltz.agent.plugins.remember :as remember]
            [kschltz.agent.plugins.repl :as repl]
            [kschltz.agent.plugins.web :as web]))

(def plugin-bundle
  "The default tool set: REPL eval, clj-edit (Clojure), file-edit
   (general/non-Clojure), web search, Portal visualize, stuck-loop
   detection, and remember (when memory is enabled).

   This is the set `make-agent` installs by default when no
   `:plugins` option is provided. The 8 individual add-*-tool!
   installers are deprecated wrappers around this list."
  [repl/plugin
   clj-edit/plugin
   file-edit/plugin
   web/plugin
   portal/plugin
   ;; remember is added conditionally (only when memory is enabled)
   ;; at make-agent time, since it depends on the live memory store.
   ])

(defn- with-conditional-remember
  "Add the remember plugin to the bundle IF the agent state has a
   memory-store. Called by make-agent after memory creation."
  [plugins state]
  (if (:memory-store state)
    (conj plugins (remember/plugin))
    plugins))
