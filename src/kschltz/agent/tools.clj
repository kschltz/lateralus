(ns kschltz.agent.tools)

;; ---- Tool Registration ----
;; Uses cond-based dispatch for registration (avoids defmulti &-arg
;; conflicts in Clojure 1.12).  New tool types are added as cond
;; branches.  The actual execution (run) and parsing (parse) still
;; use defmulti for proper type-based dispatch.
(defn- ->tool
  [type name desc fn]
  (condp = type
    :builtin {:type   :builtin
              :name   name
              :description desc
              :fn     fn}
    (throw (ex-info "Unknown tool type" {:type type}))))

(defn tool
  "Register a tool.  Currently supports :builtin — pass a fn that
   receives a single args collection (map or seq) and returns a
   string response."
  ([type name desc fn]
   (->tool type name desc fn)))

;; ---- Dispatch Functions ----
;; Must be defined BEFORE defmulti so the compiler can resolve them.
(defn run-dispatch
  "Extract tool type for run dispatch." [tool & _]
  (:type tool))

(defn parse-dispatch
  "Extract tool type for parse dispatch." [tool & _]
  (:type tool))

;; ---- Tool Execution ----
;; Dispatch on the tool's :type so new tool types extend run
;; without touching dispatch logic.
(defmulti run run-dispatch)

(defmethod run :builtin
  [tool args]
  ((:fn tool) args))

(defmethod run :default
  [tool args]
  (throw (ex-info "Unknown tool type" {:type (:type tool)})))

;; ---- Response Parsing ----
;; Dispatch on tool type — builtins always expect EDN output,
;; other types (HTTP, LLM-call) can opt for JSON, raw, etc.
(defmulti parse parse-dispatch)

(defmethod parse :builtin
  [tool response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ nil)))

(defmethod parse :default
  [tool response]
  response)

;; ---- Public API ----
(defn tool-call
  "Execute a tool with the given args and return raw output."
  [tool args]
  (run tool args))

(defn tool-call-response
  "Execute a tool, parse its response, and return the result.
   Args may be a map of named params or a seq of positional args."
  [tool args]
  (let [raw     (tool-call tool args)
        parsed  (parse tool raw)]
    (when (nil? parsed)
      (throw (ex-info "Invalid response" {:raw raw})))
    parsed))
