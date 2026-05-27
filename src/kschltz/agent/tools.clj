(ns kschltz.agent.tools
  (:require [cheshire.core :as json]
            [malli.core :as m]
            [malli.json-schema :as json-schema]
            [malli.transform :as mt]
            [malli.error :as me]))

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

;; ---- OpenAI Function Calling ----

(defn openai-tool-def
  "Convert a tool map to an OpenAI function-calling tool definition.
   Uses malli.json-schema/transform on the tool's :parameters Malli schema
   to generate JSON Schema for the API.
   Returns {:type \"function\" :function {:name ... :description ... :parameters ...}}."
  [tool]
  (let [params-schema (or (:parameters tool)
                          (throw (ex-info "Tool missing :parameters Malli schema" {:tool (:name tool)})))]
    {:type "function"
     :function {:name (:name tool)
                :description (:description tool)
                :parameters (json-schema/transform params-schema)}}))

(defn coerce-args
  "Decode JSON args string through the tool's Malli :parameters schema.
   Uses m/decode with JSON transformer for type coercion (string->int etc.).
   Returns the decoded Clojure map on success, or nil on parse failure."
  [tool args-str]
  (when-let [params-schema (:parameters tool)]
    (try
      (let [parsed (json/parse-string args-str true)]
        (m/decode params-schema parsed mt/json-transformer))
      (catch Exception _ nil))))

(defn validate-args
  "Validate coerced args against the tool's Malli :parameters schema.
   Returns {:ok decoded-map} if valid, or {:error humanized-errors} if invalid.
   Accepts pre-coerced map or raw JSON string."
  [tool arg-value]
  (let [params-schema (:parameters tool)
        decoded       (if (map? arg-value)
                        arg-value
                        (coerce-args tool arg-value))]
    (if-not params-schema
      {:ok decoded}
      (if-let [explain (m/explain params-schema decoded)]
        (if (seq (:errors explain))
          {:error (me/humanize explain)}
          {:ok decoded})
        {:ok decoded}))))

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
