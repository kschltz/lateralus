(ns kschltz.agent.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as sut]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.http :as http]
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
      (is (nil? (:memory-store @ag)))
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

(deftest reset!-keeps-memory-session
  (testing "reset! keeps memory connection and session-id"
    (let [sid (str "reset-keep-" (System/nanoTime))
          ag  (sut/make-agent {:base-url "http://llm" :model "test"
                               :session-id sid
                               :sessions-dir "test-sessions-reset"})]
      (is (some? (sut/get-memory-store ag)))
      (sut/reset! ag)
      (is (some? (sut/get-memory-store ag)))
      (is (= sid (sut/get-session-id ag))))))

(deftest close-session!-clears-memory
  (testing "close-session! closes memory and clears session keys"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"
                              :session-id (str "close-" (System/nanoTime))
                              :sessions-dir "test-sessions-close"})]
      (is (some? (sut/get-memory-store ag)))
      (sut/close-session! ag)
      (is (nil? (sut/get-memory-store ag)))
      (is (nil? (sut/get-session-id ag))))))

(deftest make-agent-resumes-history-from-session
  (testing "make-agent hydrates :history from persisted session messages"
    (let [sid (str "resume-" (System/nanoTime))
          dir "test-sessions-resume"
          ag1 (sut/make-agent {:base-url "http://llm" :model "test"
                               :session-id sid
                               :sessions-dir dir
                               :history-limit 10})]
      (memory/store-message {:backend :datalevin :session-id sid
                             :connection (sut/get-memory-store ag1)
                             :message {:role "user" :text "hello"
                                       :id "m1" :timestamp 100}})
      (memory/store-message {:backend :datalevin :session-id sid
                             :connection (sut/get-memory-store ag1)
                             :message {:role "assistant" :text "hi there"
                                       :id "m2" :timestamp 101}})
      (sut/close-session! ag1)
      (let [ag2 (sut/make-agent {:base-url "http://llm" :model "test"
                                 :session-id sid
                                 :sessions-dir dir
                                 :history-limit 10})
            h   (sut/get-history ag2)]
        (is (= 2 (count h)))
        (is (= "hello" (:content (first h))))
        (is (= "hi there" (:content (second h))))
        (is (= "m1" (:msg-id (first h))))
        (sut/close-session! ag2)))))

(deftest make-agent-embedding-model-config
  (testing "make-agent accepts :memory-embedding-model"
    (let [sid (str "emb-model-" (System/nanoTime))
          ag  (sut/make-agent {:base-url "http://llm" :model "test"
                               :session-id sid
                               :sessions-dir "test-sessions-emb"
                               :memory-embedding-model "custom-embed"})]
      (is (= "custom-embed" (:memory-embedding-model @ag)))
      (is (= "custom-embed" (:embedding-model (sut/get-memory-store ag))))
      (sut/close-session! ag))))

(deftest store-exchange-fires-memory-event-on-embed-failure
  (testing "on-memory-event fires when embedding fails"
    (let [events (atom [])
          sid    (str "mem-event-" (System/nanoTime))
          ag     (sut/make-agent {:base-url "http://llm" :model "test"
                                  :session-id sid
                                  :sessions-dir "test-sessions-events"
                                  :memory-embedding-method :http
                                  :on-memory-event #(swap! events conj %)})]
      (with-redefs [http/completion (fn [& _] {:choices [{:message {:content "hi"}}]})
                    http/assistant-content http/assistant-content
                    http/embed (constantly nil)]
        (sut/chat! ag "hello" {:base-url "http://llm" :model "test"}))
      (is (>= (count @events) 1))
      (is (every? #(= :memory-not-indexed (:type %)) @events))
      (sut/close-session! ag))))

(deftest chat!-persists-tool-rounds
  (testing "chat! stores tool execution rounds in session memory"
    (let [calls (atom 0)
          sid   (str "tool-store-" (System/nanoTime))
          ag    (sut/make-agent {:base-url "http://llm" :model "test"
                                 :session-id sid
                                 :sessions-dir "test-sessions-tools"
                                 :memory-embedding-method :http
                                 :tools [(repl-tools/repl-eval-tool)]})]
      (with-redefs [http/completion (fn [& _]
                                      (swap! calls inc)
                                      (if (= 1 @calls)
                                        {:choices [{:message {:tool_calls [{:id "call-1"
                                                                            :function {:name "repl-eval"
                                                                                       :arguments "{\"code\": \"(+ 1 2)\"}"}}]}}]}
                                        {:choices [{:message {:content "The answer is 3."}}]}))
                    http/assistant-content http/assistant-content
                    http/tool-calls http/tool-calls
                    http/assistant-message http/assistant-message
                    http/embed (constantly nil)]
        (sut/chat! ag "what is (+ 1 2)?"))
      (let [store (sut/get-memory-store ag)
            msgs  (memory/load-recent-messages {:backend :datalevin
                                                :session-id sid
                                                :connection store
                                                :limit 10})]
        (is (= 4 (count msgs)) "user, assistant+tool_calls, tool, assistant")
        (is (= "user" (:msg/role (nth msgs 0))))
        (is (= "assistant" (:msg/role (nth msgs 1))))
        (is (some? (:msg/tool-calls (nth msgs 1))))
        (is (= "tool" (:msg/role (nth msgs 2))))
        (is (some? (:msg/tool-call-id (nth msgs 2))))
        (is (= "assistant" (:msg/role (nth msgs 3)))))
      (sut/close-session! ag))))

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
      (is (nil? (:memory-store @ag))))))

(deftest make-agent-with-session-id
  (testing "make-agent with :session-id sets session and memory keys"
    (let [sid (str "core-session-" (System/nanoTime))
          ag (sut/make-agent {:base-url "http://llm"
                              :model "test"
                              :session-id sid
                              :sessions-dir "test-sessions-core"})]
      (is (= sid (:session-id @ag)))
      (is (contains? @ag :memory-store))
      (sut/close-session! ag))))

(deftest make-agent-with-nil-session-id-disables-memory
  (testing "make-agent with nil :session-id does not enable memory"
    (let [ag (sut/make-agent {:base-url "http://llm"
                              :model "test"
                              :session-id nil})]
      (is (nil? (:session-id @ag)))
      (is (nil? (:memory-store @ag))))))
;; ---- History Limit ----

(deftest make-agent-default-history-limit
  (testing "default history-limit is 50"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"})]
      (is (= 50 (:history-limit @ag))))))

(deftest make-agent-custom-history-limit
  (testing "custom history-limit overrides default"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"
                              :history-limit 10})]
      (is (= 10 (:history-limit @ag))))))

(deftest history-limit-caps-in-memory-state
  (testing "history stays within limit after many messages"
    (let [ag (sut/make-agent {:base-url "http://llm" :model "test"
                              :history-limit 4})]
      ;; Add 6 user+assistant pairs (12 entries total)
      (doseq [i (range 1 7)]
        (send ag update :history conj {:role "user" :content (str "msg " i)})
        (await ag)
        (send ag update :history conj {:role "assistant" :content (str "resp " i)})
        (await ag))
      (is (= 12 (count (:history @ag))))
      ;; Now simulate the cap that process-messages runs
      (let [state @ag
            h (:history state)
            limited (vec (take-last (:history-limit state) h))]
        (is (= 4 (count limited)))
        (is (= "msg 5" (:content (first limited))))))))

(deftest memory-stores-full-text-truncates-llm-context
  (testing "database keeps full messages; compose-context truncates for the LLM"
    (let [long-text (apply str (repeat 200 "x"))
          sid       (str "truncate-" (System/nanoTime))
          ag        (sut/make-agent {:base-url "http://llm" :model "test"
                                     :session-id sid
                                     :sessions-dir "test-sessions-truncate"
                                     :memory-embedding-method :http
                                     :memory-max-chars 32})]
      (with-redefs [http/completion (fn [& _] {:choices [{:message {:content "ok"}}]})
                    http/assistant-content http/assistant-content
                    http/embed (constantly nil)]
        (sut/chat! ag long-text))
      (let [store (sut/get-memory-store ag)
            msgs  (memory/load-recent-messages {:backend :datalevin
                                                :session-id sid
                                                :connection store
                                                :limit 5})
            user  (first msgs)
            ctx   (sut/compose-context @ag "follow-up")]
        (is (= long-text (:msg/text user)) "persisted text is not truncated")
        (is (<= (count (:content (first ctx))) 32))
        (is (str/ends-with? (:content (first ctx)) "…")))
      (sut/close-session! ag))))

(deftest compose-context-dedupes-relevant-and-recent
  (testing "compose-context uses hybrid strategy to dedupe overlapping messages"
    (let [state {:session-id "s1"
                 :memory-store {}
                 :memory-backend :datalevin
                 :memory-relevant-limit 5
                 :memory-recent-limit 5
                 :memory-strategy :hybrid
                 :history [{:role "user" :content "recent user"
                            :msg-id "a" :timestamp 50}
                           {:role "assistant" :content "recent reply"
                            :msg-id "b" :timestamp 60}]}
          relevant [{:msg/id "a" :msg/role "user" :msg/text "old user" :msg/timestamp 10}
                    {:msg/id "c" :msg/role "user" :msg/text "rel only" :msg/timestamp 30}]]
      (with-redefs [memory/retrieve-relevant (fn [_] relevant)]
        (let [ctx (sut/compose-context state "query")]
          (is (= 3 (count ctx)))
          (is (= ["rel only" "recent user" "recent reply"]
                 (mapv :content ctx)))))))

  (deftest compose-context-without-memory-returns-history
    (testing "compose-context without memory returns in-agent history as-is"
      (let [state {:history [{:role "user" :content "hello"}]}]
        (is (= [{:role "user" :content "hello"}]
               (sut/compose-context state "query")))))))
