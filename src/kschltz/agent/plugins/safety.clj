(ns kschltz.agent.plugins.safety
  "Safety guard plugin — runs `kschltz.agent.safety.pre-filter/check-input`
   on user input before it reaches the LLM. Adds a `:guard` slot
   interceptor that, when the input is blocked, sets :exchange/error
   and :exchange/response, fires :on-error, and terminates the chain
   before any LLM call is made."
  (:require [kschltz.agent.chain :as chain]
            [kschltz.agent.safety.pre-filter :as pre-filter]))

(defn plugin
  "Build a safety plugin. The :guard interceptor blocks injection
   attempts and other flagged inputs per `pre-filter/check-input`."
  []
  {:plugin/name :safety
   :plugin/doc  "Pre-LLM safety check via pre-filter/check-input."
   :plugin/slots
   {:guard
    [{:name :safety/guard
      :enter
      (fn guard-enter [ctx]
        (let [state (:agent/state ctx)
              user-text (:exchange/user-text ctx)
              ;; check-input can throw if the input is malformed;
              ;; treat throws as :pass to avoid breaking the chain.
              result (try (pre-filter/check-input user-text {})
                          (catch Exception _ (pre-filter/pass)))
              pre-filter-result (if (instance? kschltz.agent.safety.pre_filter.PreFilterResult result)
                                  result
                                  (pre-filter/pass))]
          (if (pre-filter/blocked? pre-filter-result)
            (let [err-str (str "Blocked by safety guard: "
                               (or (:reason pre-filter-result) "policy violation"))]
              (when-let [on-error (:on-error state)]
                (try (on-error (:agent/ref ctx)
                               (ex-info err-str {:pre-filter-result pre-filter-result}))
                     (catch Exception _)))
              ;; Set error + response so the deliver-responses stage
              ;; surfaces the message; then terminate the chain so
              ;; no LLM call is made.
              (-> ctx
                  (assoc :exchange/error err-str)
                  (assoc :exchange/response err-str)
                  chain/terminate))
            ctx)))}]}})