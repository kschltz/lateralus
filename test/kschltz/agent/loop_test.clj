(ns kschltz.agent.loop-test
  "Unit tests for agent.loop — LLM turn execution, tool calling,
   message processing, queue drain, and state management."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.loop :as loop]))

;; ---- Private function access ----

(defn- truncate-tool-result
  "Access private truncate-tool-result."
  [& args]
  (apply #'kschltz.agent.loop/truncate-tool-result args))

(defn- parse-tool-calls-native
  "Access private parse-tool-calls-native."
  [& args]
  (apply #'kschltz.agent.loop/parse-tool-calls-native args))

(defn- format-tool-results-native
  "Access private format-tool-results-native."
  [& args]
  (apply #'kschltz.agent.loop/format-tool-results-native args))

(defn- llm-call
  "Access private llm-call."
  [& args]
  (apply #'kschltz.agent.loop/llm-call args))

;; ---- Pure function tests ----

(deftest truncate-tool-result-test
  (testing "short strings pass through unchanged"
    (is (= "hello" (truncate-tool-result "hello"))))

  (testing "non-string input is coerced to string"
    (is (= "42" (truncate-tool-result 42)))
    (is (= "true" (truncate-tool-result true))))

  (testing "long strings are truncated"
    (let [long-str (apply str (repeat 10000 "x"))]
      (is (< (count (truncate-tool-result long-str)) (count long-str)))
      (is (.endsWith (truncate-tool-result long-str) "[truncated]"))))

  (testing "nil is coerced to empty string"
    (is (= "" (truncate-tool-result nil)))))

;; ---- Format tool results ----

(deftest format-tool-results-native-test
  (testing "formats single tool result"
    (let [results [{:id "call_1" :result "42"}]
          msgs (format-tool-results-native results)]
      (is (= 1 (count msgs)))
      (is (= "tool" (:role (first msgs))))
      (is (= "call_1" (:tool_call_id (first msgs))))
      (is (= "42" (:content (first msgs))))))

  (testing "formats tool error results"
    (let [results [{:id "call_2" :error "Something went wrong"}]
          msgs (format-tool-results-native results)]
      (is (= "Error: Something went wrong" (:content (first msgs))))))

  (testing "formats multiple results"
    (let [results [{:id "c1" :result "a"} {:id "c2" :result "b"}]
          msgs (format-tool-results-native results)]
      (is (= 2 (count msgs)))))

  (testing "truncates long results"
    (let [long-result (apply str (repeat 10000 "y"))
          results [{:id "c1" :result long-result}]
          msgs (format-tool-results-native results)]
      (is (< (count (:content (first msgs))) (count long-result))))))

;; ---- Parse tool calls ----

(deftest parse-tool-calls-native-test
  (testing "parses tool calls from response"
    (let [response {:choices [{:message {:tool_calls
                                          [{:id "call_abc"
                                            :type "function"
                                            :function {:name "repl-eval"
                                                       :arguments "{:code (+ 1 1)}"}}]}}]}
          calls (parse-tool-calls-native response)]
      (is (= 1 (count calls)))
      (is (= "call_abc" (:id (first calls))))
      (is (= "repl-eval" (:tool (first calls))))
      (is (= "{:code (+ 1 1)}" (:args (first calls))))))

  (testing "returns nil when no tool calls"
    (let [response {:choices [{:message {:content "Hello"}}]}]
      (is (nil? (parse-tool-calls-native response)))))

  (testing "parses multiple tool calls"
    (let [response {:choices [{:message {:tool_calls
                                          [{:id "c1" :type "function" :function {:name "repl-eval" :arguments "{}"}}
                                           {:id "c2" :type "function" :function {:name "web-search" :arguments "{:q \"test\"}"}}]}}]}
          calls (parse-tool-calls-native response)]
      (is (= 2 (count calls))))))

;; ---- OpenAI tools ----

(deftest openai-tools-test
  (testing "returns nil for empty tools"
    (is (nil? (loop/openai-tools []))))

  (testing "returns nil for nil tools"
    (is (nil? (loop/openai-tools nil))))

  (testing "converts tool definitions"
    (let [tools [{:name "repl-eval"
                   :description "Evaluate code"
                   :parameters [:map [:code :string]]}]
          result (loop/openai-tools tools)]
      (is (some? result))
      (is (= 1 (count result))))))

;; ---- Context composition in loop ----

(deftest llm-call-missing-config-test
  (testing "returns fallback when base-url or model missing"
    (let [state {:base-url nil :model nil}
          result (llm-call state {:user-text "hello"})]
      (is (some? result))
      (is (= "LLM not configured" (get-in result [:choices 0 :message :content]))))))

;; ---- Queue operations ----

(deftest drain-queue-test
  (testing "drains all items from queue (pure)"
    (let [state {:message-queue [{:text "hello" :promise (promise)}]}
          [items state'] (loop/drain-queue state)]
      (is (= 1 (count items)))
      (is (= [] (:message-queue state')))))

  (testing "returns empty vector for empty queue (pure)"
    (let [state {:message-queue []}
          [items state'] (loop/drain-queue state)]
      (is (= [] items))
      (is (= [] (:message-queue state')))))

(deftest drain-queue-atomic-test
  (testing "atomic drain empties the agent queue"
    (let [ag (clojure.core/agent {:message-queue [{:text "a" :promise (promise)}
                                                 {:text "b" :promise (promise)}]})]
      (let [[items _] (loop/drain-queue! ag)]
        (is (= 2 (count items)))
        (is (= [] (:message-queue @ag))))))

  (testing "atomic drain returns empty when queue is empty"
    (let [ag (clojure.core/agent {:message-queue []})]
      (let [[items _] (loop/drain-queue! ag)]
        (is (= [] items))
        (is (= [] (:message-queue @ag))))))

  (testing "concurrent send-message! does not lose items under atomic drain"
    ;; Before the fix, a message enqueued between @ag read and (send ... assoc [])
    ;; could be silently dropped. The atomic drain-queue! does read+clear
    ;; in one agent action, so this race is impossible.
    (let [ag        (clojure.core/agent {:message-queue []})
          ;; Simulate a concurrent send-message! that enqueues after a tiny delay
          _         (future
                      (Thread/sleep 10)
                      (send ag update :message-queue conj {:text "concurrent" :promise (promise)}))]
      ;; Drain immediately — if the concurrent send lands before our
      ;; atomic action, it will be captured; if after, it stays in the queue.
      ;; Either way, the message is NOT lost.
      (Thread/sleep 50)  ;; let concurrent send land
      (let [[items _] (loop/drain-queue! ag)]
        (is (= 1 (count items))
            "Concurrent message must be either drained or still in the queue — never lost")
        (is (= [] (:message-queue @ag))))))))

(deftest deliver-response-test
  (testing "delivers to promise"
    (let [p (promise)]
      (loop/deliver-response {:promise p} "hello")
      (is (= "hello" (deref p 1000 :timeout)))))

  (testing "calls handler function"
    (let [result (atom nil)]
      (loop/deliver-response {:handler (fn [r] (reset! result r))} "test-response")
      (is (= "test-response" @result))))

  (testing "calls on-response callback"
    (let [result (atom nil)]
      (loop/deliver-response {:on-response (fn [r] (reset! result r))} "callback-test")
      (is (= "callback-test" @result))))

  (testing "handles nil promise gracefully"
    (is (nil? (loop/deliver-response {} "test")))))

;; ---- History entries ----

(deftest history-entries-for-exchange-test
  (testing "creates user entry from items"
    (let [items [{:text "hello"}]
          entries (loop/history-entries-for-exchange items nil)]
      (is (= "user" (:role (first entries))))
      (is (= "hello" (:content (first entries))))))

  (testing "includes msg-id and timestamp from stored result"
    (let [items [{:text "hello"}]
          stored {:user-id "msg-123" :user-timestamp 1000 :stored-msgs []}
          entries (loop/history-entries-for-exchange items stored)]
      (is (= "msg-123" (:msg-id (first entries))))
      (is (= 1000 (:timestamp (first entries))))))

  (testing "includes transcript entries when no stored result"
    (let [items [{:text "hello"}]
          transcript [{:role "assistant" :content "hi"}]
          entries (loop/history-entries-for-exchange items nil :transcript transcript)]
      (is (= 2 (count entries)))
      (is (= "user" (:role (first entries))))
      (is (= "hi" (:content (second entries)))))))

;; ---- Callbacks ----

(deftest fire-on-thought-test
  (testing "fires callback when present"
    (let [result (atom nil)
          state {:on-thought (fn [e] (reset! result e))}]
      (loop/fire-on-thought state {:type :thinking :content "hmm"})
      (is (= :thinking (:type @result)))))

  (testing "does nothing when callback missing"
    (is (nil? (loop/fire-on-thought {} {:type :thinking})))))

(deftest fire-memory-event-test
  (testing "fires callback when present"
    (let [result (atom nil)
          state {:on-memory-event (fn [e] (reset! result e))}]
      (loop/fire-memory-event state {:type :store})
      (is (= :store (:type @result)))))

  (testing "does nothing when callback missing"
    (is (nil? (loop/fire-memory-event {} {:type :store})))))

;; ---- Store exchange ----

(deftest store-exchange-no-memory-test
  (testing "returns nil when memory-store is missing"
    (is (nil? (loop/store-exchange {:session-id "s1"} "hello")))
    (is (nil? (loop/store-exchange {} "hello")))))

;; ---- Process messages ----

(deftest process-messages-error-test
  (testing "handles exceptions gracefully"
    (let [ag (atom {:running true})
          state {:base-url nil :model nil}
          items [{:text "test" :promise (promise)}]
          result (loop/process-messages ag state items)]
      (is (some? result)))))

;; ---- Default error handler ----

(deftest default-error-handler-test
  (testing "does not set :running false by default"
    (let [ag (atom {:running true})
          e (Exception. "test error")]
      (loop/default-error-handler ag e)
      (is (:running @ag)))))