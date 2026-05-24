(ns kschltz.agent.core
  "Agent core — orchestrates LLM calls, tool execution, chat state,
  and optional session memory.

  All public functions take an agent (Clojure agent reference type)
  as their first argument: (send-message! ag \"hello\")

  Memory is optional: pass :session-id to make-agent to enable
  Datalevin-backed hybrid memory (semantic search + recent context)."
  (:require [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.http :as http]
            [kschltz.agent.tools.repl :as repl]
            [clojure.string :as str]
            [kschltz.agent.core :as agent]))

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

(def ^:private default-state
  "Initial agent state map."
  {:running        false
   :turns          0
   :max-turns      100
   :history        []
   :tools          []
   :current-response nil
   :session-id     nil
   :memory-conn    nil
   :memory-backend nil
   :base-url       nil
   :api-key        nil
   :model          nil
   :on-response    nil
   :on-error       nil
   :message-queue  []
   ;; Memory config (env var fallbacks)
   :memory-relevant-limit nil
   :memory-recent-limit   nil
   :memory-strategy       nil
   :memory-embedding-dims nil
   :history-limit    nil
   ;; Tool config
   :max-tool-calls nil})

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
   :history-limit        (fn [] (env-or "LATERALUS_HISTORY_LIMIT" default-history-limit #(Integer/parseInt %)))
   :max-tool-calls       (fn [] (env-or "LATERALUS_MAX_TOOL_CALLS" 10 #(Integer/parseInt %)))})

(defn- resolve-config
  "Resolve config: explicit opt > env var > default."
  [opts]
  (into {}
    (for [[k default-fn] config-defaults]
      [k (if (contains? opts k)
           (opts k)
           (default-fn))])))

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
    :session-id                — Session ID for memory (optional, enables memory)
    :memory-backend            — Memory backend (default :datalevin)
    :memory-relevant-limit     — Relevant messages to retrieve (env: LATERALUS_MEMORY_RELEVANT_LIMIT, default 5)
    :memory-recent-limit       — Recent context messages (env: LATERALUS_MEMORY_RECENT_LIMIT, default 10)
    :memory-strategy           — Composition strategy (env: LATERALUS_MEMORY_STRATEGY, default :hybrid)
    :memory-embedding-dims     — Embedding dimensions (env: LATERALUS_MEMORY_EMBEDDING_DIMS, default 384)
    :history-limit             — Max messages kept in agent state (env: LATERALUS_HISTORY_LIMIT, default 50)
    :max-tool-calls            — Max tool call rounds per message (env: LATERALUS_MAX_TOOL_CALLS, default 10)
    :on-response               — Default handler fn, called on every response (optional)
    :on-error                  — Error handler fn (ag, exception) => anything (optional, default: stop + rethrow)

  Returns: Clojure agent reference type."
  ([]
   (make-agent {}))
  ([opts]
   (let [{:keys [base-url api-key model turns tools initial
                 session-id memory-backend on-response on-error on-thought
                 memory-relevant-limit memory-recent-limit memory-strategy memory-embedding-dims history-limit]
          :or   {turns 100 tools [] initial [] memory-backend :datalevin}} opts
         cfg           (resolve-config opts)
         memory-enabled? (contains? opts :session-id)
         session-id'     (when memory-enabled?
                           (or session-id (str "session-" (System/currentTimeMillis))))
         embedding-dims (:memory-embedding-dims cfg)
         memory-conn     (when memory-enabled?
                           (try
                             (:connection (memory/create-session
                                           {:backend memory-backend
                                            :session-id session-id'
                                            :model model
                                            :embedding-dims embedding-dims
                                            :base-url base-url
                                            :api-key api-key}))
                             (catch Exception e
                               (println "Warning: failed to create memory session:"
                                        (.getMessage e))
                               nil)))]
     (clojure.core/agent (merge default-state
                                {:base-url       base-url
                                 :api-key        api-key
                                 :model          model
                                 :max-turns      turns
                                 :tools          (vec tools)
                                 :history        (vec initial)
                                 :session-id     session-id'
                                 :memory-conn    memory-conn
                                 :memory-backend memory-backend
                                 :on-response   on-response
                                 :on-error      on-error
                                 :on-thought    on-thought
                                 :memory-relevant-limit (:memory-relevant-limit cfg)
                                 :memory-recent-limit   (:memory-recent-limit cfg)
                                 :memory-strategy       (:memory-strategy cfg)
                                 :memory-embedding-dims (:memory-embedding-dims cfg)
                                 :history-limit         (or history-limit (:history-limit cfg))
                                 :max-tool-calls        (:max-tool-calls cfg)})))))

;; ---- Memory Helpers (pure, take state map) ----

(defn- close-memory
  "Close the active memory session if one exists. Returns updated state map."
  [state]
  (when-let [conn (:memory-conn state)]
    (try
      (memory/close-session {:backend (:memory-backend state) :connection conn})
      (catch Exception e
        (println "Warning: failed to close memory session:" (.getMessage e)))))
  (-> state
      (dissoc :memory-conn)
      (dissoc :memory-backend)
      (dissoc :session-id)))

(defn- memory-msgs->chat-msgs
  "Convert memory-format messages to chat-history format."
  [memory-msgs]
  (mapv (fn [{:msg/keys [id role text]}]
          (cond-> {:role (or role "user") :content (or text "")}
            id (assoc :msg-id id)))
        memory-msgs))

(defn- compose-context
  "Build memory-augmented context for the LLM call.
   Uses :memory-relevant-limit and :memory-recent-limit from state."
  [{:keys [session-id memory-conn memory-backend history
           memory-relevant-limit memory-recent-limit]
    :or   {memory-relevant-limit 5 memory-recent-limit 10}}
   user-input]
  (if (and memory-conn session-id)
    (let [relevant (try
                     (memory/retrieve-relevant
                       {:backend memory-backend
                        :session-id session-id
                        :connection memory-conn
                        :query user-input
                        :limit memory-relevant-limit})
                     (catch Exception _ []))
          recent (take-last memory-recent-limit history)]
      (vec (into (memory-msgs->chat-msgs relevant) recent)))
    (vec history)))

(defn- cap-history
  "Cap history to :history-limit messages. Older messages are persisted in
   Datalevin and available via semantic retrieval. Returns state with :history trimmed."
  [state]
  (if-let [limit (:history-limit state)]
    (let [h (:history state)]
      (if (> (count h) limit)
        (assoc state :history (vec (take-last limit h)))
        state))
    state))

(defn- store-exchange
  "Store a user-assistant exchange in session memory. Returns state unchanged."
  [{:keys [session-id memory-conn memory-backend]} user-input response]
  (when (and memory-conn session-id)
    (try
      (memory/store-message {:backend memory-backend
                             :session-id session-id
                             :connection memory-conn
                             :message {:role "user" :text user-input}})
      (memory/store-message {:backend memory-backend
                             :session-id session-id
                             :connection memory-conn
                             :message {:role "assistant" :text response}})
      (catch Exception e
        (println (str "Memory store error: " (.getMessage e)))))))

;; ---- LLM ----

(defn- llm-call
  "Call the LLM API. Uses memory-augmented context when available.
   When tools are registered, prepends tool manifest to the user message.
   Returns a map {:content ... :reasoning ...}."
  ([state message]
   (llm-call state message nil))
  ([state message manifest]
   (if (and (:base-url state) (:model state))
     (let [user-msg (if manifest
                      (str manifest "\n\n" message)
                      message)
           ctx      (compose-context state user-msg)
           response (http/completion (:base-url state) (:api-key state)
                                    (:model state) user-msg
                                    :chat-history ctx)]
       {:content   (or (http/assistant-content response) "No response from LLM")
        :reasoning (http/reasoning-content response)})
     {:content "LLM not configured" :reasoning nil})))

;; ---- Tool Use ----

(def ^:private tool-call-regex
  "Regex to extract tool calls from LLM responses.
   Format: \u27aatool:tool-name\u27ab Arguments \u27aa/end\u27ab
   Supports multiline arguments."
  #"\u27aatool:(.+?)\u27ab(.+?)\u27aa/end\u27ab")

(defn tool-manifest
  "Build a tool manifest string for the LLM context.
   Returns nil if no tools are registered."
  [tools]
  (when (seq tools)
    (str "\nYou have access to these tools:\n"
         (str/join "\n"
           (for [t tools]
             (str "- " (:name t) ": " (:description t))))
         "To call a tool, write: tool-name in ➪/➫ delimiters with your arguments. Example for repl-eval: ➪tool:repl-eval➫(+ 1 2 3)➪/end➫\n\n"
         "You may make multiple tool calls in one response.\n"
         "After receiving tool results, you may respond with more tool calls or a final text answer.\n"
         "If you need more information to use a tool correctly, ask the user.")))

(defn parse-tool-calls
  "Extract tool calls from LLM response text.
   Returns a vector of {:tool \"name\" :args \"...\"} or nil if none found."
  [text]
  (when-let [matches (re-seq tool-call-regex text)]
    (vec (for [[_ name args] matches]
           {:tool (str/trim name)
            :args (str/trim args)}))))

(defn- execute-tool-call
  "Execute a single tool call. Returns {:tool ... :args ... :result ... :error ...}."
  [tools call]
  (let [{:keys [tool name] :as _call} call
        tool-name (:tool call)
        tool-def  (first (filter #(= (:name %) tool-name) tools))]
    (if tool-def
      (try
        (let [result (tools/tool-call tool-def (:args call))]
          {:tool   tool-name
           :args  (:args call)
           :result result})
        (catch Exception e
          {:tool   tool-name
           :args  (:args call)
           :error (.getMessage e)}))
      {:tool   tool-name
       :args  (:args call)
       :error (str "Unknown tool: " tool-name)})))

(defn- execute-tool-calls
  "Execute multiple tool calls in parallel. Returns vector of results."
  [calls tools]
  (vec (pmap #(execute-tool-call tools %) calls)))

(defn- format-tool-results
  "Format tool execution results for the next LLM call."
  [results]
  (str "Tool results:\n"
       (str/join "\n"
         (for [{:keys [tool args result error]} results]
           (if error
             (str tool "(" args ") => Error: " error)
             (str tool "(" args ") => " result))))))

(defn- strip-tool-calls
  "Remove tool call markup from text, returning any surrounding text."
  [text]
  (str/trim (str/replace text tool-call-regex "")))

;; ---- Queue Operations ----

(defn- drain-queue
  "Drain all pending items from the queue, returning [state-sans-queue items].
   Each item is {:text ... :promise <promise> :handler <fn|nil>}."
  [state]
  [(:message-queue state) (assoc state :message-queue [])])

(defn- queue-wait
  "Sleep briefly when queue is empty to avoid busy-spinning."
  [state]
  (when (empty? (:message-queue state))
    (Thread/sleep 100)))

(defn- deliver-response
  "Deliver a response: call on-response default handler, deliver promise, call per-message handler."
  [{:keys [promise handler on-response]} response]
  (when on-response (try (on-response response) (catch Exception e
                                    (println (str "Default handler error: " (.getMessage e))))))
  (when promise (deliver promise response))
  (when handler (try (handler response) (catch Exception e
                                    (println (str "Handler error: " (.getMessage e)))))))

;; ---- Loop ----

(defn- default-error-handler
  "Default error handler: log the error and continue running.
   The agent loop catches errors so it can keep processing messages.
   Only sets :running false if :stop-on-error is set in state."
  [ag ^Exception e]
  (println (str "Agent error (continuing): " (.getMessage e)))
  (when (:stop-on-error @ag)
    (send ag assoc :running false)
    (await ag)))

(defn- fire-on-thought
  "Fire the on-thought callback if present. Catches errors so loop continues."
  [state event]
  (when-let [on-thought (:on-thought state)]
    (try (on-thought event) (catch Exception _))))

(defn- llm-turn
  "Run one LLM call. If it contains tool calls, execute them and loop.
   Returns the final text response after all tool calls are resolved.
   Bounded by max-tool-calls to prevent infinite loops.
   Fires :on-thought callback with intermediate events."
  [ag state user-text]
  (let [manifest (tool-manifest (:tools state))
        max-depth (or (:max-tool-calls state) 10)]
    (loop [ctx-text user-text
           depth  0
           first-call? true]
      (let [manifest'  (when first-call? manifest)
            resp-map   (llm-call state ctx-text manifest')
            response   (:content resp-map)
            reasoning  (:reasoning resp-map)
            _          (when reasoning
                        (fire-on-thought state {:type :thinking :content reasoning}))
            calls      (when manifest (parse-tool-calls response))]
        (cond
          (nil? calls)
          response

          (>= depth max-depth)
          (str response "\n\n[Tool call limit reached]")

          :else
          (let [known-names (into #{} (map :name) (:tools state))
                valid-calls (filterv #(contains? known-names (:tool %)) calls)]
            (if (empty? valid-calls)
              response
              (let [_         (fire-on-thought state {:type :tool-call :content response :calls valid-calls})
                    results   (execute-tool-calls valid-calls (:tools state))
                    _         (fire-on-thought state {:type :tool-result :results results})
                    tool-msg  (format-tool-results results)]
                (recur tool-msg (inc depth) false)))))))))

(defn- process-messages
  "Process a batch of drained queue items against the LLM.
   Handles tool calls in a loop until a final text response.
   Delivers each item's promise and calls its handler.
   Returns updated state map. On error, delivers error messages and continues."
  [ag state items]
  (let [texts         (mapv :text items)
        combined-input (str/join "\n" texts)]
    (try
      (let [response (llm-turn ag state combined-input)
            state'   (-> state
                        (assoc :current-response response)
                        (update :history into (map #(array-map :role "user" :content (:text %)) items))
                        (update :history conj {:role "assistant" :content response})
                        cap-history)]
        (doseq [item items]
          (deliver-response (merge item (select-keys state [:on-response])) response))
        (store-exchange state' combined-input response)
        state')
      (catch Exception e
        (let [on-error (:on-error state)
              err-str (str "Error: " (.getMessage e))]
          (when on-error
            (try (on-error ag e) (catch Exception _)))
          (doseq [item items]
            (deliver-response (merge item (select-keys state [:on-response])) err-str))
          (when-not on-error
            (default-error-handler ag e))
          (-> state
              (assoc :current-response err-str)
              (update :history into (map #(array-map :role "user" :content (:text %)) items))
              (update :history conj {:role "assistant" :content err-str})))))))


(defn- agent-loop
  "Main agent loop. Drains message queue each tick, processes as a batch.
   Sleeps when idle. Runs until max-turns reached or interrupted.
   Catches errors per-iteration so the loop keeps running."
  [ag]
  (loop [turn 0]
    (let [state @ag]
      (cond
        (not (:running state))  :stopped
        (>= turn (:max-turns state)) :stopped
        :else
        (let [next-state
              (try
                (let [[items state'] (drain-queue state)]
                  (if (empty? items)
                    (do (queue-wait state')
                        {:action :idle :turn turn})
                    (let [result    (process-messages ag state' items)
                          next-turn (inc turn)]
                      (send ag (fn [_] (assoc result :turns next-turn)))
                      {:action :processed :turn next-turn :result result})))
                (catch Exception e
                  (let [on-error (:on-error @ag)]
                    (if on-error
                      (try (on-error @ag e) (catch Exception _))
                      (default-error-handler ag e))
                    {:action :error :turn turn})))]
          (cond
            (= (:action next-state) :idle)      (recur turn)
            (= (:action next-state) :processed)  (recur (:turn next-state))
            (= (:action next-state) :error)      (recur (:turn next-state))))))))


;; ---- Public API ----

(defn start!
  "Start the agent loop (blocking). Messages are sent via send-message!.
   Returns final state map when loop exits."
  [ag]
  (send ag assoc :running true)
  (await ag)
  (try
    (agent-loop ag)
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
         response (llm-turn ag (merge state (select-keys opts [:base-url :api-key :model]))
                             message)]
     (send ag update :history conj
           {:role "user" :content message}
           {:role "assistant" :content response})
     (await ag)
     (send ag cap-history)
     (await ag)
     (store-exchange state message response)
     response)))

(defn reset!
  "Reset agent state and close memory session."
  [ag]
  (send ag (fn [s]
             (-> s
                close-memory
                (assoc :history [] :turns 0 :current-response nil :message-queue []))))
  (await ag)
  (println "Agent state reset"))

(defn get-history
  "Get the current chat history."
  [ag]
  (:history @ag))

(defn get-tools
  "Get the registered tools."
  [ag]
  (:tools @ag))

(defn get-memory-conn
  "Get the current memory connection (nil if memory not active)."
  [ag]
  (:memory-conn @ag))

(defn get-session-id
  "Get the current session ID (nil if memory not active)."
  [ag]
  (:session-id @ag))

(defn get-memory-config
  "Get the current memory configuration map."
  [ag]
  (select-keys @ag [:memory-relevant-limit :memory-recent-limit
                       :memory-strategy :memory-embedding-dims
                       :memory-backend :session-id]))

(defn get-config
  "Get the full agent configuration map."
  [ag]
  (select-keys @ag [:base-url :model :max-turns :max-tool-calls
                       :memory-relevant-limit :memory-recent-limit
                       :memory-strategy :memory-embedding-dims
                       :memory-backend :session-id]))

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

(defn add-repl-nrepl-tool!
  "Add a nREPL tool to the agent."
  ([ag]
   (add-repl-nrepl-tool! ag {}))
  ([ag opts]
   (let [tool (repl/repl-nrepl-tool opts)]
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
  (agent/set-on-error! ag (fn[ag e] (prn "Agent: " "Error:" (.getMessage e))))

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
  (def conn (memory/create-session {:backend    :datalevin
                                    :session-id "demo"
                                    :model      "deepseek-v4-flash:cloud"}))
  (memory/store-message {:backend    :datalevin
                         :session-id "demo"
                         :connection (:connection conn)
                         :message    {:role "user" :text "Hello"}})
  (memory/retrieve-relevant {:backend    :datalevin
                             :session-id "demo"
                             :connection (:connection conn)
                             :query      "hello" :limit 5})
  (memory/close-session {:backend    :datalevin
                         :connection (:connection conn)})
  )