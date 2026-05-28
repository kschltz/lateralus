(ns kschltz.agent.memory.embedding-integration-test
  "Integration test for live LangChain4j in-process embeddings through Datalevin.
   
   This test exercises the full embedding pipeline:
     1. Create provider via LangChain4j in-process ONNX model
     2. Store messages via Datalevin (with real embedding)
     3. Retrieve via semantic search
   
   Note: This test is marked slow because loading the ONNX model on first run
   can take several seconds. Run with: clojure -M:test -m cognitect.test-runner
   --namespace kschltz.agent.memory.embedding-integration-test
   
   For CI runs, consider skipping this test (use the stub-based tests in
   datalevin_test.clj instead)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.memory.datalevin :as dlevin]))

(def ^:private test-base-dir "test-sessions")
(def ^:private test-model "all-minilm-l6-v2-q")

(defn make-session-id []
  (str "lc4j-integ-" (System/nanoTime)))

(defn delete-tree
  "Delete a directory recursively."
  [dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file)
        (delete-tree file)
        (.delete file)))
    (.delete dir)))

(defn cleanup-dirs
  "Delete test session directories before and after each test."
  []
  (let [dirs [test-base-dir "sessions"]]
    (doseq [dir-path dirs]
      (let [dir (io/file dir-path)]
        (when (.exists dir) (delete-tree dir))))))

(use-fixtures :each
  (fn [test-fn]
    (cleanup-dirs)
    (test-fn)
    (cleanup-dirs)))

(deftest live-langchain4j-full-stack-slow
  "Integration test for full LangChain4j embedding pipeline."
  (testing "Live LangChain4j embedding through Datalevin - full stack"
    (let [session-id (make-session-id)
          store (memory/create-session
                 {:backend :datalevin
                  :session-id session-id
                  :model "mock-model"
                  :embedding-method :langchain4j
                  :embedding-model test-model
                  :sessions-dir test-base-dir})]
      (testing "store-message with real LangChain4j embedding"
        (let [result (dlevin/store-message!
                       (:connection store)
                       {:session-id session-id
                        :role "user"
                        :text "What is the capital of France?"
                        :timestamp (System/currentTimeMillis)})]
          (is (:stored result)
              "message should be stored")
          (is (:indexed result)
              "message should be indexed with live embedding")
          (is (nil? (:reason result))
              "embedding should succeed without errors")))
      (testing "retrieve-relevant finds stored messages"
        (let [results (dlevin/search-relevant!
                        (:connection store)
                        "capital city" session-id 5)]
          (is (some? results)
              "should return results")
          (is (pos? (count results))
              "should find at least one matching message")
          (is (= 1 (count results))
              "should find exactly one message (the one we stored)")))
      (testing "load-recent-messages returns stored messages"
        (let [recent (dlevin/load-recent-messages!
                       (:connection store)
                       session-id 5)]
          (is (= 1 (count recent))
              "should have one message")
          (is (= "user" (:msg/role (first recent)))
              "role should be user")
          (is (str/includes? (:msg/text (first recent)) "capital")
              "content should contain 'capital'")))
      (memory/close-session
       {:backend :datalevin
        :connection (:connection store)}))))

(deftest live-langchain4j-multiple-messages-slow
  "Integration test for multiple LangChain4j-stored messages."
  (testing "Multiple LangChain4j-stored messages retrieved correctly"
    (let [session-id (make-session-id)
          store (memory/create-session
                 {:backend :datalevin
                  :session-id session-id
                  :model "mock-model"
                  :embedding-method :langchain4j
                  :embedding-model test-model
                  :sessions-dir test-base-dir})
          conn (:connection store)]
      (testing "store three messages with live embeddings"
        (dlevin/store-message! conn
          {:session-id session-id
           :role "user"
           :text "What is Clojure?"
           :timestamp 1000})
        (dlevin/store-message! conn
          {:session-id session-id
           :role "assistant"
           :text "Clojure is a functional programming language"
           :timestamp 2000})
        (dlevin/store-message! conn
          {:session-id session-id
           :role "user"
           :text "Tell me about Lateralus"
           :timestamp 3000}))
      (testing "all messages retrievable by semantic search"
        (let [results (dlevin/search-relevant! conn "Clojure" session-id 5)]
          (is (some? results)
              "should return results")
          (is (some? (seq results))
              "should find at least one message")
          (is (some #(str/includes? (:msg/text %) "Clojure") results)
              "should find a message containing 'Clojure'")))
      (testing "load-recent-messages returns all messages"
        (let [recent (dlevin/load-recent-messages! conn session-id 5)]
          (is (= 3 (count recent))
              "should have three messages")
          (is (= "user" (:msg/role (first recent)))
              "first message should be user role")
          (is (str/includes? (:msg/text (last recent)) "Lateralus")
              "last message should contain 'Lateralus'")))
      (memory/close-session
       {:backend :datalevin
        :connection conn}))))

(deftest langchain4j-embedding-handles-tool-messages-slow
  "Integration test for tool metadata with LangChain4j embeddings."
  (testing "Tool metadata preserved with live LangChain4j embeddings"
    (let [session-id (make-session-id)
          store (memory/create-session
                 {:backend :datalevin
                  :session-id session-id
                  :model "mock-model"
                  :embedding-method :langchain4j
                  :embedding-model test-model
                  :sessions-dir test-base-dir})
          conn (:connection store)]
      (testing "store message with tool metadata"
        (let [result (dlevin/store-message! conn
                       {:session-id session-id
                        :role "assistant"
                        :text "I'll evaluate the expression"
                        :tool-name "repl-eval"
                        :tool-result "(+ 1 2) => 3"
                        :timestamp 1000})]
          (is (:stored result)
              "message should be stored")
          (is (:indexed result)
              "message should be indexed")))
      (testing "load-recent-messages preserves tool metadata"
        (let [recent (dlevin/load-recent-messages! conn session-id 5)]
          (is (= 1 (count recent))
              "should have one message")
          (is (= "repl-eval" (:msg/tool-name (first recent)))
              "tool-name should be preserved")
          (is (= "(+ 1 2) => 3" (:msg/tool-result (first recent)))
              "tool-result should be preserved")))
      (memory/close-session
       {:backend :datalevin
        :connection conn}))))
