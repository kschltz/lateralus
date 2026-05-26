(ns kschltz.agent.memory-e2e-test
  "Phase 5 memory integration tests: prompt shape, hybrid compose, stub embeddings.

   Uses mocked LLM and deterministic stub embeddings — no live Ollama required."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [kschltz.agent.core :as core]
            [kschltz.agent.http :as http]
            [kschltz.agent.memory :as memory]))

(def ^:private test-sessions-dir "test-sessions-memory-e2e")

(defn- delete-tree [dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file)
        (delete-tree file)
        (.delete file)))
    (.delete dir)))

(defn- cleanup-fixture [test-fn]
  (delete-tree (io/file test-sessions-dir))
  (test-fn)
  (delete-tree (io/file test-sessions-dir)))

(use-fixtures :each cleanup-fixture)

(defn- stub-embed
  "Deterministic embedding vectors for CI (matches datalevin_test pattern)."
  [text]
  (let [h (hash text)]
    (vec (for [i (range 384)]
           (double (+ 0.01 (* (mod (+ h i) 1000) 0.001)))))))

(defn- mock-assistant-content [response]
  (get-in response [:choices 0 :message :content]))

(defn- fresh-memory-agent [opts]
  (core/make-agent (merge {:base-url     "http://mock-llm"
                           :model        "mock-model"
                           :sessions-dir test-sessions-dir}
                          opts)))

(deftest e2e-memory-prompt-shape-hybrid-dedup
  (testing "LLM :messages contain deduped hybrid context before the current turn"
    (let [captured-msgs (atom nil)
          sid           (str "prompt-shape-" (System/nanoTime))
          ag            (fresh-memory-agent {:session-id sid
                                             :memory-relevant-limit 5
                                             :memory-recent-limit 5})]
      (send ag assoc :history
            [{:role "user" :content "recent user" :msg-id "b" :timestamp 60}
             {:role "assistant" :content "recent reply" :msg-id "c" :timestamp 70}])
      (with-redefs [memory/retrieve-relevant
                    (fn [_]
                      [{:msg/id "a" :msg/role "user" :msg/text "rel only" :msg/timestamp 30}
                       {:msg/id "b" :msg/role "user" :msg/text "dup user" :msg/timestamp 10}])
                    http/completion
                    (fn [_ _ _ _ & {:keys [messages]}]
                      (reset! captured-msgs (vec messages))
                      {:choices [{:message {:content "ok"}}]})
                    http/assistant-content mock-assistant-content]
        (core/chat! ag "current question"))
      (let [msgs     @captured-msgs
            contents (mapv :content msgs)]
        (is (= 4 (count msgs))
            "hybrid context (3) + current user turn (1)")
        (is (= ["rel only" "recent user" "recent reply" "current question"]
               contents)
            "deduped, chronologically ordered context before current user message")
        (is (= "user" (:role (last msgs)))))
      (core/close-session! ag))))

(deftest e2e-memory-semantic-context-in-prompt
  (testing "persisted embeddings surface semantically relevant messages in LLM prompt"
    (let [sid (str "semantic-" (System/nanoTime))
          ag  (fresh-memory-agent {:session-id sid})]
      (with-redefs [http/embed stub-embed
                    http/completion
                    (fn [_ _ _ _ & _]
                      {:choices [{:message {:content "Stored."}}]})
                    http/assistant-content mock-assistant-content]
        (core/chat! ag "The purple elephant lives in room 42."))
      (core/reset! ag)
      (let [captured-msgs (atom nil)]
        (with-redefs [http/embed stub-embed
                      http/completion
                      (fn [_ _ _ _ & {:keys [messages]}]
                        (reset! captured-msgs (vec messages))
                        {:choices [{:message {:content "Room 42."}}]})
                      http/assistant-content mock-assistant-content]
          (core/chat! ag "Which room is the elephant in?"))
        (let [contents (mapv :content @captured-msgs)]
          (is (pos? (count contents)))
          (is (some #(.contains ^String % "purple elephant") contents)
              "retrieved memory should appear in API messages after reset clears in-agent history")))
      (core/close-session! ag))))

(deftest e2e-memory-session-resume-in-prompt
  (testing "reopened session hydrates history into subsequent LLM context"
    (let [sid (str "resume-prompt-" (System/nanoTime))
          ag1 (fresh-memory-agent {:session-id sid :history-limit 10})]
      (with-redefs [http/embed (constantly nil)
                    http/completion
                    (fn [_ _ _ _ & _]
                      {:choices [{:message {:content "Hi."}}]})
                    http/assistant-content mock-assistant-content]
        (core/chat! ag1 "remember the codeword is banana"))
      (core/close-session! ag1)
      (let [captured-msgs (atom nil)
            ag2           (fresh-memory-agent {:session-id sid :history-limit 10})]
        (with-redefs [http/completion
                      (fn [_ _ _ _ & {:keys [messages]}]
                        (reset! captured-msgs (vec messages))
                        {:choices [{:message {:content "ok"}}]})
                      http/assistant-content mock-assistant-content]
          (core/chat! ag2 "what is the codeword?"))
        (let [contents (mapv :content @captured-msgs)]
          (is (some #(.contains ^String % "banana") contents)
              "resumed session history should be in LLM prompt"))
        (core/close-session! ag2)))))

(deftest e2e-memory-disabled-without-session-id
  (testing "no session-id means compose-context passes only in-agent history"
    (let [captured-msgs (atom nil)
          ag            (fresh-memory-agent {})]
      (send ag assoc :history [{:role "user" :content "local only"}])
      (with-redefs [memory/retrieve-relevant
                    (fn [_]
                      (throw (ex-info "memory should not be queried" {})))
                    http/completion
                    (fn [_ _ _ _ & {:keys [messages]}]
                      (reset! captured-msgs (vec messages))
                      {:choices [{:message {:content "ok"}}]})
                    http/assistant-content mock-assistant-content]
        (core/chat! ag "follow up"))
      (is (= 2 (count @captured-msgs)))
      (is (= "local only" (:content (first @captured-msgs))))
      (is (= "follow up" (:content (last @captured-msgs)))))))
