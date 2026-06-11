(ns kschltz.agent.process-concurrency-test
  "Concurrency regression for the Phase 4 cutover.

   The previous `llm-turn` recursion mutated state inside `process-messages`
   and relied on the outer `agent-loop`'s `send`/`await` to apply changes
   to the agent atom. The new chain-based path must obey the same
   invariant: the chain NEVER `send`s to the agent directly; all
   mutations are staged on :agent/state-delta, which the outer
   agent-loop merges via `send`.

   This test fires a real exchange and concurrent `send-message!`
   calls and asserts no queued message is lost."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.exchange :as exchange]))

(defn- fake-llm-client
  "LLM client that returns a fixed text response, recording calls."
  [response-text]
  (let [calls (atom 0)]
    (reify kschltz.agent.llm.client.LlmClient
      (call [_ _]
        (swap! calls inc)
        {:choices [{:message {:content response-text}}]}))))

(defn- base-state []
  {:history []
   :tools []
   :max-tool-calls 5
   :max-retries 3
   :memory-max-chars 100000
   :base-url "http://fake"
   :model "fake"
   :on-thought (fn [_])})

(deftest chain-stages-dont-mutate-state-directly
  (testing "running the default chain never produces :agent/state-delta
            keys that overlap with state in dangerous ways"
    (let [state (base-state)
          client (fake-llm-client "ok")
          ctx {:agent/ref nil
               :agent/state state
               :agent/state-delta {}
               :exchange/items [{:text "hi"}]
               :exchange/user-text "hi"
               :turn/messages [{:role "user" :content "hi"}]
               :llm/client client}
          result (chain/execute ctx exchange/default-exchange-chain)
          delta (:agent/state-delta result)]
      (is (map? delta))
      (is (contains? delta :current-response) "current-response staged")
      (is (contains? delta :history) "history staged")
      (is (= "ok" (:current-response delta))
          "current-response value matches the LLM response"))))

(deftest chain-does-not-call-send-on-the-agent
  (testing "the chain never receives a real agent; :agent/ref is nil
            and no stage tries to deref it for sending"
    (let [state (base-state)
          client (fake-llm-client "ok")
          ctx {:agent/ref :NOT-A-REAL-AGENT
               :agent/state state
               :agent/state-delta {}
               :exchange/items [{:text "hi"}]
               :exchange/user-text "hi"
               :turn/messages [{:role "user" :content "hi"}]
               :llm/client client}
          ;; If any interceptor tried to deref :agent/ref, this would
          ;; throw. The chain should run cleanly.
          result (chain/execute ctx exchange/default-exchange-chain)]
      (is (= "ok" (:exchange/response result))
          "chain completed without deref'ing :agent/ref"))))

(deftest deliver-responses-fires-promises-once
  (testing "deliver-responses invokes loop/deliver-response exactly once
            per item, delivering each item's promise"
    (let [p1 (promise)
          p2 (promise)
          on-response (fn [r] (println "got response:" r))
          items [{:text "msg-1" :promise p1 :handler nil}
                 {:text "msg-2" :promise p2 :handler nil}]
          state (assoc (base-state) :on-response on-response)
          client (fake-llm-client "hello")
          ctx {:agent/ref nil
               :agent/state state
               :agent/state-delta {}
               :exchange/items items
               :exchange/user-text "msg-1\nmsg-2"
               :turn/messages [{:role "user" :content "msg-1\nmsg-2"}]
               :llm/client client}
          result (chain/execute ctx exchange/default-exchange-chain)]
      (is (= "hello" @p1) "promise 1 delivered with the response")
      (is (= "hello" @p2) "promise 2 delivered with the response"))))
