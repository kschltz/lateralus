(ns kschltz.agent.stuck-loop-parity-test
  "Parity tests for the stuck-loop-detector interceptor.

   Covers fact-11: a scripted LLM that calls the same tool repeatedly
   with near-identical args must trigger the detector and produce a
   `{:type :stuck-loop ...}` event. A scripted LLM that calls a tool
   once and then answers must NOT trigger the detector."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.fixtures.scripted-llm :as sl]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.stuck-loop :as stuck-loop]
            [kschltz.agent.parity-test :as ptest]))

;; ---- Stuck-loop chain (includes the detector) ----

(def ^:private stuck-loop-chain
  [ix/error-boundary
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/stuck-loop-detector
   ix/deliver-responses
   ix/update-history
   ix/store-exchange
   ix/notify])

;; ---- Helper: build a minimal ctx, run the chain, return result ----

(defn- run-chain
  "Run the stuck-loop chain on a scripted scenario. Returns the
   full ctx (with engine keys stripped) so the test can inspect
   :exchange/error, :stuck-loop, and :turn/messages."
  [{:keys [client events user-text state]}]
  (let [ctx (ptest/map->ctx state
                            [{:text user-text}]
                            user-text
                            client)
        result (chain/execute ctx stuck-loop-chain)]
    {:exchange/error (:exchange/error result)
     :stuck-loop     (:stuck-loop result)
     :response       (:exchange/response result)
     :turn-msgs      (:turn/messages result)
     :on-thoughts    @events}))

;; ---- Build scripted scenarios ----

(defn- web-search-tool-spec []
  {:name        "web-search"
   :description "Web search"
   :type        :builtin
   :fn          (fn [_]
                  ;; Return empty results — mimics a search that
                  ;; yields nothing (the bad-session symptom).
                  (pr-str []))
   :parameters  [:map [:query :string]]})

(defn- repeated-web-search-script [n]
  "A scripted LLM that calls web-search `n` times with nearly
   identical queries, then never answers."
  (vec (repeat n
               (sl/tool-call-response
                (str "c" (rand-int 100000))
                "web-search"
                {:query "M3 model card"}))))

;; ---- Tests ----

(deftest stuck-loop-detected-after-5-identical-calls
  (testing "5 repeated identical web-search calls trigger stuck detection"
    (let [events (atom [])
          client (:client (sl/scripted (repeated-web-search-script 5)))
          state (ptest/base-state {:client client
                                   :events events
                                   :max-tool-calls 10
                                   :max-retries 3
                                   :tool-specs [(web-search-tool-spec)]})]
      ;; Lower the detector window for the test to 4 so we trip
      ;; after the 4th call rather than waiting for a 5th.
      (with-redefs [stuck-loop/config (fn [] {:window 4
                                              :hash-diversity 0.5
                                              :similarity 0.7
                                              :novelty 0.2})]
        (let [result (run-chain {:client client
                                 :events events
                                 :user-text "find model card"
                                 :state state})]
          (is (some? (:exchange/error result))
              "stuck-loop should set :exchange/error")
          (is (some? (:stuck-loop result))
              "stuck-loop should set :stuck-loop ctx key")
          (is (= :stuck-loop (-> result :stuck-loop :type))
              "stuck-loop event should have :type :stuck-loop")
          (is (string? (-> result :stuck-loop :reason))
              "stuck-loop should have a :reason")
          (is (seq (-> result :stuck-loop :recent-calls))
              "stuck-loop should carry :recent-calls")
          ;; on-thought should have fired
          (is (some #(= :stuck-loop (:type %)) @events)
              "stuck-loop should fire :on-thought event"))))))

(deftest stuck-loop-not-detected-on-single-tool-call
  (testing "one tool call followed by an answer is NOT stuck"
    (let [events (atom [])
          client (:client (sl/scripted [(sl/tool-call-response "c1" "web-search" {:query "hello"})
                                        (sl/text-response "Here's what I found")]))
          state (ptest/base-state {:client client
                                   :events events
                                   :max-tool-calls 10
                                   :max-retries 3
                                   :tool-specs [(web-search-tool-spec)]})]
      (let [result (run-chain {:client client
                               :events events
                               :user-text "search"
                               :state state})]
        (is (nil? (:exchange/error result))
            "single tool call then answer should NOT set :exchange/error")
        (is (nil? (:stuck-loop result))
            "single tool call then answer should NOT set :stuck-loop")
        (is (= "Here's what I found" (:response result)))))))

(deftest stuck-loop-not-detected-on-varied-calls
  (testing "3 different tool calls with different args are NOT stuck"
    (let [events (atom [])
          client (:client (sl/scripted [(sl/tool-call-response "c1" "web-search" {:query "alpha"})
                                        (sl/tool-call-response "c2" "web-search" {:query "beta"})
                                        (sl/tool-call-response "c3" "web-search" {:query "gamma"})
                                        (sl/text-response "got it")]))
          state (ptest/base-state {:client client
                                   :events events
                                   :max-tool-calls 10
                                   :max-retries 3
                                   :tool-specs [(web-search-tool-spec)]})]
      (let [result (run-chain {:client client
                               :events events
                               :user-text "search"
                               :state state})]
        (is (nil? (:stuck-loop result))
            "3 different calls should not trigger stuck detection")))))

(deftest stuck-loop-with-empty-results
  (testing "repeated identical calls with empty results trip detector"
    (let [events (atom [])
          ;; 4 calls with identical args AND empty results — classic
          ;; bad-session pattern.
          client (:client (sl/scripted (repeated-web-search-script 4)))
          state (ptest/base-state {:client client
                                   :events events
                                   :max-tool-calls 10
                                   :max-retries 3
                                   :tool-specs [(web-search-tool-spec)]})]
      (with-redefs [stuck-loop/config (fn [] {:window 4
                                              :hash-diversity 0.5
                                              :similarity 0.7
                                              :novelty 0.2})]
        (let [result (run-chain {:client client
                                 :events events
                                 :user-text "find M3"
                                 :state state})]
          (is (some? (:stuck-loop result))
              "should detect stuck with empty results"))))))
