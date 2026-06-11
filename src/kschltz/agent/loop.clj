(ns kschltz.agent.loop
  "Agent loop mechanics — LLM turn execution, tool calling,
  message processing, queue drain, and state management.

   Extracted from core.clj to reduce coupling and clarify boundaries.
   No behavior changes — pure refactor."
  (:require [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.http :as http]
            [kschltz.agent.llm :as llm]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.tools :as tools]))

;; ---- Default exchange chain ----

;; (Moved to kschltz.agent.exchange to break the loop <-> interceptors
;; require cycle. loop requires interceptors; interceptors requires
;; loop for delegated helpers; exchange is the third-party assembly
;; point that depends on both.)

;; ---- Callbacks ----

(defn fire-on-thought
  "Fire the on-thought callback if present. Catches errors so loop continues."
  [state event]
  (when-let [on-thought (:on-thought state)]
    (try (on-thought event) (catch Exception _))))

(defn fire-memory-event
  "Fire the on-memory-event callback if present."
  [state event]
  (when-let [on-memory (:on-memory-event state)]
    (try (on-memory event) (catch Exception _))))

(defn notify-store-result
  [state role result]
  (when (and result (not (:indexed result)))
    (fire-memory-event state (assoc result :type :memory-not-indexed :role role))))

;; ---- Memory Store Helpers ----

(defn store-memory-chat-msg
  "Persist one OpenAI-format chat message. Returns {:msg-id :timestamp :chat-msg}."
  [state session-id memory-store memory-backend ts-suffix memory-msg]
  (let [ts         (or (:timestamp memory-msg) (System/currentTimeMillis))
        msg-id     (str "msg-" ts "-" ts-suffix "-" (rand-int 100000))
        stored-msg (assoc memory-msg :id msg-id :timestamp ts)
        store-result (memory/store-message {:backend memory-backend
                                            :session-id session-id
                                            :store memory-store
                                            :message stored-msg})]
    (notify-store-result state (:role memory-msg) store-result)
    {:msg-id    msg-id
     :timestamp ts
     :chat-msg  (context/memory-msg->chat-msg
                 {:msg/id msg-id
                  :msg/role (:role memory-msg)
                  :msg/text (:text memory-msg)
                  :msg/timestamp ts
                  :msg/tool-calls (:tool-calls memory-msg)
                  :msg/tool-call-id (:tool-call-id memory-msg)})}))

(defn store-exchange
  "Store a user message and chronological turn transcript (tool calls included).
   Returns {:user-id :user-timestamp :stored-msgs} or nil when memory is inactive."
  [state user-input & {:keys [transcript]}]
  (when (and (:memory-store state) (:session-id state))
    (try
      (let [{:keys [session-id memory-store memory-backend]} state
            ts           (System/currentTimeMillis)
            user-id      (str "msg-" ts "-u-" (rand-int 100000))
            user-memory  (context/chat-msg->memory-msg {:role "user" :content user-input})
            user-result  (memory/store-message {:backend memory-backend
                                                :session-id session-id
                                                :store memory-store
                                                :message (assoc user-memory
                                                                :id user-id
                                                                :timestamp ts)})
            _            (notify-store-result state "user" user-result)
            stored-msgs  (mapv (fn [[idx msg]]
                                 (let [memory-msg (assoc (context/chat-msg->memory-msg msg)
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

(defn parse-tool-calls-native
  "Extract tool calls from an OpenAI native function-calling response.
   Returns [{:id call-id :tool name :args json-args-string}] or nil.
   Public for `kschltz.agent.interceptors` delegation (Phase 2)."
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

(defn truncate-tool-result
  "Truncate a tool result string to max-tool-result-chars."
  [s]
  (let [s (str s)]
    (if (> (count s) max-tool-result-chars)
      (str (subs s 0 max-tool-result-chars) "\n... [truncated]")
      s)))

(defn format-tool-results-native
  "Build role:\"tool\" messages from tool execution results.
   Results are truncated to max-tool-result-chars to prevent context bloat."
  [results]
  (vec (for [{:keys [id result error]} results]
         {:role "tool"
          :tool_call_id id
          :content (truncate-tool-result
                    (if error (str "Error: " error) (str result)))})))

(defn execute-tool-call
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

(defn execute-tool-calls
  "Execute multiple tool calls serially. Returns vector of results."
  [calls tools]
  (mapv #(execute-tool-call tools %) calls))

;; ---- LLM ----

(defn llm-call
  "Call the LLM API. Uses memory-augmented context when available.
   Passes tools for native function calling.
   Returns the raw API response map."
  [state {:keys [user-text turn-messages]}]
  (if (and (:base-url state) (:model state))
    (let [max-chars    (:memory-max-chars state)
          ctx          (context/compose-context state user-text)
          turn-msgs'   (when turn-messages
                         (mapv #(context/truncate-chat-message % max-chars) turn-messages))
          api-messages (if turn-msgs'
                         (into (vec ctx) turn-msgs')
                         (conj (vec ctx) (context/truncate-chat-message
                                          {:role "user" :content user-text}
                                          max-chars)))
          api-tools    (openai-tools (:tools state))]
      (llm/call {:provider :openai-compatible
                 :base-url (:base-url state)
                 :api-key  (:api-key state)
                 :model    (:model state)
                 :messages api-messages
                 :tools    api-tools}))
    {:choices [{:message {:content "LLM not configured"}}]}))

;; ---- Loop ----

(defn llm-turn-result
  [response transcript]
  {:response response :transcript (vec transcript)})

(defn llm-turn
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

(defn drain-queue!
  "Atomically swap out the message queue via a single agent action.
   Returns [items state-after-drain]. No TOCTOU race — read and clear
   happen in one agent action, so concurrent send-message! cannot be lost.
   Each item is {:text ... :promise <promise> :handler <fn|nil>}."
  [ag]
  (let [result-promise (promise)]
    (send ag (fn [state]
               (let [items (:message-queue state)]
                 (deliver result-promise [items (assoc state :message-queue [])])
                 (assoc state :message-queue []))))
    (await ag)
    @result-promise))

(defn drain-queue
  "Pure drain on a state map (no agent action). Used by tests only.
   Returns [items state-sans-queue]."
  [state]
  [(:message-queue state) (assoc state :message-queue [])])

(defn queue-wait
  "Sleep briefly when queue is empty to avoid busy-spinning."
  [state]
  (when (empty? (:message-queue state))
    (Thread/sleep 100)))

(defn deliver-response
  "Deliver a response: call on-response default handler, deliver promise, call per-message handler."
  [{:keys [promise handler on-response]} response]
  (when on-response (try (on-response response) (catch Exception e
                                                  (println (str "Default handler error: " (.getMessage e))))))
  (when promise (deliver promise response))
  (when handler (try (handler response) (catch Exception e
                                          (println (str "Handler error: " (.getMessage e)))))))

(defn default-error-handler
  "Default error handler: log the error and continue running.
   The agent loop catches errors so it can keep processing messages.
   Only sets :running false if :stop-on-error is set in state."
  [ag ^Exception e]
  (println (str "Agent error (continuing): " (.getMessage e)))
  (when (:stop-on-error @ag)
    (send ag assoc :running false)
    (await ag)))

(defn history-entries-for-exchange
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

(defn process-messages
  "Process a batch of drained queue items against the LLM.
   Builds an initial ctx and runs the default exchange chain. Returns
   a state map (with :agent/state-delta keys merged in) for the outer
   agent-loop to apply via `send`. Errors are caught by `error-boundary`
   inside the chain; this fn has a belt-and-suspenders catch for
   unexpected throws.

   NOTE: the chain is referenced via `requiring-resolve` to avoid a
   load-time cycle (loop -> exchange -> interceptors -> loop)."
  [ag state items]
  (let [texts         (mapv :text items)
        combined-input (str/join "\n" texts)
        chain-val      (deref (requiring-resolve 'kschltz.agent.exchange/default-exchange-chain))
        ctx           {:agent/ref ag
                      :agent/state state
                      :agent/state-delta {}
                      :exchange/items items
                      :exchange/user-text combined-input
                      :exchange/response nil
                      :exchange/error nil
                      :turn/messages [{:role "user" :content combined-input}]
                      :turn/transcript []
                      :turn/depth 0
                      :turn/retries 0
                      :llm/request nil
                      :llm/response nil
                      :llm/api-error nil
                      :tool/calls nil
                      :tool/results nil
                      :memory/recalled nil
                      :memory/stored nil
                      :llm/client (or (:llm/client state) (llm-client/default-client))}]
    (try
      (let [result (chain/execute ctx chain-val)
            response (or (:exchange/response result)
                         (:exchange/error result)
                         "(no response)")]
        ;; The deliver-responses interceptor (in default-exchange-chain)
        ;; already called `loop/deliver-response` for each item, which
        ;; fires the per-item handler, the on-response callback, and
        ;; delivers the promise. No need to call it again here.
        (merge state (:agent/state-delta result)))
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

(defn agent-loop
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
                (let [[items _] (drain-queue! ag)]
                  (if (empty? items)
                    (do (queue-wait @ag)
                        {:action :idle :turn turn})
                    (let [state'    @ag
                          result    (process-messages ag state' items)
                          next-turn (inc turn)]
                      ;; Merge only changed keys so concurrent send-message!
                      ;; enqueues are not overwritten by a full-state replace.
                      (send ag (fn [s]
                                 (merge s
                                        (select-keys result [:current-response :history])
                                        {:turns next-turn})))
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