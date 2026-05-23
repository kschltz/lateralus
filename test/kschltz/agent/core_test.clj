(ns kschltz.agent.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as sut]
            [kschltz.agent.tools.repl :as repl-tools]))

;; ---- Helper ----

(defn- fresh-agent []
  (sut/make-agent {:base-url "http://llm" :model "test"}))

;; ---- Agent Construction ----

(deftest make-agent-returns-clojure-agent
  (testing "make-agent returns a Clojure agent reference type"
    (let [ag (fresh-agent)]
      (is (instance? clojure.lang.Agent ag)))))

(deftest make-agent-default-state
  (testing "make-agent initializes default state"
    (let [ag (fresh-agent)]
      (is (false? (sut/running? ag)))
      (is (= [] (sut/get-history ag)))
      (is (= 0 (:turns @ag)))
      (is (= 100 (:max-turns @ag)))
      (is (nil? (:session-id @ag)))
      (is (nil? (:memory-conn @ag)))
      (is (nil? (:on-response @ag))))))

;; ---- Reset ----

(deftest reset!-clears-state
  (testing "reset! clears history, turns, queue"
    (let [ag (fresh-agent)]
      (send ag assoc :history [{:role "user" :content "hi"}]
                       :turns 5 :message-queue [{:text "pending" :promise (promise)}])
      (await ag)
      (sut/reset! ag)
      (is (= [] (sut/get-history ag)))
      (is (= 0 (:turns @ag)))
      (is (= [] (:message-queue @ag))))))

;; ---- Public API ----

(deftest running?-returns-current-state
  (testing "running? returns current running state"
    (let [ag (fresh-agent)]
      (is (false? (sut/running? ag))))))

(deftest stop!-sets-running-to-false
  (testing "stop! sets the running flag to false"
    (let [ag (fresh-agent)]
      (sut/stop! ag)
      (is (false? (sut/running? ag))))))

;; ---- Message Queue ----

(deftest send-message!-returns-promise
  (testing "send-message! returns a promise and enqueues the message"
    (let [ag (fresh-agent)
          p  (sut/send-message! ag "hello")]
      (is (not (realized? p)))
      (is (= 1 (sut/queue-size ag))))))

(deftest send-message!-with-handler
  (testing "send-message! accepts an optional handler"
    (let [ag (fresh-agent)]
      (sut/send-message! ag "hello" (fn [_] :called))
      (is (= 1 (sut/queue-size ag)))
      (is (= "hello" (:text (first (:message-queue @ag))))))))

(deftest send-message!-drops-when-full
  (testing "send-message! delivers ::dropped when queue is at capacity"
    (let [ag (fresh-agent)]
      (send ag assoc :message-queue (vec (repeat sut/maximum-message-queue-size
                                                  {:text "x" :promise (promise)})))
      (await ag)
      (let [p (sut/send-message! ag "overflow")]
        (is (realized? p))
        (is (= ::sut/dropped @p))))))

;; ---- Tool Registration ----

(deftest register-tool!-adds-tool
  (testing "register-tool! adds a tool to the agent"
    (let [ag (fresh-agent)
          tool (repl-tools/repl-eval-tool)]
      (sut/register-tool! ag tool)
      (is (some #(= (:name %) (:name tool)) (sut/get-tools ag))))))

(deftest unregister-tool!-removes-tool
  (testing "unregister-tool! removes a tool by name"
    (let [ag (fresh-agent)
          tool (repl-tools/repl-eval-tool {:name "my-eval"})]
      (sut/register-tool! ag tool)
      (is (sut/unregister-tool! ag "my-eval"))
      (is (not (some #(= (:name %) "my-eval") (sut/get-tools ag)))))))

(deftest add-repl-eval-tool!-registers-tool
  (testing "add-repl-eval-tool! creates and registers a REPL eval tool"
    (let [ag (fresh-agent)]
      (sut/add-repl-eval-tool! ag)
      (is (some #(= "repl-eval" (:name %)) (sut/get-tools ag))))))

(deftest add-repl-nrepl-tool!-registers-tool
  (testing "add-repl-nrepl-tool! creates and registers a nREPL tool"
    (let [ag (fresh-agent)]
      (sut/add-repl-nrepl-tool! ag)
      (is (some #(= "repl-nrepl" (:name %)) (sut/get-tools ag))))))

;; ---- Default Handler ----

(deftest set-on-response!-sets-handler
  (testing "set-on-response! sets the default handler"
    (let [ag (fresh-agent)]
      (is (nil? (:on-response @ag)))
      (sut/set-on-response! ag (fn [_] :called))
      (is (fn? (:on-response @ag))))))

(deftest set-on-response!-can-clear
  (testing "set-on-response! with nil clears the handler"
    (let [ag (fresh-agent)]
      (sut/set-on-response! ag (fn [_] :called))
      (sut/set-on-response! ag nil)
      (is (nil? (:on-response @ag))))))

(deftest make-agent-with-on-response
  (testing "make-agent accepts :on-response"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"
                              :on-response (fn [_] :called)})]
      (is (fn? (:on-response @ag))))))

;; ---- Error Handler ----

(deftest set-on-error!-sets-handler
  (testing "set-on-error! sets the error handler"
    (let [ag (fresh-agent)]
      (is (nil? (:on-error @ag)))
      (sut/set-on-error! ag (fn [_ _] :handled))
      (is (fn? (:on-error @ag))))))

(deftest set-on-error!-can-clear
  (testing "set-on-error! with nil clears the handler"
    (let [ag (fresh-agent)]
      (sut/set-on-error! ag (fn [_ _] :handled))
      (sut/set-on-error! ag nil)
      (is (nil? (:on-error @ag))))))

(deftest make-agent-with-on-error
  (testing "make-agent accepts :on-error"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"
                              :on-error (fn [_ _] :handled)})]
      (is (fn? (:on-error @ag))))))

;; ---- Memory Integration ----

(deftest make-agent-without-memory
  (testing "make-agent without :session-id has no memory state"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"})]
      (is (nil? (:session-id @ag)))
      (is (nil? (:memory-conn @ag))))))

(deftest make-agent-with-session-id
  (testing "make-agent with :session-id sets session and memory keys"
    (let [ag (sut/make-agent {:base-url "http://llm"
                              :model "test"
                              :session-id "my-session"})]
      (is (= "my-session" (:session-id @ag)))
      (is (contains? @ag :memory-conn)))))

(deftest make-agent-with-nil-session-id-generates-one
  (testing "make-agent with nil :session-id auto-generates session-id"
    (let [ag (sut/make-agent {:base-url "http://llm"
                              :model "test"
                              :session-id nil})]
      (is (string? (:session-id @ag)))
      (is (.startsWith ^String (:session-id @ag) "session-"))
      (is (contains? @ag :memory-conn)))))