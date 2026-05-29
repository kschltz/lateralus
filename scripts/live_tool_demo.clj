#!/usr/bin/env clojure
;; Live demo: invoke every lateralus agent tool with real execution.
;; Usage: clojure -X:demo
;; No LLM required — exercises tools/run + tools/parse directly.

(ns live-tool-demo
  (:require [clojure.pprint :as pprint]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.portal :as portal]
            [kschltz.agent.tools.repl :as repl]
            [kschltz.agent.tools.web :as web]))

(defn banner [title]
  (println)
  (println (str "══ " title " ══")))

(defn demo-result [label f]
  (print (str "  " label " … "))
  (flush)
  (try
    (let [result (f)]
      (println "OK")
      (pprint/pprint {:tool label :result result})
      {:tool label :status :ok :result result})
    (catch Exception e
      (println "FAIL")
      (pprint/pprint {:tool label :status :fail :error (.getMessage e)})
      {:tool label :status :fail :error (.getMessage e)})))

(defn -main []
  (banner "LATERALUS AGENT TOOLS — LIVE DEMO")
  (println "  Direct tool invocation (no LLM loop)\n")

  (let [repl-tool (repl/repl-eval-tool)
        web-tool  (web/web-search-tool)
        viz-tool  (portal/visualize-tool)
        ag        (core/make-agent {:base-url "http://localhost:11434"
                                    :model    "demo"
                                    :session-id "live-tool-demo"
                                    :turns    1})
        remember-tool (some #(when (= "remember" (:name %)) %) (core/get-tools ag))
        tool-names (mapv :name (core/get-tools ag))]

    (banner "Registered tools on make-agent (+ session memory)")
    (pprint/pprint tool-names)

    (banner "1/4 repl-eval")
    (demo-result "repl-eval (+ 1 2 3)"
                 #(tools/tool-call-response repl-tool {:code "(+ 1 2 3)"}))
    (demo-result "repl-eval (class (+ 1 1))"
                 #(tools/tool-call-response repl-tool {:code "(class (+ 1 1))"}))
    (demo-result "repl-eval (range 3)"
                 #(tools/tool-call-response repl-tool {:code "(range 3)"}))

    (banner "2/4 web-search (live network)")
    (demo-result "web-search clojure malli"
                 #(let [hits (tools/tool-call-response web-tool {:query "clojure malli schema"})]
                    (take 3 hits)))

    (banner "3/4 visualize (Portal tap)")
    (demo-result "visualize table"
                 #(tools/tool-call-response viz-tool
                                            {:data "[{:tool \"repl-eval\" :status :ok} {:tool \"web-search\" :status :pending}]"
                                             :viewer "table"
                                             :title "Live Tool Demo"}))

    (banner "4/4 remember (session memory)")
    (let [state @ag
          store (:memory-store state)
          sid   (:session-id state)]
      (memory/store-message
        {:backend    (:memory-backend state)
         :connection store
         :session-id sid
         :message    {:role "assistant"
                      :text "Lateralus default tools: repl-eval, web-search, visualize, remember."
                      :kind "fact"
                      :topic "tools"}})
      (demo-result "remember query"
                   #(tools/tool-call-response remember-tool {:query "lateralus tools" :limit 3})))

    (banner "OpenAI tool defs (Malli → JSON Schema)")
    (doseq [t [repl-tool web-tool viz-tool remember-tool]]
      (when t
        (println (str "  " (:name t) ":"))
        (pprint/pprint (select-keys (:function (tools/openai-tool-def t))
                                  [:name :description]))))

    (banner "SUMMARY")
    (println "  repl-eval   — local Clojure eval with timeout + delimiter repair")
    (println "  web-search  — Mojeek → Startpage → Wikipedia fallback")
    (println "  visualize   — Portal tap> for tables/charts/hiccup")
    (println "  remember    — semantic/keyword memory search (needs :session-id)")
    (println)
    (core/stop! ag)
    (System/exit 0)))

(-main)
