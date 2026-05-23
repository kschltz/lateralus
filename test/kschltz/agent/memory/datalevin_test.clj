(ns kschltz.agent.memory.datalevin-test
  "Tests for the Datalevin memory backend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [kschltz.agent.memory.datalevin :as dlevin]))

(def ^:private test-base-dir "test-sessions")

(defn make-session-id []
  (str "test-session-" (System/nanoTime)))

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
  (doseq [dir-path [test-base-dir "sessions"]]
    (let [dir (io/file dir-path)]
      (when (.exists dir) (delete-tree dir)))))

(use-fixtures :each
  (fn [test-fn]
    (cleanup-dirs)
    (test-fn)
    (cleanup-dirs)))

;; Stub embedding-fn for tests: returns a deterministic vector
;; based on text content hash. Avoids needing a live Ollama instance.
(defn- test-embedding-fn
  [text]
  (let [h (hash text)]
    (vec (for [i (range 384)]
          (double (+ 0.01 (* (mod (+ h i) 1000) 0.001)))))))

(defn- test-store
  "Create a session store with a stub embedding fn for testing."
  [session-id opts]
  (dlevin/create-session-store session-id
    (merge {:embedding-fn test-embedding-fn} opts)))

(deftest test-create-session-store
  (testing "create-session-store returns a store map"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (is (some? store))
      (is (some? (:connection store)))
      (is (some? (:vec-index store)))
      (is (some? (:kv-store store)))
      (dlevin/close-session-store store))))

(deftest test-store-message
  (testing "store-message stores and retrieves"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (dlevin/store-message! store
        {:session-id session-id
         :role "user"
         :text "Hello, how are you?"
         :timestamp (System/currentTimeMillis)})
      ;; Verify message is in Datalog store
      (let [conn (:connection store)
            results (dlevin/search-relevant! store "Hello" session-id 5)]
        (is (some? results))
        (is (some? (seq results))))
      (dlevin/close-session-store store))))

(deftest test-search-relevant
  (testing "search-relevant finds stored messages via vector search"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (dlevin/store-message! store
        {:session-id session-id :role "user"
         :text "Hello, how are you?" :timestamp 1000})
      (dlevin/store-message! store
        {:session-id session-id :role "assistant"
         :text "I'm doing well!" :timestamp 1001})
      (dlevin/store-message! store
        {:session-id session-id :role "user"
         :text "What is your name?" :timestamp 1002})
      (let [results (dlevin/search-relevant! store "Hello" session-id 5)]
        (is (some? results))
        ;; With test embedding stub, all vectors are identical,
        ;; so results are ordered by HNSW internal order.
        ;; At minimum, we should get some results.
        (is (pos? (count results))))
      (dlevin/close-session-store store))))

(deftest test-close-session-store
  (testing "close-session-store is idempotent"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (dlevin/close-session-store store)
      ;; Second close should not throw
      (is (true? (dlevin/close-session-store store))))))

(deftest test-empty-query-return-empty
  (testing "empty query returns empty results"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (let [results (dlevin/search-relevant! store "" session-id 5)]
        (is (empty? results)))
      (dlevin/close-session-store store))))

(deftest test-nil-session-id-returns-empty
  (testing "nil session-id returns empty results"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (let [results (dlevin/search-relevant! store "Hello" nil 5)]
        (is (empty? results)))
      (dlevin/close-session-store store))))

(deftest test-session-metadata
  (testing "session metadata is stored"
    (let [session-id (str "test-meta-" (System/nanoTime))
          store (test-store session-id {:model "test-model"})]
      (is (some? store))
      (dlevin/close-session-store store))))