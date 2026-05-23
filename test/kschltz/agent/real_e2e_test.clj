(ns kschltz.agent.real-e2e-test
  "End-to-end tests against a real LLM (Ollama on localhost:11434).
   These are NOT mocked — they make actual API calls.
   Skipped automatically if no LLM endpoint is available."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]))

(def ^:private llm-available?
  "Check if a local LLM endpoint is reachable."
  (try
    (let [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "localhost" 11434) 2000)
      (.close socket)
      true)
    (catch Exception _ false)))

(defn- ollama-model []
  (or (System/getenv "OLLAMA_MODEL") "qwen3.6:35b-a3b-coding-bf16"))

(def ^:private base-url "http://localhost:11434")

(defmacro when-llm [& body]
  `(if llm-available?
     (do ~@body)
     (do (is true "Skipped: no LLM endpoint available"))))

;; ============================================================
;; 1. CHAT! ONE-SHOT
;; ============================================================

(deftest real-chat!-returns-response
  (when-llm
    (testing "chat! returns a non-empty response from real LLM"
      (let [ag   (core/make-agent {:base-url base-url
                                    :model    (ollama-model)
                                    :turns    1})
            resp (core/chat! ag "What is 2+2?")]
        (is (string? resp))
        (is (pos? (count resp)) "response should be non-empty")
        (await ag)
        (is (>= (count (core/get-history ag)) 2))))))

;; ============================================================
;; 2. AGENT LOOP: SEND → RECEIVE
;; ============================================================

(deftest real-loop-processes-messages
  (when-llm
    (testing "start! processes queued messages via real LLM"
      (let [ag          (core/make-agent {:base-url base-url
                                           :model    (ollama-model)
                                           :turns    2})
            p1          (core/send-message! ag "Say the word 'banana' and nothing else.")
            loop-future (future (core/start! ag))
            r1          (deref p1 30000 ::timeout)]
        (is (not= ::timeout r1) "promise should be delivered")
        (is (string? r1))
        (is (.contains (.toLowerCase ^String r1) "banana")
            "Response should contain 'banana'")
        (when (core/running? ag) (core/stop! ag))
        @loop-future))))

;; ============================================================
;; 3. ON-RESPONSE HANDLER
;; ============================================================

(deftest real-on-response-called
  (when-llm
    (testing "on-response handler receives real LLM response"
      (let [responses (atom [])
            ag        (core/make-agent {:base-url    base-url
                                        :model       (ollama-model)
                                        :turns       2
                                        :on-response (fn [r] (swap! responses conj r))})
            p         (core/send-message! ag "Say hello.")
            loop-future (future (core/start! ag))]
        @p
        (Thread/sleep 500)
        (is (>= (count @responses) 1) "on-response should be called")
        (is (string? (first @responses)))
        (when (core/running? ag) (core/stop! ag))
        @loop-future))))

;; ============================================================
;; 4. PER-MESSAGE HANDLER
;; ============================================================

(deftest real-per-message-handler-called
  (when-llm
    (testing "per-message handler receives real LLM response"
      (let [handler-results (atom [])
            ag              (core/make-agent {:base-url base-url
                                               :model    (ollama-model)
                                               :turns    2})
            p               (core/send-message! ag "Say 'world'."
                                  (fn [r] (swap! handler-results conj r)))
            loop-future     (future (core/start! ag))]
        @p
        (Thread/sleep 500)
        (is (>= (count @handler-results) 1) "handler should be called")
        (is (string? (first @handler-results)))
        (when (core/running? ag) (core/stop! ag))
        @loop-future))))

;; ============================================================
;; 5. MEMORY ROUND-TRIP
;; ============================================================

(deftest real-memory-stores-and-retrieves
  (when-llm
    (testing "agent stores exchanges in Datalevin and retrieves them"
      (let [session-id (str "real-e2e-" (System/currentTimeMillis))
            ag        (core/make-agent {:base-url   base-url
                                         :model      (ollama-model)
                                         :turns      2
                                         :session-id session-id})]
        ;; Memory conn should exist immediately after make-agent
        (is (some? (core/get-memory-conn ag)) "memory connection should exist after make-agent")
        (let [p           (core/send-message! ag "My name is Zaphod Beeblebrox.")
              loop-future (future (core/start! ag))]
          @p
          ;; Memory conn should still be alive during the loop
          (is (some? (core/get-memory-conn ag)) "memory connection should exist during loop")
          (Thread/sleep 1000)
          (let [conn    (core/get-memory-conn ag)
                results (memory/retrieve-relevant
                           {:backend    :datalevin
                            :session-id session-id
                            :connection conn
                            :query      "Zaphod"
                            :limit      5})]
            (is (some? conn) "memory connection should exist before stop")
            (is (some? results) "should retrieve results from memory"))
          (when (core/running? ag) (core/stop! ag))
          @loop-future
          ;; Memory conn should survive loop exit (not closed on stop)
          (is (some? (core/get-memory-conn ag)) "memory connection should survive loop exit")
          (core/reset! ag))))))

;; ============================================================
;; 6. CHAT! WITH HISTORY
;; ============================================================

(deftest real-chat!-with-history
  (when-llm
    (testing "chat! builds history across multiple calls"
      (let [ag (core/make-agent {:base-url base-url
                                  :model    (ollama-model)
                                  :turns    5})
            r1 (core/chat! ag "Remember the number 42.")
            _  (await ag)
            r2 (core/chat! ag "What number did I ask you to remember?")]
        (is (string? r1))
        (is (string? r2))
        (await ag)
        (is (>= (count (core/get-history ag)) 4))))))

;; ============================================================
;; 7. ON-ERROR WITH REAL LLM (bad model)
;; ============================================================

(deftest real-on-error-bad-model
  (when-llm
    (testing "custom on-error handler catches real API error"
      (let [errors      (atom [])
            ag          (core/make-agent {:base-url base-url
                                          :model    "nonexistent-model-xyz"
                                          :turns    2
                                          :on-error (fn [_ag e]
                                                       (swap! errors conj (.getMessage e)))})
            p           (core/send-message! ag "hello")
            loop-future (future (try (core/start! ag) (catch Exception _ nil)))]
        (Thread/sleep 5000)
        (is (>= (count @errors) 1) "on-error should be called for bad model")
        (when (core/running? ag) (core/stop! ag))
        (try @loop-future (catch Exception _ nil))))))

;; ============================================================
;; 8. FULL LIFECYCLE
;; ============================================================

(deftest real-full-lifecycle
  (when-llm
    (testing "complete lifecycle: create → loop → chat → memory → stop → reset"
      (let [on-resp-calls (atom 0)
            handler-calls  (atom 0)
            session-id     (str "lifecycle-" (System/currentTimeMillis))
            ag             (core/make-agent {:base-url    base-url
                                             :model       (ollama-model)
                                             :turns       3
                                             :session-id  session-id
                                             :on-response (fn [_] (swap! on-resp-calls inc))})
            p              (core/send-message! ag "Say hello."
                                 (fn [_] (swap! handler-calls inc)))
            loop-future    (future (core/start! ag))
            result         (deref p 30000 ::timeout)]
        (is (not= ::timeout result) "promise should be delivered")
        (is (string? result))
        (Thread/sleep 500)
        (is (>= @on-resp-calls 1) "on-response called")
        (is (>= @handler-calls 1) "per-message handler called")

        ;; Phase 2: chat!
        (let [resp (core/chat! ag "What is 3+3?")]
          (is (string? resp))
          (is (pos? (count resp)) "chat! response should be non-empty")
          (await ag)
          (is (>= (count (core/get-history ag)) 4)))

        ;; Phase 3: Memory
        (let [conn    (core/get-memory-conn ag)
              results (memory/retrieve-relevant
                         {:backend    :datalevin
                          :session-id session-id
                          :connection conn
                          :query      "hello"
                          :limit      5})]
          (is (some? conn))
          (is (some? results)))

        ;; Phase 4: Cleanup
        (when (core/running? ag) (core/stop! ag))
        @loop-future
        (core/reset! ag)
        (is (= [] (core/get-history ag)))))))