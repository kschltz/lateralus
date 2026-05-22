(ns kschltz.agent.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.http :as sut]
            [clojure.core :refer [with-redefs]]))

;; ---- HTTP Client Mocking ----
;; We use clojure.core/with-redefs to stub hato's HTTP functions so tests run
;; without a live server.  Each test supplies its own response fixture.

(defn- mock-response
  "Build a minimal hato response map."
  [& {:keys [status body]
      :or   {status 200
             body   nil}}]
  {:status status
   :body   body})

;; ---- Auth Headers ----

(deftest auth-headers-with-key
  (testing "auth-headers generates Bearer token"
    (is (= {"Authorization" "Bearer test-key"}
           (sut/auth-headers "test-key")))))

(deftest auth-headers-without-key
  (testing "auth-headers returns nil when no key"
    (is (nil? (sut/auth-headers nil)))
    (is (nil? (sut/auth-headers "")))))

;; ---- Get Models ----

(deftest get-models-with-auth
  (testing "get-models sends auth header when api-key provided"
    (with-redefs [hato.client/get
                  (fn [url opts]
                    (is (= "http://127.0.0.1:8080/v1/models" url))
                    (is (contains? opts :as))
                    (is (contains? (:headers opts) "Authorization"))
                    (mock-response :body {:data [{:id "model-1"}]}))]
      (is (= [{:id "model-1"}]
             (sut/get-models "http://127.0.0.1:8080" "my-key"))))))

(deftest get-models-without-auth
  (testing "get-models omits auth header when no api-key"
    (with-redefs [hato.client/get
                  (fn [url opts]
                    (is (= "http://127.0.0.1:8080/v1/models" url))
                    (is (not (contains? opts :headers)))
                    (mock-response :body {:data [{:id "model-2" :name "test"}]}))]
      (is (= [{:id "model-2" :name "test"}]
             (sut/get-models "http://127.0.0.1:8080" nil))))))

(deftest get-models-returns-empty-list
  (testing "get-models returns empty list when no models"
    (with-redefs [hato.client/get
                  (fn [_ _]
                    (mock-response :body {:data []}))]
      (is (= [] (sut/get-models "http://127.0.0.1:8080" nil))))))

;; ---- Get Model Info ----

(deftest get-model-info-with-auth
  (testing "get-model-info sends auth header when api-key provided"
    (with-redefs [hato.client/get
                  (fn [url opts]
                    (is (.contains (str url) "/v1/models/model-1"))
                    (is (contains? (:headers opts) "Authorization"))
                    (mock-response :body {:id "model-1" :name "test"}))]
      (is (= {:id "model-1" :name "test"}
             (sut/get-model-info "http://127.0.0.1:8080" "my-key" "model-1"))))))

(deftest get-model-info-without-auth
  (testing "get-model-info omits auth header when no api-key"
    (with-redefs [hato.client/get
                  (fn [url opts]
                    (is (.contains (str url) "/v1/models/qwen"))
                    (is (not (contains? opts :headers)))
                    (mock-response :body {:id "qwen"}))]
      (is (= {:id "qwen"}
             (sut/get-model-info "http://127.0.0.1:8080" nil "qwen"))))))

(deftest get-model-info-url-construction
  (testing "get-model-info constructs correct URL with model-id"
    (with-redefs [hato.client/get
                  (fn [url opts]
                    (is (= "http://127.0.0.1:8080/v1/models/my-model" url))
                    (mock-response :body {:status "ok"}))]
      (sut/get-model-info "http://127.0.0.1:8080" nil "my-model"))))

;; ---- Completion ----

(deftest completion-basic
  (testing "completion sends POST with chat message"
    (with-redefs [hato.client/post
                  (fn [url opts]
                    (is (= "http://127.0.0.1:8080/v1/chat/completions" url))
                    (is (= :json (:as opts)))
                    (let [body (:form-params opts)]
                      (is (= "test-model" (:model body)))
                      (is (= "hello" (-> body :messages last :content)))
                      (is (= 1 (count (:messages body))))
                      (is (= "user" (-> body :messages last :role))))
                    (mock-response :body {:choices [{:message {:content "Hi there!"}}]}))]
      (is (= {:choices [{:message {:content "Hi there!"}}]}
             (sut/completion "http://127.0.0.1:8080" nil "test-model" "hello"))))))

(deftest completion-with-chat-history
  (testing "completion appends to chat history"
    (with-redefs [hato.client/post
                  (fn [url opts]
                    (let [body (:form-params opts)
                          msgs (:messages body)]
                      (is (= 3 (count msgs)))
                      (is (= "system" (-> msgs first :role)))
                      (is (= "user" (-> msgs last :role)))
                      (is (= "follow up" (-> msgs last :content))))
                    (mock-response :body {:choices [{:message {:content "Response"}}]}))]
      (let [history [{:role "system" :content "You are helpful"}
                     {:role "user" :content "hello"}]]
        (is (= {:choices [{:message {:content "Response"}}]}
               (sut/completion "http://127.0.0.1:8080" nil "model" "follow up"
                               :chat-history history)))))))

(deftest completion-with-auth
  (testing "completion sends auth header when api-key provided"
    (with-redefs [hato.client/post
                  (fn [url opts]
                    (is (contains? (:headers opts) "Authorization"))
                    (is (= "Bearer auth-token" (get-in opts [:headers "Authorization"])))
                    (mock-response :body {:choices [{:message {:content "ok"}}]}))]
      (is (= {:choices [{:message {:content "ok"}}]}
             (sut/completion "http://127.0.0.1:8080" "auth-token" "model" "msg"))))))

(deftest completion-without-auth
  (testing "completion omits auth header when no api-key"
    (with-redefs [hato.client/post
                  (fn [url opts]
                    (is (not (contains? opts :headers)))
                    (mock-response :body {:choices [{:message {:content "public"}}]}))]
      (is (= {:choices [{:message {:content "public"}}]}
             (sut/completion "http://127.0.0.1:8080" nil "model" "msg"))))))

;; ---- Assistant Content Extraction ----

(deftest assistant-content-extracts-text
  (testing "assistant-content extracts content from response"
    (is (= "Hello!"
           (sut/assistant-content {:choices [{:message {:content "Hello!"}}]})))))

(deftest assistant-content-with-empty-choices
  (testing "assistant-content handles empty choices"
    (is (nil? (sut/assistant-content {:choices []})))
    (is (nil? (sut/assistant-content {})))))

(deftest assistant-content-with-multiline
  (testing "assistant-content preserves multiline content"
    (let [content "Line 1\nLine 2\nLine 3"]
      (is (= content
             (sut/assistant-content {:choices [{:message {:content content}}]}))))))

;; ---- Step Function ----

(def default-step-args
  "Minimal args for step testing."
  {:base-url     "http://127.0.0.1:8080"
   :api-key      nil
   :model        "test-model"
   :message      "hello"
   :chat-history [{:role "user"
                   :content "You are a helpful assistant running inside a clojure process, with access to runtime via REPL, you absolutely must only return  valid clojure edn"}]
   :turn         0})

(deftest step-returns-response-and-updated-history
  (testing "step returns response with updated chat history"
    (with-redefs [hato.client/post
                  (fn [_ _]
                    (mock-response :body {:choices [{:message {:content "Hi back!"}}]}))]
      (let [result (sut/step default-step-args)]
        (is (= "Hi back!" (get-in result [:response :choices 0 :message :content])))
        (is (= 2 (count (:chat-history result))))
        (is (= "user" (-> result :chat-history first :role)))
        (is (= "assistant" (-> result :chat-history second :role)))
        (is (= "Hi back!" (-> result :chat-history second :content)))))))

(deftest step-increments-turn
  (testing "step increments turn counter"
    (with-redefs [hato.client/post
                  (fn [_ _]
                    (mock-response :body {:choices [{:message {:content "ok"}}]}))]
      (is (= 1 (-> (sut/step default-step-args) :turn))))))

(deftest step-two-arity-overloads
  (testing "step two-arity accepts map args"
    (with-redefs [hato.client/post
                  (fn [_ _]
                    (mock-response :body {:choices [{:message {:content "ok"}}]}))]
      (let [result (sut/step default-step-args)]
        (is (contains? result :response))
        (is (contains? result :chat-history))
        (is (contains? result :turn))))))

(deftest step-prefills-system-message
  (testing "step prefills a user message in chat history"
    (with-redefs [hato.client/post
                  (fn [_ _]
                    (mock-response :body {:choices [{:message {:content "ok"}}]}))]
      (let [result (sut/step default-step-args)]
        (is (= "user" (-> result :chat-history first :role)))
        (is (string? (-> result :chat-history first :content)))))))

(deftest step-with-provided-history
  (testing "step uses provided chat-history"
    (with-redefs [hato.client/post
                  (fn [_ opts]
                    (let [body (:form-params opts)
                          msgs (:messages body)]
                      (is (= 2 (count msgs)))
                      (is (= "custom" (-> msgs first :content))))
                    (mock-response :body {:choices [{:message {:content "ok"}}]}))]
      (sut/step (assoc default-step-args
                       :chat-history [{:role "user" :content "custom"}])))))
