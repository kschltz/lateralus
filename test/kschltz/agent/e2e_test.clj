(ns kschltz.agent.e2e-test
  "End-to-end integration tests for the agent system.

   Tests the full lifecycle: creation → queue → loop → handlers → memory → tools.
   LLM calls are mocked via with-redefs on http/completion and http/assistant-content."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.http :as http]
            [kschltz.agent.tools.repl :as repl-tools]))

;; ---- Mock LLM ----

(def ^:private response-counter (atom 0))

(defn- mock-completion
  "Returns a mock OpenAI-compatible completion response."
  [_url _api-key _model _message & {:keys [chat-history]}]
  {:choices [{:message {:content (str "Mock response " (swap! response-counter inc))}}]})

(defn- mock-assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn- mock-llm-fixture [test-fn]
  (reset! response-counter 0)
  (with-redefs [http/completion       mock-completion
                http/assistant-content mock-assistant-content]
    (test-fn)))

(use-fixtures :each mock-llm-fixture)

;; ---- Helpers ----

(defn- fresh-agent [opts]
  (core/make-agent (merge {:base-url "http://mock-llm" :model "mock-model"} opts)))

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
      (is (= 1 (count (core/get-tools ag))))
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
      (is (>= (count (core/get-history ag)) 3)
          "history should have 2 user msgs + 1 assistant msg")
      (when (core/running? ag) (core/stop! ag))
      @loop-future)))

(deftest e2e-loop-increments-turns
  (testing "loop increments turns counter"
    (let [ag          (fresh-agent {:turns 2})]
      (core/send-message! ag "msg1")
      (let [loop-future (future (core/start! ag))]
        @loop-future
        (is (>= (:turns @ag) 1) "turns should increment")))))

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
          ;; Error is delivered to the promise
          (is (realized? p) "promise should be delivered with error")
          (is (.startsWith @p "Error:"))
          (when (core/running? ag) (core/stop! ag))
          (try @loop-future (catch Exception _ nil)))))))

(deftest e2e-default-error-handler-stops-agent
  (testing "default error handler stops agent and rethrows"
    (let [ag (fresh-agent {:turns 3})]
      (with-redefs [http/completion (fn [& _] (throw (Exception. "fatal error")))]
        (let [_p (core/send-message! ag "trigger error")]
          (is (thrown? Exception (core/start! ag)))
          (is (false? (core/running? ag))))))))

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
    (let [ag (fresh-agent {})]
      (is (= 0 (count (core/get-tools ag))))
      (core/add-repl-eval-tool! ag)
      (is (= 1 (count (core/get-tools ag))))
      (is (= "repl-eval" (:name (first (core/get-tools ag)))))
      (core/unregister-tool! ag "repl-eval")
      (is (= 0 (count (core/get-tools ag)))))))

(deftest e2e-tool-registration-via-make-agent
  (testing "tools can be passed to make-agent"
    (let [ag (fresh-agent {:tools [(repl-tools/repl-eval-tool)]})]
      (is (= 1 (count (core/get-tools ag))))
      (is (= "repl-eval" (:name (first (core/get-tools ag))))))))

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
      (is (= 1 (count (core/get-tools ag))))

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
;; 13. TOOL USE
;; ============================================================

(deftest e2e-tool-manifest
  (testing "tool-manifest returns nil when no tools"
    (is (nil? (core/tool-manifest []))))
  (testing "tool-manifest includes tool name and description"
    (let [tools [(repl-tools/repl-eval-tool)]
          manifest (core/tool-manifest tools)]
      (is (.contains manifest "repl-eval"))
      (is (.contains manifest "Evaluate Clojure code"))
      (is (.contains manifest "➪tool:")))))

(deftest e2e-parse-tool-calls
  (testing "parse-tool-calls extracts tool calls from LLM response"
    (is (nil? (core/parse-tool-calls "Hello, no tools here.")))
    (is (= [{:tool "repl-eval" :args "(+ 1 2 3)"}]
           (core/parse-tool-calls "Let me compute that.
➪tool:repl-eval➫(+ 1 2 3)➪/end➫")))
    (is (= [{:tool "repl-eval" :args "(+ 1 2)"}
            {:tool "repl-eval" :args "(* 3 4)"}]
           (core/parse-tool-calls "➪tool:repl-eval➫(+ 1 2)➪/end➫ Some text ➪tool:repl-eval➫(* 3 4)➪/end➫")))))

(deftest e2e-tool-manifest-includes-call-format
  (testing "tool manifest includes usage instructions"
    (let [manifest (core/tool-manifest [(repl-tools/repl-eval-tool)])]
      (is (.contains manifest "➪tool:"))
      (is (.contains manifest "➪/end➫"))
      (is (.contains manifest "You may make multiple tool calls")))))