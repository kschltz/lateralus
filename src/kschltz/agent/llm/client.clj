(ns kschltz.agent.llm.client
  "LlmClient protocol — the boundary between the interceptor engine and
   any actual LLM provider. Default impl wraps `kschltz.agent.llm/call`.

   The interceptor `llm-call` reads the LlmClient from the ctx
   (`:llm/client`) and calls `.call` once per turn. Tests can inject a
   fake by associng a different impl onto the state.

   Inputs and outputs are Malli-validated when `*instrument*` is true."
  (:require [kschltz.agent.llm :as llm]
            [malli.core :as m]
            [malli.util :as mu]))

(def CallOpts
  [:map
   [:base-url :string]
   [:model    :string]
   [:messages [:vector :map]]
   [:tools    {:optional true} [:maybe [:vector :map]]]
   [:api-key  {:optional true} [:maybe :string]]])

(def CallResponse
  [:map
   [:choices [:vector :map]]])

(defprotocol LlmClient
  "Boundary for LLM calls. Implementations are responsible for any
   network/protocol-specific work; the engine sees only maps in and out."
  (call [this opts]
    "Synchronous call. Returns a raw response map. May throw on
     transport/4xx/5xx errors; the llm-call interceptor catches those
     and converts them into :llm/api-error on the ctx."))

(defn- ->clj [x]
  (if (map? x) (into {} x) x))

(defrecord DefaultLlmClient []
  LlmClient
  (call [_ {:keys [base-url api-key model messages tools]}]
    (llm/call {:provider    :openai-compatible
               :base-url    base-url
               :api-key     api-key
               :model       model
               :messages    messages
               :tools       tools})))

(defn default-client
  "Build the default LlmClient (wraps `llm/call`)."
  []
  (->DefaultLlmClient))

(defn instrumented-call
  "Wrap an LlmClient with Malli validation. Returns a new LlmClient that
   validates `opts` against `CallOpts` and the response against
   `CallResponse`; throws ex-info on schema violation.

   Used by `make-agent` in dev/test; off by default in prod for latency."
  [client]
  (reify LlmClient
    (call [_ opts]
      (when-let [err (m/explain CallOpts opts)]
        (throw (ex-info "LlmClient.call: invalid opts" {:explain err})))
      (let [resp (call client opts)]
        (when-let [err (m/explain CallResponse (->clj resp))]
          (throw (ex-info "LlmClient.call: invalid response" {:explain err})))
        resp))))
