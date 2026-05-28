(ns kschltz.agent.tools.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.repl :as sut]))

;; ---- Tool Creation ----

(deftest repl-eval-tool-defaults
  (testing "repl-eval-tool creates a tool with sensible defaults"
    (let [tool (sut/repl-eval-tool)]
      (is (= :repl (:type tool)))
      (is (= :eval (:mode tool)))
      (is (= "repl-eval" (:name tool)))
      (is (= :string (:result-type tool)))
      (is (nil? (:port tool)))
      (is (string? (:description tool))))))

(deftest repl-eval-tool-with-options
  (testing "repl-eval-tool accepts options to override defaults"
    (let [tool (sut/repl-eval-tool
                {:name "my-eval"
                 :result-type :edn
                 :description "Custom eval"})]
      (is (= :eval (:mode tool)))
      (is (= "my-eval" (:name tool)))
      (is (= :edn (:result-type tool)))
      (is (= "Custom eval" (:description tool))))))

(deftest repl-nrepl-tool-defaults
  (testing "repl-nrepl-tool creates a tool with default nrepl config"
    (let [tool (sut/repl-nrepl-tool)]
      (is (= :repl (:type tool)))
      (is (= :nrepl (:mode tool)))
      (is (= "repl-nrepl" (:name tool)))
      (is (= 59500 (:port tool)))
      (is (= :string (:result-type tool))))))

(deftest repl-nrepl-tool-with-options
  (testing "repl-nrepl-tool accepts options to override defaults"
    (let [tool (sut/repl-nrepl-tool
                {:port 59510
                 :name "custom-nrepl"
                 :result-type :edn})]
      (is (= :nrepl (:mode tool)))
      (is (= 59510 (:port tool)))
      (is (= "custom-nrepl" (:name tool)))
      (is (= :edn (:result-type tool))))))

;; ---- Execution Mode Dispatch ----

(deftest mode-dispatch-extracts-mode
  (testing "mode-dispatch returns the :mode field"
    (is (= :eval (sut/mode-dispatch {:mode :eval})))
    (is (= :nrepl (sut/mode-dispatch {:mode :nrepl})))
    (is (nil? (sut/mode-dispatch {:type :repl})))))

(deftest result-type-dispatch-extracts-result-type
  (testing "result-type-dispatch returns the :result-type field"
    (is (= :string (sut/result-type-dispatch {:result-type :string})))
    (is (= :edn (sut/result-type-dispatch {:result-type :edn})))
    (is (nil? (sut/result-type-dispatch {:type :repl})))))

;; ---- Run REPL :eval Mode ----

(deftest run-repl-eval-simple-arithmetic
  (testing "eval mode evaluates simple arithmetic"
    (let [tool (sut/repl-eval-tool)]
      (is (= "6" (tools/run tool "(+ 1 2 3)"))))))

(deftest run-repl-eval-range
  (testing "eval mode evaluates range expressions"
    (let [tool (sut/repl-eval-tool)]
      (is (= "(0 1 2 3 4)" (tools/run tool "(range 5)"))))))

(deftest run-repl-eval-string
  (testing "eval mode evaluates string expressions"
    (let [tool (sut/repl-eval-tool)]
      (is (= "\"hello\"" (tools/run tool "\"hello\""))))))

(deftest run-repl-eval-map
  (testing "eval mode evaluates map expressions"
    (let [tool (sut/repl-eval-tool)]
      (is (= "{:a 1, :b 2}" (tools/run tool "{:a 1 :b 2}"))))))

(deftest run-repl-eval-exception-handling
  (testing "eval mode catches exceptions and returns message"
    (let [tool (sut/repl-eval-tool)]
      (let [raw (tools/run tool "(throw (ex-info \"oops\" {}))")]
        (is (string? raw))
        (is (.contains raw "Exception:"))))))

(deftest run-repl-eval-nil-result
  (testing "eval mode handles nil results"
    (let [tool (sut/repl-eval-tool)]
      (is (= "nil" (tools/run tool "nil"))))))

(deftest run-repl-eval-false-result
  (testing "eval mode handles false results"
    (let [tool (sut/repl-eval-tool)]
      (is (= "false" (tools/run tool "false"))))))

(deftest run-repl-eval-fn-definition
  (testing "eval mode evaluates defn definitions"
    (let [tool (sut/repl-eval-tool)]
      (tools/run tool "(defn double [x] (* 2 x))")
      (is (= "42" (tools/run tool "(double 21)"))))))

;; ---- Run REPL :nrepl Mode ----

(deftest run-repl-nrepl-throws-unavailable
  (testing "nrepl mode throws when library not available"
    (let [tool (sut/repl-nrepl-tool)
          tool' (assoc tool :port 12345)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"nREPL mode requires"
           (tools/run tool' "(+ 1 2)"))))))

(deftest run-repl-nrepl-includes-port-in-exception
  (testing "nrepl mode exception includes port in data"
    (let [tool (assoc (sut/repl-nrepl-tool) :port 59500)
          e (try (tools/run tool "(+ 1 2)")
                 (catch Exception ex ex))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= 59500 (-> e ex-data :port))))))

;; ---- Run REPL :default Fallback ----

(deftest run-repl-default-throws
  (testing "unknown mode throws ex-info"
    (let [tool {:type :repl
                :mode :unknown
                :name "test"
                :result-type :string}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown REPL mode"
           (tools/run tool "(+ 1 2)"))))))

;; ---- Response Parsing via tools/parse ----

(deftest parse-repl-string-valid-edn
  (testing "tools/parse with :repl tool and :string result-type parses valid EDN"
    (let [tool (sut/repl-eval-tool)]
      (is (= 42 (tools/parse tool "42")))
      (is (= [1 2 3] (tools/parse tool "[1 2 3]")))
      (is (= {:key "value"} (tools/parse tool "{:key \"value\"}"))))))

(deftest parse-repl-string-invalid-edn-returns-raw
  (testing "tools/parse with :string returns raw on invalid EDN"
    (let [tool (sut/repl-eval-tool)]
      (is (= "#bad-tag" (tools/parse tool "#bad-tag"))))))

(deftest parse-repl-edn-valid
  (testing "tools/parse with :edn result-type parses valid EDN"
    (let [tool (sut/repl-eval-tool {:result-type :edn})]
      (is (= 42 (tools/parse tool "42")))
      (is (= '(quote (a b c)) (tools/parse tool "(quote (a b c))"))))))

(deftest parse-repl-edn-invalid-returns-raw
  (testing "tools/parse with :edn returns raw on invalid EDN"
    (let [tool (sut/repl-eval-tool {:result-type :edn})]
      (is (= "#bad-tag" (tools/parse tool "#bad-tag"))))))

(deftest parse-repl-default-returns-raw
  (testing "tools/parse with unknown result-type returns response unchanged"
    (let [tool {:type :repl
                :mode :eval
                :result-type :unknown}]
      (is (= "raw" (tools/parse tool "raw"))))))

;; ---- Integration via tools/run and tools/parse ----

(deftest tools-run-delegates-to-run-repl-for-repl-type
  (testing "tools/run dispatches :repl to run-repl"
    (let [tool (sut/repl-eval-tool)]
      (is (= "6" (tools/run tool "(+ 1 2 3)"))))))

(deftest tools-parse-delegates-to-parse-repl-for-repl-type
  (testing "tools/parse dispatches :repl to parse-repl"
    (let [tool (sut/repl-eval-tool)]
      (is (= "hello" (tools/parse tool "\"hello\""))))))

(deftest tool-call-response-eval-integration
  (testing "tool-call-response with eval mode executes and parses"
    (let [tool (sut/repl-eval-tool)]
      (is (= 6 (tools/tool-call-response tool "(+ 1 2 3)")))
      (is (= [0 1 2 3 4] (tools/tool-call-response tool "(range 5)")))
      (is (= "hello" (tools/tool-call-response tool "\"hello\""))))))

(deftest tool-call-response-eval-with-edn-result-type
  (testing "tool-call-response with :edn result-type parses maps"
    (let [tool (sut/repl-eval-tool {:result-type :edn})]
      (is (= {:status "ok" :value 42}
             (tools/tool-call-response tool "{:status \"ok\" :value 42}"))))))

(deftest tool-call-with-eval-integration
  (testing "tool-call with eval mode returns raw string"
    (let [tool (sut/repl-eval-tool)]
      (is (= "6" (tools/tool-call tool "(+ 1 2 3)")))
      (is (= "42" (tools/tool-call tool "(inc 41)"))))))

;; ---- Edge Cases ----

(deftest run-repl-eval-with-unicode
  (testing "eval mode handles unicode strings"
    (let [tool (sut/repl-eval-tool)]
      (is (= "\"こんにちは\"" (tools/run tool "\"こんにちは\""))))))

(deftest run-repl-eval-with-special-chars
  (testing "eval mode handles special characters in strings"
    (let [tool (sut/repl-eval-tool)]
      (let [raw (tools/run tool "\"\\n\\t\\r\"")]
        (is (.contains raw "\\n"))))))

(deftest run-repl-eval-nested-structures
  (testing "eval mode handles nested collections"
    (let [tool (sut/repl-eval-tool)]
      (is (= "[1 [2 [3]]]" (tools/run tool "[1 [2 [3]]]"))))))

(deftest run-repl-eval-empty-list
  (testing "eval mode handles empty list"
    (let [tool (sut/repl-eval-tool)]
      (is (= "()" (tools/run tool "()"))))))

(deftest run-repl-eval-boolean-results
  (testing "eval mode preserves boolean results"
    (let [tool (sut/repl-eval-tool)]
      (is (= "true" (tools/run tool "true")))
      (is (= "false" (tools/run tool "false"))))))

(deftest run-repl-eval-strips-markdown-fences
  (testing "eval mode accepts markdown-fenced code from LLM output"
    (let [tool (sut/repl-eval-tool)]
      (is (= "6" (tools/run tool "```clojure\n(+ 1 2 3)\n```"))))))

(deftest run-repl-eval-multi-form
  (testing "eval mode evaluates multiple top-level forms"
    (let [tool (sut/repl-eval-tool)]
      (is (= "2" (tools/run tool "(def x 1)\n(inc x)"))))))

(deftest run-repl-eval-repairs-missing-paren
  (testing "eval mode auto-repairs missing closing delimiter"
    (let [tool (sut/repl-eval-tool)
          raw (tools/run tool {:code "(let [x 1] (inc x"})]
      (is (string? raw))
      (is (= "2\n; delimiter repair applied before eval" raw)))))

(deftest run-repl-eval-repairs-extra-paren
  (testing "eval mode auto-repairs extra closing delimiter"
    (let [tool (sut/repl-eval-tool)]
      (is (= "6\n; delimiter repair applied before eval"
             (tools/run tool "(+ 1 2 3))")))))

(deftest run-repl-eval-repairs-thread-macro
  (testing "eval mode auto-repairs broken threading forms"
    (let [tool (sut/repl-eval-tool)]
      (is (= "3\n; delimiter repair applied before eval"
             (tools/run tool "(-> 1 inc inc")))))))
