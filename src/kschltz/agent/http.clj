(ns kschltz.agent.http
  (:require [hato.client :as hato]
            [kschltz.agent.memory.schemas :as mem-schemas]
            [malli.core :as m]))

(def ^:private default-connect-timeout-ms 2000)
(def ^:private default-timeout-ms 60000)

(defn- timeout-ms
  "Request timeout (ms). Override with LATERALUS_HTTP_TIMEOUT_MS for slow cloud models."
  []
  (or (some-> (System/getenv "LATERALUS_HTTP_TIMEOUT_MS") parse-long)
      default-timeout-ms))

(defn- http-opts
  "Base hato opts with timeouts so unreachable LLM/embed hosts fail fast."
  [extra]
  (merge {:connect-timeout default-connect-timeout-ms
          :timeout         (timeout-ms)}
         extra))

(defn auth-headers [api-key]
  (when (and api-key (not= api-key ""))
    {"Authorization" (str "Bearer " api-key)}))

(defn get-models [base-url api-key]
  (let [url (format "%s/v1/models" base-url)]
    (-> (hato/get url (cond-> {:as :json}
                        api-key
                        (assoc :headers (auth-headers api-key))))
        :body
        :data)))

(defn get-model-info [base-url api-key model-id]
  (let [url (format "%s/v1/models/%s" base-url model-id)]
    (-> (hato/get url (cond-> {:as :json}
                        api-key
                        (assoc :headers (auth-headers api-key))))
        :body)))

(defn completion [url api-key model message & {:keys [chat-history messages tools]
                                               :or   {chat-history []}}]
  (let [url (format "%s/v1/chat/completions" url)
        body (cond-> {:model    model
                      :messages (or messages
                                    (conj (vec chat-history)
                                          {:role "user" :content message}))}
               tools (assoc :tools tools))]
    (-> (hato/post url (http-opts (cond-> {:content-type :json
                                           :form-params  body
                                           :as           :json}
                                    api-key
                                    (assoc :headers (auth-headers api-key)))))
        :body)))

(defn- embed-request
  [base-url api-key model text]
  (when-not (m/validate mem-schemas/BaseUrl base-url)
    (throw (ex-info "Invalid embed base-url" {:base-url base-url})))
  (when-not (m/validate mem-schemas/ApiKey api-key)
    (throw (ex-info "Invalid embed api-key" {:api-key api-key})))
  (when-not (m/validate mem-schemas/EmbedModel model)
    (throw (ex-info "Invalid embed model" {:model model})))
  (when-not (m/validate mem-schemas/EmbedText text)
    (throw (ex-info "Invalid embed text" {:text text})))
  (let [url  (format "%s/v1/embeddings" base-url)
        body {:model model :input text}
        resp (hato/post url (http-opts (cond-> {:content-type :json
                                                :form-params  body
                                                :as           :json}
                                         api-key
                                         (assoc :headers (auth-headers api-key)))))]
    (get-in resp [:body :data 0 :embedding])))

(defn embed
  "Call an OpenAI-compatible /embeddings endpoint.
   Returns the embedding vector, or nil on failure."
  [base-url api-key model text]
  (try
    (when-let [result (embed-request base-url api-key model text)]
      (when-not (m/validate mem-schemas/EmbeddingVector result)
        (throw (ex-info "Invalid embedding response"
                        {:model model :result-size (count result)})))
      result)
    (catch Exception e
      (println "Warning: embedding request failed:" (.getMessage e))
      nil)))

(defn assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn reasoning-content [response]
  "Extract reasoning/thinking content from LLM response (e.g. DeepSeek V4 thinking mode)."
  (get-in response [:choices 0 :message :reasoning_content]))

(defn tool-calls
  "Extract tool_calls from a completion response. Returns nil if none."
  [response]
  (get-in response [:choices 0 :message :tool_calls]))

(defn assistant-message
  "Build a full assistant message map from a response, including content,
   tool_calls, and reasoning_content."
  [response]
  (let [msg (get-in response [:choices 0 :message])]
    (cond-> {:role "assistant"}
      (:content msg) (assoc :content (:content msg))
      (:tool_calls msg) (assoc :tool_calls (:tool_calls msg))
      (:reasoning_content msg) (assoc :reasoning_content (:reasoning_content msg)))))

(defn step
  ([base-url api-key model message]
   (step {:base-url     base-url
          :api-key      api-key
          :model        model
          :message      message
          :chat-history [{:role    "user"
                          :content "You are a helpful assistante running inside a clojure process, with access to runtime via REPL, you absolutely must only return  valid clojure edn"}]
          :turn        0}))
  ([{:keys [base-url api-key model message chat-history turn tools]
     :or   {turn 0}}]
   (let [response         (completion base-url api-key model message :chat-history chat-history)
         new-chat-history (conj chat-history {:role    "assistant"
                                              :content (assistant-content response)})]
     {:response       response
      :base-url       base-url
      :api-key        api-key
      :model          model
      :chat-history   new-chat-history
      :turn           (inc turn)})))

(comment
  (clojure.repl.deps/sync-deps)
  (def base-url "http://127.0.0.1:8080")
  (def qwen "unsloth/Qwen3.6-35B-A3B-MTP-GGUF:BF16")
  (get-models base-url nil))
