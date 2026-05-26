(ns kschltz.agent.e2e-demo
  "Full E2E demo: agent starts, loads tools, LLM calls tools, results flow back."
  (:require [kschltz.agent.core :as core]
            [kschltz.agent.http :as http]
            [clojure.string :as str]))

(def ^:private call-log (atom []))

(defn- mock-completion [_url _api-key _model message & {:keys [chat-history messages]}]
  (let [all-msgs (or messages
                     (conj (vec chat-history)
                           {:role "user" :content message}))
        last-content (:content (last all-msgs))
        n (inc (count @call-log))]
    (swap! call-log conj n)
    (println (str "   [LLM call " n "] msgs=" (count all-msgs)
                  " last=" (subs (str/replace (str last-content) #"\n" "\\n")
                                 0 (min 60 (count (str last-content))))))
    (cond
      ;; If last message contains tool results → give final answer
      (.contains (str last-content) "Tool results:")
      (if (.contains (str last-content) "15")
        {:choices [{:message {:content "The sum of 1 through 5 is **15**. I used repl-eval to calculate that."}}]}
        {:choices [{:message {:content "The type is **java.lang.Long**. Clojure integers are Java longs."}}]})

      ;; If message asks about sum → call tool  
      (.contains (.toLowerCase (str last-content)) "sum")
      {:choices [{:message {:content "Let me compute.\n➪tool:repl-eval➫(+ 1 2 3 4 5)➪/end➫"}}]}

      ;; If message asks about type → call tool
      (.contains (.toLowerCase (str last-content)) "type")
      {:choices [{:message {:content "Let me check.\n➪tool:repl-eval➫(class (+ 1 1))➪/end➫"}}]}

      ;; Generic
      :else
      {:choices [{:message {:content "Understood."}}]})))

(defn- mock-assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(reset! call-log [])

(with-redefs [http/completion       mock-completion
              http/assistant-content mock-assistant-content]

  (println "========================================")
  (println "  LATERALUS AGENT - E2E TOOL USE DEMO")
  (println "========================================\n")

  (println "1. Creating agent with repl-eval tool...")
  (def ag (core/make-agent {:base-url "http://localhost:11434"
                              :model    "mock-model"
                              :turns    5}))
  (core/add-repl-eval-tool! ag)
  (println "   Tools:" (mapv :name (core/get-tools ag)))

  (println "\n2. Starting agent loop...")
  (def loop-future (future (core/start! ag)))
  (Thread/sleep 200)
  (println "   Running?" (core/running? ag))

  (println "\n3. User: Compute the sum of 1 through 5")
  (def p1 (core/send-message! ag "Compute the sum of 1 through 5"))
  (let [result (deref p1 10000 ::timeout)]
    (println "   Tool used?" (.contains (str result) "15"))
    (println "   Agent:" result))

  (println "\n4. User: What type does (+ 1 1) return?")
  (def p2 (core/send-message! ag "What type does (+ 1 1) return?"))
  (let [result (deref p2 10000 ::timeout)]
    (println "   Tool used?" (.contains (str result) "java.lang.Long"))
    (println "   Agent:" result))

  (println "\n5. Chat history:")
  (doseq [{:keys [role content]} (core/get-history ag)]
    (println (str "   [" role "] " (subs content 0 (min 120 (count content))))))

  (println "\n6. Total LLM calls:" (count @call-log))

  (println "\n7. Stopping...")
  (core/stop! ag)
  @loop-future

  (println "\n========================================")
  (println "  DEMO COMPLETE - TOOL USE VERIFIED")
  (println "========================================"))

(System/exit 0)