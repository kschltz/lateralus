(ns kschltz.agent.tools.repl
  "REPL eval tool — evaluates Clojure code via local eval.

   Extends the multimethod system in kschltz.agent.tools by adding
   a :repl tool type with :eval mode.

   Execution is handled by multimethods defined here:
     run-repl   — dispatches on :mode (:eval)
     parse-repl — dispatches on :result-type (:string | :edn)

   A single :repl method on tools/run delegates to run-repl.

   Usage:
     (def tool (repl-eval-tool))
     (tool-call-response tool \\\"(+ 1 2 3)\\\")    ; => 6
     (tool-call-response tool \\\"(range 5)\\\")    ; => (0 1 2 3 4)

   The agent passes a code string as args; the tool evaluates it and
   returns a parsed result (falls back to raw string if EDN parse fails).")

(require '[kschltz.agent.tools :as tools]
         '[kschltz.agent.delimiter-repair :as delimiter-repair]
         '[kschltz.agent.nrepl-server :as nrepl-srv]
         '[clojure.string :as str]
         '[edamame.core :as edamame]
         '[nrepl.core :as nrepl])

(defn- strip-markdown-fence
  "Remove optional ```clojure fences from LLM-generated code."
  [s]
  (let [trimmed (str/trim (str s))]
    (if (str/starts-with? trimmed "```")
      (-> trimmed
          (str/replace #"^```(?:clojure|clj)?\s*\n?" "")
          (str/replace #"\n?```[\s\S]*$" "")
          str/trim)
      trimmed)))

(defn- normalize-code
  "Accept native tool args map {:code ...} or a bare code string."
  [args]
  (str/trim
    (str (cond
           (string? args) args
           (map? args) (:code args)
           :else args))))

(defn- sanitize-code
  "Normalize LLM-generated code before evaluation."
  [code]
  (-> code normalize-code strip-markdown-fence str/trim))

(import (java.util.concurrent Executors TimeUnit ThreadFactory))

(def ^:private eval-timeout-ms
  "Timeout for eval-forms. Override with LATERALUS_EVAL_TIMEOUT_MS."
  (or (some-> (System/getenv "LATERALUS_EVAL_TIMEOUT_MS") parse-long)
      30000))

(def ^:private eval-executor
  "Daemon thread pool for eval-forms. Daemon threads let the JVM exit cleanly."
  (Executors/newCachedThreadPool
    (proxy [ThreadFactory] []
      (newThread [r]
        (doto (Thread. r)
          (.setDaemon true))))))

(defn- eval-forms
  "Evaluate one or more Clojure forms from a code string.
   Runs in a daemon thread with a timeout to prevent blocking the agent.
   Pushes the current thread's Var bindings so eval sees the right namespace,"
  [code]
  (let [forms (edamame/parse-string-all code {:all true
                                                :read-cond :allow
                                                :auto-resolve name})
        bindings (get-thread-bindings)
        task  (fn []
                (with-bindings bindings
                  (if (= 1 (count forms))
                    (clojure.core/eval (first forms))
                    (clojure.core/eval `(do ~@forms)))))
        fut   (.submit eval-executor ^Callable task)
        result (try (.get fut eval-timeout-ms TimeUnit/MILLISECONDS)
                    (catch java.util.concurrent.TimeoutException _
                      (.cancel fut true)
                      (throw (ex-info (str "Eval timed out after " eval-timeout-ms "ms. "
                                          "Use a future/thread for long-running code.")
                                      {:timeout-ms eval-timeout-ms})))
                    (catch Exception e
                      (.cancel fut true)
                      (throw e)))]
    result))

(defn- format-eval-result
  "pr-str the value; note when delimiter repair ran before eval."
  [value repaired?]
  (let [base (pr-str value)]
    (if repaired?
      (str base "\n; delimiter repair applied before eval")
      base)))

(defn- eval-with-delimiter-repair
  "Sanitize, repair delimiters, eval; retry once with forced repair on failure."
  [raw-code]
  (let [code (sanitize-code raw-code)
        {:keys [code repaired?]} (delimiter-repair/prepare-for-eval code)]
    (try
      {:value (eval-forms code) :repaired? repaired?}
      (catch Exception first-e
        (if repaired?
          (throw first-e)
          (if-some [retry (delimiter-repair/force-repair code)]
            (try
              {:value (eval-forms retry) :repaired? true}
              (catch Exception e
                (throw e)))
            (throw first-e)))))))

(defn- nrepl-connect-opts
  [{:keys [host port]}]
  (cond-> {:port (or port 59500)}
    host (assoc :host host)
    (not host) (assoc :host "127.0.0.1")))

(defn- nrepl-eval-response
  "Run code on an nREPL client and return a string result (value or error)."
  [client code]
  (let [{:keys [value err out status]}
        (-> (nrepl/message client {:op "eval" :code code})
            nrepl/combine-responses)]
    (cond
      (and status (contains? status "eval-error"))
      (str "Exception: " (or err "eval failed"))

      (some-> err not-empty)
      (str "Exception: " err)

      (and (seq out) (not (seq value)))
      (str/trim out)

      (seq value)
      (str (last value))

      :else "nil")))

(defn- ensure-nrepl-server!
  "Start the embedded nREPL server when :auto-start? is true and it is not running."
  [{:keys [port auto-start?] :or {port 59500 auto-start? true}}]
  (when auto-start?
    (when-not (nrepl-srv/running?)
      (nrepl-srv/start! port))))

(defn- eval-via-nrepl
  [tool raw-code]
  (let [{:keys [code repaired?]} (-> raw-code sanitize-code delimiter-repair/prepare-for-eval)
        connect-opts (nrepl-connect-opts tool)]
    (ensure-nrepl-server! {:port (:port tool) :auto-start? (:auto-start? tool)})
    (try
      (with-open [conn (nrepl/connect connect-opts)]
        (let [client (nrepl/client conn eval-timeout-ms)
              result (nrepl-eval-response client code)]
          (if repaired?
            (str result "\n; delimiter repair applied before eval")
            result)))
      (catch Exception e
        (str "Exception: " (.getMessage e))))))

;; ---- Execution Mode Dispatch ----
;; These dispatch functions are defined BEFORE defmulti so the
;; compiler can resolve them.  The :repl multimethods dispatch on
;; the tool's :mode field to distinguish :eval from :nrepl.

(defn mode-dispatch
  "Extract the tool's :mode for run-repl / parse-repl dispatch."
  [tool & _]
  (:mode tool))

(defn result-type-dispatch
  "Extract the tool's :result-type for parse-repl dispatch."
  [tool & _]
  (:result-type tool))

;; ---- Multimethods ----

(defmulti run-repl mode-dispatch)

(defmethod run-repl :eval
  [_tool args]
  (try
    (let [{:keys [value repaired?]} (eval-with-delimiter-repair args)]
      (format-eval-result value repaired?))
    (catch Exception e
      (str "Exception: " (.getMessage e)))))

(defmethod run-repl :nrepl
  [tool args]
  (eval-via-nrepl tool args))

(defmethod run-repl :default
  [tool args]
  (throw (ex-info "Unknown REPL mode"
                  {:mode (:mode tool)
                   :tool (:name tool)})))

;; ---- Response Parsing ----

(defmulti parse-repl result-type-dispatch)

(defmethod parse-repl :string
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch RuntimeException _ response)))

(defmethod parse-repl :edn
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch RuntimeException _ response)))

(defmethod parse-repl :default
  [_ response]
  response)

;; ---- Run integration ----
;; Add a :repl method on the main run multimethod.  This delegates
;; to run-repl which then dispatches on :mode.
(defmethod tools/run :repl
  [tool args]
  (run-repl tool args))

(defmethod tools/parse :repl
  [tool response]
  (parse-repl tool response))

;; ---- Tool Registration ----
;; Simple tool map factory — no :fn needed since multimethods handle
;; execution and parsing.  The :result-type field tells parse-repl how
;; to decode the string returned by run-repl.

(defn- make-repl-tool
  "Build a REPL tool map. Args are passed as a decoded map from the LLM's JSON args."
  ([mode name desc]
   (make-repl-tool mode name desc nil nil nil))
  ([mode name desc port]
   (make-repl-tool mode name desc port :string nil))
  ([mode name desc port result-type]
   (make-repl-tool mode name desc port result-type nil))
  ([mode name desc port result-type opts]
   (merge
    (cond-> {:type        :repl
             :mode        mode
             :name        name
             :result-type (or result-type :string)
             :description desc
             :parameters  [:map [:code :string]]}
      port (assoc :port port))
    (select-keys (or opts {}) [:host :auto-start?]))))

(defn repl-eval-tool
  "Create a :repl tool that evaluates Clojure code locally via
   clojure.core/eval.

   Args: {:code \\\"(+ 1 2 3)\\\"} (decoded from LLM JSON args via Malli).
   Returns: parsed result (falls back to raw string on parse failure).

   Options:
     :result-type  — parse mode (:string or :edn, default :string)
     :name         — tool name (default: \\\"repl-eval\\\")
     :description  — tool description"
  ([]
   (make-repl-tool :eval "repl-eval"
                   "Evaluate Clojure code locally. Unbalanced delimiters are auto-repaired before eval. Args: {:code string}. Returns: parsed result."))
  ([opts]
   (make-repl-tool :eval (or (:name opts) "repl-eval")
                   (or (:description opts)
                       "Evaluate Clojure code locally. Unbalanced delimiters are auto-repaired before eval. Args: {:code string}. Returns: parsed result.")
                   nil (or (:result-type opts) :string))))

(defn repl-nrepl-tool
  "Create a :repl tool that evaluates Clojure code via remote nREPL.

   Args: a code string.
   Returns: parsed result (falls back to raw string on parse failure).

   Starts the embedded nREPL server on demand unless :auto-start? is false.

   Options:
     :port         — nREPL port (default: 59500)
     :host         — nREPL host (default: 127.0.0.1)
     :auto-start?  — start kschltz.agent.nrepl-server if not running (default: true)
     :result-type  — parse mode (:string or :edn, default :string)
     :name         — tool name (default: \\\"repl-nrepl\\\")
     :description  — tool description"
  ([]
   (repl-nrepl-tool {:port 59500}))
  ([opts]
   (let [{:keys [port host auto-start? result-type name description]
          :or   {port 59500
                 host "127.0.0.1"
                 auto-start? true
                 result-type :string
                 name "repl-nrepl"
                 description "Evaluate Clojure code via nREPL. Args: {:code string}. Returns: parsed result."}} opts]
     (make-repl-tool :nrepl name description port result-type
                     {:host host :auto-start? auto-start?}))))
