(ns kschltz.agent.watchdog-test
  "Integration tests for the heartbeat watchdog.

   Covers fact-12: a mocked LlmClient that never writes a heartbeat
   past T=0 must cause the loop to emit :session-unresponsive
   within the configured threshold plus one tick of slack, and
   the message queue must be reset to [] afterwards."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.llm.client :as client]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.fixtures.scripted-llm :as sl]))

;; ---- A stalled LLM client: heartbeat ref is older than threshold ----

(defrecord StalledClient [stalled-ms]
  client/LlmClient
  (call [_ _]
    ;; Never returns — but our tests don't actually invoke this
    ;; (we test the watchdog's check fn directly).
    (Thread/sleep 60000)
    {:choices [{:message {:content "would-have-responded"}}]})
  (start-heartbeat! [_]
    ;; Return a heartbeat state with last-beat in the past, simulating
    ;; a stall.
    (atom {:last-beat (- (System/currentTimeMillis) stalled-ms)
           :running? true
           :future nil}))
  (cancel [_ _] nil))

;; ---- Direct unit tests of the watchdog's check fn ----

(deftest heartbeat-stalled-detects-old-heartbeat
  (testing "heartbeat-stalled? returns true when last-beat is older than threshold"
    ;; Use a very short threshold (default is 60s)
    (with-redefs [loop/heartbeat-timeout-ms (fn [] 100)]
      (let [ag (atom {:llm/heartbeat-state
                      (atom {:last-beat (- (System/currentTimeMillis) 1000)
                             :running? true})})]
        (is (number? (loop/heartbeat-stalled? ag (System/currentTimeMillis)))
            "should detect stall")))))

(deftest heartbeat-stalled-nil-when-no-heartbeat
  (testing "heartbeat-stalled? returns nil when no heartbeat ref"
    (let [ag (atom {})]
      (is (nil? (loop/heartbeat-stalled? ag (System/currentTimeMillis)))))))

(deftest heartbeat-stalled-nil-when-fresh-heartbeat
  (testing "heartbeat-stalled? returns nil when heartbeat is recent"
    (with-redefs [loop/heartbeat-timeout-ms (fn [] 60000)]
      (let [ag (atom {:llm/heartbeat-state
                      (atom {:last-beat (System/currentTimeMillis)
                             :running? true})})]
        (is (nil? (loop/heartbeat-stalled? ag (System/currentTimeMillis)))
            "fresh heartbeat should not be flagged as stalled")))))

;; ---- Watchdog integration: stalled LLM fires :session-unresponsive ----

(deftest watchdog-fires-session-unresponsive
  (testing "watchdog cancels stalled request, resets queue, fires callbacks"
    (let [stalled-stalled-ms 1000
          ;; Use a 100ms threshold for fast tests
          _ (with-redefs [loop/heartbeat-timeout-ms (fn [] 100)]
              (let [events (atom [])
                    errors (atom [])
                    on-thought (fn [event] (swap! events conj event))
                    on-error (fn [_ ex] (swap! errors conj ex))
                    ag (atom {:running true
                              :message-queue [{:text "pending"}]
                              :llm/heartbeat-state
                              (atom {:last-beat (- (System/currentTimeMillis) stalled-stalled-ms)
                                     :running? true})
                              :llm/client (->StalledClient stalled-stalled-ms)
                              :on-thought on-thought
                              :on-error on-error})
                    wd (loop/watchdog! ag {:check-interval-ms 50})
                    ;; Wait for the watchdog to detect the stall
                    _ (Thread/sleep 250)]
                (try
                  (is (some #(= :session-unresponsive (:type %)) @events)
                      "watchdog should fire :on-thought :session-unresponsive")
                  (is (seq @errors)
                      "watchdog should fire :on-error")
                  (is (empty? (:message-queue @ag))
                      "watchdog should reset :message-queue to []")
                  (is (nil? (:llm/heartbeat-state @ag))
                      "watchdog should clear :llm/heartbeat-state")
                  (finally
                    (loop/stop-watchdog! wd)))))])))

;; ---- stop-watchdog! ----

(deftest stop-watchdog-cancels-future
  (testing "stop-watchdog! sets running? to false"
    (let [ag (atom {:running true})
          wd (loop/watchdog! ag)]
      (loop/stop-watchdog! wd)
      (is (false? @(:running? wd))
          ":running? should be false after stop-watchdog!"))))

;; ---- ScriptedLlmClient uses default heartbeat behavior ----

(deftest scripted-client-heartbeat-is-fresh
  (testing "ScriptedLlmClient's heartbeat is fresh (current time)"
    (let [{:keys [client]} (sl/scripted [])
          state (client/start-heartbeat! client)
          now (System/currentTimeMillis)]
      (is (<= (- now (:last-beat @state)) 1000)
          "ScriptedLlmClient's heartbeat is within 1s of now"))))
