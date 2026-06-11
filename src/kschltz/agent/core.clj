(ns kschltz.agent.core
  "Agent core — orchestrates LLM calls, tool execution, chat state,
  and optional session memory.

  All public functions take an agent (Clojure agent reference type)
  as their first argument: (send-message! ag \"hello\")

  Memory is optional: pass :session-id to make-agent to enable
  Datalevin-backed hybrid memory (semantic search + recent context).

  Plugins (Phase 5): the default tool set is assembled from a
  plugin list. Pass :plugins [] for a bare agent, or override
  individual tools by passing only the plugins you want."
  (:refer-clojure :exclude [reset!])
  (:require [kschltz.agent.context :as context]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.plugin :as plugin]
            [kschltz.agent.plugins.clj-edit :as p-clj-edit]
            [kschltz.agent.plugins.defaults :as p-defaults]
            [kschltz.agent.plugins.portal :as p-portal]
            [kschltz.agent.plugins.remember :as p-remember]
            [kschltz.agent.plugins.repl :as p-repl]
            [kschltz.agent.plugins.web :as p-web]
            [clojure.string :as str]))

;; ---- REPL Usage ----
;;
;; (require '[kschltz.agent.core :as agent])
;;
;; (def ag (agent/make-agent {:base-url \"http://localhost:11434\"
;;                             :model    \"deepseek-v4-flash:cloud\"
;;                             :turns    5}))
;; (agent/start! ag)
;; (agent/send-message! ag \"What is 2+2?\")

;; ---- Constants ----

(def ^:const maximum-message-queue-size 1000)
(def ^:const default-history-limit 50) ;; Keep last N messages in state; older ones live in Datalevin
(def ^:const default-memory-max-chars 500)

;; ---- Default State ----

(def ^:private default-state
  {:running        false
   :message-queue  []
   :tools          []
   :history        []
   :turns          0
   :max-turns      100
   :max-tool-calls 10
   :max-retries    3
   :stop-on-error  false
   :on-thought     (fn [_])})

;; ---- Config ----

(defn- env-or
  "Return env var value if set, otherwise the fallback."
  [env-var fallback]
  (or (System/getenv env-var) fallback))

(def ^:private config-defaults
  {:memory-relevant-limit 5
   :memory-recent-limit   10
   :memory-strategy       :hybrid
   :memory-embedding-dims 384
   :memory-embedding-model "all-minilm-l6-v2-q"
   :memory-embedding-method :langchain4j
   :history-limit         default-history-limit
   :memory-max-chars      default-memory-max-chars
   :max-tool-calls        10
   :max-retries            3
   :sessions-dir          "sessions/"})

(defn- resolve-config
  "Layered config: defaults → env vars → explicit opts."
  []
  (let [env-cfg {:memory-relevant-limit (some-> (System/getenv "LATERALUS_MEMORY_RELEVANT_LIMIT") parse-long)
                 :memory-recent-limit   (some-> (System/getenv "LATERALUS_MEMORY_RECENT_LIMIT") parse-long)
                 :memory-strategy       (some-> (System/getenv "LATERALUS_MEMORY_STRATEGY") keyword)
                 :memory-embedding-dims (some-> (System/getenv "LATERALUS_MEMORY_EMBEDDING_DIMS") parse-long)
                 :memory-embedding-model (System/getenv "LATERALUS_EMBEDDING_MODEL")
                 :memory-embedding-method (some-> (System/getenv "LATERALUS_EMBEDDING_METHOD") keyword)
                 :history-limit         (some-> (System/getenv "LATERALUS_HISTORY_LIMIT") parse-long)
                 :memory-max-chars      (some-> (System/getenv "LATERALUS_MEMORY_MAX_CHARS") parse-long)
                 :max-tool-calls        (some-> (System/getenv "LATERALUS_MAX_TOOL_CALLS") parse-long)
                 :max-retries            (some-> (System/getenv "LATERALUS_MAX_RETRIES") parse-long)
                 :sessions-dir          (System/getenv "LATERALUS_SESSIONS_DIR")}]
    (merge config-defaults (into {} (remove (fn [[_ v]] (nil? v)) env-cfg)))))

;; ---- Agent Construction ----

(defn compose-context
  "Build a single user turn (used by CLI/tests; the chain
   `compose-context` interceptor is the live path)."
  [state user-input]
  (context/compose-context state user-input))

(defn- assemble-default-plugins
  "Compute the default plugin set for make-agent when no :plugins
   are supplied. Equivalent to the legacy 5 default tools, with
   remember added if memory is enabled."
  [memory-store]
  (if memory-store
    (conj p-defaults/plugin-bundle (p-remember/plugin))
    p-defaults/plugin-bundle))

(defn- apply-plugins
  "Apply each plugin's :plugin/register fn to the state, in
   declaration order. Returns the updated state."
  [state plugins]
  (reduce (fn [s plugin]
            (if-let [reg (:plugin/register plugin)]
              (reg s [])
              s))
          state
          plugins))

(defn- merge-tools
  "Append plugin-registered tools to user-supplied :tools, avoiding
   name collisions."
  [state user-tools]
  (let [user-names (set (map :name user-tools))
        plugin-tools (remove #(contains? user-names (:name %))
                             (:tools state))
        ;; Put user tools first, then plugin tools
        merged (into (vec user-tools) plugin-tools)]
    (assoc state :tools merged)))

(defn make-agent
  "Create a new agent (Clojure agent reference type) holding state.

  Options:
    :base-url                  — LLM API base URL
    :api-key                   — API key (optional)
    :model                     — Model ID
    :turns                     — Max turns (default 100)
    :tools                     — Tool vector (optional; merged with
                                 plugin-registered tools, your tools
                                 take precedence on name conflicts)
    :initial                   — Initial messages (optional)
    :plugins                   — Plugin list (default: standard
                                 toolset). Pass [] for a bare agent.
                                 See `kschltz.agent.plugins.*`.
    :session-id                — Session ID for memory; defaults to
                                 \"default\" when omitted. Pass nil or
                                 :memory-enabled false to disable memory.
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
    :on-thought                — Thought-event handler fn (event) (optional)
    :on-memory-event           — Memory-event handler fn (event) (optional)

  Returns: Clojure agent reference type."
  ([]
   (make-agent {}))
  ([opts]
   (let [{:keys [base-url api-key model turns tools initial plugins
                 session-id memory-enabled memory-backend on-response on-error on-thought
                 memory-relevant-limit memory-recent-limit memory-strategy memory-embedding-dims
                 memory-embedding-model memory-embedding-method history-limit memory-max-chars sessions-dir
                 on-memory-event max-tool-calls max-retries]
          :or   {turns 100 tools [] initial [] memory-backend :datalevin
                 plugins :default}} opts
         cfg           (resolve-config)
         session-id'   (when (not (false? memory-enabled))
                         (cond
                           (and (contains? opts :session-id) (nil? session-id)) nil
                           (contains? opts :session-id) session-id
                           :else "default"))
         embedding-dims (or memory-embedding-dims (:memory-embedding-dims cfg))
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
         ;; Plugin resolution: explicit :plugins, or :plugins :none
         ;; (empty vector), or the default set. The :plugins key is
         ;; validated by `plugin/validate-plugins` (throws ex-info on
         ;; bad shape, fast-fail at make-agent time).
         plugins-resolved (cond
                            (= plugins :default)
                            (assemble-default-plugins memory-store)
                            (nil? plugins)
                            (assemble-default-plugins memory-store)
                            (vector? plugins)
                            plugins
                            :else
                            (throw (ex-info "make-agent :plugins must be a vector of plugin maps (or omit for defaults)"
                                            {:plugins plugins})))
         _ (when-let [err (plugin/validate-plugins plugins-resolved)]
             (throw (ex-info "make-agent: invalid plugin map" {:explain err})))
         state-with-plugins (apply-plugins
                              (merge default-state
                                     {:base-url       base-url
                                      :api-key        api-key
                                      :model          model
                                      :max-turns      turns
                                      :history        []
                                      :session-id     session-id'
                                      :memory-store   memory-store
                                      :memory-backend memory-backend
                                      :sessions-dir   sessions-dir'
                                      :on-response   on-response
                                      :on-error      on-error
                                      :on-thought    on-thought
                                      :on-memory-event on-memory-event
                                      :memory-relevant-limit (or memory-relevant-limit (:memory-relevant-limit cfg))
                                      :memory-recent-limit   (or memory-recent-limit (:memory-recent-limit cfg))
                                      :memory-strategy       (or memory-strategy (:memory-strategy cfg))
                                      :memory-embedding-dims embedding-dims
                                      :memory-embedding-model embedding-model
                                      :memory-embedding-method embedding-method
                                      :history-limit         history-limit'
                                      :memory-max-chars      (or memory-max-chars (:memory-max-chars cfg))
                                      :max-tool-calls        (or (:max-tool-calls opts) (:max-tool-calls cfg))
                                      :max-retries            (or (:max-retries opts) (:max-retries cfg))})
                              plugins-resolved)
         state-with-tools  (merge-tools state-with-plugins tools)
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
     (clojure.core/agent (assoc state-with-tools :history start-history)))))

;; ---------------------------------------------------------------------------
;; Deprecated tool installers (Phase 5)
;;
;; The 8 add-*-tool! fns were the legacy way to install tools on an
;; agent. In Phase 5 they became one-line wrappers around the
;; plugin system: each fn builds a one-off plugin and applies its
;; :plugin/register fn. Kept for back-compat; log a deprecation
;; warning on first call.
;; ---------------------------------------------------------------------------

(def ^:private deprecation-warned (atom #{}))

(defn- deprecate!
  [fn-name]
  (when-not (contains? @deprecation-warned fn-name)
    (swap! deprecation-warned conj fn-name)
    (println (str "DEPRECATION: " fn-name
                  " is deprecated; use the plugin system instead. "
                  "See kschltz.agent.plugins.* and pass :plugins to make-agent."))))

(defn register-tool!
  "Register a tool for the agent to use. The tool is added to the
   `:tools` vector in agent state."
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

;; ---- DEPRECATED: REPL Tools ----

(defn- register-tool-from-plugin
  "Run a plugin's :plugin/register fn against a fresh empty tool
   state and extract the first registered tool. Used by the
   deprecated add-*-tool! wrappers."
  [plugin]
  (let [state (merge default-state {})
        out-state ((:plugin/register plugin) state [])
        tool (first (:tools out-state))]
    tool))

(defn add-repl-eval-tool!
  "DEPRECATED: use the `:repl-eval` plugin via `:plugins` instead."
  ([ag]
   (add-repl-eval-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-repl-eval-tool!")
   (register-tool! ag (register-tool-from-plugin p-repl/plugin))))

(defn add-clj-edit-tool!
  "DEPRECATED: use the `:clj-edit` plugin via `:plugins` instead."
  ([ag]
   (add-clj-edit-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-clj-edit-tool!")
   (register-tool! ag (register-tool-from-plugin p-clj-edit/plugin))))

(defn add-repl-nrepl-tool!
  "DEPRECATED: use the `:repl-nrepl` plugin via `:plugins` instead."
  ([ag]
   (add-repl-nrepl-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-repl-nrepl-tool!")
   (register-tool! ag (register-tool-from-plugin (p-repl/nrepl-plugin)))))

(defn add-visualize-tool!
  "DEPRECATED: use the `:portal-visualize` plugin via `:plugins` instead."
  ([ag]
   (add-visualize-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-visualize-tool!")
   (register-tool! ag (register-tool-from-plugin p-portal/plugin))))

(defn add-web-search-tool!
  "DEPRECATED: use the `:web-search` plugin via `:plugins` instead."
  ([ag]
   (add-web-search-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-web-search-tool!")
   (register-tool! ag (register-tool-from-plugin p-web/plugin))))

(defn add-remember-tool!
  "DEPRECATED: use the `:remember` plugin via `:plugins` instead."
  ([ag]
   (add-remember-tool! ag {}))
  ([ag _opts]
   (deprecate! "add-remember-tool!")
   (register-tool! ag (register-tool-from-plugin p-remember/plugin))))

;; ---------------------------------------------------------------------------
;; Agent Lifecycle & Inspection
;; ---------------------------------------------------------------------------

(defn start!
  "Start the agent loop in a future. Returns the future (for callers
  that want to await; most can ignore the return value)."
  [ag]
  (send ag assoc :running true)
  (await ag)
  (future (loop/agent-loop ag)))

(defn stop!
  "Stop the agent loop. Returns the agent."
  [ag]
  (send ag assoc :running false)
  (await ag)
  ag)

(defn running?
  "True if the agent is currently running."
  [ag]
  (:running @ag))

(defn send-message!
  "Enqueue a user message. Returns the promise (NOT the item map)
  that will be delivered with the response. Dereference to block:
  @p, or check non-blocking: (realized? p). On queue overflow,
  returns a pre-delivered promise containing ::dropped.

  Each queued item is a map:
    {:text      \"hello\"
     :promise   <promise>  ; delivered with the response
     :handler   <fn>       ; optional; called with the response}"
  ([ag message]
   (send-message! ag message nil))
  ([ag message handler]
   (let [queue (:message-queue @ag)]
     (if (>= (clojure.core/count queue) maximum-message-queue-size)
       (do
         (println (str "Warning: message queue full ("
                       maximum-message-queue-size
                       "), dropping message"))
         (let [p (promise)]
           (deliver p ::dropped)
           p))
       (let [p (promise)
             item {:text message :promise p :handler handler}]
         (send ag update :message-queue conj item)
         (await ag)
         p)))))

(defn queue-size
  "Current number of queued messages waiting to be processed."
  [ag]
  (clojure.core/count (:message-queue @ag)))

(defn chat!
  "One-shot chat: enqueue, run, deliver. Returns the response string.
  The agent is started and stopped around the call. Useful for tests
  and CLI one-shots."
  ([ag text]
   (chat! ag text {}))
  ([ag text opts]
   (let [p (promise)
         _  (send-message! ag text (assoc opts :promise p))
         _  (start! ag)]
     (try
       @p
       (finally (stop! ag))))))

(defn reset!
  "Reset the agent's :history, :turns, :message-queue, and
  :current-response. Memory session and tools are preserved."
  [ag]
  (send ag (fn [state]
             (-> state
                 (assoc :history [] :turns 0
                        :message-queue []
                        :current-response nil))))
  (await ag)
  ag)

(defn close-session!
  "Close the agent's memory session. After this, memory is inactive
  but tools and history are preserved."
  [ag]
  (send ag (fn [state]
             (when (:memory-store state)
               (try
                 (memory/close-session
                  {:backend (:memory-backend state)
                   :store   (:memory-store state)})
                 (catch Exception _)))
             (dissoc state :memory-store :session-id)))
  (await ag)
  ag)

;; ---- Inspection ----

(defn get-history
  "Return the agent's current :history vector (live snapshot)."
  [ag]
  (:history @ag))

(defn get-tools
  "Return the agent's current :tools vector."
  [ag]
  (:tools @ag))

(defn get-memory-store
  "Return the agent's memory store (the Datalevin conn map), or nil
  when memory is disabled."
  [ag]
  (:memory-store @ag))

(defn get-memory-conn
  "Alias for get-memory-store (kept for back-compat)."
  [ag]
  (get-memory-store ag))

(defn get-session-id
  "Return the agent's session ID, or nil when memory is disabled."
  [ag]
  (:session-id @ag))

(defn get-memory-config
  "Return the agent's memory config map (backend, dims, model, method)."
  [ag]
  (let [s @ag]
    (cond-> {:backend (:memory-backend s)}
      (:session-id s)        (assoc :session-id (:session-id s))
      (:memory-embedding-method s) (assoc :embedding-method (:memory-embedding-method s))
      (:memory-embedding-model s)  (assoc :embedding-model (:memory-embedding-model s))
      (:memory-embedding-dims s)   (assoc :embedding-dims (:memory-embedding-dims s)))))

(defn get-config
  "Return the agent's full state map (live snapshot)."
  [ag]
  @ag)

;; ---- Callback Setters ----

(defn set-on-response!
  "Set the default response handler."
  [ag f]
  (send ag assoc :on-response f)
  (await ag)
  ag)

(defn set-on-error!
  "Set the error handler."
  [ag f]
  (send ag assoc :on-error f)
  (await ag)
  ag)

(defn set-on-thought!
  "Set the thought-event handler."
  [ag f]
  (send ag assoc :on-thought f)
  (await ag)
  ag)

(defn set-on-memory-event!
  "Set the memory-event handler."
  [ag f]
  (send ag assoc :on-memory-event f)
  (await ag)
  ag)
