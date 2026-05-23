(ns kschltz.agent.llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core :refer [with-redefs]]
            [kschltz.agent.llm :as sut]
            [kschltz.agent.http :as http]))

(defn- mock-response
  "Minimal hato response map."
  [& {:keys [status body]
      :or   {status 200
             body   {:choices [{:message {:content "Hi there!"}}]}}}]
  {:status status :body body})

;; ---- Dispatch Function Tests ----

(deftest provider-dispatch-extracts-provider
  (testing "provider-dispatch returns the :provider key from opts"
    (is (= :openai-compatible (sut/provider-dispatch {:provider :openai-compatible})))
    (is (= :ollama            (sut/provider-dispatch {:provider :ollama})))
    (is (nil? (sut/provider-dispatch {})))))

;; ---- Default Provider Throws ----

(deftest call-default-throws
  (testing "call throws on unknown provider"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/call {:provider :unknown})))))

;; ---- :openai-compatible Provider Tests ----

(deftest call-openai-compatible-missing-base-url
  (testing "call :openai-compatible throws when :base-url is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/call {:provider :openai-compatible :model "test" :message "hi"})))))

(deftest call-openai-compatible-missing-model
  (testing "call :openai-compatible throws when :model is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/call {:provider :openai-compatible :base-url "http://localhost" :message "hi"})))))

(deftest call-openai-compatible-missing-message
  (testing "call :openai-compatible throws when :message is missing"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/call {:provider :openai-compatible :base-url "http://localhost" :model "test"})))))

(deftest call-openai-compatible-with-chat-history
  (testing "call :openai-compatible passes chat-history to http/completion"
    (with-redefs [http/completion
                  (fn [url api-key model message & {:keys [chat-history]}]
                    (is (= "http://localhost:8080" url))
                    (is (= "my-key" api-key))
                    (is (= "my-model" model))
                    (is (= "current msg" message))
                    (is (= [{:role "user" :content "hi"}] chat-history))
                    (mock-response))]
      (let [opts {:provider      :openai-compatible
                  :base-url      "http://localhost:8080"
                  :api-key       "my-key"
                  :model         "my-model"
                  :message       "current msg"
                  :chat-history  [{:role "user" :content "hi"}]}]
        (is (= {:choices [{:message {:content "Hi there!"}}]}
               (sut/call opts)))))))

(deftest call-openai-compatible-without-chat-history
  (testing "call :openai-compatible defaults chat-history to empty vector"
    (with-redefs [http/completion
                  (fn [_ _ _ _ & {:keys [chat-history]}]
                    (is (= [] chat-history))
                    (mock-response))]
      (sut/call {:provider     :openai-compatible
                 :base-url     "http://localhost"
                 :model        "model"
                 :message      "msg"}))))

(deftest call-openai-compatible-api-key-optional
  (testing "call :openai-compatible works without api-key"
    (with-redefs [http/completion
                  (fn [url api-key model message & _]
                    (is (nil? api-key))
                    (mock-response))]
      (is (= {:choices [{:message {:content "Hi there!"}}]}
             (sut/call {:provider :openai-compatible
                        :base-url "http://localhost"
                        :model    "model"
                        :message  "msg"}))))))