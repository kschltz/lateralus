(ns kschltz.agent.delimiter-repair
  "Automatic Clojure delimiter repair for LLM-generated code.
   
   Based on clojure-mcp-light's approach by Bruce Hauman:
   1. Detect errors with edamame parser
   2. Repair with parinferish (indent mode)
   3. Validate repaired code parses clean
   
   Usage in repl-eval:
     (let [fixed (repair-code code)]
       (eval-string (or fixed code)))"
  (:require [edamame.core :as edamame]
            [parinferish.core :as parinferish]))

(defn delimiter-error?
  "Returns true if the code string has unbalanced delimiters.
   Uses edamame's tolerant parser which reports :edamame/error with
   delimiter info when parens/brackets/braces don't match."
  [s]
  (try
    (edamame/parse-string-all s {:all true
                                  :read-cond :allow
                                  :readers (fn [_tag] (fn [data] data))
                                  :auto-resolve name})
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (= :edamame/error (:type data))))
    (catch Exception _
      ;; If we can't parse at all, assume it might have delimiter issues
      ;; and let parinferish try to fix it
      true)))

(defn repair-with-parinferish
  "Attempt to repair delimiter errors using parinferish (indent mode).
   Returns repaired code string or nil if repair failed."
  [s]
  (try
    (let [repaired (parinferish/flatten
                     (parinferish/parse s {:mode :indent}))]
      (when (and repaired (not= repaired s))
        repaired))
    (catch Exception _
      nil)))

(defn repair-code
  "Attempt to repair delimiter errors in Clojure code.
   Returns the repaired code string if successful, nil if unfixable.
   If no errors exist, returns the original code."
  [s]
  (if (delimiter-error? s)
    (let [repaired (repair-with-parinferish s)]
      (when (and repaired (not (delimiter-error? repaired)))
        repaired))
    s))

(defn repair-or-original
  "Repair code, falling back to original if repair fails.
   Always returns a string — never nil."
  [s]
  (or (repair-code s) s))