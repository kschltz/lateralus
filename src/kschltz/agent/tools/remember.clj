(ns kschltz.agent.tools.remember
  "Remember tool — persist explicit facts into session memory."
  (:require [clojure.string :as str]
            [kschltz.agent.memory.schemas :as schemas]
            [kschltz.agent.tools :as tools]
            [malli.core :as m]))

(defn- normalize-args
  [args]
  (cond
    (map? args) args
    :else {}))

(defn- remember-response
  [result]
  (when-not (m/validate schemas/RememberResult result)
    (throw (ex-info "Invalid remember result" {:result result})))
  (pr-str result))

(defn remember-tool
  "Create a :remember tool. Requires :store-fact! fn from make-agent wiring."
  ([]
   (remember-tool {}))
  ([opts]
   {:type        :remember
    :name        (or (:name opts) "remember")
    :description (or (:description opts)
                      "Store an explicit fact in session memory. Args: {:content string :topic string (optional) :tags [string] (optional)}. Returns {:type \"memory\" :stored bool :content string :msg-id string}.")
    :parameters  [:map
                  [:content [:string {:min 1}]]
                  [:topic {:optional true} :string]
                  [:tags {:optional true} [:vector :string]]]
    :store-fact! (:store-fact! opts)}))

(defmethod tools/run :remember
  [tool args]
  (let [decoded (normalize-args args)]
    (if-not (m/validate schemas/RememberInput decoded)
      (remember-response {:type "memory" :stored false
                          :error "Invalid remember args: content is required"})
      (if-let [store-fn (:store-fact! tool)]
        (try
          (let [result (store-fn decoded)]
            (remember-response {:type "memory"
                                :stored true
                                :msg-id (:msg-id result)
                                :content (:content decoded)}))
          (catch Exception e
            (remember-response {:type "memory" :stored false
                                :error (.getMessage e)})))
        (remember-response {:type "memory" :stored false
                            :error "memory disabled"})))))

(defmethod tools/parse :remember
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))
