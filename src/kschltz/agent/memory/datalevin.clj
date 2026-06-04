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
   :msg/indexed        {:db/valueType :db.type/boolean}
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

(defn- corrupt-indicator?
  "True when the exception strongly suggests Datalevin/LMDB corruption
   (e.g. map/validation errors, missing required keys in stored data).
   Disk-full, permissions, and other transient errors should NOT trigger recovery."
  [^Throwable e]
  (let [msg (.getMessage e)]
    (boolean
      (some #(re-find % (or msg ""))
            [#"(?i)corrupt"
             #"(?i)invalid header"
             #"(?i)bad page"
             #"(?i)map validation"
             #"(?i)read-only|read only"
             #"(?i) MDB_PAGE_NOTFOUND"
             #"(?i) MDB_CORRUPT"]))))

(defn- create-conn-with-recovery
  "Open a Datalevin conn; only renames the dir on corruption indicators,
   not on transient errors (disk full, permissions, etc.).
   Rethrows non-corruption exceptions so the caller can handle them."
  [db-path session-id schema]
  (try
    (d/create-conn db-path schema)
    (catch Throwable e
      (if (corrupt-indicator? e)
        (do
          (println "Warning: corrupt session store, renaming:" db-path
                   (.getMessage e))
          (rename-corrupt-dir! (io/file db-path))
          (d/create-conn db-path schema))
        (do
          (println "Error: failed to open session store:" db-path
                   (.getMessage e))
          (throw e))))))

(defn- open-kv-with-recovery
  "Open vector KV store; only renames the dir on corruption indicators.
   Rethrows non-corruption exceptions so the caller can handle them."
  [kv-path session-id]
  (try
    (d/open-kv kv-path)
    (catch Throwable e
      (if (corrupt-indicator? e)
        (do
          (println "Warning: corrupt vector store, renaming:" kv-path
                   (.getMessage e))
          (rename-corrupt-dir! (io/file kv-path))
          (d/open-kv kv-path))
        (do
          (println "Error: failed to open vector store:" kv-path
                   (.getMessage e))
          (throw e))))))

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

(defn reindex-pending!
  "Scan for messages with :msg/indexed false and retry vector indexing.
   Called on session startup to recover from crashes between Datalog write
   and vector index write. Returns count of messages successfully reindexed."
  [store]
  (let [conn       (:store store)
        vec-index  (:vec-index store)]
    (when (and conn vec-index)
      (try
        (let [pending (d/q '[:find (pull ?e [:msg/id :msg/text])
                             :in $
                             :where
                             [?e :msg/indexed false]]
                           (d/db conn))
              ids    (mapv (comp :msg/id first) pending)
              texts  (mapv (comp :msg/text first) pending)]
          (when (seq ids)
            (println "Reindexing" (count ids) "pending messages...")
            (reduce
              (fn [reindexed [msg-id text]]
                (if-let [emb (compute-embedding store text)]
                  (try
                    (d/add-vec vec-index msg-id emb)
                    (d/transact conn [[:db/add [:msg/id msg-id] :msg/indexed true]])
                    (inc reindexed)
                    (catch Exception e
                      (println "Warning: reindex failed for" msg-id ":"
                               (.getMessage e))
                      reindexed))
                  reindexed))
              0
              (map vector ids texts))))
        (catch Exception e
          (println "Warning: reindex scan failed:" (.getMessage e))
          0)))))

;; ---- Session store creation -----------------------------------------------

(defn create-session-store
  "Create a session store: a Datalog store handle + a standalone vector index.
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
    (let [store-map {:store          conn
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
                     :db-path             db-path}]
      ;; Recover any messages left unindexed by a previous crash
      (reindex-pending! store-map)
      store-map)))

;; ---- Store messages -------------------------------------------------------

(defn store-message!
  "Store a message in the Datalog store and index its embedding vector.
   Returns {:msg-id ... :stored true :indexed bool :reason ...}."
  [store message-map]
  (when-not (m/validate schemas/StoreMessage message-map)
    (throw (ex-info "Invalid store message" {:message message-map})))
  (let [conn       (:store store)
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
    (d/transact conn [(assoc entity :msg/indexed false)])
    (let [embedding (when-not (str/blank? text)
                      (compute-embedding store text))
          indexed?  (boolean
                     (when embedding
                       (try
                         (d/add-vec vec-index msg-id embedding)
                         (d/transact conn [[:db/add [:msg/id msg-id] :msg/indexed true]])
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
  [:msg/id :msg/role :msg/text :msg/timestamp :msg/indexed :msg/tool-calls :msg/tool-call-id
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
                conn      (:store store)
                neighbors (d/search-vec vec-index query-emb {:top top-y})
                msg-ids   (vec neighbors)]
            (if (seq msg-ids)
              (lookup-messages conn msg-ids)
              []))
          (catch Exception e
            (println "Warning: vector search failed, falling back:" (.getMessage e))
            (brute-force-search (:store store) session-id top-y)))
        ;; No embedding available: brute-force
        (brute-force-search (:store store) session-id top-y)))))

(defn load-recent-messages!
  "Return the most recent messages for a session, sorted chronologically."
  [store session-id limit]
  (when (and store session-id limit (pos? limit))
    (brute-force-search (:store store) session-id limit)))

;; ---- Close ----------------------------------------------------------------

(defn close-session-store
  "Close both the Datalog store handle and the vector index."
  [store]
  (try
    (when-let [vec-index (:vec-index store)]
      (d/close-vector-index vec-index))
    (when-let [kv-store (:kv-store store)]
      (d/close-kv kv-store))
    (when-let [conn (:store store)]
      (d/close conn))
    true
    (catch Exception e
      (println "Error closing Datalevin store:" (.getMessage e))
      false)))
