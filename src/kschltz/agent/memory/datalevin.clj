(ns kschltz.agent.memory.datalevin
  "Datalevin-based session memory backend.
   Uses a Datalog store for structured message data and a standalone
   vector index for semantic similarity search. Embeddings default to
   LangChain4j in-process ONNX models; HTTP providers are optional."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.schemas :as schemas]
            [malli.core :as m]))

;; ---- Schema (Datalog store) -----------------------------------------------

(def ^:private schema
  {:session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :session/model      {:db/valueType :db.type/string}
   :session/emb-method {:db/valueType :db.type/string}
   :session/emb-model  {:db/valueType :db.type/string}
   :msg/id             {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :msg/session        {:db/valueType :db.type/string}
   :msg/role           {:db/valueType :db.type/string}
   :msg/text           {:db/valueType :db.type/string}
   :msg/timestamp      {:db/valueType :db.type/long}
   :msg/tool-name      {:db/valueType :db.type/string}
   :msg/tool-result    {:db/valueType :db.type/string}
   :msg/tool-calls     {:db/valueType :db.type/string}
   :msg/tool-call-id   {:db/valueType :db.type/string}
   :msg/kind           {:db/valueType :db.type/string}
   :msg/topic          {:db/valueType :db.type/string}
   :msg/tags           {:db/valueType :db.type/string}})

;; ---- Defaults -------------------------------------------------------------

(def ^:private default-embedding-dims embedding/default-dims)
(def ^:private default-embedding-model embedding/default-langchain4j-model)
(def ^:private default-embedding-method embedding/default-method)
(def ^:private default-sessions-dir "sessions")

;; ---- Paths ----------------------------------------------------------------

(defn- env-sessions-dir
  "Sessions root directory from LATERALUS_SESSIONS_DIR or default."
  []
  (or (System/getenv "LATERALUS_SESSIONS_DIR") default-sessions-dir))

(defn session-db-path
  "Absolute filesystem path for a session store directory."
  [sessions-dir session-id]
  (.getAbsolutePath (io/file sessions-dir session-id)))

(defn- rename-corrupt-dir!
  "Rename a corrupt session directory instead of deleting it."
  [^java.io.File dir]
  (when (.exists dir)
    (let [corrupt (io/file (str (.getAbsolutePath dir)
                                ".corrupt-" (System/currentTimeMillis)))]
      (.renameTo dir corrupt))))

(defn- create-conn-with-recovery
  "Open a Datalevin conn; on failure rename the corrupt dir and retry once."
  [db-path session-id schema]
  (try
    (d/create-conn db-path schema)
    (catch Throwable e
      (println "Warning: corrupt session store, renaming:" db-path
               (.getMessage e))
      (rename-corrupt-dir! (io/file db-path))
      (d/create-conn db-path schema))))

(defn- open-kv-with-recovery
  "Open vector KV store; on failure rename corrupt dir and retry once."
  [kv-path session-id]
  (try
    (d/open-kv kv-path)
    (catch Throwable e
      (println "Warning: corrupt vector store, renaming:" kv-path
               (.getMessage e))
      (rename-corrupt-dir! (io/file kv-path))
      (d/open-kv kv-path))))

;; ---- Session store creation -----------------------------------------------

(defn create-session-store
  "Create a session store: a Datalog connection + a standalone vector index.
   opts may include:
     :embedding-method - :langchain4j (default) or :http
     :embedding-dims   - vector dimensionality (default 384)
     :embedding-model  - model name (default all-minilm-l6-v2-q for langchain4j)
     :base-url         - Ollama/API base URL (required for :http)
     :api-key          - API key for remote HTTP providers
     :embedding-fn     - custom (fn [text] => vec) override for tests
     :model            - LLM model name (stored as metadata)
     :sessions-dir     - root directory for session stores (default sessions/ or LATERALUS_SESSIONS_DIR)"
  [session-id opts]
  (let [sessions-dir (or (:sessions-dir opts) (env-sessions-dir))
        db-path      (session-db-path sessions-dir session-id)
        vectors-path (str db-path "/vectors")
        emb-method   (or (:embedding-method opts) default-embedding-method)
        emb-dims     (or (:embedding-dims opts) default-embedding-dims)
        emb-model    (or (:embedding-model opts) default-embedding-model)
        base-url     (:base-url opts)
        api-key      (:api-key opts)
        embedding-fn (:embedding-fn opts)
        provider     (embedding/create-provider
                      {:method emb-method
                       :model  emb-model
                       :dims   emb-dims
                       :base-url base-url
                       :api-key api-key})
        conn         (create-conn-with-recovery db-path session-id schema)
        kv-store     (open-kv-with-recovery vectors-path session-id)
        vec-index    (d/new-vector-index kv-store
                                         {:dimensions emb-dims :metric-type :cosine})
        session-meta (merge {:session/id session-id}
                            (embedding/provider-metadata provider)
                            (when (:model opts)
                              {:session/model (:model opts)}))]
    (d/transact conn [session-meta])
    {:connection          conn
     :kv-store            kv-store
     :vec-index           vec-index
     :embedding-dims      emb-dims
     :embedding-model     (embedding/provider-model provider)
     :embedding-method    (embedding/provider-method provider)
     :embedding-provider  provider
     :base-url            base-url
     :api-key             api-key
     :embedding-fn        embedding-fn
     :session-id          session-id
     :sessions-dir        sessions-dir
     :db-path             db-path}))

;; ---- Embedding computation ------------------------------------------------

(defn- compute-embedding
  "Compute embedding vector for text. Uses custom :embedding-fn if provided,
   otherwise the configured EmbeddingProvider.
   Returns vector of floats or nil on failure."
  [store text]
  (let [emb-fn (:embedding-fn store)]
    (if emb-fn
      (try (emb-fn text) (catch Exception _ nil))
      (embedding/embed-text (:embedding-provider store) text))))

;; ---- Store messages -------------------------------------------------------

(defn store-message!
  "Store a message in the Datalog store and index its embedding vector.
   Returns {:msg-id ... :stored true :indexed bool :reason ...}."
  [store message-map]
  (when-not (m/validate schemas/StoreMessage message-map)
    (throw (ex-info "Invalid store message" {:message message-map})))
  (let [conn       (:connection store)
        vec-index  (:vec-index store)
        msg-id     (or (:id message-map) (:msg/id message-map)
                       (str "msg-" (System/currentTimeMillis) "-" (rand-int 100000)))
        role       (or (:role message-map) (:msg/role message-map) "assistant")
        text       (or (:text message-map) (:msg/text message-map) "")
        timestamp  (or (:timestamp message-map) (:msg/timestamp message-map)
                       (System/currentTimeMillis))
        session-id (or (:session-id message-map) (:msg/session message-map)
                       (:session-id store))
        entity     (cond-> {:msg/id msg-id :msg/role role :msg/text text
                            :msg/timestamp timestamp}
                     session-id                              (assoc :msg/session session-id)
                     (not-empty (:tool-name message-map))    (assoc :msg/tool-name (:tool-name message-map))
                     (not-empty (:tool-result message-map))  (assoc :msg/tool-result (:tool-result message-map))
                     (not-empty (:tool-calls message-map))   (assoc :msg/tool-calls (:tool-calls message-map))
                     (not-empty (:tool-call-id message-map)) (assoc :msg/tool-call-id (:tool-call-id message-map))
                     (not-empty (:kind message-map))       (assoc :msg/kind (:kind message-map))
                     (not-empty (:topic message-map))      (assoc :msg/topic (:topic message-map))
                     (seq (:tags message-map))             (assoc :msg/tags (json/generate-string (:tags message-map))))]
    (d/transact conn [entity])
    (let [embedding (when-not (str/blank? text)
                      (compute-embedding store text))
          indexed?  (boolean
                     (when embedding
                       (try
                         (d/add-vec vec-index msg-id embedding)
                         true
                         (catch Exception e
                           (println "Warning: vector index failed for" msg-id ":"
                                    (.getMessage e))
                           false))))
          result    (cond-> {:msg-id msg-id :stored true :indexed indexed?}
                      (and (not (str/blank? text)) (not indexed?))
                      (assoc :reason (if embedding "index-failed" "embedding-failed")))]
      (when (and (not (str/blank? text)) (not indexed?))
        (println "Warning: message stored without vector index:" msg-id
                 (:reason result)))
      (when-not (m/validate schemas/StoreResult result)
        (throw (ex-info "Invalid store result" {:result result})))
      result)))

;; ---- Search ---------------------------------------------------------------

(def ^:private msg-pull-pattern
  [:msg/id :msg/role :msg/text :msg/timestamp :msg/tool-calls :msg/tool-call-id
   :msg/tool-name :msg/tool-result :msg/kind :msg/topic :msg/tags])

(defn- sort-memory-msgs [msgs]
  (vec (sort-by :msg/timestamp msgs)))

(defn- brute-force-search
  "Fallback search: return most recent messages for session, limited by top-y."
  [conn session-id top-y]
  (try
    (let [msgs (d/q '[:find (pull ?e msg-pull-pattern)
                      :in $ ?sid msg-pull-pattern
                      :where
                      [?e :msg/session ?sid]]
                    (d/db conn) session-id msg-pull-pattern)]
      (->> (map first msgs)
           sort-memory-msgs
           (take-last top-y)
           vec))
    (catch Exception e
      (println "Warning: brute-force search failed:" (.getMessage e))
      [])))

(defn- lookup-messages
  "Given ordered msg-ids from vector search, look up full message data.
   Preserves similarity ranking from HNSW search."
  [conn msg-ids]
  (when (seq msg-ids)
    (try
      (let [id-set (set msg-ids)
            pulls  (d/q '[:find (pull ?e msg-pull-pattern)
                          :in $ ?ids msg-pull-pattern
                          :where [?e :msg/id ?id] [(contains? ?ids ?id)]]
                        (d/db conn) id-set msg-pull-pattern)
            by-id  (into {} (map (fn [[m]] [(:msg/id m) m]) pulls))]
        (vec (keep by-id msg-ids)))
      (catch Exception _ []))))

(defn search-relevant!
  "Search for messages semantically relevant to query-text.
   Uses vector similarity search (search-vec) when embeddings are available,
   falls back to brute-force chronological search otherwise."
  [store query-text session-id top-y]
  (when (and query-text (not (str/blank? query-text)) session-id)
    (let [top-y (or top-y 10)]
      (if-let [query-emb (compute-embedding store query-text)]
        ;; Semantic search via vector index
        (try
          (let [vec-index (:vec-index store)
                conn      (:connection store)
                neighbors (d/search-vec vec-index query-emb {:top top-y})
                msg-ids   (vec neighbors)]
            (if (seq msg-ids)
              (lookup-messages conn msg-ids)
              []))
          (catch Exception e
            (println "Warning: vector search failed, falling back:" (.getMessage e))
            (brute-force-search (:connection store) session-id top-y)))
        ;; No embedding available: brute-force
        (brute-force-search (:connection store) session-id top-y)))))

(defn load-recent-messages!
  "Return the most recent messages for a session, sorted chronologically."
  [store session-id limit]
  (when (and store session-id limit (pos? limit))
    (brute-force-search (:connection store) session-id limit)))

;; ---- Close ----------------------------------------------------------------

(defn close-session-store
  "Close both the Datalog connection and the vector index."
  [store]
  (try
    (when-let [vec-index (:vec-index store)]
      (d/close-vector-index vec-index))
    (when-let [kv-store (:kv-store store)]
      (d/close-kv kv-store))
    (when-let [conn (:connection store)]
      (d/close conn))
    true
    (catch Exception e
      (println "Error closing Datalevin store:" (.getMessage e))
      false)))
