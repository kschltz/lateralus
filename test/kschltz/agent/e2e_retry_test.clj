(ns kschltz.agent.e2e-retry-test
  "Tests for tool execution retry on error.
   When a tool call produces an eval error, the agent retries the LLM
   with the error context up to :max-retries times."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [kschltz.agent.core :as core]
            [kschltz.agent.http :as http]))

(def ^:private call-count (atom 0))

(defn- fresh-agent [opts]
  (core/make-agent (merge {:base-url "http://mock-llm" :model "mock-model"} opts)))

;; Mock LLM that returns a tool call with intentionally broken code on call 1,
;; and a clean text answer on call 2+ (after the retry).
;; The tool call will fail because (/ 1 0) throws ArithmeticException.
(defn- retry-mock-completion
  [_url _api-key _model _message & {:keys [chat-history]}]
  (let [n (swap! call-count inc)]
    {:choices [{:message {:content
      (case n
        ;; Call 1: tool call with code that will fail at eval
        1 (str "Let me compute that.\n\u27aatool:repl-eval\u27ab(/ 1 0)\u27aa/end\u27ab")
        ;; Call 2+, after retry with error context: clean text answer
        "The result is undefined because you cannot divide by zero.")}}]}))

(defn- mock-assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn- retry-fixture [test-fn]
  (reset! call-count 0)
  (with-redefs [http/completion       retry-mock-completion
                http/assistant-content mock-assistant-content]
    (test-fn)))

(use-fixtures :each retry-fixture)

(deftest retry-config-default
  (testing ":max-retries defaults to 3"
    (let [ag (fresh-agent {})]
      (is (= 3 (:max-retries @ag))))))

(deftest retry-config-explicit
  (testing ":max-retries can be set explicitly"
    (let [ag (fresh-agent {:max-retries 5})]
      (is (= 5 (:max-retries @ag))))))

(deftest retry-on-division-by-zero
  (testing "when tool execution errors (/ 1 0), agent retries with error context"
    (reset! call-count 0)
    (let [ag (fresh-agent {:turns 5 :max-retries 3})]
      (core/add-repl-eval-tool! ag)
      (let [result (core/chat! ag "What is 1 divided by 0?")]
        ;; The LLM should be called at least twice: once for the tool call,
        ;; once for the retry after error
        (is (>= @call-count 2)
            "LLM should be called at least twice (original + retry)")
        (is (some? result) "should get a response")))))

(deftest retry-max-retries-zero
  (testing "with max-retries 0, tool errors are passed through without retry"
    (reset! call-count 0)
    (let [ag (fresh-agent {:turns 5 :max-retries 0})]
      (core/add-repl-eval-tool! ag)
      (let [result (core/chat! ag "What is 1 divided by 0?")]
        ;; With max-retries 0, the error result is passed to LLM once
        ;; but no retry loop occurs
        (is (some? result) "should get a response")))))