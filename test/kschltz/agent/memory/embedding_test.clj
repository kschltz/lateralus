(ns kschltz.agent.memory.embedding-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.http :as http]
            [kschltz.agent.memory.embedding :as embedding]
            [kschltz.agent.memory.schemas :as schemas]
            [malli.core :as m]))

(deftest create-provider-defaults
  (testing "default provider is LangChain4j in-process"
    (let [provider (embedding/create-provider {})]
      (is (= :langchain4j (embedding/provider-method provider)))
      (is (= "all-minilm-l6-v2-q" (embedding/provider-model provider)))
      (is (= 384 (embedding/provider-dims provider)))
      (is (= {:session/emb-method "langchain4j-in-process"
              :session/emb-model  "all-minilm-l6-v2-q"}
             (embedding/provider-metadata provider)))))

  (testing "http provider metadata"
    (let [provider (embedding/create-provider
                    {:method :http
                     :base-url "http://127.0.0.1:11434"
                     :model "nomic-embed-text"})]
      (is (= :http (embedding/provider-method provider)))
      (is (= "nomic-embed-text" (embedding/provider-model provider)))
      (is (= {:session/emb-method "openai-compatible-http"
              :session/emb-model  "nomic-embed-text"}
             (embedding/provider-metadata provider))))))

(deftest langchain4j-embed-text
  (testing "LangChain4j provider returns a 384-dim vector"
    (let [provider (embedding/create-provider {:method :langchain4j})
          vector   (embedding/embed-text provider "hello world")]
      (is (vector? vector))
      (is (= 384 (count vector)))
      (is (every? number? vector))
      (is (m/validate schemas/EmbeddingVector vector)))))

(deftest http-provider-delegates-to-http-embed
  (testing "HTTP provider uses http/embed with Malli validation"
    (with-redefs [http/embed (fn [base-url api-key model text]
                               (is (= "http://mock" base-url))
                               (is (= "key" api-key))
                               (is (= "test-model" model))
                               (is (= "input" text))
                               (vec (repeat 384 0.5)))]
      (let [provider (embedding/create-provider
                      {:method :http
                       :base-url "http://mock"
                       :api-key "key"
                       :model "test-model"})
            vector   (embedding/embed-text provider "input")]
        (is (= 384 (count vector)))))))

(deftest embed-text-rejects-blank-input
  (testing "blank text returns nil without calling provider"
    (let [called? (atom false)
          provider (reify embedding/EmbeddingProvider
                     (provider-method [_] :langchain4j)
                     (provider-model [_] "test")
                     (provider-dims [_] 384)
                     (embed-text* [_ _] (reset! called? true) [1.0]))]
      (is (nil? (embedding/embed-text provider "")))
      (is (false? @called?)))))
