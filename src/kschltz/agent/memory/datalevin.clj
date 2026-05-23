(ns kschltz.agent.memory.datalevin
  "Datalevin-based session memory backend."
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

(def ^:private schema
  {:session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :session/model      {:db/valueType :db.type/string}
   :session/emb-method {:db/valueType :db.type/string}
   :session/emb-model  {:db/valueType :db.type/string}
   :msg/id           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :msg/session        {:db/valueType :db.type/string}
   :msg/role           {:db/valueType :db.type/string}
   :msg/text           {:db/valueType :db.type/string :db/embedding true :db.embedding/domains ["messages"]}
   :msg/timestamp      {:db/valueType :db.type/long}
   :msg/msg-vec        {:db/valueType :db.type/vec :db.vec/domains ["messages"]}
   :msg/tool-name      {:db/valueType :db.type/string}
   :msg/tool-result    {:db/valueType :db.type/string}})

(def ^:private default-opts
  {:embedding-opts {:provider :default}
   :embedding-domains {"messages" {:provider :default}}
   :vector-opts {:dimensions 384 :metric-type :cosine}
   :vector-domains {"messages" {:dimensions 384 :metric-type :cosine}}})

(defn create-session-store
  [session-id opts]
  (let [db-path     (str "sessions/" session-id)
        merged-opts (cond-> (merge default-opts opts)
                      (:embedding-dims opts) (assoc-in [:vector-opts :dimensions] (:embedding-dims opts)))
        merged-opts (if (:embedding-dims opts)
                      (assoc-in merged-opts [:vector-domains "messages" :dimensions] (:embedding-dims opts))
                      merged-opts)
        conn (try
               (d/create-conn db-path schema merged-opts)
               (catch Throwable _
                 (d/create-conn db-path (dissoc schema :msg/text :msg/msg-vec) merged-opts)))]
    (when-let [model (get opts :model)]
      (d/transact conn [{:session/id session-id :session/model model}]))
    conn))

(defn store-message!
  [conn message-map]
  (let [msg-id     (or (:id message-map) (:msg/id message-map) (str "msg-" (System/currentTimeMillis) "-" (rand-int 100000)))
        role       (or (:role message-map) (:msg/role message-map) "assistant")
        text       (or (:text message-map) (:msg/text message-map) "")
        timestamp  (or (:timestamp message-map) (:msg/timestamp message-map) (System/currentTimeMillis))
        session-id (or (:session-id message-map) (:msg/session message-map))
        entity     (cond-> {:msg/id msg-id :msg/role role :msg/text text :msg/timestamp timestamp}
                     session-id                              (assoc :msg/session session-id)
                     (not-empty (:tool-name message-map))    (assoc :msg/tool-name (:tool-name message-map))
                     (not-empty (:tool-result message-map))  (assoc :msg/tool-result (:tool-result message-map)))]
    (d/transact conn [entity])
    (let [db-after (d/db conn)]
      (d/q '[:find ?e . :in $ ?ts ?txt :where [?e :msg/timestamp ?ts] [?e :msg/text ?txt]]
            db-after timestamp text))))

(defn search-relevant!
  [conn query-text session-id top-y]
  (when (and query-text (not (str/blank? query-text)) session-id)
    (let [top-y (or top-y 10)]
      (try
        (let [results (d/q '[:find ?id ?text ?role ?ts
                             :in $ ?q ?sid ?top
                             :where
                             [(embedding-neighbors $ :msg/text ?q {:top ?top}) [[?e _ _]]]
                             [?e :msg/id ?id]
                             [?e :msg/text ?text]
                             [?e :msg/role ?role]
                             [?e :msg/timestamp ?ts]
                             [?e :msg/session ?sid]]
                           (d/db conn) query-text session-id top-y)]
          (mapv (fn [[id text role ts]]
                  {:msg/id id :msg/role role :msg/text text :msg/timestamp ts})
                results))
        (catch Exception e
          (println "Warning: search-relevant embedding search failed:" (.getMessage e))
          ;; Fallback to brute-force if embeddings not available
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
            (catch Exception e2
              (println "Warning: brute-force search also failed:" (.getMessage e2))
              [])))))))

(defn close-session-store
  [conn]
  (try
    (when conn (d/close conn))
    true
    (catch Exception e
      (println "Error closing Datalevin connection:" (.getMessage e))
      false)))

(defonce agent-state (agent nil))

(defn start!
  [session-id opts]
  (let [conn (create-session-store session-id opts)]
    (send agent-state #(assoc % :connection conn))
    (await agent-state)
    {:connection conn}))

(defn store!
  [message-map]
  (let [conn (-> @agent-state :connection)]
    (if conn
      (store-message! conn message-map)
      (println "Error: No active connection found"))))

(defn search!
  [query-text session-id top-y]
  (let [conn (-> @agent-state :connection)]
    (if conn
      (search-relevant! conn query-text session-id top-y)
      (println "Error: No active connection found"))))

(defn close!
  []
  (let [conn (-> @agent-state :connection)]
    (if conn
      (close-session-store conn)
      true)))

(defn active-session
  []
  (-> @agent-state :connection))
