(ns kschltz.agent.delimiter-repair
  "Automatic Clojure delimiter repair for LLM-generated code.

   Pipeline (aligned with clojure-mcp-light):
   1. Detect delimiter errors with edamame
   2. Repair with parinferish (indent mode)
   3. Validate repaired code parses clean"
  (:require [edamame.core :as edamame]
            [parinferish.core :as parinferish]))

(def ^:private edamame-opts
  {:all true
   :read-cond :allow
   :readers (fn [_tag] (fn [data] data))
   :auto-resolve name})

(defn- parse-all
  [s]
  (edamame/parse-string-all s edamame-opts))

(defn delimiter-error?
  "True when edamame reports an :edamame/error with opened-delimiter info,
   or when parsing fails in a way that may be delimiter-related."
  [s]
  (try
    (parse-all s)
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (or (and (= :edamame/error (:type data))
                 (contains? data :edamame/opened-delimiter))
            (= :edamame/error (:type data)))))
    (catch Exception _
      true)))

(defn repair-with-parinferish
  "Run parinferish indent repair. Returns repaired code when parinfer succeeds."
  [s]
  (try
    (parinferish/flatten (parinferish/parse s {:mode :indent}))
    (catch Exception _
      nil)))

(defn repair-code
  "Repair delimiter errors when detected. Returns repaired string, or
   the original when already valid, or nil when repair fails."
  [s]
  (if (delimiter-error? s)
    (let [repaired (repair-with-parinferish s)]
      (when (and repaired (not (delimiter-error? repaired)))
        repaired))
    s))

(defn force-repair
  "Always attempt parinferish repair; accept result when it parses cleanly."
  [s]
  (let [repaired (repair-with-parinferish s)]
    (when (and repaired (not (delimiter-error? repaired)))
      repaired)))

(defn repair-or-original
  "Repair code, falling back to original when repair fails or is unnecessary."
  [s]
  (or (repair-code s) s))

(defn prepare-for-eval
  "Return {:code string :repaired? boolean} ready for eval."
  [s]
  (let [code (repair-or-original s)]
    {:code code
     :repaired? (not= code s)}))
