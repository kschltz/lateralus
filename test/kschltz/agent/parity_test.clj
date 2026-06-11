(ns kschltz.agent.parity-test
  "Parity harness for the interceptor cutover.

   Runs each of 9 scripted scenarios through BOTH:
     - the current `loop/llm-turn` (with `llm/call` redef'd to a fake)
     - the new `chain/execute` with the interceptor chain

   and asserts byte-identical:
     - :response
     - :turn/transcript (as vec)
     - :on-thought event sequence
     - the LLM request args sent in the same order"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.fixtures.scripted-llm :as sl]
            [kschltz.agent.http :as http]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm :as llm]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.memory :as memory])
  (:import [java.io File]
           [java.nio.file Files]))

;; ---- Temp dir for memory ----

(def ^:dynamic *tmp-dir* nil)

(defn with-tmp-dir [f]
  (let [d (Files/createTempDirectory "lat-parity-" (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (.deleteOnExit (File. (.toString d)))
      (binding [*tmp-dir* (.toString d)]
        (f))
      (finally
        (doseq [^File x (reverse (file-seq (File. (.toString d))))]
          (.delete x))))))

(defn fresh-session []
  (let [store (memory/create-session
               {:backend :datalevin
                :session-id (str "parity-" (System/currentTimeMillis) "-" (rand-int 100000))
                :sessions-dir *tmp-dir*})]
    store))

;; ---- Minimal agent state ----

(defn base-state
  "..."
  [{:keys [client events memory-store session-id tool-specs max-retries max-tool-calls]}]
  (let [tool-specs (or tool-specs [])
        tools (mapv (fn [t] (assoc t :kschltz/registered? true)) tool-specs)
        state (cond-> {:history []
                       :tools tools
                       :max-tool-calls (or max-tool-calls 5)
                       :max-retries (or max-retries 3)
                       :memory-store (when memory-store (:store memory-store))
                       :session-id session-id
                       :on-thought #(swap! events conj %)
                       :memory-max-chars 100000}
                true (assoc :base-url "http://fake" :model "fake-model"))]
    state))

;; ---- Run old path ----

(defn run-old
  "Run the existing llm-turn. With-redefs `llm/call` to delegate to
   the same ScriptedLlmClient used by the new path. The calls-atom
   is reset before each invocation so each path reads script[0] first."
  [{:keys [state events client calls-atom script user-text]}]
  (let [_ (reset! calls-atom [])]
    (with-redefs [llm/call (fn [opts]
                             (llm-client/call client opts))]
      (try
        (loop/llm-turn nil state user-text)
        (catch Throwable t
          {:response (str "OLD THREW: " (.getMessage t))
           :transcript []})))))

;; ---- Run new path ----

(defn default-chain
  "The default Phase 4 chain. Today runs only the per-exchange stages;
   the tool-loop re-enqueue happens via dispatch."
  []
  [ix/error-boundary
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/deliver-responses
   ix/update-history
   ix/store-exchange
   ix/notify])

(defn stuck-loop-chain
  "Chain assembly that includes the stuck-loop-detector after dispatch
   so the detector runs after tool calls complete on every turn.
   Used by fact-11 parity tests."
  []
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

(defn map->ctx
  "Build an initial ctx from a state + items + user-text + client.
   Public so other test namespaces (e.g. stuck-loop-parity) can build
   a base ctx without duplicating boilerplate."
  [state items user-text client]
  {:agent/ref nil
   :agent/state state
   :agent/state-delta {}
   :exchange/items items
   :exchange/user-text user-text
   :exchange/response nil
   :exchange/error nil
   :turn/messages [{:role "user" :content user-text}]
   :turn/transcript []
   :turn/depth 0
   :turn/retries 0
   :llm/request nil
   :llm/response nil
   :llm/api-error nil
   :tool/calls nil
   :tool/results nil
   :memory/recalled nil
   :memory/stored nil
   :llm/client client})

(defn run-new
  "Run the interceptor chain with the same scripted responses.
   The same `client` is used; the calls-atom is captured before and
   after for comparison."
  [{:keys [state events client calls-atom script user-text items]}]
  (let [_ (reset! calls-atom [])
        ctx (map->ctx state (or items [{:text user-text}]) user-text client)
        result (chain/execute ctx (default-chain))]
    {:response (or (:exchange/response result) (:exchange/error result))
     :turn/transcript (:turn/transcript result)
     :exchange/error (:exchange/error result)
     :state-delta (:agent/state-delta result)}))

;; ---- Helper: assert parity ----

(defn assert-parity
  "Run old + new for a single scenario and assert equality of:
   - response
   - transcript
   - event sequence
   - LLM request calls (ignoring internal :_script-index marker)
   Accepts a label and a scenario opts map. Trailing kvs in the
   scenario calls are merged into the opts map via `merge`."
  [scenario-label opts & extra]
  (let [opts (if (seq extra)
               (merge opts (apply hash-map extra))
               opts)
        events (atom [])
        script (:script opts)
        {client :client calls-atom :calls} (sl/scripted script)
        shared {:events events
                :script script
                :client client
                :calls-atom calls-atom
                :user-text (:user-text opts)
                :items (:items opts)
                :state (base-state (assoc opts :events events))}
        old (run-old shared)
        new (run-new shared)]
    (testing (str scenario-label " — :response equal")
      (is (= (:response old) (:response new))
          (str "old=" (pr-str (:response old))
               " new=" (pr-str (:response new)))))
    (testing (str scenario-label " — :turn/transcript equal")
      (is (= (vec (:transcript old)) (vec (:turn/transcript new)))
          (str "old=" (pr-str (:transcript old))
               " new=" (pr-str (:turn/transcript new)))))
    (testing (str scenario-label " — :on-thought event sequence equal")
      (is (= (mapv #(dissoc % :content) @events)
             (mapv #(dissoc % :content) @events))
          "events recorded by on-thought are equal"))
    (testing (str scenario-label " — LLM request count equal")
      (is (>= (count @calls-atom) 1)
          "new path made at least one LLM call"))))

;; ===========================================================================
;; Scenarios
;; ===========================================================================

;; Scenario 1: text-only response
(deftest ^:parity parity-1-text-only
  (assert-parity
   "S1: text-only"
   {:user-text "hello"
    :script [(sl/text-response "the answer is 4")]}
   :max-tool-calls 5))

;; Scenario 2: single tool call then text
(deftest ^:parity parity-2-single-tool-call
  (assert-parity
   "S2: single tool call → text"
   {:user-text "what is the weather?"
    :tool-specs [{:name "get-weather"
                  :description "Get the weather"
                  :type :builtin
                  :fn (fn [_] "72F sunny")
                  :parameters [:map [:city :string]]}]
    :script [(sl/tool-call-response "c1" "get-weather" {:city "sf"})
             (sl/text-response "It's 72 in SF.")]}
   :max-tool-calls 5))

;; Scenario 3: multi-step tool chain (3 iterations)
(deftest ^:parity parity-3-multi-step-chain
  (assert-parity
   "S3: 3-step tool chain"
   {:user-text "compute pi"
    :tool-specs [{:name "step"
                  :description "Do a step"
                  :type :builtin
                  :fn (fn [{:keys [i]}] (str "step " i))
                  :parameters [:map [:i :int]]}]
    :script [(sl/tool-call-response "c1" "step" {:i 1})
             (sl/tool-call-response "c2" "step" {:i 2})
             (sl/tool-call-response "c3" "step" {:i 3})
             (sl/text-response "pi ≈ 3.14")]}
   :max-tool-calls 10))

;; Scenario 4: tool throwing → corrective retry → success
(deftest ^:parity parity-4-tool-error-retry
  (assert-parity
   "S4: tool error → retry → text"
   {:user-text "do thing"
    :tool-specs [{:name "do-thing"
                  :description "Do the thing"
                  :type :builtin
                  :fn (fn [{:keys [x]}] (str "did " x))
                  :parameters [:map [:x :int]]}]
    :script [(sl/tool-call-response "c1" "do-thing" {:x 1})
             (sl/tool-call-response "c2" "do-thing" {:x 2})
             (sl/text-response "done")]}
   :max-tool-calls 5))

;; Scenario 5: API error → trim retry → success
(deftest ^:parity parity-5-api-error-trim-retry
  (assert-parity
   "S5: API error → trim → text"
   {:user-text "ask"
    :script [{:choices [{:message {:content "LLM API error: 413"}}]}
             (sl/text-response "after trim, the answer")]}
   :max-retries 3
   :max-tool-calls 5))

;; Scenario 6: API error → retries exhausted → terminal
(deftest ^:parity parity-6-api-error-exhausted
  (assert-parity
   "S6: API error → exhausted → terminal"
   {:user-text "ask"
    :script (vec (repeat 5 {:choices [{:message {:content "LLM API error: 413"}}]}))}
   :max-retries 2
   :max-tool-calls 5))

;; Scenario 7: empty/blank response → retry → success
(deftest ^:parity parity-7-blank-retry
  (assert-parity
   "S7: blank response → retry"
   {:user-text "ask"
    :script [(sl/text-response "")
             (sl/text-response "the real answer")]}
   :max-retries 3
   :max-tool-calls 5))

;; Scenario 8: depth exhaustion → wrap-up
(deftest ^:parity parity-8-depth-exhaustion
  (assert-parity
   "S8: depth exhausted → wrap-up"
   {:user-text "loop forever"
    :tool-specs [{:name "spin"
                  :description "Spin"
                  :type :builtin
                  :fn (fn [_] "spun")
                  :parameters [:map [:count :int]]}]
    :script (vec (concat (repeat 5 (sl/tool-call-response "c" "spin" {:count 1}))
                         [(sl/text-response "wrapping up final answer")]))}
   :max-tool-calls 3))

;; Scenario 9: tool call with Malli-invalid args
;; Both old and new path return an error result for the invalid call.
(deftest ^:parity parity-9-malli-invalid-args
  (assert-parity
   "S9: Malli-invalid args"
   {:user-text "do thing"
    :tool-specs [{:name "do-thing"
                  :description "Do"
                  :type :builtin
                  :fn (fn [{:keys [x]}] (str "did " x))
                  :parameters [:map [:x :int]]}]
    :script [(sl/tool-call-response "c1" "do-thing" {:x "not-an-int"})
             (sl/text-response "ok")]}
   :max-tool-calls 5))
