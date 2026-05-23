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
            [clojure.string :as str]))

;; ---- REPL Usage ----
;;
;; (require '[kschltz.agent.core :as agent])
;;
;; ;; Create and start
;; (def ag (agent/make-agent {:base-url "http://localhost:11434"
;;                            :model "llama3" :turns 100}))
;; (agent/start! ag)
;;
;; ;; With session memory
;; (def ag (agent/make-agent {:base-url "http://localhost:11434"
;;                            :model "llama3" :turns 100
;;                            :session-id "my-session"}))
;; (agent/start! ag)
;;
;; ;; Send messages to the queue (loop must be running)
;; (agent/send-message! ag "Hello, who are you?")
;; (agent/send-message! ag "Write a haiku about Clojure")
;; (agent/queue-size ag)
;;
;; ;; One-shot without the loop
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
   :message-queue  []})

;; ---- Agent Construction ----

(defn make-agent
  "Create a new agent (Clojure agent reference type) holding state.

  Options:
    :base-url        — LLM API base URL
    :api-key         — API key (optional)
    :model           — Model ID
    :turns           — Max turns (default 100)
    :tools           — Tool vector (optional)
    :initial         — Initial messages (optional)
    :session-id      — Session ID for memory (optional, enables memory)
    :memory-backend  — Memory backend (default :datalevin)
    :on-response     — Default handler fn, called on every response (optional)
    :on-error        — Error handler fn (ag, exception) => anything (optional, default: stop + rethrow)

  Returns: Clojure agent reference type."
  ([]
   (make-agent {}))
  ([opts]
   (let [{:keys [base-url api-key model turns tools initial
                 session-id memory-backend on-response on-error]
          :or   {turns 100 tools [] initial [] memory-backend :datalevin}} opts
         memory-enabled? (contains? opts :session-id)
         session-id'     (when memory-enabled?
                           (or session-id (str "session-" (System/currentTimeMillis))))
         memory-conn     (when memory-enabled?
                           (try
                             (:connection (memory/create-session
                                           {:backend memory-backend
                                            :session-id session-id'
                                            :model model}))
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
                                 :on-error      on-error})))))

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
  "Build memory-augmented context for the LLM call."
  [{:keys [session-id memory-conn memory-backend history]} user-input]
  (if (and memory-conn session-id)
    (let [relevant (try
                     (memory/retrieve-relevant
                       {:backend memory-backend
                        :session-id session-id
                        :connection memory-conn
                        :query user-input
                        :limit 5})
                     (catch Exception _ []))
          recent (take-last 10 history)]
      (vec (into (memory-msgs->chat-msgs relevant) history)))
    (vec history)))

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
  "Call the LLM API. Uses memory-augmented context when available."
  [{:keys [base-url api-key model] :as state} message]
  (if (and base-url model)
    (let [ctx      (compose-context state message)
          response (http/completion base-url api-key model message
                                    :chat-history ctx)
          content  (http/assistant-content response)]
      (or content "No response from LLM"))
    "LLM not configured - provide :base-url and :model to make-agent"))

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
  "Default error handler: stop the agent, log the error, rethrow."
  [ag ^Exception e]
  (send ag assoc :running false)
  (await ag)
  (println (str "Agent error (stopping): " (.getMessage e)))
  (throw e))

(defn- process-messages
  "Process a batch of drained queue items against the LLM.
   Delivers each item's promise and calls its handler.
   Returns updated state map. On error, calls on-error handler."
  [ag state items]
  (let [texts         (mapv :text items)
        combined-input (str/join "\n" texts)]
    (try
      (let [response (llm-call state combined-input)
            state'   (-> state
                        (assoc :current-response response)
                        (update :history into (map #(array-map :role "user" :content (:text %)) items))
                        (update :history conj {:role "assistant" :content response}))]
        ;; Deliver each item's promise + handler
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
          (if on-error
            ;; Custom handler: notify done, deliver errors, keep running
            (assoc state :current-response err-str)
            ;; Default handler: stop agent and rethrow
            (default-error-handler ag e)))))))

(defn- agent-loop
  "Main agent loop. Drains message queue each tick, processes as a batch.
   Sleeps when idle. Runs until max-turns reached or interrupted."
  [ag]
  (loop [turn 0]
    (let [state @ag]
      (cond
        (not (:running state))  :stopped
        (>= turn (:max-turns state)) :stopped
        :else
        (let [[items state'] (drain-queue state)]
          (if (empty? items)
            (do
              (queue-wait state')
              (recur turn))
            (let [state''   (process-messages ag state' items)
                  next-turn (inc turn)]
              (send ag (fn [_] (assoc state'' :turns next-turn)))
              (if (:current-response state'')
                (recur next-turn)
                (recur turn)))))))))

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
  Stores the exchange in session memory if active.

  Returns: assistant response string"
  ([ag message]
   (chat! ag message {}))
  ([ag message opts]
   (let [state    @ag
         response (llm-call (merge state (select-keys opts [:base-url :api-key :model]))
                           message)]
     (send ag update :history conj
           {:role "user" :content message}
           {:role "assistant" :content response})
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
  ;; === Create and start ===
  (require '[kschltz.agent.core :as agent])
  (def ag (agent/make-agent {:base-url "https://api-inference.huggingface.co"
                             :api-key (System/getenv "HF_TOKEN") :model "stepfun-ai/Step-3.5-Flash:fastest" :turns 100}))
  (future (agent/start! ag))

  ;; === With session memory ===
  (def ag (agent/make-agent {:base-url "https://api-inference.huggingface.co"
                             :on-response (fn [r] (println "Agent:" r))
                             :api-key (System/getenv "HF_TOKEN") :model "stepfun-ai/Step-3.5-Flash:fastest" :turns 100
                             :session-id "my-session"}))
  (future (agent/start! ag))

  ;; === Send messages to the queue ===
  ;; Returns a promise — deref to block for response
  (def p1 (agent/send-message! ag "Hello, who are you?"))
  @p1  ;;=> "I am a Clojure agent..."

  ;; With handler callback (runs async on response)
  (def p2 (agent/send-message! ag "Write a haiku"
             (fn [r] (println "Got response:" r))))
  @p2  ;; also works

  ;; Non-blocking check
  (realized? p2)  ;;=> true|false

  (agent/queue-size ag)

  ;; === One-shot (no loop needed) ===
  (agent/chat! ag "What is Clojure?")

  ;; === Inspect state ===
  (agent/running? ag)
  (agent/get-history ag)
  (agent/get-session-id ag)

  ;; === Interrupt or reset ===
  (agent/stop! ag)
  (agent/reset! ag)

  ;; === Default response handler (runs on every response) ===
  (agent/set-on-response! ag (fn [r] (println "Agent:" r)))
  (agent/set-on-error! ag nil)  ;; reset default handler

  ;; === Error handler ===
  ;; Default: stop agent, log error, rethrow
  ;; Custom: e.g. log and continue
  (agent/set-on-error! ag (fn [ag e] (println "Error:" (.getMessage e))))
  (agent/set-on-error! ag nil)  ;; restore default

  ;; === Add tools ===
  (agent/add-repl-eval-tool! ag)
  (agent/add-repl-nrepl-tool! ag {:port 59500})
  (agent/get-tools ag)

  ;; === Direct memory API ===
  (require '[kschltz.agent.memory :as memory])
  (def conn (memory/create-session {:backend :datalevin
                                     :session-id "demo"
                                     :model "stepfun-ai/Step-3.5-Flash:fastest"}))
  (memory/store-message {:backend :datalevin
                         :session-id "demo"
                         :connection (:connection conn)
                         :message {:role "user" :text "Hello"}})
  (memory/retrieve-relevant {:backend :datalevin
                            :session-id "demo"
                            :connection (:connection conn)
                            :query "hello" :limit 5})
  (memory/close-session {:backend :datalevin
                         :connection (:connection conn)})
  )