(ns kschltz.agent.tools.remember
  "Remember tool — retrieve relevant memories from session memory.
   Searches stored facts and conversation history for information
   related to the query via semantic similarity (when embeddings are
   available) or chronological keyword matching as fallback."
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

(defn- format-memory-hit
  "Format a single memory message as a readable string for the agent."
  [{:msg/keys [role text topic tags kind timestamp]}]
  (let [tag-str (when (and (seq tags) (string? tags) (not (str/blank? tags)))
                  (str " [" tags "]"))
        kind-tag (when kind (str "(" kind ")"))]
    (str (or kind-tag role) tag-str ": " text)))

(defn remember-tool
  "Create a :remember tool. Requires :search-fn from make-agent wiring.
   The tool searches session memory for relevant facts and messages."
  ([]
   (remember-tool {}))
  ([opts]
   {:type        :remember
    :name        (or (:name opts) "remember")
    :description (or (:description opts)
                      "Search session memory for relevant information. Args: {:query string, :limit int (optional, default 5)}. Returns matching facts and messages from the conversation history.")
    :parameters  [:map
                  [:query [:string {:min 1}]]
                  [:limit {:optional true} :int]]
    :search-fn  (:search-fn opts)}))

(defmethod tools/run :remember
  [tool args]
  (let [decoded (normalize-args args)]
    (if-not (m/validate schemas/RememberInput decoded)
      (remember-response {:type "memory" :stored false
                          :error "Invalid remember args: query is required"})
      (if-let [search-fn (:search-fn tool)]
        (try
          (let [limit   (or (:limit decoded) 5)
                results (search-fn {:query (:query decoded) :limit limit})]
            (if (seq results)
              (remember-response {:type "memory"
                                  :stored true
                                  :content (str "Found " (count results) " memories:\n"
                                                (str/join "\n" (map format-memory-hit results)))})
              (remember-response {:type "memory"
                                  :stored false
                                  :content "No matching memories found."})))
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