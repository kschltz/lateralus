(ns kschltz.agent.interceptors
  "Interceptor stages for the agent exchange pipeline.

   Each stage is a plain map satisfying `kschltz.agent.interceptors.schema/Interceptor`.
   Stages delegate to existing helpers in `kschltz.agent.loop` and
   `kschltz.agent.context`. No behavior change in this phase: stages
   are wired up in Phase 4 (cutover) by `process-messages` building a
   context and calling `chain/execute`.

   Stage map shape (Pedestal-style, see `kschltz.agent.chain`):
     :name   keyword
     :enter  (fn [ctx] ctx')     ; optional
     :leave  (fn [ctx] ctx')     ; optional
     :error  (fn [ctx ex] ctx')  ; optional"
  (:require [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.http :as http]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.loop :as loop]
            [kschltz.agent.stuck-loop :as stuck-loop]))

;; Forward declarations so the chain/enqueue lists can reference any
;; stage by symbol even though some are defined later in this file.
(declare compose-context llm-call parse-response api-error-retry dispatch
         execute-tools tool-error-retry stuck-loop-detector wrap-up
         store-exchange update-history deliver-responses notify error-boundary)

;; ---------------------------------------------------------------------------
;; compose-context (enter)
;; ---------------------------------------------------------------------------

(def compose-context
  "Build the OpenAI-format :llm/request from current turn messages and
   agent state. Delegates to `context/compose-context`."
  {:name ::compose-context
   :enter (fn [ctx]
            (let [state (:agent/state ctx)
                  user-text (:exchange/user-text ctx)
                  turn-messages (:turn/messages ctx)
                  composed (context/compose-context state user-text)
                  max-chars (:memory-max-chars state)
                  truncated (mapv #(context/truncate-chat-message % max-chars) turn-messages)
                  final-msgs (if (seq truncated)
                               (into (vec composed) truncated)
                               (conj (vec composed)
                                     (context/truncate-chat-message
                                      {:role "user" :content user-text}
                                      max-chars)))
                  tools (loop/openai-tools (:tools state))]
              (assoc ctx :llm/request
                     (cond-> {:base-url (:base-url state) :api-key (:api-key state) :model (:model state) :messages final-msgs}
                       tools (assoc :tools tools)))))})

;; ---------------------------------------------------------------------------
;; llm-call (enter) - protocol-based, catches errors as :llm/api-error
;; ---------------------------------------------------------------------------

(def llm-call
  "Invoke the LLM client. Network/protocol errors are caught and stored
   on :llm/api-error. The :enter stage starts an LLM heartbeat so the
   watchdog can detect a stalled request; the :leave stage clears it."
  {:name ::llm-call
   :enter (fn [ctx]
            (let [client (or (:llm/client ctx) (llm-client/default-client))
                  req (:llm/request ctx)
                  ag (:agent/ref ctx)
                  ;; Start a heartbeat so the watchdog can detect a stall
                  heartbeat-state (llm-client/start-heartbeat! client)]
              (when-not req
                (throw (ex-info "llm-call: missing :llm/request"
                                {:stage :enter})))
              (when ag
                (send ag assoc :llm/heartbeat-state heartbeat-state)
                (await ag))
              (try
                (assoc ctx
                       :llm/response (llm-client/call client req)
                       :llm/heartbeat-state heartbeat-state)
                (catch Throwable t
                  (let [data (ex-data t)
                        short (or (get-in data [:body :error :message])
                                  (get-in data [:body :error])
                                  (.getMessage t))]
                    (-> ctx
                        (assoc :llm/api-error {:exception t :message short})
                        (assoc :llm/response
                               {:choices [{:message {:content (str "LLM API error: " short)}}]})))))))
   :leave (fn [ctx]
            (let [ag (:agent/ref ctx)
                  client (:llm/client ctx)
                  hb (:llm/heartbeat-state ctx)]
              (when (and ag hb client)
                (try (llm-client/cancel client hb)
                     (send ag dissoc :llm/heartbeat-state)
                     (await ag)
                     (catch Throwable _ nil)))
              ctx))})

;; ---------------------------------------------------------------------------
;; parse-response (enter)
;; ---------------------------------------------------------------------------

(def parse-response
  "Extract text content, reasoning, and tool calls from the last
   :llm/response. Fires :on-thought :thinking when reasoning is present."
  {:name ::parse-response
   :enter (fn [ctx]
            (let [response (:llm/response ctx)
                  state (:agent/state ctx)
                  content (http/assistant-content response)
                  reasoning (http/reasoning-content response)
                  api-err (:llm/api-error ctx)
                  calls (when-not api-err (loop/parse-tool-calls-native response))]
              (when reasoning
                (loop/fire-on-thought state {:type :thinking :content reasoning}))
              (assoc ctx
                     :tool/calls calls
                     :exchange/response
                     (or content
                         (and api-err (str "LLM API error: " (:message api-err)))
                         ""))))})

;; ---------------------------------------------------------------------------
;; api-error-retry (enter)
;; ---------------------------------------------------------------------------

(def api-error-retry
  "If :llm/api-error is set and retries remain, trim the message history
   to its first user message plus a corrective prompt, increment retries,
   and re-enqueue [llm-call parse-response api-error-retry dispatch].
   Otherwise set a terminal :exchange/error."
  {:name ::api-error-retry
   :enter (fn [ctx]
            (if-let [api-err (:llm/api-error ctx)]
              (let [retries (:turn/retries ctx)
                    max-retries (or (:max-retries (:agent/state ctx)) 3)]
                (if (< retries max-retries)
                  (let [msgs (:turn/messages ctx)
                        trimmed (if (> (count msgs) 2)
                                  (conj (subvec msgs 0 1)
                                        {:role "user"
                                         :content (str "The previous LLM call failed ("
                                                       (:message api-err)
                                                       "). This is often caused by large context. "
                                                       "Provide a shorter response. Avoid repeating large outputs.")})
                                  msgs)]
                    (-> ctx
                        (assoc :turn/messages trimmed)
                        (update :turn/retries inc)
                        (chain/enqueue [llm-call parse-response
                                        (deref #'api-error-retry) (deref #'dispatch)])))
                  (assoc ctx :exchange/error
                         (str "LLM API error: " (:message api-err)))))
              ctx))})

;; ---------------------------------------------------------------------------
;; dispatch (enter) - the loop brain
;; ---------------------------------------------------------------------------

(def dispatch
  "Replaces `loop/recur` in the original `llm-turn`."
  {:name ::dispatch
   :enter (fn [ctx]
            (let [state (:agent/state ctx)
                  max-depth (or (:max-tool-calls state) 10)
                  calls (:tool/calls ctx)
                  depth (:turn/depth ctx)
                  api-err (:llm/api-error ctx)
                  text (or (:exchange/response ctx) "")]
              (cond
                (and (seq calls) (< depth max-depth))
                (-> ctx
                    (update :turn/depth inc)
                    (chain/enqueue [(deref #'execute-tools) (deref #'tool-error-retry)
                                    (deref #'stuck-loop-detector)
                                    llm-call parse-response (deref #'dispatch)]))

                (and (seq calls) (>= depth max-depth))
                (chain/enqueue ctx [(deref #'wrap-up)])

                api-err
                (chain/enqueue ctx [(deref #'api-error-retry)])

                (str/blank? text)
                (let [retries (:turn/retries ctx)
                      max-retries (or (:max-retries state) 3)]
                  (if (< retries max-retries)
                    (-> ctx
                        (update :turn/messages conj
                                (http/assistant-message (:llm/response ctx))
                                {:role "user"
                                 :content "Your previous response was empty. Provide a plain-text answer."})
                        (update :turn/retries inc)
                        (chain/enqueue [llm-call parse-response (deref #'dispatch)]))
                    ctx))

                :else
                (update ctx :turn/transcript conj
                        {:role "assistant" :content text}))))})

;; ---------------------------------------------------------------------------
;; stuck-loop-detector (enter) - guards against tool-call loops
;; ---------------------------------------------------------------------------

(def stuck-loop-detector
  "Detect when the agent is making no forward progress with its tool
   calls. Runs after every `execute-tools` and inspects the recent
   tool-call history plus the recent tool results.

   When `kschltz.agent.stuck-loop/stuck?` returns a stuck-result, the
   detector:
     1. Sets `:exchange/error` to a structured stuck-loop message
     2. Sets `:stuck-loop` ctx key with the full signal data
     3. Fires `:on-thought` with the stuck event (for live observers)
     4. Calls `chain/terminate` so no more stages run

   The structured error format lets `deliver-responses` surface a
   `{:type :stuck-loop :recent-calls [...] :reason \"...\"}` event to
   the user. The user is then expected to respond (per the goal's
   intervention policy: stop the turn and ask the user)."
  {:name ::stuck-loop-detector
   :enter (fn [ctx]
            (let [turn-msgs (:turn/messages ctx)
                  state (:agent/state ctx)
                  cfg (stuck-loop/config)
                  calls (stuck-loop/extract-recent-calls turn-msgs)
                  ;; Only consider results from tool messages that match
                  ;; the recent calls we extracted.
                  results (stuck-loop/extract-recent-results
                           (vec (take-last (* 2 (long (:window cfg))) turn-msgs)))]
              (if-let [stuck (stuck-loop/stuck? calls results)]
                (let [event (assoc stuck :type :stuck-loop :depth (:turn/depth ctx))
                      msg (str "Agent appears stuck: " (:reason stuck)
                               ". Recent calls: "
                               (vec (take 3 (mapv :tool (:recent-calls stuck)))))]
                  (loop/fire-on-thought state
                                        {:type :stuck-loop
                                         :content msg
                                         :event event})
                  (-> ctx
                      (assoc :stuck-loop event)
                      (assoc :exchange/error msg)
                      (chain/terminate)))
                ctx)))})

;; ---------------------------------------------------------------------------
;; execute-tools (enter)
;; ---------------------------------------------------------------------------

(def execute-tools
  "Run all parsed tool calls serially. Append assistant + tool messages
   to :turn/messages and :turn/transcript. Fire :on-thought for each
   call/result."
  {:name ::execute-tools
   :enter (fn [ctx]
            (let [state (:agent/state ctx)
                  calls (:tool/calls ctx)
                  text (or (:exchange/response ctx) "")
                  results (loop/execute-tool-calls calls (:tools state))
                  asst-msg (http/assistant-message (:llm/response ctx))
                  tool-msgs (loop/format-tool-results-native results)]
              (loop/fire-on-thought state {:type :tool-call :content text :calls calls})
              (loop/fire-on-thought state {:type :tool-result :results results})
              (-> ctx
                  (assoc :tool/results results)
                  (update :turn/messages into (into [asst-msg] tool-msgs))
                  (update :turn/transcript into (into [asst-msg] tool-msgs)))))})

;; ---------------------------------------------------------------------------
;; tool-error-retry (enter)
;; ---------------------------------------------------------------------------

(def tool-error-retry
  "If any tool call errored and retries remain, append a corrective
   user message and increment :turn/retries."
  {:name ::tool-error-retry
   :enter (fn [ctx]
            (let [errors (filterv :error (:tool/results ctx))
                  retries (:turn/retries ctx)
                  max-retries (or (:max-retries (:agent/state ctx)) 3)]
              (if (and (seq errors) (< retries max-retries))
                (-> ctx
                    (update :turn/messages conj
                            {:role "user"
                             :content (str "The following tool calls failed. "
                                           "Review the errors carefully and fix the issue.\n"
                                           "Common fixes:\n"
                                           "  - Fix the specific error mentioned (e.g. ClassNotFoundException → fix the import)\n"
                                           "  - Simplify the code if it was too complex\n"
                                           "  - If a tool keeps failing, try a different approach or answer from what you know\n"
                                           "The stuck-loop-detector will fire mechanically if you repeat similar calls;\n"
                                           "the corrective guidance has moved to the detector.\n"
                                           "Errors:\n"
                                           (str/join "\n" (map #(str "  " (:tool %) ": " (:error %)) errors)))})
                    (update :turn/retries inc))
                ctx)))})

;; ---------------------------------------------------------------------------
;; wrap-up (enter)
;; ---------------------------------------------------------------------------

(def wrap-up
  "When depth is exhausted, inject a wrap-up user message and run one
   final llm-call + parse-response. No tool calls allowed."
  {:name ::wrap-up
   :enter (fn [ctx]
            (let [max-depth (or (:max-tool-calls (:agent/state ctx)) 10)
                  wrap-up-prompt (str "You have reached the maximum number of tool calls ("
                                      max-depth "). "
                                      "You cannot make any more tool calls. "
                                      "Using only the information you already have from previous tool results, "
                                      "provide the best possible answer to the user now. "
                                      "Do not attempt any more tool calls.")
                  client (or (:llm/client ctx) (llm-client/default-client))
                  req (assoc (:llm/request ctx)
                             :messages
                             (conj (:messages (:llm/request ctx))
                                   {:role "user" :content wrap-up-prompt}))]
              (try
                (let [resp (llm-client/call client req)
                      final (or (http/assistant-content resp) "")]
                  (loop/fire-on-thought (:agent/state ctx)
                                        {:type :thinking
                                         :content (or (http/reasoning-content resp) "")})
                  (-> ctx
                      (assoc :llm/response resp)
                      (assoc :exchange/response final)
                      (update :turn/transcript conj
                              {:role "assistant" :content final})))
                (catch Throwable t
                  (assoc ctx :exchange/error
                         (str "LLM API error: " (.getMessage t)))))))})

;; ---------------------------------------------------------------------------
;; store-exchange (leave)
;; ---------------------------------------------------------------------------

(def store-exchange
  "Persist user input + transcript to memory. Stores :memory/stored on
   the ctx. Runs as a :leave stage so it executes once per exchange."
  {:name ::store-exchange
   :leave (fn [ctx]
            (let [state (:agent/state ctx)
                  text (:exchange/user-text ctx)
                  stored (loop/store-exchange state text
                                              :transcript (:turn/transcript ctx))]
              (assoc ctx :memory/stored stored)))})

;; ---------------------------------------------------------------------------
;; update-history (leave)
;; ---------------------------------------------------------------------------

(def update-history
  "Build the history entries for this exchange and cap the rolling
   history. Stages the mutation in :agent/state-delta."
  {:name ::update-history
   :leave (fn [ctx]
            (let [state (:agent/state ctx)
                  items (:exchange/items ctx)
                  stored (:memory/stored ctx)
                  entries (loop/history-entries-for-exchange items stored
                                                             :transcript (:turn/transcript ctx))
                  capped (context/cap-history
                          (into (:history state) entries))
                  delta (cond-> {}
                          (some? (:exchange/response ctx))
                          (assoc :current-response (:exchange/response ctx))
                          (seq entries)
                          (assoc :history capped))]
              (assoc ctx :agent/state-delta delta)))})

;; ---------------------------------------------------------------------------
;; deliver-responses (leave)
;; ---------------------------------------------------------------------------

(def deliver-responses
  "Deliver the final response to each queue item: promise, per-item
   handler, and the default :on-response callback."
  {:name ::deliver-responses
   :leave (fn [ctx]
            (let [state (:agent/state ctx)
                  items (:exchange/items ctx)
                  response (or (:exchange/response ctx) (:current-response state))
                  err (:exchange/error ctx)
                  delivered (or err response)]
              (doseq [item items]
                (loop/deliver-response
                 (merge item (select-keys state [:on-response]))
                 delivered))
              ctx))})

;; ---------------------------------------------------------------------------
;; notify (leave) - no-op for now, reserved for Phase 5 plugins
;; ---------------------------------------------------------------------------

(def notify
  "Reserved for Phase 5 plugin notifications. Currently a no-op."
  {:name ::notify
   :leave (fn [ctx] ctx)})

;; ---------------------------------------------------------------------------
;; error-boundary (error)
;; ---------------------------------------------------------------------------

(def error-boundary
  "Catch errors from any stage. Sets :exchange/error and stores the
   payload so `deliver-responses` can surface it to callers on the
   unwind. Does NOT re-throw — agents keep running across exchanges."
  {:name ::error-boundary
   :error (fn [ctx ex]
            (let [state (:agent/state ctx)
                  err-str (str "Error: " (.getMessage ex))]
              (when-let [on-error (:on-error state)]
                (try (on-error (:agent/ref ctx) ex) (catch Exception _)))
              (assoc ctx :exchange/error err-str)))})
