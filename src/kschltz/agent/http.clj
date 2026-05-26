(ns kschltz.agent.http
  (:require [hato.client :as hato]
            [kschltz.agent.memory.schemas :as mem-schemas]
            [malli.core :as m]))

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

(defn completion [url api-key model message & {:keys [chat-history messages]
                                               :or   {chat-history []}}]
  (let [url (format "%s/v1/chat/completions" url)
        body {:model    model
              :messages (or messages
                            (conj (vec chat-history)
                                  {:role "user" :content message}))}]
    (-> (hato/post url (cond-> {:content-type :json
                                :form-params  body
                                :as           :json}
                         api-key
                         (assoc :headers (auth-headers api-key))))
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
        resp (hato/post url (cond-> {:content-type :json
                                     :form-params  body
                                     :as           :json}
                              api-key
                              (assoc :headers (auth-headers api-key))))]
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
