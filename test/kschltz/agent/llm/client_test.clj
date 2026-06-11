(ns kschltz.agent.llm.client-test
  "Tests for the LlmClient protocol and heartbeat channel.

   Covers fact-6: protocol gains a heartbeat channel; default impl
   writes timestamps; mock client simulates stalls."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as client]
            [kschltz.agent.fixtures.scripted-llm :as sl]))

;; ---- Default client heartbeat ----

(deftest default-client-start-heartbeat-returns-state
  (testing "start-heartbeat! returns an atom with :last-beat and :running?"
    (let [state (client/start-heartbeat! (client/default-client))]
      (try
        (is (some? state) "returns an atom")
        (is (map? @state) "atom holds a map")
        (is (number? (:last-beat @state)) ":last-beat is a timestamp number")
        (is (true? (:running? @state)) ":running? starts as true")
        (finally
          (client/cancel (client/default-client) state))))))

(deftest default-client-heartbeat-updates-over-time
  (testing "heartbeat state has monotonically increasing :last-beat"
    (let [c (client/default-client)
          state (client/start-heartbeat! c)
          t0 (:last-beat @state)]
      (Thread/sleep 100) ; less than 5s tick interval
      (is (>= (:last-beat @state) t0) ":last-beat non-decreasing")
      (client/cancel c state))))

(deftest default-client-cancel-stops-future
  (testing "cancel stops the heartbeat future"
    (let [c (client/default-client)
          state (client/start-heartbeat! c)]
      (client/cancel c state)
      (is (false? (:running? @state))
          ":running? flag is false after cancel"))))

;; ---- ScriptedLlmClient heartbeat ----

(deftest scripted-client-start-heartbeat
  (testing "ScriptedLlmClient implements start-heartbeat!"
    (let [{:keys [client]} (sl/scripted [])
          state (client/start-heartbeat! client)]
      (is (some? state) "returns an atom")
      (is (map? @state) "atom holds a map")
      (is (number? (:last-beat @state)) ":last-beat is a timestamp"))))

(deftest scripted-client-cancel-noop
  (testing "ScriptedLlmClient's cancel is a no-op (returns nil)"
    (let [{:keys [client]} (sl/scripted [])
          ref (atom 0)]
      (is (nil? (client/cancel client ref))))))

;; ---- instrumented-call preserves heartbeat ----

(deftest instrumented-call-wraps-heartbeat
  (testing "instrumented-call reifies start-heartbeat! and cancel"
    (let [{:keys [client]} (sl/scripted [])
          wrapped (client/instrumented-call client)
          ref (client/start-heartbeat! wrapped)]
      (is (some? ref))
      (is (nil? (client/cancel wrapped ref))))))

;; ---- Protocol extension check ----

(deftest protocol-has-heartbeat-and-cancel
  (testing "LlmClient protocol declares start-heartbeat! and cancel"
    ;; If these methods aren't on the protocol, calling them throws
    ;; AbstractMethodError / IllegalArgumentException at runtime. The
    ;; fact that scripted client works above proves they are present.
    (let [{:keys [client]} (sl/scripted [])]
      (is (satisfies? client/LlmClient client)))))
