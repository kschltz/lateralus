(ns kschltz.agent.memory.datalevin
  "Datalevin-based session memory backend.
   Uses a Datalog store for structured message data and a standalone
   vector index for semantic similarity search. Embedding vectors
   are computed via an OpenAI-compatible /embeddings endpoint (e.g. Ollama)."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [kschltz.agent.http :as http]))

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
   :msg/tool-result    {:db/valueType :db.type/string}})

;; ---- Defaults -------------------------------------------------------------

(def ^:private default-embedding-dims 384)
(def ^:private default-embedding-model "nomic-embed-text")

;; ---- Session store creation -----------------------------------------------

(defn create-session-store
  "Create a session store: a Datalog connection + a standalone vector index.
   opts may include:
     :embedding-dims  - vector dimensionality (default 384)
     :embedding-model - model name for /embeddings (default nomic-embed-text)
     :base-url        - Ollama/API base URL (default http://localhost:11434)
     :api-key         - API key for remote providers
     :embedding-fn   - custom (fn [text] => vec) override
     :model           - LLM model name (stored as metadata)"
  [session-id opts]
  (let [db-path      (str "sessions/" session-id)
        emb-dims     (or (:embedding-dims opts) default-embedding-dims)
        emb-model    (or (:embedding-model opts) default-embedding-model)
        base-url     (or (:base-url opts) "http://localhost:11434")
        api-key      (:api-key opts)
        embedding-fn (:embedding-fn opts)
        conn (try
               (d/create-conn db-path schema)
               (catch Throwable _
                 (try
                   (.exec (Runtime/getRuntime) (str "rm -rf " db-path))
                   (d/create-conn db-path schema)
                   (catch Throwable t
                     (throw (ex-info "Failed to create Datalevin conn"
                                     {:session-id session-id
                                      :error      (.getMessage t)}))))))
        kv-store (try
                   (d/open-kv (str db-path "/vectors"))
                   (catch Throwable _
                     (try
                       (.exec (Runtime/getRuntime) (str "rm -rf " db-path "/vectors"))
                       (d/open-kv (str db-path "/vectors"))
                       (catch Throwable t
                         (throw (ex-info "Failed to open vector KV store"
                                         {:session-id session-id
                                          :error      (.getMessage t)}))))))
        vec-index (d/new-vector-index kv-store
                                      {:dimensions emb-dims :metric-type :cosine})]
    (when-let [model (get opts :model)]
      (d/transact conn [{:session/id session-id :session/model model}]))
    {:connection     conn
     :kv-store       kv-store
     :vec-index      vec-index
     :embedding-dims emb-dims
     :embedding-model emb-model
     :base-url       base-url
     :api-key        api-key
     :embedding-fn   embedding-fn
     :session-id     session-id}))

;; ---- Embedding computation ------------------------------------------------

(defn- compute-embedding
  "Compute embedding vector for text. Uses custom :embedding-fn if provided,
   otherwise calls the /embeddings endpoint via http/embed.
   Returns vector of floats or nil on failure."
  [store text]
  (let [emb-fn (:embedding-fn store)]
    (if emb-fn
      (try (emb-fn text) (catch Exception _ nil))
      (try
        (http/embed (:base-url store)
                    (:api-key store)
                    (:embedding-model store)
                    text)
        (catch Exception _ nil)))))

;; ---- Store messages -------------------------------------------------------

(defn store-message!
  "Store a message in the Datalog store and index its embedding vector."
  [store message-map]
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
                      session-id                            (assoc :msg/session session-id)
                      (not-empty (:tool-name message-map))  (assoc :msg/tool-name (:tool-name message-map))
                      (not-empty (:tool-result message-map)) (assoc :msg/tool-result (:tool-result message-map)))]
    (d/transact conn [entity])
    (when (not (str/blank? text))
      (when-let [emb (compute-embedding store text)]
        (try
          (d/add-vec vec-index msg-id emb)
          (catch Exception _ nil))))))

;; ---- Search ---------------------------------------------------------------

(defn- brute-force-search
  "Fallback search: return most recent messages for session, limited by top-y."
  [conn session-id top-y]
  (try
    (let [results (d/q '[:find ?id ?text ?role ?ts
                         :in $ ?sid
                         :where
                         [?e :msg/id ?id]
                         [?e :msg/text ?text]
                         [?e :msg/role ?role]
                         [?e :msg/timestamp ?ts]
                         [?e :msg/session ?sid]]
                       (d/db conn) session-id)]
      (->> results
           (sort-by (fn [r] (nth r 3)))
           (take top-y)
           (mapv (fn [[id text role ts]]
                   {:msg/id id :msg/role role :msg/text text :msg/timestamp ts}))))
    (catch Exception e
      (println "Warning: brute-force search failed:" (.getMessage e))
      [])))

(defn- lookup-messages
  "Given a list of msg-ids from vector search, look up full message data."
  [conn msg-ids]
  (let [id-set (set msg-ids)]
    (try
      (let [results (d/q '[:find ?id ?text ?role ?ts
                           :in $ ?ids
                           :where
                           [?e :msg/id ?id]
                           [?e :msg/text ?text]
                           [?e :msg/role ?role]
                           [?e :msg/timestamp ?ts]
                           [(contains? ?ids ?id)]]
                         (d/db conn) id-set)]
        (mapv (fn [[id text role ts]]
                {:msg/id id :msg/role role :msg/text text :msg/timestamp ts})
              results))
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

;; ---- Legacy agent-state adapter -------------------------------------------

(defonce agent-state (agent nil))

(defn start!
  [session-id opts]
  (let [store (create-session-store session-id opts)]
    (send agent-state
          (fn [s]
            (when (and s (:connection s))
              (close-session-store s))
            store))
    (await agent-state)
    store))

(defn store!
  [message-map]
  (let [store @agent-state]
    (if (and store (:connection store))
      (store-message! store message-map)
      (println "Error: No active session store found"))))

(defn search!
  [query-text session-id top-y]
  (let [store @agent-state]
    (if (and store (:connection store))
      (search-relevant! store query-text session-id top-y)
      (println "Error: No active session store found"))))

(defn close!
  []
  (let [store @agent-state]
    (if (and store (:connection store))
      (close-session-store store)
      true)))

(defn active-session
  []
  @agent-state)