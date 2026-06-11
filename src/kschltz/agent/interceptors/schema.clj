(ns kschltz.agent.interceptors.schema
  "Malli schemas for the interceptor engine contract.

   `Interceptor` validates the shape of a single stage map.
   `Ctx` is intentionally permissive (open map with optional namespaced
   keys) - engine state keys (::queue ::stack ::error) are namespaced
   to never collide with domain keys, and domain keys are open so
   plugins can extend the context freely without a schema migration."
  (:require [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [malli.core :as m]))

(def Interceptor
  "An interceptor is a plain map with a required :name and optional
   :enter, :leave, :error stage functions.

   Stage functions:
   - :enter and :leave: (fn [ctx] ctx')
   - :error: (fn [ctx ex] ctx') where ex is the caught throwable"
  [:map {:closed false}
   [:name :keyword]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

(def ^:private engine-key?
  ;; These resolve to kschltz.agent.chain/chain/... (NOT schema/chain/...)
  ;; so the validator matches the actual engine state keys.
  #{::chain/queue ::chain/stack ::chain/error})

(def Ctx
  "Context map threaded through the chain. Open map: domain keys are
   free-form. Engine keys (::chain/queue, ::chain/stack, ::chain/error)
   must not appear in a well-formed input ctx - they're engine state.

   Optional instrumentation keys:
   - :chain/instrument?  boolean
   - :chain/validate     (fn [ctx] nil-or-explanation)"
  [:map {:closed false}
   [:chain/instrument? {:optional true} :boolean]
   [:chain/validate {:optional true} fn?]])

(defn make-validator
  "Build a ctx validator suitable for :chain/validate. Returns a fn
   that returns nil when ctx is well-formed, or a string explanation
   otherwise. The check is shallow - full structural validation of
   domain keys is the application's responsibility."
  []
  (fn validate-ctx [ctx]
    (cond
      (not (map? ctx))
      (str "ctx is not a map: " (type ctx))

      (some engine-key? (keys ctx))
      (str "ctx leaked engine key(s): "
           (->> (filter engine-key? (keys ctx))
                (map name)
                (str/join ", ")))

      :else nil)))
