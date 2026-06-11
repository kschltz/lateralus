(ns kschltz.agent.plugin
  "Plugin system for the agent interceptor chain.

   A plugin is a pure data map:

     {:plugin/name     :my-plugin
      :plugin/slots    {:guard   [<interceptor>]
                        :enrich  [<interceptor> ...]
                        :tools   [<interceptor> ...]
                        :persist [<interceptor>]
                        :observe [<interceptor>]}
      :plugin/register (fn [state tool-defs] new-state)  ; optional

   Named slots (in execution order; see `default-slot-order`):
     :guard       — security / safety checks before compose
     :stuck-loop  — tool-call loop detection (fires after tool calls)
     :enrich      — RAG / memory recall before compose
     :compose     — context construction interceptors
     :llm         — interceptors that wrap the LLM call
     :dispatch    — tool-loop dispatch interceptors
     :tools       — tool interceptors (typically no-ops; the dispatcher
                    routes tool-calls to the registered tool defs)
     :finalize    — after the loop, before leave
     :history     — leave stage for history updates
     :persist     — leave stage for memory persistence
     :observe     — leave stage for tracing / metrics
     :notify      — leave stage for event callbacks

   `assemble-chain` is a pure fold: same plugins in the same order
   produce the same chain. Within a slot, plugin declaration order
   determines interceptor order."
  (:require [kschltz.agent.interceptors.schema :as schema]
            [malli.core :as m]))

(def default-slot-order
  "The default order in which slots are folded into a chain."
  [:guard :stuck-loop :enrich :compose :llm :dispatch
   :tools :finalize
   :history :persist :observe :notify])

(def Plugin
  "Shape of a plugin map. Either `:plugin/slots` (contributes to
   the chain) or `:plugin/chain` (replaces the chain). At least one
   of the two must be present."
  [:map {:closed false}
   [:plugin/name :keyword]
   [:plugin/slots {:optional true}
    [:map-of :keyword [:vector :any]]]
   [:plugin/chain {:optional true}
    [:vector :any]]
   [:plugin/doc {:optional true} :string]
   [:plugin/register {:optional true} fn?]])

(defn- build-interceptor
  "Wrap a plugin's slot interceptor with metadata."
  [plugin-name slot ix]
  {:name (keyword (str (name plugin-name) "." (name slot)))
   :enter (:enter ix)
   :leave (:leave ix)
   :error (:error ix)
   :plugin/name plugin-name
   :plugin/slot slot
   :plugin/original-name (:name ix)})

(defn assemble-chain
  "Fold a seq of plugins into a single chain (vector of interceptors).

   Reserved slots (per `default-slot-order`): interceptors from each
   plugin in declaration order, appending to the accumulator.

   A plugin with `:plugin/chain` (no slots) is appended verbatim
   after the slot-folded interceptors.

   Throws ex-info with {:explain ...} if any plugin violates the
   `Plugin` schema."
  [plugins]
  (let [explain (m/explain [:sequential Plugin] (vec plugins))]
    (when explain
      (throw (ex-info "Invalid plugin map"
                      {:explain (str (first plugins))}))))
  (let [acc (atom [])]
    (doseq [slot default-slot-order]
      (doseq [plugin plugins
              ix  (get-in plugin [:plugin/slots slot])]
        (swap! acc conj (build-interceptor (:plugin/name plugin) slot ix))))
    (doseq [plugin plugins
            :when (contains? plugin :plugin/chain)]
      (swap! acc into (:plugin/chain plugin)))
    @acc))

(defn validate-plugins
  "Validate a seq of plugins against the `Plugin` schema. Returns nil
   on success, or a human-readable explanation string on failure."
  [plugins]
  (when-let [explain (m/explain [:sequential Plugin] (vec plugins))]
    (str (first plugins))))
