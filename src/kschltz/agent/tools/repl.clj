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
         '[clojure.string :as str]
         '[edamame.core :as edamame])

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

(defn- sanitize-code
  "Normalize LLM-generated code before evaluation."
  [code]
  (-> code str/trim strip-markdown-fence str/trim))

(defn- eval-forms
  "Evaluate one or more Clojure forms from a code string."
  [code]
  (let [forms (edamame/parse-string-all code {:all true
                                              :read-cond :allow
                                              :auto-resolve name})]
    (if (= 1 (count forms))
      (clojure.core/eval (first forms))
      (clojure.core/eval `(do ~@forms)))))

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
  [tool args]
  (let [code       (sanitize-code args)
        fixed-code (delimiter-repair/repair-or-original code)
        result     (try
                     (eval-forms fixed-code)
                     (catch Exception e
                       (str "Exception: " (.getMessage e))))]
    (pr-str result)))

(defmethod run-repl :nrepl
  [tool args]
  (let [port (:port tool)
        code (str args)]
    ;; TODO: Add nrepl library dependency to deps.edn for nREPL support.
    ;; Until then, this mode is a stub.
    (throw (ex-info "nREPL mode requires [nrepl/nrepl] dependency"
                    {:port port}))))

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
  "Build a REPL tool map.  Args are passed as a code string at call time."
  ([mode name desc]
   (make-repl-tool mode name desc nil nil))
  ([mode name desc port]
   (make-repl-tool mode name desc port :string))
  ([mode name desc port result-type]
   {:type       :repl
    :mode       mode
    :name       name
    :result-type (or result-type :string)
    :description desc
    :port       port}))

(defn repl-eval-tool
  "Create a :repl tool that evaluates Clojure code locally via
   clojure.core/eval.

   Args: a code string (e.g. \\\"(+ 1 2 3)\\\").
   Returns: parsed result (falls back to raw string on parse failure).

   Options:
     :result-type  — parse mode (:string or :edn, default :string)
     :name         — tool name (default: \\\"repl-eval\\\")
     :description  — tool description"
  ([]
   (make-repl-tool :eval "repl-eval"
                   "Evaluate Clojure code locally. Args: code string. Returns: parsed result."))
  ([opts]
   (make-repl-tool :eval (or (:name opts) "repl-eval")
                   (or (:description opts) "Evaluate Clojure code locally. Args: code string. Returns: parsed result.")
                   nil (or (:result-type opts) :string))))

(defn repl-nrepl-tool
  "Create a :repl tool that evaluates Clojure code via remote nREPL.

   Args: a code string.
   Returns: parsed result (falls back to raw string on parse failure).

   Requires: [nrepl/nrepl] in deps.edn for full nREPL protocol support.
   Until then, this mode throws an error.

   Options:
     :port         — nREPL port (default: 59500)
     :result-type  — parse mode (:string or :edn, default :string)
     :name         — tool name (default: \\\"repl-nrepl\\\")
     :description  — tool description"
  ([]
   (repl-nrepl-tool {:port 59500}))
  ([opts]
   (let [{:keys [port result-type name description]
          :or   {port 59500
                 result-type :string
                 name "repl-nrepl"
                 description "Evaluate Clojure code via nREPL. Args: code string. Returns: parsed result."}} opts]
     (make-repl-tool :nrepl name description port result-type))))
