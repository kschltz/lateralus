(ns kschltz.agent.http
  (:require [hato.client :as hato]))

(defn- auth-headers [api-key]
  (when api-key
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

(defn- assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn step
  ([base-url api-key model message]
   (step {:base-url     base-url
          :api-key      api-key
          :model        model
          :message      message
          :chat-history [{:role    "user"
                          :content "You are a helpful assistante running inside a clojure process, with access to runtime via REPL"}]
          :turn        0}))
  ([{:keys [base-url api-key model message chat-history turn]}]
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
  (get-models "http://127.0.0.1:11434" nil)
  (get-model-info "http://127.0.0.1:11434" nil "nemotron-3-super:cloud")

  (let [model-id (-> (get-models "http://127.0.0.1:11434" nil) first :id)]
    (completion "http://127.0.0.1:11434" nil model-id "HI"))

  (-> (step "http://127.0.0.1:11434" nil "nemotron-3-super:cloud" "HI")
      (assoc :message "What is the weather in Tokyo?")
      (step)
      (assoc :message "how do you know that?")
      (step))

  )