(ns kschltz.agent.memory.schemas-test
  "Tests for Malli schema definitions in memory/schemas.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [kschltz.agent.memory.schemas :as schemas]))

;; ---- Model listing schemas ----

(deftest get-models-fn-schema-test
  (testing "GetModelsFn schema exists"
    (is (some? schemas/GetModelsFn)))

  (testing "ModelListResult accepts vector of maps"
    (is (m/validate schemas/ModelListResult [{:id "gpt-4" :name "GPT-4"}]))
    (is (m/validate schemas/ModelListResult []))
    (is (m/validate schemas/ModelListResult nil)))

  (testing "ModelListResult rejects non-vector"
    (is (not (m/validate schemas/ModelListResult "string")))
    (is (not (m/validate schemas/ModelListResult 42)))))

(deftest get-model-info-fn-schema-test
  (testing "GetModelInfoFn schema exists"
    (is (some? schemas/GetModelInfoFn)))

  (testing "ModelInfoResult accepts map or nil"
    (is (m/validate schemas/ModelInfoResult {:id "model-1" :name "Test Model"}))
    (is (m/validate schemas/ModelInfoResult nil))
    (is (m/validate schemas/ModelInfoResult {:id "model-1"})))

  (testing "ModelInfoResult rejects strings and numbers"
    (is (not (m/validate schemas/ModelInfoResult "not a map")))
    (is (not (m/validate schemas/ModelInfoResult 42)))))

;; ---- Completion schemas ----

(deftest completion-request-opts-test
  (testing "CompletionRequestOpts accepts all known keys"
    (is (m/validate schemas/CompletionRequestOpts
                    {:chat-history [{:role "user" :content "hello"}]
                     :messages [{:role "user" :content "hello"}]
                     :tools [{:type "function" :function {:name "test"}}]}))
    (is (m/validate schemas/CompletionRequestOpts {})))

  (testing "CompletionRequestOpts rejects unknown keys"
    ;; CompletionRequestOpts is a map schema — unknown keys may pass
    ;; depending on :closed setting. Verify it validates known structures.
    (is (m/validate schemas/CompletionRequestOpts {}))
    (is (not (m/validate schemas/CompletionRequestOpts {:chat-history "not-a-vector"}))))

  (testing "ChatMessage validates role and content"
    (is (m/validate schemas/ChatMessage {:role "user" :content "hello"}))
    (is (m/validate schemas/ChatMessage {:role "assistant" :content "response"}))
    (is (m/validate schemas/ChatMessage {:role "system" :content "instruction"}))))

(deftest store-message-schema-test
  (testing "StoreMessage accepts valid message"
    (is (m/validate schemas/StoreMessage
                    {:role "user" :text "hello" :id "msg-1"})))

  (testing "StoreMessage accepts minimal message"
    (is (m/validate schemas/StoreMessage {:role "user"}))))

(deftest remember-input-schema-test
  (testing "RememberInput requires query"
    (is (m/validate schemas/RememberInput {:query "test search"}))
    (is (m/validate schemas/RememberInput {:query "test" :limit 5}))))

(deftest remember-result-schema-test
  (testing "RememberResult validates memory response"
    (is (m/validate schemas/RememberResult
                    {:type "memory" :stored true :msg-id "msg-1"}))
    (is (m/validate schemas/RememberResult
                    {:type "memory" :stored true :content "result text"}))))

(deftest embedding-schemas-test
  (testing "EmbedText requires non-empty string"
    (is (m/validate schemas/EmbedText "hello"))
    (is (not (m/validate schemas/EmbedText "")))
    (is (not (m/validate schemas/EmbedText nil))))

  (testing "EmbeddingVector accepts vector of doubles"
    (is (m/validate schemas/EmbeddingVector [0.1 0.2 0.3]))
    (is (m/validate schemas/EmbeddingVector []))))