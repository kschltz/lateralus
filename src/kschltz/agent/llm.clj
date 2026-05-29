(ns kschltz.agent.llm
  "LLM provider abstraction via multimethod dispatch.

  Providers (dispatch on :provider in opts):
    :openai-compatible  — OpenAI-compatible API (default)
    :ollama             — Ollama local API (future)

  Usage:
    (call {:provider :openai-compatible
           :base-url \"http://127.0.0.1:8080\"
           :api-key  nil
           :model    \"my-model\"
           :message  \"What is the weather?\"
           :chat-history []})")

(require '[kschltz.agent.http :as http])

(defn provider-dispatch
  "Extract the LLM provider from opts."
  [opts]
  (:provider opts))

(defmulti call provider-dispatch :default :openai-compatible)

(defmethod call :default
  [opts]
  (throw (ex-info (str "Unknown LLM provider: " (provider-dispatch opts))
                  {:opts opts})))

(defmethod call :openai-compatible
  [{:keys [base-url api-key model message chat-history messages tools]
    :or   {chat-history []}
    :as   opts}]
  (let [url     (or base-url
                    (throw (ex-info "Missing :base-url" {:opts opts})))
        model'  (or model
                    (throw (ex-info "Missing :model" {:opts opts})))
        _       (when (and (nil? message) (nil? messages))
                  (throw (ex-info "Missing :message or :messages" {:opts opts})))
        http-res (http/completion url api-key model' message
                                   :chat-history chat-history
                                   :messages messages
                                   :tools tools)]
    (or (:body http-res) http-res)))