(ns kschltz.agent.cli-test
  "CLI behavior tests (Phase 5): session opt-in mirrors -main wiring."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.core :as core]))

(deftest parse-args-session-flag
  (testing "-s / --session sets :session"
    (is (= "my-session" (:session (#'cli/parse-args ["-s" "my-session" "hello"]))))
    (is (= "prompt text" (:prompt (#'cli/parse-args ["-s" "sid" "prompt text"]))))))

(deftest parse-args-embedding-flags
  (testing "embedding CLI flags parse correctly"
    (is (= :http (:embedding-method (#'cli/parse-args ["-E" "http" "-s" "s" "hi"]))))
    (is (= :http (:embedding-method (#'cli/parse-args ["--embedding-method" "http"]))))
    (is (= "nomic-embed-text"
           (:embedding-model (#'cli/parse-args ["--embedding-model" "nomic-embed-text"]))))
    (is (= 512 (:embedding-dims (#'cli/parse-args ["--embedding-dims" "512"]))))
    (is (= :langchain4j (:embedding-method (#'cli/parse-args ["-E" "langchain4j"]))))
    (is (= :http (:embedding-method (#'cli/parse-args ["-E" "http"]))))))

(deftest parse-args-embedding-dims-parsing
  (testing "embedding-dims parses string to integer"
    (is (number? (:embedding-dims (#'cli/parse-args ["--embedding-dims" "512"]))))
    (is (= 512 (:embedding-dims (#'cli/parse-args ["--embedding-dims" "512"]))))
    (is (= 384 (:embedding-dims (#'cli/parse-args ["--embedding-dims" "384"]))))))

(deftest parse-embedding-method-validation
  (testing "parse-embedding-method validates and converts to keyword"
    (is (= :langchain4j (#'cli/parse-embedding-method "langchain4j")))
    (is (= :http (#'cli/parse-embedding-method "http")))
    (is (nil? (#'cli/parse-embedding-method nil)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'cli/parse-embedding-method "ollama")))))

(deftest cli-embedding-wiring
  (testing "make-agent receives embedding opts like CLI -main"
    (let [sid (str "cli-embed-" (System/nanoTime))
          ag  (core/make-agent {:base-url "http://mock"
                                :model "mock"
                                :session-id sid
                                :sessions-dir "test-sessions-cli"
                                :memory-embedding-method :http
                                :memory-embedding-model "nomic-embed-text"
                                :memory-embedding-dims 384})]
      (is (= :http (:memory-embedding-method (core/get-memory-config ag))))
      (is (= "nomic-embed-text" (:memory-embedding-model (core/get-memory-config ag))))
      (is (= 384 (:memory-embedding-dims (core/get-memory-config ag))))
      (core/close-session! ag))))

(deftest cli-embedding-last-flag-wins
  (testing "Multiple CLI flags - last one wins"
    (is (= :langchain4j (:embedding-method (#'cli/parse-args ["-E" "http" "-E" "langchain4j"]))))
    (is (= 768 (:embedding-dims (#'cli/parse-args ["--embedding-dims" "384" "--embedding-dims" "768"]))))))

(deftest cli-embedding-bad-method
  (testing "Invalid method via CLI raises error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'cli/parse-args ["-E" "ollama"]))
        "CLI with invalid method should throw")
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'cli/parse-args ["--embedding-method" "ollama"]))
        "--embedding-method with invalid value should throw")))

(deftest cli-embedding-nil-method
  (testing "-E without value fails fast with clear error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'cli/parse-args ["-E"]))
        "-E without value should throw error")
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'cli/parse-args ["-E" ""]))
        "-E with empty string should throw error")))

(deftest cli-embedding-method-invalid-in-env
  (testing "Invalid embedding method via CLI -main fails"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cli/-main "-E" "ollama"))
        "Invalid CLI method should fail before agent start")))

(deftest cli-precedence-embedding-method
  (testing "CLI -E flag wins over LATERALUS_EMBEDDING_METHOD env var"
    (is (= :langchain4j
           (#'cli/resolve-embedding-method
             {:embedding-method :langchain4j}
             (fn [_] "http")))
        "CLI embedding-method should beat env var")
    (is (= :http
           (#'cli/resolve-embedding-method
             {}
             (fn [_] "http")))
        "env var should be used when CLI omits -E")
    (is (nil? (#'cli/resolve-embedding-method {} (fn [_] nil)))
        "nil when neither CLI nor env is set")))

(deftest cli-memory-opt-in
  (testing "memory is disabled without -s or LATERALUS_SESSION (CLI default)"
    (let [session-id (or nil (System/getenv "LATERALUS_SESSION"))]
      (when (nil? session-id)
        (let [ag (core/make-agent {:base-url "http://mock" :model "mock" :session-id nil})]
          (is (nil? (core/get-memory-store ag))
              "make-agent with explicit nil session-id should not open memory")))))
  (testing "memory is enabled when session-id is provided like CLI -s"
    (let [sid (str "cli-opt-in-" (System/nanoTime))
          ag  (core/make-agent {:base-url    "http://mock"
                                :model       "mock"
                                :session-id  sid
                                :sessions-dir "test-sessions-cli"})]
      (is (some? (core/get-memory-store ag)))
      (is (= sid (core/get-session-id ag)))
      (core/close-session! ag))))
