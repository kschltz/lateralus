(ns kschltz.agent.core
  "Agent core — orchestrates LLM calls, tool execution, chat state,
  and optional session memory.

  All public functions take an agent (Clojure agent reference type)
  as their first argument: (send-message! ag \"hello\")

  Memory is optional: pass :session-id to make-agent to enable
  Datalevin-backed hybrid memory (semantic search + recent context)."
  (:refer-clojure :exclude [reset!])
  (:require [cheshire.core :as json]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.http :as http]
            [kschltz.agent.tools.repl :as repl]
            [kschltz.agent.tools.web :as web]
            [kschltz.agent.tools.remember :as remember]
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
(def ^:const truncation-suffix "…")

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

(defn- truncate-text
  "Truncate text to max-chars (including suffix). nil max-chars disables truncation."
  [text max-chars]
  (if (and max-chars (pos? max-chars) (string? text) (> (count text) max-chars))
    (let [keep (max 1 (- max-chars (count truncation-suffix)))]
      (str (subs text 0 keep) truncation-suffix))
    text))

(defn- truncate-tool-calls
  "Truncate tool call arguments, preserving valid JSON.
   Tool call :arguments must be valid JSON or the API rejects the request.
   If the arguments are too long, replace them with an empty JSON object
   rather than truncating mid-string (which produces invalid JSON)."
  [tool-calls max-chars]
  (when (seq tool-calls)
    (mapv (fn [tc]
            (if-let [f (:function tc)]
              (update tc :function
                      (fn [func]
                        (if (and max-chars
                                 (pos? max-chars)
                                 (string? (:arguments func))
                                 (> (count (:arguments func)) max-chars))
                          ;; Arguments are too long — replace with empty JSON object
                          ;; rather than producing invalid JSON via truncation.
                          ;; The LLM will see the tool name and know it was truncated.
                          (assoc func :arguments "{}")
                          func)))
              tc))
          tool-calls)))

(defn- sanitize-context-messages
  "Strip tool_calls and tool_call_id from context messages before sending to the LLM API.
   Historical tool calls are stale — their IDs don't match any tool results,
   and sending assistant messages with tool_calls but no matching tool results
   causes 'invalid tool call arguments' errors from the API.
   Convert tool-related messages to plain text summaries instead."
  [messages]
  (mapv (fn [msg]
          (cond
            ;; Assistant message with tool_calls -> plain text summary
            (and (= (:role msg) "assistant") (seq (:tool_calls msg)))
            (let [tool-names (mapv (fn [tc]
                                    (get-in tc [:function :name] "unknown"))
                                  (:tool_calls msg))
                  base      (str "[Used tools: " (str/join ", " tool-names) "]")
                  content   (if-let [c (:content msg)]
                               (str base "\n" c)
                               base)]
              {:role "assistant" :content content})

            ;; Tool result message -> plain text summary
            (= (:role msg) "tool")
            {:role "user"
             :content (str "[Tool result: "
                          (subs (:content msg "") 0 (min 500 (count (:content msg ""))))
                          "]")}

            ;; Normal message - pass through unchanged
            :else msg))
        messages))

(defn- truncate-chat-message
  "Truncate :content and tool :arguments in an OpenAI-format chat message."
  [msg max-chars]
  (cond-> msg
    (contains? msg :content)
    (update :content truncate-text max-chars)
    (:tool_calls msg)
    (update :tool_calls truncate-tool-calls max-chars)))

(defn- serialize-tool-calls
  [tool-calls]
  (when (seq tool-calls)
    (json/generate-string tool-calls)))

(defn- deserialize-tool-calls
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (json/parse-string s true)
         (catch Exception _ nil))))

(defn- chat-msg->memory-msg
  "Convert an OpenAI chat message to Datalevin storage format (full text, no truncation)."
  [{:keys [role content tool_calls tool_call_id]}]
  (cond-> {:role (or role "user")
           :text (or content "")}
    (seq tool_calls) (assoc :tool-calls (serialize-tool-calls tool_calls))
    tool_call_id (assoc :tool-call-id tool_call_id)))

(defn- memory-msg->chat-msg
  [{:msg/keys [id role text timestamp tool-calls tool-call-id]}]
  (let [chat (cond-> {:role (or role "user") :content (or text "")}
               id (assoc :msg-id id)
               timestamp (assoc :timestamp timestamp)
               tool-call-id (assoc :tool_call_id tool-call-id))]
    (if-let [tcs (deserialize-tool-calls tool-calls)]
      (assoc chat :tool_calls tcs)
      chat)))

(defn- memory-msgs->chat-msgs
  "Convert memory-format messages to chat-history format (chronological).
   Explicit facts are excluded; they appear in the [memory] context block."
  [memory-msgs]
  (mapv memory-msg->chat-msg
        (remove #(= "fact" (:msg/kind %)) memory-msgs)))

(defn- fact-msg? [msg]
  (= "fact" (:msg/kind msg)))

(defn- format-fact-line
  [{:msg/keys [text topic tags]}]
  (let [tags-str (when (and (string? tags) (not (str/blank? tags)))
                   (str " (" tags ")"))]
    (cond
      (str/blank? topic) (str "- " text)
      tags-str (str "- " topic tags-str ": " text)
      :else (str "- " topic ": " text))))

(defn- format-memory-block
  [fact-msgs]
  (str "[memory]\n"
       (str/join "\n" (map format-fact-line (sort-by :msg/timestamp fact-msgs)))
       "\n[/memory]"))

(defn- split-facts-and-chat
  [memory-msgs]
  [(filterv fact-msg? memory-msgs)
   (vec (remove fact-msg? memory-msgs))])

(defn- default-agent-tools
  [memory-store session-id memory-backend]
  (vec (remove nil?
               [(repl/repl-eval-tool)
                (web/web-search-tool)
                (when (and memory-store session-id)
                  (remember/remember-tool
                    {:store-fact! (fn [{:keys [content topic tags]}]
                                    (memory/store-message
                                      {:backend memory-backend
                                       :connection memory-store
                                       :session-id session-id
                                       :message (cond-> {:role "assistant"
                                                         :text content
                                                         :kind "fact"}
                                                  topic (assoc :topic topic)
                                                  (seq tags) (assoc :tags tags))}))}))])))

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
                             (:connection (memory/create-session
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
   (map-indexed (fn [idx msg]
                  (let [{:keys [role text tool-calls tool-call-id]}
                        (chat-msg->memory-msg msg)
                        {:keys [msg-id timestamp]} msg]
                    (cond-> {:msg/role role :msg/text text}
                      msg-id (assoc :msg/id msg-id)
                      timestamp (assoc :msg/timestamp timestamp)
                      tool-calls (assoc :msg/tool-calls tool-calls)
                      tool-call-id (assoc :msg/tool-call-id tool-call-id)
                      (and (not timestamp) (not msg-id)) (assoc :msg/timestamp idx))))
                history)))

(defn compose-context
  "Build memory-augmented context for the LLM call.
   Retrieves semantically relevant messages, merges with recent in-agent
   history via :memory-strategy (default :hybrid), deduped and sorted."
  [{:keys [session-id memory-store memory-backend history memory-max-chars
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
                                    :recent-limit memory-recent-limit})
          [facts chat-msgs] (split-facts-and-chat composed)
          memory-block (when (seq facts)
                         [{:role "system" :content (format-memory-block facts)}])
          chat-context (sanitize-context-messages
                        (mapv #(truncate-chat-message % memory-max-chars)
                              (memory-msgs->chat-msgs chat-msgs)))]
      (into (vec memory-block) chat-context))
    (sanitize-context-messages
     (mapv #(truncate-chat-message % memory-max-chars) history))))

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

(defn- store-memory-chat-msg
  "Persist one OpenAI-format chat message. Returns {:msg-id :timestamp :chat-msg}."
  [state session-id memory-store memory-backend ts-suffix memory-msg]
  (let [ts         (or (:timestamp memory-msg) (System/currentTimeMillis))
        msg-id     (str "msg-" ts "-" ts-suffix "-" (rand-int 100000))
        stored-msg (assoc memory-msg :id msg-id :timestamp ts)
        store-result (memory/store-message {:backend memory-backend
                                            :session-id session-id
                                            :connection memory-store
                                            :message stored-msg})]
    (notify-store-result state (:role memory-msg) store-result)
    {:msg-id    msg-id
     :timestamp ts
     :chat-msg  (memory-msg->chat-msg
                 {:msg/id msg-id
                  :msg/role (:role memory-msg)
                  :msg/text (:text memory-msg)
                  :msg/timestamp ts
                  :msg/tool-calls (:tool-calls memory-msg)
                  :msg/tool-call-id (:tool-call-id memory-msg)})}))

(defn- store-exchange
  "Store a user message and chronological turn transcript (tool calls included).
   Returns {:user-id :user-timestamp :stored-msgs} or nil when memory is inactive."
  [state user-input & {:keys [transcript]}]
  (when (and (:memory-store state) (:session-id state))
    (try
      (let [{:keys [session-id memory-store memory-backend]} state
            ts           (System/currentTimeMillis)
            user-id      (str "msg-" ts "-u-" (rand-int 100000))
            user-memory  (chat-msg->memory-msg {:role "user" :content user-input})
            user-result  (memory/store-message {:backend memory-backend
                                                :session-id session-id
                                                :connection memory-store
                                                :message (assoc user-memory
                                                                :id user-id
                                                                :timestamp ts)})
            _            (notify-store-result state "user" user-result)
            stored-msgs  (mapv (fn [[idx msg]]
                                 (let [memory-msg (assoc (chat-msg->memory-msg msg)
                                                         :timestamp (+ ts 1 idx))]
                                   (store-memory-chat-msg state session-id memory-store memory-backend
                                                          (str "x" idx) memory-msg)))
                               (map-indexed vector (or transcript [])))]
        {:user-id user-id :user-timestamp ts :stored-msgs stored-msgs})
      (catch Exception e
        (println (str "Memory store error: " (.getMessage e)))
        nil))))

;; ---- Tool Use (OpenAI Native Function Calling) ----

(defn openai-tools
  "Build the OpenAI-format tools array from registered tools.
   Returns nil if no tools are registered."
  [tools]
  (when (seq tools)
    (vec (mapv tools/openai-tool-def tools))))

(defn- parse-tool-calls-native
  "Extract tool calls from an OpenAI native function-calling response.
   Returns [{:id call-id :tool name :args json-args-string}] or nil."
  [response]
  (when-let [tcs (http/tool-calls response)]
    (vec (for [tc tcs
               :let [f (get tc :function)]]
           {:id   (:id tc)
            :tool (:name f)
            :args (:arguments f)}))))

(def ^:private max-tool-result-chars
  "Maximum characters for a single tool result. Longer results are truncated."
  (or (some-> (System/getenv "LATERALUS_MAX_TOOL_RESULT_CHARS") parse-long)
      8000))

(defn- truncate-tool-result
  "Truncate a tool result string to max-tool-result-chars."
  [s]
  (let [s (str s)]
    (if (> (count s) max-tool-result-chars)
      (str (subs s 0 max-tool-result-chars) "\n... [truncated]")
      s)))

(defn- format-tool-results-native
  "Build role:\"tool\" messages from tool execution results.
   Results are truncated to max-tool-result-chars to prevent context bloat."
  [results]
  (vec (for [{:keys [id result error]} results]
         {:role "tool"
          :tool_call_id id
          :content (truncate-tool-result
                     (if error (str "Error: " error) (str result)))})))

(defn- execute-tool-call
  "Execute a single tool call with Malli validation.
   Returns {:id call-id :tool name :args decoded-map :result output-str}
   or {:id call-id :tool name :args raw-json :error humanized-errors}."
  [tools call]
  (let [{:keys [id tool args]} call
        tool-def (first (filter #(= (:name %) tool) tools))]
    (if-not tool-def
      {:id id :tool tool :args args :error (str "Unknown tool: " tool)}
      (let [validation (tools/validate-args tool-def args)]
        (if-let [err (:error validation)]
          {:id id :tool tool :args args :error (pr-str err)}
          (try
            (let [result (tools/tool-call tool-def (:ok validation))]
              {:id id :tool tool :args (:ok validation) :result result})
            (catch Exception e
              {:id id :tool tool :args args :error (.getMessage e)})))))))

(defn- execute-tool-calls
  "Execute multiple tool calls in parallel. Returns vector of results."
  [calls tools]
  (vec (pmap #(execute-tool-call tools %) calls)))

;; ---- LLM ----

(defn- llm-call
  "Call the LLM API. Uses memory-augmented context when available.
   Passes tools for native function calling.
   Returns the raw API response map."
  [state {:keys [user-text turn-messages]}]
  (if (and (:base-url state) (:model state))
    (let [max-chars    (:memory-max-chars state)
          ctx          (compose-context state user-text)
          turn-msgs'   (when turn-messages
                         (mapv #(truncate-chat-message % max-chars) turn-messages))
          api-messages (if turn-msgs'
                         (into (vec ctx) turn-msgs')
                         (conj (vec ctx) (truncate-chat-message
                                          {:role "user" :content user-text}
                                          max-chars)))
          api-tools    (openai-tools (:tools state))]
      (http/completion (:base-url state) (:api-key state)
                       (:model state) nil
                       :messages api-messages
                       :tools api-tools))
    {:choices [{:message {:content "LLM not configured"}}]}))

;; ---- Loop ----

(defn- llm-turn-result
  [response transcript]
  {:response response :transcript (vec transcript)})

(defn- llm-turn
  "Run one LLM call. If it contains tool calls, execute them and loop.
   Returns {:response ... :transcript [...]} — chronological assistant/tool messages.
   Bounded by :max-tool-calls to prevent infinite loops.
   Fires :on-thought callback with intermediate events.
   Retries on Malli validation errors or tool execution errors
   up to :max-retries times (default 3, env LATERALUS_MAX_RETRIES)."
  [ag state user-text]
  (let [max-depth   (or (:max-tool-calls state) 10)
        max-retries (or (:max-retries state) 3)]
    (loop [turn-msgs    [{:role "user" :content user-text}]
           depth        0
           retry-count  0
           transcript   []]
      (let [{:keys [response api-error? api-error-msg]}
            (try
              {:response (llm-call state {:user-text user-text
                                          :turn-messages turn-msgs})}
              (catch Exception e
                ;; LLM API error (400, timeout, connection refused, etc.)
                ;; Fire on-error for logging, then tell the LLM so it can self-correct
                (let [msg   (.getMessage e)
                      data  (ex-data e)
                      short (or (get-in data [:body :error :message])
                                (get-in data [:body :error])
                                msg)]
                  (when-let [on-error (:on-error state)]
                    (try (on-error ag e) (catch Exception _)))
                  (fire-on-thought state {:type :error :content (str "ERROR: " short)})
                  {:response {:choices [{:message {:content (str "LLM API error: " short)}}]}
                   :api-error? true
                   :api-error-msg short})))
            content    (http/assistant-content response)
            reasoning  (http/reasoning-content response)
            _          (when reasoning
                         (fire-on-thought state {:type :thinking :content reasoning}))
            calls      (when-not api-error? (parse-tool-calls-native response))]
        (cond
          ;; No tool calls — handle API errors, empty responses, or normal text
          (nil? calls)
          (let [text (or content "")]
            (cond
              ;; LLM API error — retry with trimmed context, not by adding the error
              api-error?
              (if (< retry-count max-retries)
                (let [trimmed (if (> (count turn-msgs) 2)
                                (conj (subvec turn-msgs 0 1) ;; keep only first user msg
                                      {:role "user"
                                       :content (str "The previous LLM call failed ("
                                                    (or api-error-msg "unknown error")
                                                    "). This is often caused by large context. "
                                                    "Provide a shorter response. Avoid repeating large outputs.")})
                                turn-msgs)] ;; already minimal, retry as-is
                  (recur trimmed depth (inc retry-count) transcript))
                (llm-turn-result (str "LLM API error: " (or api-error-msg "unknown error"))
                                 (conj transcript {:role "assistant"
                                                   :content (str "LLM API error: " (or api-error-msg "unknown error"))})))

              ;; Empty response — retry
              (str/blank? text)
              (if (< retry-count max-retries)
                (recur (conj turn-msgs
                             (http/assistant-message response)
                             {:role "user"
                              :content "Your previous response was empty. Provide a plain-text answer."})
                       depth (inc retry-count) transcript)
                (llm-turn-result text (conj transcript {:role "assistant" :content text})))

              ;; Normal text response — return it
              :else
              (llm-turn-result text (conj transcript {:role "assistant" :content text}))))

          (>= depth max-depth)
          ;; Give the LLM one final turn to synthesize a response
          ;; instead of just appending [Tool call limit reached].
          ;; We inject a system-like user message telling the model to wrap up,
          ;; then call the LLM one more time and return THAT response.
          (let [wrap-up-prompt (str "You have reached the maximum number of tool calls ("
                                   max-depth "). "
                                   "You cannot make any more tool calls. "
                                   "Using only the information you already have from previous tool results, "
                                   "provide the best possible answer to the user now. "
                                   "Do not attempt any more tool calls.")
                final-turn  (conj (into turn-msgs
                                        (into [(http/assistant-message response)]
                                              (format-tool-results-native
                                            (execute-tool-calls calls (:tools state)))))
                                   {:role "user" :content wrap-up-prompt})
                final-resp  (llm-call state {:user-text user-text
                                            :turn-messages final-turn})
                final-text  (or (http/assistant-content final-resp) "")]
            (fire-on-thought state {:type :thinking :content (or (http/reasoning-content final-resp) "")})
            (llm-turn-result final-text
                             (conj transcript {:role "assistant" :content final-text})))

          :else
          (let [_         (fire-on-thought state {:type :tool-call :content (or content "")
                                                  :calls calls})
                results    (execute-tool-calls calls (:tools state))
                _          (fire-on-thought state {:type :tool-result :results results})
                errors     (filterv :error results)
                asst-msg   (http/assistant-message response)
                tool-msgs  (format-tool-results-native results)
                transcript' (into transcript (into [asst-msg] tool-msgs))]
            (if (and (seq errors) (< retry-count max-retries))
              (recur (-> turn-msgs
                         (into [asst-msg])
                         (into tool-msgs)
                         (into [{:role "user"
                                 :content (str "The following tool calls failed. "
                                               "Review the errors carefully and fix the issue.\n"
                                               "Common fixes:\n"
                                               "  - Fix the specific error mentioned (e.g. ClassNotFoundException → fix the import)\n"
                                               "  - Simplify the code if it was too complex\n"
                                               "  - If a tool keeps failing, try a different approach or answer from what you know\n"
                                               "Do NOT repeat the exact same call.\n"
                                               "Errors:\n"
                                               (str/join "\n" (map #(str "  " (:tool %) ": " (:error %)) errors)))}]))
                     (inc depth) (inc retry-count) transcript')
              (recur (into turn-msgs (into [asst-msg] tool-msgs))
                     (inc depth) retry-count transcript'))))))))

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

(defn- default-error-handler
  "Default error handler: log the error and continue running.
   The agent loop catches errors so it can keep processing messages.
   Only sets :running false if :stop-on-error is set in state."
  [ag ^Exception e]
  (println (str "Agent error (continuing): " (.getMessage e)))
  (when (:stop-on-error @ag)
    (send ag assoc :running false)
    (await ag)))

(defn- history-entries-for-exchange
  "Build chronological chat-history entries for a completed exchange."
  [items stored & {:keys [transcript]}]
  (let [user-entry (cond-> {:role "user" :content (str/join "\n" (mapv :text items))}
                     stored (assoc :msg-id (:user-id stored)
                                   :timestamp (:user-timestamp stored)))
        transcript-entries (if stored
                             (mapv (fn [{:keys [msg-id timestamp chat-msg]}]
                                     (cond-> chat-msg
                                       msg-id (assoc :msg-id msg-id)
                                       timestamp (assoc :timestamp timestamp)))
                                   (:stored-msgs stored))
                             (vec (or transcript [])))]
    (into [user-entry] transcript-entries)))

(defn- process-messages
  "Process a batch of drained queue items against the LLM.
   Handles tool calls in a loop until a final text response.
   Delivers each item's promise and calls its handler.
   Returns updated state map. On error, delivers error messages and continues."
  [ag state items]
  (let [texts         (mapv :text items)
        combined-input (str/join "\n" texts)]
    (try
      (let [{:keys [response transcript]} (llm-turn ag state combined-input)
            stored   (store-exchange state combined-input :transcript transcript)
            entries  (history-entries-for-exchange items stored :transcript transcript)
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
            (= (:action next-state) :error)      (recur (inc (:turn next-state)))))))))

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
         {:keys [response transcript]} (llm-turn ag (merge state (select-keys opts [:base-url :api-key :model]))
                                                 message)
         stored   (store-exchange state message :transcript transcript)
         entries  (history-entries-for-exchange [{:text message}] stored :transcript transcript)]
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

(defn add-remember-tool!
  "Add a remember tool wired to the agent's memory store."
  ([ag]
   (add-remember-tool! ag {}))
  ([ag opts]
   (let [state @ag
         tool  (remember/remember-tool
                 (merge opts
                        {:store-fact! (or (:store-fact! opts)
                                          (when (:memory-store state)
                                            (fn [fact]
                                              (memory/store-message
                                                {:backend (:memory-backend state)
                                                 :connection (:memory-store state)
                                                 :session-id (:session-id state)
                                                 :message (cond-> {:role "assistant"
                                                                   :text (:content fact)
                                                                   :kind "fact"}
                                                          (:topic fact) (assoc :topic (:topic fact))
                                                          (seq (:tags fact)) (assoc :tags (:tags fact)))}))))}))]
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
                         :connection (:connection conn)}))
