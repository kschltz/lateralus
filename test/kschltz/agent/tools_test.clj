(ns kschltz.agent.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools :as sut]))

;; ---- Tool Registration ----

(deftest tool-creates-builtin-tool
  (testing "creates a builtin tool with all expected keys"
    (let [tool (sut/tool :builtin "test-tool" "A test tool"
                         (fn [_] "ok"))]
      (is (= :builtin (:type tool)))
      (is (= "test-tool" (:name tool)))
      (is (= "A test tool" (:description tool)))
      (is (fn? (:fn tool))))))

(deftest tool-throws-unknown-type
  (testing "throws ex-info for unsupported tool types"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown tool type"
         (sut/tool :unknown "x" "y" identity)))))

;; ---- Execution (run) ----

(deftest run-builtin-executes-fn
  (testing "builtin run method calls the tool fn with args"
    (let [tool {:type :builtin
                :name   "add"
                :fn     (fn [args]
                          (str (apply + args)))}]
      (is (= "6" (sut/run tool [1 2 3]))))))

(deftest run-builtin-with-map-args
  (testing "builtin run passes map args to fn"
    (let [tool {:type :builtin
                :name   "greet"
                :fn     #(str "Hello, " (:name %))}]
      (is (= "Hello, Alice"
             (sut/run tool {:name "Alice"}))))))

(deftest run-builtin-with-empty-args
  (testing "builtin run works with nil args"
    (let [tool {:type :builtin :name "noop" :fn (fn [_] "done")}]
      (is (= "done" (sut/run tool nil))))))

(deftest run-default-throws
  (testing "unknown tool type throws ex-info via default method"
    (let [tool {:type :magic :name "spell" :fn (fn [_] nil)}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown tool type"
           (sut/run tool []))))))

;; ---- Response Parsing (parse) ----

(deftest parse-builtin-parses-edn-map
  (testing "builtin parse converts EDN map strings"
    (let [tool {:type :builtin}]
      (is (= {:key "value"}
             (sut/parse tool "{:key \"value\"}"))))))

(deftest parse-builtin-parses-edn-scalar
  (testing "builtin parse converts EDN scalars"
    (let [tool {:type :builtin}]
      (is (= 42 (sut/parse tool "42")))
      (is (= 3.14 (sut/parse tool "3.14")))
      (is (= true (sut/parse tool "true"))))))

(deftest parse-builtin-parses-edn-seq
  (testing "builtin parse converts EDN sequence strings"
    (let [tool {:type :builtin}]
      (is (= [1 2 3] (sut/parse tool "[1 2 3]")))
      (is (= '(quote (a b c))
             (sut/parse tool "(quote (a b c))")))))

  (deftest parse-builtin-returns-nil-on-invalid-edn
    (testing "builtin parse returns nil for truly unparseable strings"
      (let [tool {:type :builtin}]
        (is (nil? (sut/parse tool "#bad-tag")))))))

(deftest parse-default-returns-raw
  (testing "default parse returns response unchanged"
    (let [tool {:type :unknown}]
      (is (= "raw response" (sut/parse tool "raw response"))))))

;; ---- Public API (tool-call, tool-call-response) ----

(deftest tool-call-returns-raw-string
  (testing "tool-call returns the raw string output from the tool"
    (let [tool {:type :builtin :name "echo" :fn #(str "echo: " %)}]
      (is (= "echo: hello" (sut/tool-call tool "hello"))))))

(deftest tool-call-response-parses-and-returns
  (testing "tool-call-response executes and parses the tool output"
    (let [tool {:type :builtin
                :name   "math"
                :fn     #(str (apply + %))}]
      (is (= 6 (sut/tool-call-response tool [1 2 3]))))))

(deftest tool-call-response-throws-on-unparseable
  (testing "tool-call-response throws when parse returns nil"
    (let [tool {:type :builtin :name "noisy"
                :fn (constantly "#bad-tag")}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid response"
           (sut/tool-call-response tool "anything"))))))

(deftest tool-call-response-with-map-args
  (testing "tool-call-response works with map arguments"
    (let [tool {:type :builtin
                :name   "double"
                :fn     #(str (* 2 (:value %)))}]
      (is (= 10
             (sut/tool-call-response tool {:value 5}))))))

;; ---- Dispatch functions ----

(deftest run-dispatch-extracts-tool-type
  (testing "run-dispatch returns the :type field of a tool"
    (is (= :builtin (sut/run-dispatch {:type :builtin :name "test" :fn identity})))
    (is (= :other (sut/run-dispatch {:type :other :name "test" :fn identity})))
    (is (nil? (sut/run-dispatch {:name "test" :fn identity})))))

(deftest parse-dispatch-extracts-tool-type
  (testing "parse-dispatch returns the :type field of a tool"
    (is (= :builtin (sut/parse-dispatch {:type :builtin :name "test"})))
    (is (= :custom (sut/parse-dispatch {:type :custom :name "test"})))
    (is (nil? (sut/parse-dispatch {:name "test"})))))
