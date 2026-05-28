(ns kschltz.agent.e2e-test
  "End-to-end integration tests for the agent system.

   Tests the full lifecycle: creation → queue → loop → handlers → memory → tools.
   LLM and embedding calls are mocked via with-redefs on http/completion,
   http/assistant-content, and http/embed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.http :as http]
            [kschltz.agent.tools.repl :as repl-tools]))

;; ---- Mock LLM ----

(def ^:private response-counter (atom 0))

(defn- mock-completion
  "Returns a mock OpenAI-compatible completion response."
  [_url _api-key _model _message & {:keys [chat-history messages]}]
  (let [all-msgs (or messages
                     (conj (vec chat-history)
                           {:role "user" :content _message}))
        last-content (:content (last all-msgs))]
    {:choices [{:message {:content (str "Mock response " (swap! response-counter inc)
                                        " re:" (subs (str last-content) 0 (min 20 (count (str last-content)))))}}]}))

(defn- mock-assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn- mock-embed [& _]
  "Skip real HTTP in tests; memory falls back to brute-force search."
  nil)

(defn- mock-llm-fixture [test-fn]
  (reset! response-counter 0)
  (with-redefs [http/completion        mock-completion
                http/assistant-content mock-assistant-content
                http/embed             mock-embed]
    (test-fn)))

(use-fixtures :each mock-llm-fixture)

;; ---- Helpers ----

(defn- fresh-agent [opts]
  (core/make-agent (merge {:base-url     "http://mock-llm"
                           :model        "mock-model"
                           :session-id   nil
                           :sessions-dir (str (System/getProperty "java.io.tmpdir")
                                              "/lateralus-e2e-sessions")
                           :memory-embedding-method :http}
                          opts)))

(defn- drain-and-wait
  "Send a message, start the loop in a thread, wait for promise delivery."
  [ag msg & [handler timeout-ms]]
  (let [p      (core/send-message! ag msg handler)
        result (deref p (or timeout-ms 5000) ::timeout)]
    result))

;; ============================================================
;; 1. AGENT CONSTRUCTION
;; ============================================================

(deftest e2e-make-agent-with-all-options
  (testing "make-agent with all options initializes correctly"
    (let [on-resp (fn [_] nil)
          on-err  (fn [_ _] nil)
          ag     (fresh-agent {:turns       5
                               :session-id  "e2e-session"
                               :on-response on-resp
                               :on-error    on-err
                               :tools       [(repl-tools/repl-eval-tool)]})]
      (is (instance? clojure.lang.Agent ag))
      (is (false? (core/running? ag)))
      (is (= 5 (:max-turns @ag)))
      (is (= "e2e-session" (core/get-session-id ag)))
      (is (= on-resp (:on-response @ag)))
      (is (= on-err (:on-error @ag)))
      (is (= 3 (count (core/get-tools ag))))
      (is (some? (core/get-memory-conn ag))))))

(deftest e2e-make-agent-without-memory
  (testing "make-agent without session-id has no memory"
    (let [ag (fresh-agent {})]
      (is (nil? (core/get-session-id ag)))
      (is (nil? (core/get-memory-conn ag))))))

;; ============================================================
;; 2. MESSAGE QUEUE + PROMISE DELIVERY
;; ============================================================

(deftest e2e-send-message-returns-promise
  (testing "send-message! returns a promise that is not yet realized"
    (let [ag (fresh-agent {})
          p  (core/send-message! ag "hello")]
      (is (not (realized? p)))
      (is (= 1 (core/queue-size ag))))))

(deftest e2e-queue-overflow-returns-dropped
  (testing "queue overflow returns ::dropped promise"
    (let [ag (fresh-agent {:turns 1})]
      (send ag assoc :message-queue
            (vec (repeat core/maximum-message-queue-size {:text "x" :promise (promise)})))
      (await ag)
      (let [p (core/send-message! ag "overflow")]
        (is (realized? p))
        (is (= ::core/dropped @p))))))

;; ============================================================
;; 3. AGENT LOOP: START → SEND → RESPONSE → STOP
;; ============================================================

(deftest e2e-loop-processes-messages
  (testing "start! processes queued messages and delivers promises"
    (let [ag          (fresh-agent {:turns 5})
          p1          (core/send-message! ag "first")
          p2          (core/send-message! ag "second")
          loop-future (future (core/start! ag))
          r1          (deref p1 5000 ::timeout)
          r2          (deref p2 5000 ::timeout)]
      (is (not= ::timeout r1) "p1 should be delivered")
      (is (not= ::timeout r2) "p2 should be delivered")
      (is (.startsWith ^String r1 "Mock response"))
      (is (.startsWith ^String r2 "Mock response"))
      (is (>= (count (core/get-history ag)) 2)
          "history should have batched user msg + assistant response")
      (when (core/running? ag) (core/stop! ag))
      @loop-future)))

(deftest e2e-loop-increments-turns
  (testing "loop increments turns counter"
    (let [ag          (fresh-agent {:turns 5})
          p           (core/send-message! ag "msg1")
          loop-future (future (core/start! ag))]
      @p
      (when (core/running? ag) (core/stop! ag))
      @loop-future
      (is (>= (:turns @ag) 1) "turns should increment"))))

;; ============================================================
;; 4. ON-RESPONSE HANDLER
;; ============================================================

(deftest e2e-on-response-called-on-every-response
  (testing "on-response handler is called for each response"
    (let [responses   (atom [])
          ag          (fresh-agent {:turns       3
                                    :on-response (fn [r] (swap! responses conj r))})
          p1          (core/send-message! ag "msg1")
          p2          (core/send-message! ag "msg2")
          loop-future (future (core/start! ag))]
      @p1 @p2
      (Thread/sleep 100)
      (is (>= (count @responses) 2) "on-response should be called at least twice")
      (is (every? #(.startsWith ^String % "Mock response") @responses)
          "on-response should receive mock response strings")
      (when (core/running? ag) (core/stop! ag))
      @loop-future)))

(deftest e2e-set-on-response-replaces-handler
  (testing "set-on-response! replaces the default handler"
    (let [calls       (atom [])
          ag          (fresh-agent {:turns 2})]
      (core/set-on-response! ag (fn [r] (swap! calls conj {:tag :first :r r})))
      (let [p           (core/send-message! ag "hello")
            loop-future (future (core/start! ag))]
        @p
        (Thread/sleep 100)
        (is (some #(= :first (:tag %)) @calls))
        (core/set-on-response! ag (fn [r] (swap! calls conj {:tag :second :r r})))
        (core/stop! ag)
        @loop-future))))

;; ============================================================
;; 5. PER-MESSAGE HANDLER
;; ============================================================

(deftest e2e-per-message-handler-called
  (testing "per-message handler receives the response"
    (let [handler-results (atom [])
          ag              (fresh-agent {:turns 3})
          p               (core/send-message! ag "hello"
                                              (fn [r] (swap! handler-results conj r)))
          loop-future     (future (core/start! ag))]
      @p
      (Thread/sleep 100)
      (is (>= (count @handler-results) 1) "handler should be called")
      (is (.startsWith ^String (first @handler-results) "Mock response"))
      (when (core/running? ag) (core/stop! ag))
      @loop-future)))

;; ============================================================
;; 6. ON-ERROR HANDLER
;; ============================================================

(deftest e2e-custom-on-error-handler-called
  (testing "custom on-error handler receives the exception"
    (let [errors       (atom [])
          ag           (fresh-agent {:turns    3
                                     :on-error (fn [_ag e]
                                                 (swap! errors conj (.getMessage e)))})]
      ;; Override LLM to throw
      (with-redefs [http/completion (fn [& _] (throw (Exception. "LLM connection refused")))]
        (let [p           (core/send-message! ag "trigger error")
              loop-future (future (try (core/start! ag) (catch Exception _ nil)))]
          ;; The custom handler is a notification; loop continues
          (Thread/sleep 500)
          (is (>= (count @errors) 1) "on-error handler should be called")
          (is (.contains (first @errors) "LLM connection refused"))
          ;; Agent recovers gracefully from LLM errors instead of crashing
          (is (or (.contains @p "LLM API error")
                  (.startsWith @p "Error:"))
              "response should mention the API error or start with Error:")
          (when (core/running? ag) (core/stop! ag))
          (try @loop-future (catch Exception _ nil)))))))

(deftest e2e-default-error-handler-stops-agent
  (testing "default error handler logs and continues; agent eventually exits after max-turns"
    (let [ag (fresh-agent {:turns 1})]
      (with-redefs [http/completion (fn [& _] (throw (Exception. "fatal error")))]
        (let [_p (core/send-message! ag "trigger error")]
          ;; start! blocks until max-turns is reached
          (let [result (core/start! ag)]
            ;; Agent should have completed without crashing
            (is (false? (:running result))
                "agent should not be running after max-turns")
            ;; The error message should be in the response
            (is (some? (:current-response result))
                "agent should have an error response")))))))

;; ============================================================
;; 7. CHAT! ONE-SHOT
;; ============================================================

(deftest e2e-chat!-returns-response
  (testing "chat! makes a single LLM call and returns response"
    (let [ag   (fresh-agent {})
          resp (core/chat! ag "What is 2+2?")]
      (await ag)
      (is (.startsWith ^String resp "Mock response"))
      (is (>= (count (core/get-history ag)) 2)))))

(deftest e2e-chat!-records-history
  (testing "chat! adds user + assistant to history"
    (let [ag (fresh-agent {})]
      (core/chat! ag "hello")
      (await ag)
      (let [h (core/get-history ag)]
        (is (some #(= "user" (:role %)) h))
        (is (some #(= "assistant" (:role %)) h))))))

;; ============================================================
;; 8. TOOLS
;; ============================================================

(deftest e2e-register-and-unregister-tools
  (testing "tools can be registered and unregistered"
    (let [custom-tool {:type :builtin
                       :name "custom-e2e"
                       :description "test tool"
                       :parameters [:map]
                       :fn (constantly "ok")}
          ag (fresh-agent {})]
      (is (= 2 (count (core/get-tools ag))))
      (core/register-tool! ag custom-tool)
      (is (= 3 (count (core/get-tools ag))))
      (is (some #(= "custom-e2e" (:name %)) (core/get-tools ag)))
      (core/unregister-tool! ag "custom-e2e")
      (is (= 2 (count (core/get-tools ag)))))))

(deftest e2e-tool-registration-via-make-agent
  (testing "tools can be passed to make-agent"
    (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]})]
      (is (= 2 (count (core/get-tools ag))))
      (is (some #(= "repl-eval" (:name %)) (core/get-tools ag))))))

;; ============================================================
;; 9. MEMORY INTEGRATION
;; ============================================================

(deftest e2e-memory-session-creation
  (testing "agent with session-id creates memory connection"
    (let [ag (fresh-agent {:session-id "e2e-mem-test"})]
      (is (= "e2e-mem-test" (core/get-session-id ag)))
      (is (some? (core/get-memory-conn ag))))))

(deftest e2e-memory-stores-and-retrieves
  (testing "agent loop stores exchanges in memory"
    (let [ag          (fresh-agent {:turns      3
                                    :session-id "e2e-store-test"})
          p           (core/send-message! ag "remember this")
          loop-future (future (core/start! ag))]
      @p
      (Thread/sleep 200)
      (let [conn    (core/get-memory-conn ag)
            results (memory/retrieve-relevant
                     {:backend    :datalevin
                      :session-id "e2e-store-test"
                      :connection conn
                      :query      "remember"
                      :limit      5})]
        (is (some? conn) "memory connection should exist")
        (is (some? results) "should retrieve results from memory"))
      (when (core/running? ag) (core/stop! ag))
      @loop-future)))

;; ============================================================
;; 10. RESET
;; ============================================================

(deftest e2e-reset-clears-state
  (testing "reset! clears history, turns, and queue"
    (let [ag (fresh-agent {:turns 5})]
      (core/chat! ag "hello")
      (await ag)
      (core/send-message! ag "queued")
      (is (>= (count (core/get-history ag)) 1))
      (is (= 1 (core/queue-size ag)))
      (core/reset! ag)
      (is (= [] (core/get-history ag)))
      (is (= 0 (:turns @ag)))
      (is (= 0 (core/queue-size ag))))))

;; ============================================================
;; 11. SET-ON-ERROR!
;; ============================================================

(deftest e2e-set-on-error!-round-trip
  (testing "set-on-error! sets and clears handler"
    (let [ag (fresh-agent {})]
      (is (nil? (:on-error @ag)))
      (core/set-on-error! ag (fn [_ _] :handled))
      (is (fn? (:on-error @ag)))
      (core/set-on-error! ag nil)
      (is (nil? (:on-error @ag))))))

;; ============================================================
;; 12. FULL LIFECYCLE: CREATE → LOOP → CHAT → MEMORY → STOP → RESET
;; ============================================================

(deftest e2e-full-lifecycle
  (testing "complete agent lifecycle"
    (let [on-resp-calls (atom 0)
          handler-calls  (atom 0)
          ag             (fresh-agent {:turns       5
                                       :session-id  "lifecycle-test"
                                       :on-response (fn [_] (swap! on-resp-calls inc))
                                       :tools       [(repl-tools/repl-eval-tool)]})]

      ;; Phase 1: Initial state
      (is (false? (core/running? ag)))
      (is (= "lifecycle-test" (core/get-session-id ag)))
      (is (some? (core/get-memory-conn ag)))
      (is (= 3 (count (core/get-tools ag))))

      ;; Phase 2: Send message with handler, start loop
      (let [p           (core/send-message! ag "hello"
                                            (fn [_] (swap! handler-calls inc)))
            loop-future (future (core/start! ag))
            result      (deref p 5000 ::timeout)]
        (is (not= ::timeout result) "promise should be delivered")
        (is (.startsWith ^String result "Mock response"))

        (Thread/sleep 200)

        (is (>= @on-resp-calls 1) "on-response handler called")
        (is (>= @handler-calls 1) "per-message handler called")

        ;; Phase 3: chat! one-shot
        (let [resp (core/chat! ag "one-shot")]
          (is (.startsWith ^String resp "Mock response"))
          (is (>= (count (core/get-history ag)) 4)))

        ;; Phase 4: Verify memory was stored
        (let [conn    (core/get-memory-conn ag)
              results (memory/retrieve-relevant
                       {:backend    :datalevin
                        :session-id "lifecycle-test"
                        :connection conn
                        :query      "hello"
                        :limit      5})]
          (is (some? conn))
          (is (some? results)))

        ;; Phase 5: Stop and reset
        (when (core/running? ag) (core/stop! ag))
        @loop-future
        (core/reset! ag)
        (is (= [] (core/get-history ag)))
        (is (= 0 (:turns @ag)))))))

;; ============================================================
;; 13. NATIVE TOOL CALLING
;; ============================================================

(deftest e2e-openai-tools-empty
  (testing "openai-tools returns nil when no tools registered"
    (is (nil? (core/openai-tools [])))
    (is (nil? (core/openai-tools nil)))))

(deftest e2e-openai-tools-builds-array
  (testing "openai-tools builds OpenAI-format tools array"
    (let [tools [(repl-tools/repl-eval-tool)]
          result (core/openai-tools tools)]
      (is (= 1 (count result)))
      (is (= "function" (:type (first result))))
      (is (= "repl-eval" (get-in (first result) [:function :name])))
      (is (contains? (get-in (first result) [:function :parameters]) :type)))))

(deftest e2e-native-tool-call-roundtrip
  (testing "chat! handles native tool_calls and returns final response"
    (let [call-n (atom 0)
          captured-msgs (atom [])]
      (with-redefs [http/completion
                    (fn [_url _api-key _model _message & {:keys [messages]}]
                      (reset! captured-msgs messages)
                      (let [n (swap! call-n inc)]
                        (if (= n 1)
                          {:choices [{:message {:tool_calls [{:id "call-1"
                                                              :function {:name "repl-eval"
                                                                         :arguments "{\"code\": \"(+ 1 2 3)\"}"}}]}}]}
                          {:choices [{:message {:content "The sum is 6."}}]})))
                    http/assistant-content mock-assistant-content
                    http/tool-calls http/tool-calls
                    http/assistant-message http/assistant-message]
        (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]})
              resp (core/chat! ag "What is (+ 1 2 3)?")]
          (is (= "The sum is 6." resp))
          (is (= 2 @call-n) "should make 2 LLM calls (tool + final)")
          (let [msgs @captured-msgs]
            (is (some #(= "tool" (:role %)) msgs)
                "second call should include tool result message")
            (is (some #(= "call-1" (:tool_call_id %)) msgs)
                "tool result should include tool_call_id")))))))

(deftest e2e-native-tool-call-validation-error
  (testing "Malli validation error triggers retry with humanized feedback"
    (let [call-n (atom 0)
          retry-prompts (atom [])]
      (with-redefs [http/completion
                    (fn [_url _api-key _model _message & {:keys [messages]}]
                      (let [n (swap! call-n inc)
                            last-msg (:content (last messages))]
                        (when (> n 1)
                          (reset! retry-prompts last-msg))
                        (if (= n 1)
                          {:choices [{:message {:tool_calls [{:id "call-1"
                                                              :function {:name "repl-eval"
                                                                         :arguments "{\"code\": 123}"}}]}}]}
                          {:choices [{:message {:content "Fixed: (+ 1 2 3) = 6."}}]})))
                    http/assistant-content mock-assistant-content
                    http/tool-calls http/tool-calls
                    http/assistant-message http/assistant-message]
        (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]})
              resp (core/chat! ag "eval (+ 1 2 3)")]
          (is (= 2 @call-n) "should retry after validation error")
          (is (.contains (str @retry-prompts) "failed")
              "retry prompt should mention failure"))))))

(deftest e2e-native-tool-call-unknown-tool
  (testing "unknown tool in tool_calls produces error"
    (let [call-n (atom 0)]
      (with-redefs [http/completion
                    (fn [_url _api-key _model _message & _opts]
                      (let [n (swap! call-n inc)]
                        (if (= n 1)
                          {:choices [{:message {:tool_calls [{:id "call-1"
                                                              :function {:name "nonexistent"
                                                                         :arguments "{}"}}]}}]}
                          {:choices [{:message {:content "I cannot use that tool."}}]})))
                    http/assistant-content mock-assistant-content
                    http/tool-calls http/tool-calls
                    http/assistant-message http/assistant-message]
        (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]})
              resp (core/chat! ag "use a bad tool")]
          (is (some? resp)))))))

(deftest e2e-native-tool-call-max-depth
  (testing "tool call loop stops at max-tool-calls limit and gives LLM a final turn"
    (let [call-n (atom 0)
          last-prompt (atom nil)]
      (with-redefs [http/completion
                    (fn [_url _api-key _model _msg & {:keys [messages]}]
                      (swap! call-n inc)
                      (let [last-msg (-> messages last :content)]
                        (reset! last-prompt last-msg)
                        (if (and (string? last-msg) (.contains ^String last-msg "maximum number of tool"))
                          ;; Wrap-up call: return text, not a tool call
                          {:choices [{:message {:content "I've summarized what I found from the tools."}}]}
                          ;; Normal call: return tool call
                          {:choices [{:message {:tool_calls [{:id (str "call-" @call-n)
                                                              :function {:name "repl-eval"
                                                                         :arguments "{\"code\": \"1\"}"}}]}}]})))
                    http/assistant-content mock-assistant-content
                    http/tool-calls http/tool-calls
                    http/assistant-message http/assistant-message]
        (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]
                               :max-tool-calls 2})
              resp (core/chat! ag "loop forever")]
          ;; LLM gets a final turn to synthesize after hitting the limit
          (is (.contains resp "summarized"))
          ;; The wrap-up prompt tells the LLM no more tool calls
          (is (.contains ^String @last-prompt "maximum number of tool"))
          ;; 2 tool-call rounds + 1 limit-hit round + 1 final wrap-up call = 4
          (is (= 4 @call-n)))))))
