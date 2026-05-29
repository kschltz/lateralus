(ns kschltz.agent.context
  "Context assembly for LLM calls — truncation, history shaping,
  memory message conversion, and composed context construction.

   Extracted from core.clj to reduce coupling and clarify boundaries.
   No behavior changes — pure refactor."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kschltz.agent.memory :as memory]))

;; ---- Constants ----

(def ^:const truncation-suffix
  "Suffix appended when text is truncated."
  "…")

(def ^:private openai-msg-keys
  "Only these keys are valid in OpenAI Chat Completion message objects.
   Any other keys (e.g. :msg-id, :timestamp, :msg/id, :msg/timestamp)
   will cause a 400 from strict providers like the HuggingFace router."
  #{:role :content :name :tool_calls :tool_call_id :reasoning_content})

;; ---- Truncation ----

(defn truncate-text
  "Truncate text to max-chars (including suffix). nil max-chars disables truncation."
  [text max-chars]
  (if (and max-chars (pos? max-chars) (string? text) (> (count text) max-chars))
    (let [keep (max 1 (- max-chars (count truncation-suffix)))]
      (str (subs text 0 keep) truncation-suffix))
    text))

(defn truncate-tool-calls
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

(defn truncate-chat-message
  "Truncate :content and tool :arguments in an OpenAI-format chat message."
  [msg max-chars]
  (cond-> msg
    (contains? msg :content)
    (update :content #(truncate-text (str %) max-chars))
    (:tool_calls msg)
    (update :tool_calls truncate-tool-calls max-chars)))

;; ---- Sanitization ----

(defn sanitize-context-messages
  "Strip tool_calls, tool_call_id, and non-OpenAI metadata from context messages
   before sending to the LLM API.
   Historical tool calls are stale — their IDs don't match any tool results,
   and sending assistant messages with tool_calls but no matching tool results
   causes 'invalid tool call arguments' errors from the API.
   Internal metadata like :msg-id and :timestamp cause 400 errors from strict
   providers (e.g. HuggingFace router) that reject unknown properties.
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
            (let [c (str (or (:content msg) ""))]
              {:role "user"
               :content (str "[Tool result: "
                            (subs c 0 (min 500 (count c)))
                            "]")})

            ;; Normal message - strip non-OpenAI keys, coerce content to string
            :else (let [m (into {} (filter (fn [[k _]] (contains? openai-msg-keys k)) msg))]
                    (cond-> m
                      (contains? m :content)
                      (update :content #(str (or % "")))))))
        messages))

;; ---- Serialization ----

(defn serialize-tool-calls
  [tool-calls]
  (when (seq tool-calls)
    (json/generate-string tool-calls)))

(defn deserialize-tool-calls
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (json/parse-string s true)
         (catch Exception _ nil))))

;; ---- Memory Message shaping ----

(defn chat-msg->memory-msg
  "Convert an OpenAI chat message to Datalevin storage format (full text, no truncation)."
  [{:keys [role content tool_calls tool_call_id]}]
  (cond-> {:role (or role "user")
           :text (str (or content ""))}
    (seq tool_calls) (assoc :tool-calls (serialize-tool-calls tool_calls))
    tool_call_id (assoc :tool-call-id tool_call_id)))

(defn memory-msg->chat-msg
  [{:msg/keys [id role text timestamp tool-calls tool-call-id]}]
  (let [chat (cond-> {:role (or role "user") :content (str (or text ""))}
               id (assoc :msg-id id)
               timestamp (assoc :timestamp timestamp)
               tool-call-id (assoc :tool_call_id tool-call-id))]
    (if-let [tcs (deserialize-tool-calls tool-calls)]
      (assoc chat :tool_calls tcs)
      chat)))

(defn memory-msgs->chat-msgs
  "Convert memory-format messages to chat-history format (chronological).
   Explicit facts are excluded; they appear in the [memory] context block."
  [memory-msgs]
  (mapv memory-msg->chat-msg
        (remove #(= "fact" (:msg/kind %)) memory-msgs)))

(defn fact-msg? [msg]
  (= "fact" (:msg/kind msg)))

(defn format-fact-line
  [{:msg/keys [text topic tags]}]
  (let [tags-str (when (and (string? tags) (not (str/blank? tags)))
                   (str " (" tags ")"))]
    (cond
      (str/blank? topic) (str "- " text)
      tags-str (str "- " topic tags-str ": " text)
      :else (str "- " topic ": " text))))

(defn format-memory-block
  [fact-msgs]
  (str "[memory]\n"
       (str/join "\n" (map format-fact-line (sort-by :msg/timestamp fact-msgs)))
       "\n[/memory]"))

(defn split-facts-and-chat
  [memory-msgs]
  [(filterv fact-msg? memory-msgs)
   (vec (remove fact-msg? memory-msgs))])

;; ---- History ----

(defn history->memory-msgs
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

;; ---- Context Composition ----

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
                       :store memory-store
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

(defn cap-history
  "Cap history to :history-limit messages. Older messages are persisted in
   Datalevin and available via semantic retrieval. Returns state with :history trimmed."
  [state]
  (if-let [limit (:history-limit state)]
    (let [h (:history state)]
      (if (> (count h) limit)
        (assoc state :history (vec (take-last limit h)))
        state))
    state))