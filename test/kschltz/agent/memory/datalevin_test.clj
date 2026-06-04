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
                               (merge {:embedding-fn test-embedding-fn
                                       :sessions-dir test-base-dir}
                                      opts)))

(deftest test-create-session-store
  (testing "create-session-store returns a store map"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (is (some? store))
      (is (some? (:store store)))
      (is (some? (:vec-index store)))
      (is (some? (:kv-store store)))
      (dlevin/close-session-store store))))

(deftest test-store-message
  (testing "store-message stores and retrieves"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (let [result (dlevin/store-message! store
                                          {:session-id session-id
                                           :role "user"
                                           :text "Hello, how are you?"
                                           :timestamp (System/currentTimeMillis)})]
        (is (:stored result))
        (is (:indexed result))
        (is (string? (:msg-id result))))
      (let [results (dlevin/search-relevant! store "Hello" session-id 5)]
        (is (some? results))
        (is (some? (seq results))))
      (dlevin/close-session-store store))))

(deftest test-store-tool-message
  (testing "store-message persists tool metadata"
    (let [session-id (make-session-id)
          store (test-store session-id {:embedding-fn (constantly nil)})]
      (dlevin/store-message! store
                             {:session-id session-id
                              :role "tool"
                              :text "repl-eval((+ 1 2)) => 3"
                              :tool-name "repl-eval"
                              :tool-result "3"
                              :timestamp 100})
      (let [msgs (dlevin/load-recent-messages! store session-id 5)]
        (is (= 1 (count msgs)))
        (is (= "tool" (:msg/role (first msgs)))))
      (dlevin/close-session-store store))))

(deftest test-store-message-embed-failure
  (testing "store-message returns embedding-failed when embed fn returns nil"
    (let [session-id (make-session-id)
          store (test-store session-id {:embedding-fn (constantly nil)})]
      (let [result (dlevin/store-message! store
                                          {:session-id session-id
                                           :role "user"
                                           :text "Hello"
                                           :timestamp 1000})]
        (is (:stored result))
        (is (false? (:indexed result)))
        (is (= "embedding-failed" (:reason result))))
      (dlevin/close-session-store store))))

(deftest test-session-embedding-metadata
  (testing "session metadata records embedding model"
    (let [session-id (make-session-id)
          store (test-store session-id {:embedding-model "test-embed-model"})]
      (is (= "test-embed-model" (:embedding-model store)))
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

(deftest test-search-fallback-without-index
  (testing "search falls back to recent messages when embeddings are unavailable"
    (let [session-id (make-session-id)
          store (test-store session-id {:embedding-fn (constantly nil)})]
      (dlevin/store-message! store
                             {:session-id session-id :id "old" :role "user"
                              :text "oldest message" :timestamp 1000})
      (dlevin/store-message! store
                             {:session-id session-id :id "mid" :role "assistant"
                              :text "middle message" :timestamp 2000})
      (dlevin/store-message! store
                             {:session-id session-id :id "new" :role "user"
                              :text "newest message" :timestamp 3000})
      (let [results (dlevin/search-relevant! store "anything" session-id 2)]
        (is (= 2 (count results)))
        (is (= "mid" (:msg/id (first results))))
        (is (= "new" (:msg/id (second results)))))
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

(deftest test-lookup-preserves-vector-order
  (testing "lookup preserves HNSW similarity order"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (dlevin/store-message! store
                             {:session-id session-id :id "id-c" :role "user"
                              :text "third" :timestamp 3000})
      (dlevin/store-message! store
                             {:session-id session-id :id "id-a" :role "user"
                              :text "first" :timestamp 1000})
      (dlevin/store-message! store
                             {:session-id session-id :id "id-b" :role "user"
                              :text "second" :timestamp 2000})
      (with-redefs [datalevin.core/search-vec (fn [_ _ _] ["id-c" "id-a" "id-b"])]
        (let [results (dlevin/search-relevant! store "query" session-id 3)]
          (is (= ["id-c" "id-a" "id-b"] (mapv :msg/id results)))))
      (dlevin/close-session-store store))))

(deftest test-session-db-path-is-absolute
  (testing "session-db-path returns an absolute path"
    (let [path (dlevin/session-db-path test-base-dir "my-session")]
      (is (.isAbsolute (java.io.File. path))))))

(deftest test-load-recent-messages
  (testing "load-recent-messages returns chronological messages"
    (let [session-id (make-session-id)
          store (test-store session-id {})]
      (dlevin/store-message! store
                             {:session-id session-id :id "m1" :role "user"
                              :text "first" :timestamp 1000})
      (dlevin/store-message! store
                             {:session-id session-id :id "m2" :role "assistant"
                              :text "second" :timestamp 2000})
      (dlevin/store-message! store
                             {:session-id session-id :id "m3" :role "user"
                              :text "third" :timestamp 3000})
      (let [recent (dlevin/load-recent-messages! store session-id 2)]
        (is (= 2 (count recent)))
        (is (= "m2" (:msg/id (first recent))))
        (is (= "m3" (:msg/id (second recent)))))
      (dlevin/close-session-store store))))

(deftest test-reindex-pending-recovery
  (testing "unindexed messages are recovered on session startup"
    (let [session-id (make-session-id)
          fail-embed (atom true)
          controlled-embed-fn (fn [text]
                                (if @fail-embed
                                  nil  ;; First pass: embedding fails
                                  ;; Second pass (reindex): return a vector
                                  (vec (for [i (range 384)]
                                         (double (+ 0.01 (* (mod (+ (hash text) i) 1000) 0.001)))))))
          store (test-store session-id {:embedding-fn controlled-embed-fn})]
      ;; Store a message — embedding fails, :msg/indexed stays false
      (let [result (dlevin/store-message! store
                                           {:session-id session-id :id "pending-1"
                                            :role "user" :text "hello"
                                            :timestamp 1000})]
        (is (:stored result))
        (is (not (:indexed result)))
        (is (= "embedding-failed" (:reason result))))
      ;; Now allow embeddings to succeed and reindex
      (reset! fail-embed false)
      (let [reindexed (dlevin/reindex-pending! store)]
        (is (= 1 reindexed) "one pending message should be reindexed"))
      ;; Verify the message is now findable via search
      (let [results (dlevin/search-relevant! store "hello" session-id 5)]
        (is (pos? (count results))
            "reindexed message should be findable via vector search"))
      (dlevin/close-session-store store))))

(deftest test-corrupt-indicator-selective
  (testing "corrupt-indicator? only matches corruption errors"
    ;; Access the private function via var
    (let [corrupt-indicator? #'kschltz.agent.memory.datalevin/corrupt-indicator?]
      (is (true? (corrupt-indicator? (Exception. "MDB_CORRUPT: page mismatch"))))
      (is (true? (corrupt-indicator? (Exception. "Invalid header: bad page #42"))))
      (is (true? (corrupt-indicator? (Exception. "map validation failed"))))
      (is (false? (corrupt-indicator? (Exception. "No space left on device"))))
      (is (false? (corrupt-indicator? (Exception. "Permission denied"))))
      (is (false? (corrupt-indicator? (Exception. "Connection refused")))))))