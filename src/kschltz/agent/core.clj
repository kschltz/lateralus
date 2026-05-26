(ns kschltz.agent.core
  "Agent core — orchestrates LLM calls, tool execution, chat state,
  and optional session memory.

  All public functions take an agent (Clojure agent reference type)
  as their first argument: (send-message! ag \"hello\")

  Memory is optional: pass :session-id to make-agent to enable
  Datalevin-backed hybrid memory (semantic search + recent context)."
  (:refer-clojure :exclude [reset!])
  (:require [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.http :as http]
            [kschltz.agent.tools.repl :as repl]
            [kschltz.agent.tools.web :as web]
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
   :history-limit    nil
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
   :memory-embedding-model (fn [] (env-or "LATERALUS_EMBEDDING_MODEL" "nomic-embed-text"))
   :history-limit        (fn [] (env-or "LATERALUS_HISTORY_LIMIT" default-history-limit #(Integer/parseInt %)))
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

(defn- memory-msgs->chat-msgs
  "Convert memory-format messages to chat-history format."
  [memory-msgs]
  (mapv (fn [{:msg/keys [id role text timestamp]}]
          (cond-> {:role (or role "user") :content (or text "")}
            id (assoc :msg-id id)
            timestamp (assoc :timestamp timestamp)))
        memory-msgs))

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
    :session-id                — Session ID for memory (optional, enables memory when non-nil)
    :memory-backend            — Memory backend (default :datalevin)
    :memory-relevant-limit     — Relevant messages to retrieve (env: LATERALUS_MEMORY_RELEVANT_LIMIT, default 5)
    :memory-recent-limit       — Recent context messages (env: LATERALUS_MEMORY_RECENT_LIMIT, default 10)
    :memory-strategy           — Composition strategy (env: LATERALUS_MEMORY_STRATEGY, default :hybrid)
    :memory-embedding-dims     — Embedding dimensions (env: LATERALUS_MEMORY_EMBEDDING_DIMS, default 384)
    :memory-embedding-model    — Embedding model (env: LATERALUS_EMBEDDING_MODEL, default nomic-embed-text)
    :history-limit             — Max messages kept in agent state (env: LATERALUS_HISTORY_LIMIT, default 50)
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
                 session-id memory-backend on-response on-error on-thought
                 memory-relevant-limit memory-recent-limit memory-strategy memory-embedding-dims
                 memory-embedding-model history-limit sessions-dir on-memory-event]
          :or   {turns 100 tools [] initial [] memory-backend :datalevin}} opts
         cfg           (resolve-config opts)
         memory-enabled? (some? session-id)
         session-id'     (when memory-enabled? session-id)
         embedding-dims (:memory-embedding-dims cfg)
         embedding-model (or memory-embedding-model (:memory-embedding-model cfg))
         sessions-dir'  (or sessions-dir (:sessions-dir cfg))
         history-limit' (or history-limit (:history-limit cfg))
         memory-store    (when memory-enabled?
                           (try
                             (:connection (memory/create-session
                                           {:backend memory-backend
                                            :session-id session-id'
                                            :model model
                                            :embedding-dims embedding-dims
                                            :embedding-model embedding-model
                                            :base-url base-url
                                            :api-key api-key
                                            :sessions-dir sessions-dir'}))
                             (catch Exception e
                               (println "Warning: failed to create memory session:"
                                        (.getMessage e))
                               nil)))
         loaded-history  (when (and memory-store session-id' (empty? initial))
                           (try
                             (memory/load-recent-messages
                               {:backend memory-backend
                                :session-id session-id'
                                :connection memory-store
                                :limit history-limit'})
                             (catch Exception _ [])))
         start-history   (if (seq initial)
                           (vec initial)
                           (memory-msgs->chat-msgs (or loaded-history [])))]
     (clojure.core/agent (merge default-state
                                {:base-url       base-url
                                 :api-key        api-key
                                 :model          model
                                 :max-turns      turns
                                 :tools          (vec tools)
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
                                 :history-limit         history-limit'
                                 :max-tool-calls        (:max-tool-calls cfg)
                                 :max-retries            (:max-retries cfg)})))))

;; ---- Memory Helpers (pure, take state map) ----

(defn- close-memory
  "Close the active memory session if one exists. Returns updated state map."
  [state]
  (when-let [store (:memory-store state)]
    (try
      (memory/close-session {:backend (:memory-backend state) :connection store})
      (catch Exception e
        (println "Warning: failed to close memory session:" (.getMessage e)))))
  (-> state
      (dissoc :memory-store)
      (dissoc :memory-backend)
      (dissoc :session-id)))

(defn- history->memory-msgs
  "Convert in-agent chat history to memory-format messages for composition."
  [history]
  (vec
    (map-indexed (fn [idx {:keys [role content msg-id timestamp]}]
                   (cond-> {:msg/role role :msg/text content}
                     msg-id (assoc :msg/id msg-id)
                     timestamp (assoc :msg/timestamp timestamp)
                     (and (not timestamp) (not msg-id)) (assoc :msg/timestamp idx)))
                 history)))

(defn compose-context
  "Build memory-augmented context for the LLM call.
   Retrieves semantically relevant messages, merges with recent in-agent
   history via :memory-strategy (default :hybrid), deduped and sorted."
  [{:keys [session-id memory-store memory-backend history
           memory-relevant-limit memory-recent-limit memory-strategy]
    :or   {memory-relevant-limit 5 memory-recent-limit 10 memory-strategy :hybrid}}
   user-input]
  (if (and memory-store session-id)
    (let [relevant (try
                     (memory/retrieve-relevant
                       {:backend memory-backend
                        :session-id session-id
                        :connection memory-store
                        :query user-input
                        :limit memory-relevant-limit})
                     (catch Exception _ []))
          recent   (history->memory-msgs history)
          composed (memory/compose {:strategy memory-strategy
                                    :relevant relevant
                                    :recent recent
                                    :relevant-limit memory-relevant-limit
                                    :recent-limit memory-recent-limit})]
      (memory-msgs->chat-msgs composed))
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

(defn- fire-on-thought
  "Fire the on-thought callback if present. Catches errors so loop continues."
  [state event]
  (when-let [on-thought (:on-thought state)]
    (try (on-thought event) (catch Exception _))))

(defn- fire-memory-event
  "Fire the on-memory-event callback if present."
  [state event]
  (when-let [on-memory (:on-memory-event state)]
    (try (on-memory event) (catch Exception _))))

(defn- notify-store-result
  [state role result]
  (when (and result (not (:indexed result)))
    (fire-memory-event state (assoc result :type :memory-not-indexed :role role))))

(defn- tool-round-summary
  "Compact text summary of a tool execution round."
  [{:keys [tool args result error]}]
  (if error
    (str tool "(" args ") => Error: " error)
    (str tool "(" args ") => " result)))

(defn- tool-round-result-text
  "Stored :msg/tool-result value for a tool round."
  [{:keys [result error]}]
  (if error (str "Error: " error) (str result)))

(defn- store-exchange
  "Store a user-assistant exchange in session memory, including any tool rounds.
   Returns {:user-id ... :assistant-id ... :tools [...] ...}
   or nil when memory is inactive or storage fails."
  [state user-input response & {:keys [tool-rounds]}]
  (when (and (:memory-store state) (:session-id state))
    (try
      (let [{:keys [session-id memory-store memory-backend]} state
            ts           (System/currentTimeMillis)
            user-id      (str "msg-" ts "-u-" (rand-int 100000))
            rounds       (vec (or tool-rounds []))
            user-result  (memory/store-message {:backend memory-backend
                                                :session-id session-id
                                                :connection memory-store
                                                :message {:role "user" :text user-input
                                                          :id user-id :timestamp ts}})
            tool-stored  (mapv (fn [[idx tr]]
                                 (let [tool-ts (inc (+ ts idx))
                                       tool-id (str "msg-" tool-ts "-t-" (rand-int 100000))
                                       store-result (memory/store-message
                                                      {:backend memory-backend
                                                       :session-id session-id
                                                       :connection memory-store
                                                       :message {:role "tool"
                                                                 :text (tool-round-summary tr)
                                                                 :tool-name (:tool tr)
                                                                 :tool-result (tool-round-result-text tr)
                                                                 :id tool-id
                                                                 :timestamp tool-ts}})]
                                   (notify-store-result state "tool" store-result)
                                   {:tool-id tool-id :timestamp tool-ts}))
                               (map-indexed vector rounds))
            assistant-ts (+ ts 1 (count rounds))
            assistant-id (str "msg-" assistant-ts "-a-" (rand-int 100000))
            asst-result  (memory/store-message {:backend memory-backend
                                                :session-id session-id
                                                :connection memory-store
                                                :message {:role "assistant" :text response
                                                          :id assistant-id :timestamp assistant-ts}})]
        (notify-store-result state "user" user-result)
        (notify-store-result state "assistant" asst-result)
        {:user-id user-id :assistant-id assistant-id
         :user-timestamp ts :assistant-timestamp assistant-ts
         :tools tool-stored})
      (catch Exception e
        (println (str "Memory store error: " (.getMessage e)))
        nil))))

;; ---- LLM ----

(defn- llm-call
  "Call the LLM API. Uses memory-augmented context when available.

   Options map:
     :user-text      — original user message (used for memory retrieval)
     :manifest       — tool manifest prepended to the first user turn
     :turn-messages  — in-turn API messages [{:role ... :content ...} ...]

   Returns a map {:content ... :reasoning ...}."
  [state {:keys [user-text manifest turn-messages]}]
  (if (and (:base-url state) (:model state))
    (let [ctx          (compose-context state user-text)
          api-messages (if turn-messages
                         (into (vec ctx) turn-messages)
                         (let [user-msg (if manifest
                                          (str manifest "\n\n" user-text)
                                          user-text)]
                           (conj (vec ctx) {:role "user" :content user-msg})))]
      (let [response (http/completion (:base-url state) (:api-key state)
                                      (:model state) nil
                                      :messages api-messages)]
        {:content   (or (http/assistant-content response) "No response from LLM")
         :reasoning (http/reasoning-content response)}))
    {:content "LLM not configured" :reasoning nil}))

;; ---- Tool Use ----

(def ^:private tool-call-regex
  "Regex to extract tool calls from LLM responses.
   Format: \u27aatool:tool-name\u27ab Arguments \u27aa/end\u27ab
   Allows optional text (e.g. 'store-thought') between /end and closing \u27ab
   so LLM inventions like \u27aa/end store-thought\u27ab still parse correctly."
  #"\u27aatool:(.+?)\u27ab(.+?)\u27aa/end[^\u27ab]*\u27ab")

(defn tool-manifest
  "Build a tool manifest string for the LLM context.
   Returns nil if no tools are registered."
  [tools]
  (when (seq tools)
    (str "\nYou have access to these tools:\n"
         (str/join "\n"
           (for [t tools]
             (str "- " (:name t) ": " (:description t))))
         "\nTo call a tool, write EXACTLY: ➪tool:tool-name➫(arguments)➪/end➫\n"
         "Example: ➪tool:repl-eval➫(+ 1 2 3)➪/end➫\n"
         "Example: ➪tool:web-search➫Clojure multimethods➪/end➫\n"
         "IMPORTANT: Always include ➪/end➫ after the arguments.\n"
         "Do NOT use placeholders like (code) — pass real executable code or queries.\n"
         "Do NOT show code in markdown fences — call the tool instead.\n"
         "Do NOT paste code and say \"let me evaluate\" — execute it via a tool call.\n"
         "When asked to explain tools, answer in plain text. Do NOT call repl-eval for that.\n"
         "repl-eval runs Clojure in the JVM; it cannot register new agent tools at runtime.\n"
         "To add tools permanently, create a namespace under kschltz.agent.tools and register it in the CLI.\n"
         "IMPORTANT: The closing tag is ➪/end➫ — NOTHING else.\n"
         "Do NOT add suffixes like ➪/end store-thought➫ or ➪/end➫/thought.\n"
         "Do NOT write tool results yourself — you will receive real results.\n\n"
         "You may make multiple tool calls in one response.\n"
         "After receiving tool results, you may respond with more tool calls or a final text answer.\n"
         "If you need more information to use a tool correctly, ask the user.")))

(def ^:private tool-args-boundary-regex
  "Stops arg extraction at the next tool marker or end tag."
  #"(\u27aa/end|\u27aatool:)")

(def ^:private markdown-clojure-regex
  "Extract fenced Clojure blocks the LLM often emits instead of tool calls."
  #"```(?:clojure|clj)?\s*\n([\s\S]*?)```")

(defn- strip-markdown-fence
  "Remove optional ```clojure fences from LLM-generated code."
  [s]
  (let [trimmed (str/trim s)]
    (if (str/starts-with? trimmed "```")
      (-> trimmed
          (str/replace #"^```(?:clojure|clj)?\s*\n?" "")
          (str/replace #"\n?```[\s\S]*$" "")
          str/trim)
      trimmed)))

(defn- scan-to-close
  "Return index of the closing delimiter matching open-char at index 0."
  [s open-char close-char]
  (loop [i 0 depth 0]
    (when (< i (count s))
      (let [c (.charAt s i)]
        (cond
          (= c open-char) (recur (inc i) (inc depth))
          (and (= c close-char) (pos? depth))
          (if (= depth 1) i (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))))

(defn- extract-balanced-arg
  "Extract the first balanced s-expression or bracketed form from s."
  [s]
  (let [s (strip-markdown-fence s)]
    (when (seq s)
      (let [c (.charAt s 0)]
        (cond
          (= c \()
          (when-let [end (scan-to-close s \( \))]
            (subs s 0 (inc end)))

          (= c \[)
          (when-let [end (scan-to-close s \[ \])]
            (subs s 0 (inc end)))

          (= c \{)
          (when-let [end (scan-to-close s \{ \})]
            (subs s 0 (inc end)))

          :else
          (some-> (re-find #"^[^\u27aa]+" s) str/trim))))))

(defn- extract-tool-args
  "Extract tool arguments from text after the opener delimiter."
  [after-opener]
  (let [before-boundary (str/trim (or (first (str/split after-opener tool-args-boundary-regex))
                                      after-opener))]
    (extract-balanced-arg before-boundary)))

(defn- parse-tool-calls-lenient
  "Fallback parser for LLM output missing \u27aa/end\u27ab closers or using markdown fences."
  [text]
  (let [segments (str/split text #"\u27aatool:")]
    (when (> (count segments) 1)
      (vec (for [segment (rest segments)
                 :let [name-end (.indexOf segment (int \u27ab))
                       tool-name (when (pos? name-end) (str/trim (subs segment 0 name-end)))
                       after-opener (when name-end (subs segment (inc name-end)))
                       args (when after-opener (extract-tool-args after-opener))]
                 :when (and tool-name args (not (str/blank? args)))]
             {:tool tool-name
              :args (str/trim args)})))))

(defn- parse-markdown-repl-calls
  "Fallback: treat ```clojure blocks as repl-eval calls when the LLM skips tool markup."
  [text]
  (when-let [blocks (seq (re-seq markdown-clojure-regex text))]
    (vec (for [[_ code] blocks
               :let [trimmed (str/trim code)]
               :when (seq trimmed)]
           {:tool "repl-eval"
            :args trimmed}))))

(defn- merge-tool-calls
  "Combine tool calls from multiple parsers, deduping by tool+args."
  [& groups]
  (let [merged (mapcat (fn [g] (or g [])) groups)]
    (not-empty
      (vec (vals (reduce (fn [m c] (assoc m [(:tool c) (:args c)] c))
                         {}
                         merged))))))

(defn parse-tool-calls
  "Extract tool calls from LLM response text.
   Returns a vector of {:tool \"name\" :args \"...\"} or nil if none found.

   When markdown-fallback? is false, fenced ```clojure blocks are ignored.
   Use false after tool results to avoid re-executing code in final answers."
  ([text]
   (parse-tool-calls text true))
  ([text markdown-fallback?]
   (merge-tool-calls
     (when-let [matches (re-seq tool-call-regex text)]
       (vec (for [[_ name args] matches]
              {:tool (str/trim name)
               :args (str/trim (strip-markdown-fence args))})))
     (parse-tool-calls-lenient text)
     (when markdown-fallback? (parse-markdown-repl-calls text)))))

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

(defn- strip-unclosed-tool-markup
  "Remove tool opener + args when the LLM omitted ➪/end➫."
  [text]
  (loop [s text]
    (if-some [idx (str/index-of s "\u27aatool:")]
      (let [after-tool (subs s (+ idx (count "\u27aatool:")))
            name-end   (.indexOf after-tool (int \u27ab))]
        (if (pos? name-end)
          (let [after-opener (subs after-tool (inc name-end))
                args         (extract-tool-args after-opener)]
            (if (and args (pos? (count args)))
              (let [args-start (str/index-of s args idx)
                    end        (+ args-start (count args))
                    before     (str/trim (subs s 0 idx))
                    after      (str/trim (subs s end))]
                (recur (if (seq before)
                         (str before " " after)
                         after)))
              (recur (subs s (+ idx (count "\u27aatool:"))))))
          (recur (subs s (+ idx (count "\u27aatool:"))))))
      s)))

(defn strip-tool-calls
  "Remove tool call markup from text, returning any surrounding text.
   Also strips any residual \u27aa...\u27ab fragments the LLM may emit."
  [text]
  (let [without-calls    (str/replace text tool-call-regex "")
        without-unclosed (strip-unclosed-tool-markup without-calls)
        without-markdown (str/replace without-unclosed markdown-clojure-regex "")
        without-residual (str/replace without-markdown #"\u27aa/end[^\u27ab]*\u27ab" "")]
    (str/trim (str/replace without-residual #"\s{2,}" " "))))

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

(defn has-unparsed-tool-markup?
  "Check if text contains ➪tool:...➫ markup that wasn't parsed as tool calls.
   This detects LLM hallucinations where it writes tool-call syntax with
   malformed delimiters (e.g. ➪/end store-thought➫)."
  [text]
  (boolean (re-find #"\u27aatool:" text)))

(defn- llm-turn-result
  [response tool-rounds]
  {:response response :tool-rounds (vec tool-rounds)})

(defn- llm-turn
  "Run one LLM call. If it contains tool calls, execute them and loop.
   Returns {:response ... :tool-rounds [...]} after all tool calls are resolved.
   Bounded by max-tool-calls to prevent infinite loops.
   Fires :on-thought callback with intermediate events.
   When tool execution produces errors, retries the LLM with error context
   up to :max-retries times (default 3, env LATERALUS_MAX_RETRIES)."
  [ag state user-text]
  (let [manifest          (tool-manifest (:tools state))
        max-depth         (or (:max-tool-calls state) 10)
        max-retries       (or (:max-retries state) 3)
        first-user-content (if manifest
                             (str manifest "\n\n" user-text)
                             user-text)]
    (loop [turn-msgs    [{:role "user" :content first-user-content}]
           depth        0
           retry-count  0
           tool-rounds  []]
      (let [resp-map           (llm-call state {:user-text user-text
                                              :turn-messages turn-msgs})
            response           (:content resp-map)
            reasoning          (:reasoning resp-map)
            _                  (when reasoning
                                 (fire-on-thought state {:type :thinking :content reasoning}))
            parse-text         (if reasoning (str response "\n" reasoning) response)
            markdown-fallback? (zero? depth)
            calls              (when manifest (parse-tool-calls parse-text markdown-fallback?))]
        (cond
          ;; No tool calls found — retry if markup looks broken or response is empty
          (nil? calls)
          (let [stripped (strip-tool-calls response)]
            (cond
              (and (str/blank? stripped) (< retry-count max-retries))
              (recur (conj turn-msgs
                           {:role "assistant" :content response}
                           {:role "user"
                            :content (str "Your previous response was empty or contained only tool markup. "
                                          "Provide a plain-text answer for the user based on the conversation above.")})
                     depth (inc retry-count) tool-rounds)

              (and (has-unparsed-tool-markup? response)
                   (< retry-count max-retries))
              (recur (conj turn-msgs
                           {:role "assistant" :content response}
                           {:role "user"
                            :content (str "Your previous response contained malformed tool-call markup. "
                                           "Use EXACTLY: ➪tool:tool-name➫(arguments)➪/end➫ with real code, "
                                           "no markdown fences, and no placeholders like (code).")})
                     depth (inc retry-count) tool-rounds)

              :else (llm-turn-result stripped tool-rounds)))

          (>= depth max-depth)
          (llm-turn-result (str (strip-tool-calls response) "\n\n[Tool call limit reached]")
                           tool-rounds)

          :else
          (let [known-names (into #{} (map :name) (:tools state))
                valid-calls (filterv #(contains? known-names (:tool %)) calls)]
            (if (empty? valid-calls)
              (llm-turn-result (strip-tool-calls response) tool-rounds)
              (let [_         (fire-on-thought state {:type :tool-call :content response :calls valid-calls})
                    results   (execute-tool-calls valid-calls (:tools state))
                    _         (fire-on-thought state {:type :tool-result :results results})
                    errors    (filterv :error results)
                    tool-msg  (format-tool-results results)
                    rounds'   (into tool-rounds results)]
                (if (and (seq errors) (< retry-count max-retries))
                  (recur (into turn-msgs
                               [{:role "assistant" :content response}
                                {:role "user"
                                 :content (str tool-msg
                                               "\n\nThe above tool calls failed. Do NOT explain or apologize. "
                                               "Call the tool again with corrected code. Errors:\n"
                                               (str/join "\n" (map #(str "  " (:tool %) ": " (:error %)) errors)))}])
                         (inc depth) (inc retry-count) rounds')
                  (recur (into turn-msgs
                               [{:role "assistant" :content response}
                                {:role "user" :content tool-msg}])
                         (inc depth) retry-count rounds'))))))))))

(defn- history-entries-for-exchange
  "Build chat-history entries for a stored user/tool/assistant exchange."
  [items response stored tool-rounds]
  (let [user-entry (cond-> {:role "user" :content (str/join "\n" (mapv :text items))}
                      stored (assoc :msg-id (:user-id stored)
                                    :timestamp (:user-timestamp stored)))
        tool-entries (mapv (fn [[idx tr]]
                             (let [stored-tool (get-in stored [:tools idx])]
                               (cond-> {:role "tool" :content (tool-round-summary tr)}
                                 stored-tool (assoc :msg-id (:tool-id stored-tool)
                                                    :timestamp (:timestamp stored-tool)))))
                           (map-indexed vector tool-rounds))
        assistant-entry (cond-> {:role "assistant" :content response}
                          stored (assoc :msg-id (:assistant-id stored)
                                        :timestamp (:assistant-timestamp stored)))]
    (into [user-entry] (concat tool-entries [assistant-entry]))))

(defn- process-messages
  "Process a batch of drained queue items against the LLM.
   Handles tool calls in a loop until a final text response.
   Delivers each item's promise and calls its handler.
   Returns updated state map. On error, delivers error messages and continues."
  [ag state items]
  (let [texts         (mapv :text items)
        combined-input (str/join "\n" texts)]
    (try
      (let [{:keys [response tool-rounds]} (llm-turn ag state combined-input)
            stored   (store-exchange state combined-input response :tool-rounds tool-rounds)
            entries  (history-entries-for-exchange items response stored tool-rounds)
            state'   (-> state
                        (assoc :current-response response)
                        (update :history into entries)
                        cap-history)]
        (doseq [item items]
          (deliver-response (merge item (select-keys state [:on-response])) response))
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
                    (let [_         (do (send ag #(assoc % :message-queue []))
                                         (await ag))
                          result    (process-messages ag state' items)
                          next-turn (inc turn)]
                      (send ag (fn [_] (assoc result :turns next-turn)))
                      (await ag)
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
         {:keys [response tool-rounds]} (llm-turn ag (merge state (select-keys opts [:base-url :api-key :model]))
                                                   message)
         stored   (store-exchange state message response :tool-rounds tool-rounds)
         entries  (history-entries-for-exchange [{:text message}] response stored tool-rounds)]
     (send ag update :history into entries)
     (await ag)
     (send ag cap-history)
     (await ag)
     response)))

(defn reset!
  "Reset agent runtime state: clear history, turns, queue, and current response.
   Keeps the memory session connection open so persisted messages remain available."
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
                       :memory-embedding-model
                       :memory-backend :session-id :sessions-dir]))

(defn get-config
  "Get the full agent configuration map."
  [ag]
  (select-keys @ag [:base-url :model :max-turns :max-tool-calls :max-retries
                       :memory-relevant-limit :memory-recent-limit
                       :memory-strategy :memory-embedding-dims :memory-embedding-model
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

(defn add-repl-nrepl-tool!
  "Add a nREPL tool to the agent."
  ([ag]
   (add-repl-nrepl-tool! ag {}))
  ([ag opts]
   (let [tool (repl/repl-nrepl-tool opts)]
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