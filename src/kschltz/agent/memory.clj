(ns kschltz.agent.memory
  "Memory multimethods for session storage, retrieval, and context composition.

  Backends (dispatch on :backend in opts):
    :datalevin  — Datalog + vector search

  Strategies (dispatch on :strategy in opts):
    :hybrid     — recent + deduped relevant

  Usage:
    (create-session {:backend :datalevin :session-id \"my-session\" :model \"...\"})
    (store-message {:backend :datalevin :session-id \"my-session\" :message {...}})
    (retrieve-relevant {:backend :datalevin :session-id \"my-session\" :query \"...\" :limit 10})
    (compose {:strategy :hybrid :relevant [...] :recent [...]})
    (close-session {:backend :datalevin :session-id \"my-session\"})"
  (:require [kschltz.agent.memory.datalevin :as dlevin]))

(defn backend-dispatch
  "Extract the storage backend from opts."
  [opts]
  (:backend opts))

(defn strategy-dispatch
  "Extract the composition strategy from opts."
  [opts]
  (:strategy opts))

;; ---- Storage Multimethods ----

(defmulti create-session
  "Create a new session store."
  backend-dispatch)

(defmethod create-session :default
  [opts]
  (throw (ex-info (str "Unknown memory backend: " (backend-dispatch opts))
                  {:opts opts})))

(defmethod create-session :datalevin
  [{:keys [session-id model] :as opts}]
  (let [conn (dlevin/create-session-store session-id (select-keys opts [:model]))]
    {:connection conn}))

(defmulti store-message
  "Store a message in the session."
  backend-dispatch)

(defmethod store-message :default
  [opts]
  (throw (ex-info (str "Unknown memory backend: " (backend-dispatch opts))
                  {:opts opts})))

(defmethod store-message :datalevin
  [{:keys [session-id message] :as opts}]
  (let [conn (get-in opts [:connection])
        msg  (assoc message :session-id session-id)]
    (if conn
      (dlevin/store-message! conn msg)
      (throw (ex-info "No connection in opts" {:opts opts})))))

(defmulti retrieve-relevant
  "Retrieve relevant messages via embedding similarity."
  backend-dispatch)

(defmethod retrieve-relevant :default
  [opts]
  (throw (ex-info (str "Unknown memory backend: " (backend-dispatch opts))
                  {:opts opts})))

(defmethod retrieve-relevant :datalevin
  [{:keys [session-id query limit connection] :as opts}]
  (let [conn (or connection
                (throw (ex-info "No connection in opts" {:opts opts})))
        q    (or query
                 (throw (ex-info "Missing :query" {:opts opts})))
        sid  (or session-id
                 (throw (ex-info "Missing :session-id" {:opts opts})))
        top  (or limit 10)]
    (dlevin/search-relevant! conn q sid top)))

(defmulti close-session
  "Close the session store."
  backend-dispatch)

(defmethod close-session :default
  [opts]
  (throw (ex-info (str "Unknown memory backend: " (backend-dispatch opts))
                  {:opts opts})))

(defmethod close-session :datalevin
  [{:keys [connection] :as opts}]
  (if connection
    (dlevin/close-session-store connection)
    true))

;; ---- Composition Strategies ----

(defmulti compose
  "Compose context from relevant and recent messages."
  strategy-dispatch)

(defmethod compose :default
  [opts]
  (throw (ex-info (str "Unknown composition strategy: " (strategy-dispatch opts))
                  {:opts opts})))

(defmethod compose :hybrid
  [{:keys [relevant recent relevant-limit recent-limit]}]
  (let [recent-items (take-last (or recent-limit 5) recent)
        recent-set   (into #{} (keep :msg/id) recent-items)
        rel-unique   (remove #(contains? recent-set (:msg/id %)) relevant)
        rel-items    (take (or relevant-limit 5) rel-unique)]
    (->> (concat recent-items rel-items)
         (sort-by :msg/timestamp)
         (vec))))