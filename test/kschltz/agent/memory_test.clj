(ns kschltz.agent.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.memory :as sut]))

;; ---- Dispatch Function Tests ----

(deftest backend-dispatch-extracts-backend
  (testing "backend-dispatch returns the :backend key from opts"
    (is (= :datalevin (sut/backend-dispatch {:backend :datalevin})))
    (is (= :sqlite    (sut/backend-dispatch {:backend :sqlite})))
    (is (nil? (sut/backend-dispatch {})))))

(deftest strategy-dispatch-extracts-strategy
  (testing "strategy-dispatch returns the :strategy key from opts"
    (is (= :hybrid (sut/strategy-dispatch {:strategy :hybrid})))
    (is (= :recent (sut/strategy-dispatch {:strategy :recent})))
    (is (nil? (sut/strategy-dispatch {})))))

;; ---- Default Backend Throws ----

(deftest create-session-default-throws
  (testing "create-session throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/create-session {:backend :unknown})))))

(deftest store-message-default-throws
  (testing "store-message throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/store-message {:backend :unknown})))))

(deftest retrieve-relevant-default-throws
  (testing "retrieve-relevant throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/retrieve-relevant {:backend :unknown})))))

(deftest load-recent-messages-default-throws
  (testing "load-recent-messages throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/load-recent-messages {:backend :unknown})))))

(deftest close-session-default-throws
  (testing "close-session throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/close-session {:backend :unknown})))))

;; ---- Default Strategy Throws ----

(deftest compose-default-throws
  (testing "compose throws on unknown strategy"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/compose {:strategy :unknown})))))

;; ---- :hybrid Compose Strategy Tests ----

(deftest compose-hybrid-empty-inputs
  (testing "compose :hybrid with no inputs returns empty vector"
    (is (= [] (sut/compose {:strategy :hybrid})))
    (is (= [] (sut/compose {:strategy :hybrid :relevant [] :recent []})))
    (is (= [] (sut/compose {:strategy :hybrid :relevant [] :recent [] :relevant-limit 5 :recent-limit 5})))))

(deftest compose-hybrid-relevant-only
  (testing "compose :hybrid with only relevant messages returns relevant"
    (let [msgs [{:msg/id "a" :msg/text "alpha" :msg/timestamp 100}
                {:msg/id "b" :msg/text "beta"  :msg/timestamp 200}]]
      (is (= msgs (sut/compose {:strategy :hybrid :relevant msgs :recent []}))))))

(deftest compose-hybrid-respects-relevant-limit
  (testing "respects relevant-limit"
    (let [msgs [{:msg/id "a" :msg/text "alpha" :msg/timestamp 100}
                {:msg/id "b" :msg/text "beta"  :msg/timestamp 200}
                {:msg/id "c" :msg/text "gamma" :msg/timestamp 300}]]
      (is (= [(first msgs)]
             (sut/compose {:strategy :hybrid :relevant msgs :recent [] :relevant-limit 1}))))))

(deftest compose-hybrid-recent-only
  (testing "compose :hybrid with only recent messages returns recent"
    (let [msgs [{:msg/id "x" :msg/text "first"  :msg/timestamp 10}
                {:msg/id "y" :msg/text "second" :msg/timestamp 20}]]
      (is (= msgs (sut/compose {:strategy :hybrid :relevant [] :recent msgs}))))))

(deftest compose-hybrid-respects-recent-limit
  (testing "respects recent-limit"
    (let [msgs [{:msg/id "x" :msg/text "first"  :msg/timestamp 10}
                {:msg/id "y" :msg/text "second" :msg/timestamp 20}
                {:msg/id "z" :msg/text "third"  :msg/timestamp 30}]]
      (is (= [(last msgs)]
             (sut/compose {:strategy :hybrid :relevant [] :recent msgs :recent-limit 1}))))))

(deftest compose-hybrid-dedup-preserves-recent
  (testing "messages present in both relevant and recent are kept in recent"
    (let [recent   [{:msg/id "a" :msg/text "recent-a" :msg/timestamp 50}
                    {:msg/id "b" :msg/text "recent-b" :msg/timestamp 60}]
          relevant [{:msg/id "a" :msg/text "rel-a"    :msg/timestamp 10}
                    {:msg/id "b" :msg/text "rel-b"    :msg/timestamp 20}
                    {:msg/id "c" :msg/text "rel-c"    :msg/timestamp 30}]]
      (let [result (sut/compose {:strategy :hybrid
                                 :relevant relevant
                                 :recent   recent
                                 :relevant-limit 10
                                 :recent-limit   5})]
        ;; result = [rel-c@30, recent-a@50, recent-b@60] — sorted by timestamp after dedup
        (is (= 3 (count result)))
        (is (= "rel-c"    (:msg/text (first result))))
        (is (= "recent-a" (:msg/text (second result))))
        (is (= "recent-b" (:msg/text (nth result 2))))))))

(deftest compose-hybrid-uses-chronological-order
  (testing "compose :hybrid returns results sorted by timestamp"
    (let [recent   [{:msg/id "b" :msg/timestamp 50}]
          relevant [{:msg/id "a" :msg/timestamp 10}
                    {:msg/id "c" :msg/timestamp 30}]]
      (let [result (sut/compose {:strategy :hybrid :relevant relevant :recent recent})]
        (is (= ["a" "c" "b"] (mapv :msg/id result)))))))

(deftest compose-recent-only
  (testing "compose :recent returns only recent messages"
    (let [recent [{:msg/id "x" :msg/timestamp 10}
                  {:msg/id "y" :msg/timestamp 20}]
          relevant [{:msg/id "z" :msg/timestamp 5}]]
      (is (= recent (sut/compose {:strategy :recent :relevant relevant :recent recent}))))))