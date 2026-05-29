(ns kschltz.agent.http
  (:require [hato.client :as hato]
            [kschltz.agent.memory.schemas :as mem-schemas]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def ^:private default-connect-timeout-ms 10000)
(def ^:private default-timeout-ms 60000)

(defn- timeout-ms
  "Request timeout (ms). Override with LATERALUS_HTTP_TIMEOUT_MS for slow cloud models."
  []
  (or (some-> (System/getenv "LATERALUS_HTTP_TIMEOUT_MS") parse-long)
      default-timeout-ms))

(defn- connect-timeout-ms
  "Connect timeout (ms). Override with LATERALUS_CONNECT_TIMEOUT_MS for slow networks."
  []
  (or (some-> (System/getenv "LATERALUS_CONNECT_TIMEOUT_MS") parse-long)
      default-connect-timeout-ms))

(defn- http-opts
  "Base hato opts with timeouts so unreachable LLM/embed hosts fail fast."
  [extra]
  (merge {:connect-timeout   (connect-timeout-ms)
          :timeout           (timeout-ms)
          :throw-exceptions  false}
         extra))

(defn auth-headers [api-key]
  (when (and api-key (not= api-key ""))
    {"Authorization" (str "Bearer " api-key)}))

(defn- build-completion-messages
  [message chat-history messages]
  (or messages
      (conj (vec chat-history)
            {:role "user" :content message})))

(defn- ensure-string-message-content
  "OpenAI-compatible APIs require string :content; tool results may be numbers."
  [msgs]
  (mapv (fn [m]
          (cond-> m
            (contains? m :content)
            (update :content #(str (or % "")))
            (contains? m :reasoning_content)
            (update :reasoning_content #(when (some? %) (str %)))))
        msgs))

(m/=> completion-request mem-schemas/CompletionRequestFn)
(defn- completion-request
  [url api-key model message & [opts]]
  (let [{:keys [chat-history messages tools]
         :or   {chat-history []}}
        (or opts {})
        msgs (ensure-string-message-content
               (build-completion-messages message chat-history messages))
        url  (format "%s/v1/chat/completions" url)
        body (cond-> {:model model :messages msgs}
               tools (assoc :tools tools))
        resp (hato/post url (http-opts (cond-> {:content-type :json
                                               :form-params  body
                                               :as           :json}
                                        api-key
                                        (assoc :headers (auth-headers api-key)))))
        status (:status resp)]
    (if (and status (>= status 400))
      (let [error-body (or (:body resp) {:error {:message (str "HTTP " status)}})
            error-msg  (or (get-in error-body [:error :message])
                           (get-in error-body [:error])
                           (str error-body))]
        (throw (ex-info (str "LLM API error: status: " status ", message: " error-msg)
                        {:status status :body error-body :url url})))
      (:body resp))))

(defn- normalize-completion-opts
  "Coerce message content before Malli input validation on completion-request."
  [opts]
  (cond-> (or opts {})
    (:messages opts) (update :messages ensure-string-message-content)
    (seq (:chat-history opts [])) (update :chat-history ensure-string-message-content)))

(defn completion
  "Public entry; coerces keyword opts to a map for instrumented completion-request."
  [url api-key model message & opts]
  (let [opts'  (if (seq opts) (apply hash-map opts) {})
        opts'' (normalize-completion-opts opts')
        msg'   (when (some? message) (str message))]
    (completion-request url api-key model msg' opts'')))

(defn get-models [base-url api-key]
  (let [url (format "%s/v1/models" base-url)
        resp (hato/get url (http-opts (cond-> {:as :json}
                                api-key
                                (assoc :headers (auth-headers api-key)))))]
    (if (and (:status resp) (>= (:status resp) 400))
      (throw (ex-info (str "API error: status: " (:status resp)) {:status (:status resp) :body (:body resp)}))
      (get-in resp [:body :data]))))

(defn get-model-info [base-url api-key model-id]
  (let [url (format "%s/v1/models/%s" base-url model-id)
        resp (hato/get url (http-opts (cond-> {:as :json}
                                api-key
                                (assoc :headers (auth-headers api-key)))))]
    (if (and (:status resp) (>= (:status resp) 400))
      (throw (ex-info (str "API error: status: " (:status resp)) {:status (:status resp) :body (:body resp)}))
      (:body resp))))

(m/=> embed-request mem-schemas/EmbedRequestFn)
(defn- embed-request
  [base-url api-key model text]
  (let [url  (format "%s/v1/embeddings" base-url)
        body {:model model :input text}
        resp (hato/post url (http-opts (cond-> {:content-type :json
                                                :form-params  body
                                                :as           :json}
                                         api-key
                                         (assoc :headers (auth-headers api-key)))))
        status (:status resp)]
    (if (and status (>= status 400))
      (throw (ex-info (str "Embed API error: status: " status)
                      {:status status :body (:body resp)}))
      (get-in resp [:body :data 0 :embedding]))))

(defn embed
  "Call an OpenAI-compatible /embeddings endpoint.
   Returns the embedding vector, or nil on failure."
  [base-url api-key model text]
  (try
    (embed-request base-url api-key model text)
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

;; Instrument network-boundary fns (input + output) per project rule.
(mi/instrument! {:ns ['kschltz.agent.http]})

(comment
  (clojure.repl.deps/sync-deps)
  (def base-url "http://127.0.0.1:8080")
  (def qwen "unsloth/Qwen3.6-35B-A3B-MTP-GGUF:BF16")
  (get-models base-url nil))
