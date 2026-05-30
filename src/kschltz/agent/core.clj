(ns kschltz.agent.core
  "Agent core — orchestrates LLM calls, tool execution, chat state,
  and optional session memory.

  All public functions take an agent (Clojure agent reference type)
  as their first argument: (send-message! ag \"hello\")

  Memory is optional: pass :session-id to make-agent to enable
  Datalevin-backed hybrid memory (semantic search + recent context)."
  (:refer-clojure :exclude [reset!])
  (:require [kschltz.agent.context :as context]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.repl :as repl]
            [kschltz.agent.tools.web :as web]
            [kschltz.agent.tools.remember :as remember]
            [kschltz.agent.tools.portal :as portal]
            [kschltz.agent.tools.rewrite :as rewrite]
            [clojure.string :as str]))

;; ---- REPL Usage ----
;;
;; (require '[kschltz.agent.core :as agent])
;;
;; ;; Create and start (local Ollama)
;; (def ag (agent/make-agent {:base-url "http://localhost:11434"
;;                            :model "deepseek-v4-flash:cloud"
;;                            :turns 5}))
;; (future (agent/start! ag))
;;
;; ;; With session memory + tools
;; (def ag (agent/make-agent {:base-url "http://localhost:11434"
;;                            :model "deepseek-v4-flash:cloud"
;;                            :turns 5
;;                            :session-id "my-session"}))
;; (agent/add-repl-eval-tool! ag)
;; (future (agent/start! ag))
;;
;; ;; Send messages to the queue (loop must be running)
;; ;; Returns a promise — deref to block
;; (def p1 (agent/send-message! ag "Hello, who are you?"))
;; @p1
;;
;; ;; With handler callback (runs async on response)
;; (def p2 (agent/send-message! ag "Evaluate (+ 1 2 3)" (fn [r] (println "Got:" r))))
;; @p2
;;
;; ;; Non-blocking check
;; (realized? p2)
;;
;; (agent/queue-size ag)
;;
;; ;; One-shot (no loop needed)
;; (agent/chat! ag "What is Clojure?")
;;
;; ;; Inspect state
;; (agent/running? ag)
;; (agent/get-history ag)
;; (agent/get-session-id ag)
;;
;; ;; Interrupt or reset
;; (agent/stop! ag)
;; (agent/reset! ag)

(def ^:const maximum-message-queue-size 1000)
(def ^:const default-history-limit 50) ;; Keep last N messages in state; older ones live in Datalevin
(def ^:const default-memory-max-chars 500)

(def ^:private default-state
  "Initial agent state map."
  {:running        false
   :turns          0
   :max-turns      100
   :history        []
   :tools          []
   :current-response nil
   :session-id     nil
   :memory-store   nil
   :memory-backend nil
   :base-url       nil
   :api-key        nil
   :model          nil
   :on-response    nil
   :on-error       nil
   :on-memory-event nil
   :message-queue  []
   ;; Memory config (env var fallbacks)
   :memory-relevant-limit nil
   :memory-recent-limit   nil
   :memory-strategy       nil
   :memory-embedding-dims nil
   :memory-embedding-model nil
   :memory-embedding-method nil
   :history-limit    nil
   :memory-max-chars nil
   :sessions-dir     nil
   ;; Tool config
   :max-tool-calls nil
   ;; Retry config
   :max-retries      nil})

;; ---- Env Var Defaults ----

(defn- env-or
  "Get value from env var, or fall back to default. Parses integers."
  ([env-key default]
   (env-or env-key default nil))
  ([env-key default parse-fn]
   (if-let [v (System/getenv env-key)]
     (if parse-fn
       (try (parse-fn v) (catch Exception _ default))
       v)
     default)))

(def ^:private config-defaults
  "Defaults for memory config, with env var fallbacks."
  {:memory-relevant-limit (fn [] (env-or "LATERALUS_MEMORY_RELEVANT_LIMIT" 5 #(Integer/parseInt %)))
   :memory-recent-limit   (fn [] (env-or "LATERALUS_MEMORY_RECENT_LIMIT" 10 #(Integer/parseInt %)))
   :memory-strategy       (fn [] (env-or "LATERALUS_MEMORY_STRATEGY" :hybrid keyword))
   :memory-embedding-dims (fn [] (env-or "LATERALUS_MEMORY_EMBEDDING_DIMS" 384 #(Integer/parseInt %)))
   :memory-embedding-model (fn [] (env-or "LATERALUS_EMBEDDING_MODEL" "all-minilm-l6-v2-q"))
   :memory-embedding-method (fn [] (env-or "LATERALUS_EMBEDDING_METHOD" :langchain4j keyword))
   :history-limit        (fn [] (env-or "LATERALUS_HISTORY_LIMIT" default-history-limit #(Integer/parseInt %)))
   :memory-max-chars     (fn [] (env-or "LATERALUS_MEMORY_MAX_CHARS" default-memory-max-chars #(Integer/parseInt %)))
   :sessions-dir         (fn [] (env-or "LATERALUS_SESSIONS_DIR" "sessions"))
   :max-tool-calls       (fn [] (env-or "LATERALUS_MAX_TOOL_CALLS" 10 #(Integer/parseInt %)))
   :max-retries          (fn [] (env-or "LATERALUS_MAX_RETRIES" 3 #(Integer/parseInt %)))})

(defn- resolve-config
  "Resolve config: explicit opt > env var > default."
  [opts]
  (into {}
        (for [[k default-fn] config-defaults]
          [k (if (contains? opts k)
               (opts k)
               (default-fn))])))

;; ---- Public API (delegated to context ns) ----

(defn compose-context
  "Build memory-augmented context for the LLM call.
   Delegates to kschltz.agent.context/compose-context."
  [state user-input]
  (context/compose-context state user-input))

(defn- default-agent-tools
  [memory-store session-id memory-backend]
  (vec (remove nil?
               [(repl/repl-eval-tool)
                (rewrite/clj-edit-tool)
                (web/web-search-tool)
                (portal/visualize-tool)
                (when (and memory-store session-id)
                  (remember/remember-tool
                   {:search-fn (fn [{:keys [query limit]}]
                                 (memory/retrieve-relevant
                                  {:backend memory-backend
                                   :store memory-store
                                   :session-id session-id
                                   :query query
                                   :limit (or limit 5)}))}))])))

(defn- merge-tools
  "Append default tools without duplicating names from user tools."
  [user-tools default-tools]
  (let [names (set (map :name user-tools))]
    (into (vec user-tools)
          (remove #(contains? names (:name %)) default-tools))))

;; ---- Agent Construction ----

(defn make-agent
  "Create a new agent (Clojure agent reference type) holding state.

  Options:
    :base-url                  — LLM API base URL
    :api-key                   — API key (optional)
    :model                     — Model ID
    :turns                     — Max turns (default 100)
    :tools                     — Tool vector (optional)
    :initial                   — Initial messages (optional)
    :session-id                — Session ID for memory; defaults to \"default\" when omitted.
                                Pass nil or :memory-enabled false to disable memory.
    :memory-backend            — Memory backend (default :datalevin)
    :memory-relevant-limit     — Relevant messages to retrieve (env: LATERALUS_MEMORY_RELEVANT_LIMIT, default 5)
    :memory-recent-limit       — Recent context messages (env: LATERALUS_MEMORY_RECENT_LIMIT, default 10)
    :memory-strategy           — Composition strategy (env: LATERALUS_MEMORY_STRATEGY, default :hybrid)
    :memory-embedding-dims     — Embedding dimensions (env: LATERALUS_MEMORY_EMBEDDING_DIMS, default 384)
    :memory-embedding-model    — Embedding model (env: LATERALUS_EMBEDDING_MODEL, default all-minilm-l6-v2-q)
    :memory-embedding-method   — Embedding backend (env: LATERALUS_EMBEDDING_METHOD, default :langchain4j; or :http)
    :history-limit             — Max messages kept in agent state (env: LATERALUS_HISTORY_LIMIT, default 50)
    :memory-max-chars          — Truncate messages in LLM context only (env: LATERALUS_MEMORY_MAX_CHARS, default 500)
    :sessions-dir              — Session storage root (env: LATERALUS_SESSIONS_DIR, default sessions/)
    :max-tool-calls            — Max tool call rounds per message (env: LATERALUS_MAX_TOOL_CALLS, default 10)
    :max-retries               — Max retries on tool execution errors (env: LATERALUS_MAX_RETRIES, default 3)
    :on-response               — Default handler fn, called on every response (optional)
    :on-error                  — Error handler fn (ag, exception) => anything (optional, default: stop + rethrow)

  Returns: Clojure agent reference type."
  ([]
   (make-agent {}))
  ([opts]
   (let [{:keys [base-url api-key model turns tools initial
                 session-id memory-enabled memory-backend on-response on-error on-thought
                 memory-relevant-limit memory-recent-limit memory-strategy memory-embedding-dims
                 memory-embedding-model memory-embedding-method history-limit memory-max-chars sessions-dir
                 on-memory-event]
          :or   {turns 100 tools [] initial [] memory-backend :datalevin}} opts
         cfg           (resolve-config opts)
         session-id'   (when (not (false? memory-enabled))
                         (cond
                           (and (contains? opts :session-id) (nil? session-id)) nil
                           (contains? opts :session-id) session-id
                           :else "default"))
         embedding-dims (:memory-embedding-dims cfg)
         embedding-model (or memory-embedding-model (:memory-embedding-model cfg))
         embedding-method (or memory-embedding-method (:memory-embedding-method cfg))
         sessions-dir'  (or sessions-dir (:sessions-dir cfg))
         history-limit' (or history-limit (:history-limit cfg))
         memory-store    (when session-id'
                           (try
                             (:store (memory/create-session
                                      {:backend memory-backend
                                       :session-id session-id'
                                       :model model
                                       :embedding-dims embedding-dims
                                       :embedding-model embedding-model
                                       :embedding-method embedding-method
                                       :base-url base-url
                                       :api-key api-key
                                       :sessions-dir sessions-dir'}))
                             (catch Exception e
                               (println "Warning: failed to create memory session:"
                                        (.getMessage e))
                               nil)))
         default-tools   (default-agent-tools memory-store session-id' memory-backend)
         tools'          (merge-tools (vec tools) default-tools)
         loaded-history  (when (and memory-store session-id' (empty? initial))
                           (try
                             (memory/load-recent-messages
                              {:backend memory-backend
                               :session-id session-id'
                               :store memory-store
                               :limit history-limit'})
                             (catch Exception _ [])))
         start-history   (if (seq initial)
                           (vec initial)
                           (context/memory-msgs->chat-msgs (or loaded-history [])))]
     (clojure.core/agent (merge default-state
                                {:base-url       base-url
                                 :api-key        api-key
                                 :model          model
                                 :max-turns      turns
                                 :tools          tools'
                                 :history        start-history
                                 :session-id     session-id'
                                 :memory-store   memory-store
                                 :memory-backend memory-backend
                                 :sessions-dir   sessions-dir'
                                 :on-response   on-response
                                 :on-error      on-error
                                 :on-thought    on-thought
                                 :on-memory-event on-memory-event
                                 :memory-relevant-limit (:memory-relevant-limit cfg)
                                 :memory-recent-limit   (:memory-recent-limit cfg)
                                 :memory-strategy       (:memory-strategy cfg)
                                 :memory-embedding-dims (:memory-embedding-dims cfg)
                                 :memory-embedding-model (:memory-embedding-model cfg)
                                 :memory-embedding-method (:memory-embedding-method cfg)
                                 :history-limit         history-limit'
                                 :memory-max-chars      (or memory-max-chars (:memory-max-chars cfg))
                                 :max-tool-calls        (:max-tool-calls cfg)
                                 :max-retries            (:max-retries cfg)})))))

;; ---- Memory Helpers (pure, take state map) ----

(defn- close-memory
  "Close the active memory session if one exists. Returns updated state map."
  [state]
  (when-let [store (:memory-store state)]
    (try
      (memory/close-session {:backend (:memory-backend state) :store store})
      (catch Exception e
        (println "Warning: failed to close memory session:" (.getMessage e)))))
  (-> state
      (dissoc :memory-store)
      (dissoc :memory-backend)
      (dissoc :session-id)))

;; ---- Public API ----

(defn start!
  "Start the agent loop (blocking). Messages are sent via send-message!."
  [ag]
  (send ag assoc :running true)
  (await ag)
  (try
    (loop/agent-loop ag)
    (finally
      (send ag assoc :running false)
      (await ag)))
  @ag)

(defn stop!
  "Interrupt the agent loop."
  [ag]
  (send ag assoc :running false)
  (await ag)
  (println "Agent interrupt signal sent"))

(defn running?
  "Check if the agent loop is currently running."
  [ag]
  (:running @ag))

(defn send-message!
  "Enqueue a message for the running agent to process.
  Returns a Clojure promise that delivers the assistant response.
  Dereference to block: @p, check non-blocking: (realized? p).
  Optional handler fn called async on response. Errors caught and logged.
  On queue overflow, returns pre-delivered promise containing ::dropped."
  ([ag message]
   (send-message! ag message nil))
  ([ag message handler]
   (let [queue (:message-queue @ag)]
     (if (>= (count queue) maximum-message-queue-size)
       (do
         (println (str "Warning: message queue full (" maximum-message-queue-size "), dropping message"))
         (let [p (promise)]
           (deliver p ::dropped)
           p))
       (let [p (promise)
             item {:text message :promise p :handler handler}]
         (send ag update :message-queue conj item)
         (await ag)
         p)))))
(defn queue-size
  "Get the current number of messages waiting in the queue."
  [ag]
  (count (:message-queue @ag)))

(defn chat!
  "Send a single message and return the response immediately.
   Does not use the message queue or require the agent loop.
   Handles tool calls internally.
   Stores the exchange in session memory if active.

   Returns: assistant response string"
  ([ag message]
   (chat! ag message {}))
  ([ag message opts]
   (let [state    @ag
         {:keys [response transcript]} (loop/llm-turn ag (merge state (select-keys opts [:base-url :api-key :model]))
                                                      message)
         stored   (loop/store-exchange state message :transcript transcript)
         entries  (loop/history-entries-for-exchange [{:text message}] stored :transcript transcript)]
     (send ag update :history into entries)
     (await ag)
     (send ag context/cap-history)
     (await ag)
     response)))

(defn reset!
  "Reset agent runtime state: clear history, turns, queue, and current response.
   Keeps the memory session store open so persisted messages remain available."
  [ag]
  (send ag (fn [s]
             (assoc s :history [] :turns 0 :current-response nil :message-queue [])))
  (await ag)
  (println "Agent state reset"))

(defn close-session!
  "Close the memory session and remove session keys from agent state.
   Disk data is preserved; reopen with the same :session-id to resume."
  [ag]
  (send ag close-memory)
  (await ag)
  (println "Memory session closed"))

(defn get-history
  "Get the current chat history."
  [ag]
  (:history @ag))

(defn get-tools
  "Get the registered tools."
  [ag]
  (:tools @ag))

(defn get-memory-store
  "Get the current memory store (nil if memory not active)."
  [ag]
  (:memory-store @ag))

(defn get-memory-conn
  "Deprecated alias for get-memory-store."
  [ag]
  (get-memory-store ag))

(defn get-session-id
  "Get the current session ID (nil if memory not active)."
  [ag]
  (:session-id @ag))

(defn get-memory-config
  "Get the current memory configuration map."
  [ag]
  (select-keys @ag [:memory-relevant-limit :memory-recent-limit
                    :memory-strategy :memory-embedding-dims
                    :memory-embedding-model :memory-embedding-method
                    :memory-max-chars
                    :memory-backend :session-id :sessions-dir]))

(defn get-config
  "Get the full agent configuration map."
  [ag]
  (select-keys @ag [:base-url :model :max-turns :max-tool-calls :max-retries
                    :memory-relevant-limit :memory-recent-limit
                    :memory-strategy :memory-embedding-dims :memory-embedding-model
                    :memory-embedding-method
                    :memory-backend :session-id :sessions-dir]))

(defn set-on-response!
  "Set or replace the default handler fn called on every response.
   Pass nil to remove. Returns the agent."
  [ag handler-fn]
  (send ag assoc :on-response handler-fn)
  (await ag)
  ag)

(defn set-on-error!
  "Set or replace the error handler fn. Receives (ag, exception).
   Default: stop agent, log error, rethrow.
   Pass nil to restore default. Returns the agent."
  [ag handler-fn]
  (send ag assoc :on-error handler-fn)
  (await ag)
  ag)

(defn set-on-thought!
  "Set or replace the thought handler fn. Called on intermediate events:
   {:type :thinking :content reasoning-text}
   {:type :tool-call :content llm-text :calls [...]}
   {:type :tool-result :results [...]}
   Pass nil to remove. Returns the agent."
  [ag handler-fn]
  (send ag assoc :on-thought handler-fn)
  (await ag)
  ag)

(defn set-on-memory-event!
  "Set or replace the memory event handler fn. Called when a message is stored
   but not vector-indexed, e.g.:
   {:type :memory-not-indexed :role \"user\" :msg-id ... :reason \"embedding-failed\"}
   Pass nil to remove. Returns the agent."
  [ag handler-fn]
  (send ag assoc :on-memory-event handler-fn)
  (await ag)
  ag)

;; ---- Tool Registration ----

(defn register-tool!
  "Register a tool for the agent to use."
  [ag tool]
  (send ag update :tools conj tool)
  (await ag)
  tool)

(defn unregister-tool!
  "Unregister a tool by name."
  [ag name]
  (send ag update :tools (fn [tools] (vec (remove #(= (:name %) name) tools))))
  (await ag)
  true)

;; ---- REPL Tools ----

(defn add-repl-eval-tool!
  "Add a REPL eval tool to the agent."
  ([ag]
   (add-repl-eval-tool! ag {}))
  ([ag opts]
   (let [tool (repl/repl-eval-tool opts)]
     (register-tool! ag tool)
     tool)))

(defn add-clj-edit-tool!
  "Add a :clj-edit tool for structured Clojure/EDN source editing.
   Uses rewrite-clj for comment/formatting-preserving edits."
  ([ag]
   (add-clj-edit-tool! ag {}))
  ([ag opts]
   (let [tool (rewrite/clj-edit-tool opts)]
     (register-tool! ag tool)
     tool)))

(defn add-repl-nrepl-tool!
  "Add a nREPL tool to the agent."
  ([ag]
   (add-repl-nrepl-tool! ag {}))
  ([ag opts]
   (let [tool (repl/repl-nrepl-tool opts)]
     (register-tool! ag tool)
     tool)))

(defn add-visualize-tool!
  "Add a :visualize tool that lets the LLM display data in a Portal inspector.
   Requires djblue/portal on the classpath. If Portal is not available,
   the tool returns an error with installation instructions."
  ([ag]
   (add-visualize-tool! ag {}))
  ([ag opts]
   (let [tool (portal/visualize-tool opts)]
     (register-tool! ag tool)
     tool)))

(defn add-web-search-tool!
  "Add a DuckDuckGo web search tool to the agent."
  ([ag]
   (add-web-search-tool! ag {}))
  ([ag opts]
   (let [tool (web/web-search-tool opts)]
     (register-tool! ag tool)
     tool)))

(defn add-remember-tool!
  "Add a remember tool wired to the agent's memory store."
  ([ag]
   (add-remember-tool! ag {}))
  ([ag opts]
   (let [state    @ag
         search-fn (or (:search-fn opts)
                       (when (:memory-store state)
                         (fn [{:keys [query limit]}]
                           (memory/retrieve-relevant
                            {:backend     (:memory-backend state)
                             :store  (:memory-store state)
                             :session-id  (:session-id state)
                             :query       query
                             :limit       (or limit 5)}))))
         tool     (remember/remember-tool (merge opts {:search-fn search-fn}))]
     (register-tool! ag tool)
     tool)))

(comment
  ;; === Create and start (local Ollama) ===
  (require '[kschltz.agent.core :as agent])
  (def ag (agent/make-agent {:base-url "http://localhost:11434"
                             :model "deepseek-v4-flash:cloud"
                             :turns 5}))
  (agent/add-repl-eval-tool! ag)
  (future (agent/start! ag))

  ;; === With session memory + on-response handler ===
  (def ag (agent/make-agent {:base-url    "http://localhost:11434"
                             :model       "gemini-3-flash-preview:cloud"
                             :turns       5
                             :session-id  "my-session"
                             :on-response (fn [r] (println "Agent:" r))}))
  (agent/add-repl-eval-tool! ag)
  (future (agent/start! ag))

  ;; === Send messages to the queue ===
  ;; Returns a promise — deref to block for response
  (def p1 (agent/send-message! ag "What did we talk about?"))
  (deref p1)

  ;; With handler callback (runs async on response)
  (def p2 (agent/send-message! ag "Evaluate (+ 1 2 3)"
                               (fn [r] (println "Got:" r))))
  (deref p2)

  ;; Non-blocking check
  (realized? p2)

  (agent/queue-size ag)

  ;; === One-shot (no loop needed) ===
  (def r (agent/send-message!
          ag
          "I meant I want you to read the online docs for repl based clojure tools"))
  (deref r 20000 ::timeout)
  ;; === Inspect state ===
  (agent/running? ag)
  (agent/get-history ag)
  (agent/get-session-id ag)
  (agent/get-config ag)

  ;; === Interrupt or reset ===
  (agent/stop! ag)
  (agent/reset! ag)

  ;; === Default response handler (runs on every response) ===
  (agent/set-on-response! ag (fn [r] (println "Agent:" r)))
  (agent/set-on-error! ag (fn [ag e] (prn "Agent: " "Error:" (.getMessage e))))

  ;; === Error handler ===
  ;; Default: stop agent, log error, rethrow
  ;; Custom: e.g. log and continue
  (agent/set-on-error! ag (fn [ag e] (println "Error:" (.getMessage e))))
  (agent/set-on-error! ag nil)

  ;; === Add tools ===
  (agent/add-repl-eval-tool! ag)
  (agent/add-repl-nrepl-tool! ag {:port 59500})
  (agent/get-tools ag)

  ;; === Memory config ===
  ;; Env vars: LATERALUS_MEMORY_RELEVANT_LIMIT, LATERALUS_MEMORY_RECENT_LIMIT,
  ;;           LATERALUS_MEMORY_STRATEGY, LATERALUS_MEMORY_EMBEDDING_DIMS
  (def ag (agent/make-agent {:base-url  "http://localhost:11434"
                             :model     "deepseek-v4-flash:cloud"
                             :turns     5
                             :session-id "my-session"
                             :memory-relevant-limit 10
                             :memory-recent-limit   20
                             :memory-embedding-dims 384}))
  (agent/get-config ag)

  ;; === Direct memory API ===
  (require '[kschltz.agent.memory :as memory])
  (def session (memory/create-session {:backend    :datalevin
                                       :session-id "demo"
                                       :model      "deepseek-v4-flash:cloud"}))
  (memory/store-message {:backend    :datalevin
                         :session-id "demo"
                         :store      (:store session)
                         :message    {:role "user" :text "Hello"}})
  (memory/retrieve-relevant {:backend    :datalevin
                             :session-id "demo"
                             :store      (:store session)
                             :query      "hello" :limit 5})
  (memory/close-session {:backend    :datalevin
                         :store      (:store session)}))
