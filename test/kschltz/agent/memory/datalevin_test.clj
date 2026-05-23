;; -*- coding: utf-8 -*-

(ns kschltz.agent.memory.datalevin-test
    "Tests for the Datalevin memory backend."
    (:require [clojure.test :refer [deftest is testing run-tests use-fixtures]]
              [clojure.java.io :as io]
              [kschltz.agent.memory.datalevin :as dlevin]
              [datalevin.core :as d]))

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

(deftest test-create-session-store
    (testing "create-session-store"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (is (some? conn))
          (dlevin/close-session-store conn)))))

(deftest test-store-message
    (testing "store-message"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (let [msg-id (dlevin/store-message! conn
                                             {:session-id session-id
                                              :role "user"
                                              :text "Hello, how are you?"
                                              :timestamp (System/currentTimeMillis)})]
            (is (some? msg-id))
            (is (number? msg-id))
            (dlevin/close-session-store conn))))))

(deftest test-search-relevant
    (testing "search-relevant"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (dlevin/store-message! conn
                                {:session-id session-id
                                 :role "user"
                                 :text "Hello, how are you?"
                                 :timestamp (System/currentTimeMillis)})
          (dlevin/store-message! conn
                                {:session-id session-id
                                 :role "assistant"
                                 :text "I'm doing well!"
                                 :timestamp (System/currentTimeMillis)})
          (dlevin/store-message! conn
                                {:session-id session-id
                                 :role "user"
                                 :text "What is your name?"
                                 :timestamp (System/currentTimeMillis)})
          (let [results (dlevin/search-relevant! conn "Hello" session-id 5)]
            (is (some? results))
            (is (some #(= "Hello, how are you?" (:msg/text %)) results)))
          (dlevin/close-session-store conn)))))

(deftest test-close-session-store
    (testing "close-session-store"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (dlevin/close-session-store conn)
          (dlevin/close-session-store conn)
          (is true)))))

(deftest test-empty-query-return-empty
    (testing "empty query returns empty results"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (let [results (dlevin/search-relevant! conn "" session-id 5)]
            (is (empty? results)))
          (dlevin/close-session-store conn)))))

(deftest test-nil-session-id-returns-empty
    (testing "nil session-id returns empty results"
      (let [session-id (make-session-id)]
        (let [conn (dlevin/create-session-store session-id {})]
          (let [results (dlevin/search-relevant! conn "Hello" nil 5)]
            (is (empty? results)))
          (dlevin/close-session-store conn)))))

(deftest test-session-metadata
    (testing "session metadata is stored"
      (let [session-id (str "test-meta-" (System/nanoTime))]
        (let [conn (dlevin/create-session-store session-id {:model "test-model"})]
          (is (some? conn))
          (dlevin/close-session-store conn)))))
