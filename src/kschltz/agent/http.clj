(ns kschltz.agent.http
  (:require [hato.client :as hato]))

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

(defn completion [url api-key model message & {:keys [chat-history]
                                               :or   {chat-history []}}]
  (let [url (format "%s/v1/chat/completions" url)
        body {:model    model
              :messages (conj (vec chat-history)
                              {:role "user" :content message})}]
    (-> (hato/post url (cond-> {:content-type :json
                                :form-params  body
                                :as           :json}
                         api-key
                         (assoc :headers (auth-headers api-key))))
        :body)))

(defn embed
  "Call an OpenAI-compatible /embeddings endpoint.
   Returns the embedding vector, or nil on failure."
  [base-url api-key model text]
  (try
    (let [url  (format "%s/v1/embeddings" base-url)
          body {:model model :input text}
          resp (hato/post url (cond-> {:content-type :json
                                       :form-params  body
                                       :as           :json}
                                api-key
                                (assoc :headers (auth-headers api-key))))]
      (get-in resp [:body :data 0 :embedding]))
    (catch Exception _
      nil)))

(defn assistant-content [response]
  (get-in response [:choices 0 :message :content]))

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
  (get-models base-url nil)
  [{:id "unsloth/Qwen3.6-35B-A3B-MTP-GGUF:BF16",
    :aliases ["unsloth/Qwen3.6-35B-A3B-MTP-GGUF:BF16"],
    :tags [],
    :object "model",
    :created 1779309212,
    :owned_by "llamacpp",
    :meta
    {:vocab_type 2,
     :n_vocab 248320,
     :n_ctx 262144,
     :n_ctx_train 262144,
     :n_embd 2048,
     :n_params 35505251456,
     :size 71054950912}}]

  (get-model-info base-url nil qwen)

  (let [model-id (-> (get-models base-url nil) first :id)]
    (completion base-url nil model-id "HI"))

  (-> (step base-url nil "unsloth/Qwen3.6-35B-A3B-MTP-GGUF:BF16"  "HI")
      (assoc :message "What is the weather in Tokyo?")
      (step)
      (assoc :message "how do you know that?")
      (step)))