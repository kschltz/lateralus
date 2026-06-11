(ns kschltz.agent.interceptors-test
  "Unit tests for interceptor stages.

   Each interceptor is tested in isolation against a synthetic ctx
   map. No network, no Datalevin, no real embedder. LLM calls are
   mocked via a fake `kschltz.agent.llm.client/LlmClient`."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.http :as http]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.interceptors.schema :as schema]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.loop :as loop]
            [malli.core :as m])
  (:import [kschltz.agent.llm.client DefaultLlmClient]))

(defrecord FakeLlmClient [script]
  llm-client/LlmClient
  (call [_ _] (if (empty? script)
                {:choices [{:message {:content "(no more scripted responses)"}}]}
                (first script))))

(defn fake-client [& responses]
  (->FakeLlmClient (vec responses)))

(defn base-ctx []
  {:agent/ref nil
   :agent/state {:history [] :tools [] :base-url nil :model nil}
   :exchange/items []
   :exchange/user-text "hello"
   :turn/messages []
   :turn/transcript []
   :turn/depth 0
   :turn/retries 0
   :memory/recalled nil
   :memory/stored nil
   :llm/response nil
   :llm/request nil
   :llm/api-error nil
   :tool/calls nil
   :tool/results nil
   :exchange/response nil
   :exchange/error nil})

(deftest every-interceptor-conforms-to-schema
  (doseq [[n v] (sort (ns-publics 'kschltz.agent.interceptors))]
    (testing n
      (is (not (m/explain schema/Interceptor @v))))))

(deftest compose-context-builds-request-without-tools
  (let [ctx (assoc (base-ctx) :turn/messages [{:role "user" :content "hi"}])
        out ((:enter ix/compose-context) ctx)]
    (is (vector? (-> out :llm/request :messages)))
    (is (seq (-> out :llm/request :messages)))
    (is (not (contains? (:llm/request out) :tools)))))

(deftest llm-call-stores-response
  (let [client (fake-client {:choices [{:message {:content "hi"}}]})
        ctx (assoc (base-ctx) :llm/client client :llm/request {:messages []})
        out ((:enter ix/llm-call) ctx)]
    (is (= "hi" (http/assistant-content (:llm/response out))))
    (is (nil? (:llm/api-error out)))))

(defrecord ThrowingClient []
  llm-client/LlmClient
  (call [_ _] (throw (ex-info "boom" {}))))

(deftest llm-call-converts-exceptions-to-api-error
  (let [ctx (assoc (base-ctx) :llm/client (->ThrowingClient) :llm/request {:messages []})
        out ((:enter ix/llm-call) ctx)]
    (is (some? (:llm/api-error out)))
    (is (= "boom" (-> out :llm/api-error :message)))
    (is (string? (-> out :llm/response :choices first :message :content)))))

(deftest llm-call-throws-on-missing-request
  (is (thrown? clojure.lang.ExceptionInfo ((:enter ix/llm-call) (base-ctx)))))

(deftest parse-response-extracts-text-and-calls
  (let [resp {:choices [{:message {:content "hello" :tool_calls [{:id "c1" :function {:name "f" :arguments "{}"}}]}}]}
        out ((:enter ix/parse-response) (assoc (base-ctx) :llm/response resp))]
    (is (= "hello" (:exchange/response out)))
    (is (seq (:tool/calls out)))
    (is (= "f" (-> out :tool/calls first :tool)))))

(deftest parse-response-fires-on-thought-when-reasoning-present
  (let [events (atom [])
        state (assoc (:agent/state (base-ctx)) :on-thought #(swap! events conj %))
        resp {:choices [{:message {:content "" :reasoning_content "deep thought"}}]}
        out ((:enter ix/parse-response) (assoc (base-ctx) :llm/response resp :agent/state state))]
    (is (some #(= :thinking (:type %)) @events))))

(deftest api-error-retry-trims-and-re-enqueues
  (let [ctx (-> (base-ctx)
                (assoc :llm/api-error {:message "boom"}
                       :turn/messages [{:role "user" :content "old"}
                                       {:role "assistant" :content "x"}
                                       {:role "user" :content "y"}]
                       :turn/retries 0))
        out ((:enter ix/api-error-retry) ctx)]
    (is (= 2 (count (:turn/messages out))))
    (is (re-find #"previous LLM call failed" (-> out :turn/messages second :content)))
    (is (= 1 (:turn/retries out)))
    (is (seq (::chain/queue out)))))

(deftest api-error-retry-terminal-on-exhaustion
  (let [ctx (assoc (base-ctx) :llm/api-error {:message "boom"} :turn/retries 99 :max-retries 3)
        out ((:enter ix/api-error-retry) ctx)]
    (is (string? (:exchange/error out)))))

(deftest api-error-retry-passthrough-when-no-error
  (is (= (base-ctx) ((:enter ix/api-error-retry) (base-ctx)))))

(deftest dispatch-tool-calls-within-depth-enqueue-loop
  (let [ctx (-> (base-ctx)
                (assoc :tool/calls [{:id "c1" :tool "f" :args "{}"}]
                       :turn/depth 0))
        out ((:enter ix/dispatch) ctx)]
    (is (= 1 (:turn/depth out)))
    (is (seq (::chain/queue out)))))

(deftest dispatch-depth-exhausted-enqueues-wrap-up
  (let [ctx (-> (base-ctx)
                (assoc :tool/calls [{:id "c1" :tool "f" :args "{}"}]
                       :turn/depth 99
                       :max-tool-calls 5))
        out ((:enter ix/dispatch) ctx)]
    (is (seq (::chain/queue out)))))

(deftest dispatch-blank-response-retries
  (let [ctx (-> (base-ctx)
                (assoc :exchange/response ""
                       :llm/response {:choices [{:message {:content ""}}]}
                       :turn/retries 0))
        out ((:enter ix/dispatch) ctx)]
    (is (seq (::chain/queue out)))))

(deftest dispatch-normal-text-appends-to-transcript
  (let [ctx (assoc (base-ctx) :exchange/response "the answer")
        out ((:enter ix/dispatch) ctx)]
    (is (nil? (::chain/queue out)))
    (is (= [{:role "assistant" :content "the answer"}] (:turn/transcript out)))))

(deftest execute-tools-stub
  (let [ctx (-> (base-ctx)
                (assoc :tool/calls [{:id "c1" :tool "no-such-tool" :args "{}"}]))
        out ((:enter ix/execute-tools) ctx)]
    (is (vector? (:tool/results out)))))

(deftest tool-error-retry-no-errors-passthrough
  (let [ctx (assoc (base-ctx) :tool/results [{:id "c1" :tool "f" :result "ok"}])
        out ((:enter ix/tool-error-retry) ctx)]
    (is (= ctx out))))

(deftest tool-error-retry-with-errors-adds-corrective-msg
  (let [ctx (assoc (base-ctx) :tool/results [{:id "c1" :tool "f" :error "boom"}] :turn/messages [] :turn/retries 0)
        out ((:enter ix/tool-error-retry) ctx)]
    (is (= 1 (count (:turn/messages out))))
    (is (re-find #"tool calls failed" (-> out :turn/messages first :content)))
    (is (= 1 (:turn/retries out)))))

(deftest store-exchange-noop-without-memory
  (let [out ((:leave ix/store-exchange) (base-ctx))]
    (is (nil? (:memory/stored out)))))

(deftest update-history-stages-delta
  (let [ctx (assoc (base-ctx) :exchange/response "hi"
                                 :exchange/items [{:text "hi"}]
                                 :turn/transcript [{:role "assistant" :content "hi"}])
        out ((:leave ix/update-history) ctx)]
    (is (map? (:agent/state-delta out)))
    (is (some? (-> out :agent/state-delta :current-response)))
    (is (vector? (-> out :agent/state-delta :history)))))

(deftest deliver-responses-no-items-passthrough
  (is (= (base-ctx) ((:leave ix/deliver-responses) (base-ctx)))))

(deftest notify-noop
  (is (= (base-ctx) ((:leave ix/notify) (base-ctx)))))

(deftest error-boundary-sets-exchange-error-and-fires-on-error
  (let [errs (atom [])
        state (assoc (:agent/state (base-ctx)) :on-error (fn [_ag ex] (swap! errs conj ex)))
        ctx (assoc (base-ctx) :agent/state state)
        out ((:error ix/error-boundary) ctx (ex-info "boom" {}))]
    (is (string? (:exchange/error out)))
    (is (= 1 (count @errs)))))

(deftest chain-runs-minimal-exchange-end-to-end
  (let [client (fake-client {:choices [{:message {:content "ok"}}]})
        initial (assoc (base-ctx) :llm/client client)
        result (chain/execute
                initial
                [ix/compose-context ix/llm-call ix/parse-response
                 ix/dispatch ix/deliver-responses ix/update-history
                 ix/store-exchange ix/notify ix/error-boundary])]
    (is (= "ok" (:exchange/response result)))
    (is (some #(= "ok" (:content %)) (:turn/transcript result)))
    (is (not (contains? result ::chain/queue)))
    (is (not (contains? result ::chain/stack)))
    (is (not (contains? result ::chain/error)))))
