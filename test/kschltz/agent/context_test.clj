(ns kschltz.agent.context-test
  "Unit tests for agent.context — truncation, sanitization, serialization,
   memory message conversion, history capping, and context composition."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.context :as sut]))

;; ---- Truncate text ----

(deftest truncate-text-basics
  (testing "truncates when over limit"
    (is (= "Hel…" (sut/truncate-text "Hello world" 4))))

  (testing "no-op when under limit"
    (is (= "Hello" (sut/truncate-text "Hello" 10))))

  (testing "no-op when exactly at limit"
    (is (= "Hello" (sut/truncate-text "Hello" 5))))

  (testing "nil max-chars disables truncation"
    (is (= "Hello world" (sut/truncate-text "Hello world" nil))))

  (testing "zero max-chars disables truncation"
    (is (= "Hello world" (sut/truncate-text "Hello world" 0))))

  (testing "non-string text is returned as-is"
    (is (= 12345 (sut/truncate-text 12345 4)))))

;; ---- Truncate tool calls ----

(deftest truncate-tool-calls-basics
  (testing "truncates long arguments to empty JSON object"
    (let [args (apply str (repeat 1000 "x"))
          tcs [{:id "call_1" :function {:name "repl-eval" :arguments args}}]
          result (sut/truncate-tool-calls tcs 100)]
      (is (= 1 (count result)))
      (is (= "call_1" (:id (first result))))
      (is (= "{}" (get-in (first result) [:function :arguments])))))

  (testing "preserves short arguments"
    (let [tcs [{:id "call_1" :function {:name "repl-eval" :arguments "{}"}}]
          result (sut/truncate-tool-calls tcs 100)]
      (is (= "{}" (get-in (first result) [:function :arguments])))))

  (testing "returns nil for nil tool calls"
    (is (nil? (sut/truncate-tool-calls nil 100))))

  (testing "returns nil for empty tool calls"
    (is (nil? (sut/truncate-tool-calls [] 100)))))

;; ---- Truncate chat message ----

(deftest truncate-chat-message-basics
  (testing "truncates message content"
    (let [long-content (apply str (repeat 200 "x"))
          msg {:role "user" :content long-content}
          result (sut/truncate-chat-message msg 50)]
      (is (< (count (:content result)) (count long-content)))))

  (testing "coerces numeric content to string before truncating"
    (let [msg {:role "assistant" :content 42}
          result (sut/truncate-chat-message msg 100)]
      (is (= "42" (:content result)))))

  (testing "leaves short content unchanged"
    (let [msg {:role "user" :content "hello"}
          result (sut/truncate-chat-message msg 100)]
      (is (= "hello" (:content result)))))

  (testing "truncates tool calls"
    (let [msg {:role "assistant"
               :content ""
               :tool_calls [{:id "c1" :function {:name "test" :arguments (apply str (repeat 200 "x"))}}]}
          result (sut/truncate-chat-message msg 50)]
      (is (= "{}" (get-in (first (:tool_calls result)) [:function :arguments]))))))

;; ---- Sanitize context messages ----

(deftest sanitize-basic-messages
  (testing "strips non-OpenAI keys from messages"
    (let [msgs [{:role "user" :content "hi" :msg-id "123" :timestamp 9999}]
          result (sut/sanitize-context-messages msgs)]
      (is (= 1 (count result)))
      (is (nil? (:msg-id (first result))))
      (is (nil? (:timestamp (first result))))
      (is (= "hi" (:content (first result))))))

  (testing "coerces numeric content to string"
    (let [msgs [{:role "user" :content 42}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "42" (:content (first result))))))

  (testing "coerces nil content to empty string"
    (let [msgs [{:role "user" :content nil}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "" (:content (first result))))))

  (testing "preserves reasoning_content"
    (let [msgs [{:role "assistant" :content "hi" :reasoning_content "thinking..."}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "thinking..." (:reasoning_content (first result))))))

  (testing "coerces numeric reasoning_content to string"
    (let [msgs [{:role "assistant" :content "ok" :reasoning_content 123}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "123" (:reasoning_content (first result)))))))

(deftest sanitize-assistant-tool-calls
  (testing "converts assistant tool_calls to text summary"
    (let [msgs [{:role "assistant"
                 :content "Let me search"
                 :tool_calls [{:id "1" :function {:name "web-search"}}]}]
          result (sut/sanitize-context-messages msgs)]
      (is (= 1 (count result)))
      (is (= "assistant" (:role (first result))))
      (is (str/includes? (:content (first result)) "[Used tools: web-search]"))
      (is (str/includes? (:content (first result)) "Let me search"))))

  (testing "assistant with tool_calls but no content"
    (let [msgs [{:role "assistant"
                 :tool_calls [{:id "1" :function {:name "web-search"}}]}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "[Used tools: web-search]" (:content (first result)))))))

(deftest sanitize-tool-result-messages
  (testing "converts tool result to plain text summary"
    (let [msgs [{:role "tool" :content "The capital is Paris" :tool_call_id "1"}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "user" (:role (first result))))
      (is (str/includes? (:content (first result)) "The capital is Paris"))
      (is (str/includes? (:content (first result)) "[Tool result:"))))

  (testing "coerces numeric tool content to string"
    (let [msgs [{:role "tool" :content 42 :tool_call_id "1"}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "user" (:role (first result))))
      (is (str/includes? (:content (first result)) "42"))))

  (testing "handles nil tool content"
    (let [msgs [{:role "tool" :content nil :tool_call_id "1"}]
          result (sut/sanitize-context-messages msgs)]
      (is (= "user" (:role (first result))))))

  (testing "truncates long tool results to 500 chars"
    (let [long-result (apply str (repeat 1000 "x"))
          msgs [{:role "tool" :content long-result :tool_call_id "1"}]
          result (sut/sanitize-context-messages msgs)]
      (is (< (count (:content (first result))) (count long-result))))))

;; ---- Serialization ----

(deftest serialize-tool-calls-test
  (testing "serializes tool calls to JSON"
    (let [tcs [{:id "c1" :function {:name "repl-eval" :arguments "{}"}}]
          result (sut/serialize-tool-calls tcs)]
      (is (string? result))
      (is (str/includes? result "repl-eval"))))

  (testing "returns nil for empty tool calls"
    (is (nil? (sut/serialize-tool-calls nil)))
    (is (nil? (sut/serialize-tool-calls [])))))

(deftest deserialize-tool-calls-test
  (testing "round-trips tool calls through JSON"
    (let [tcs [{:id "c1" :function {:name "repl-eval" :arguments "{:code (+ 1 1)}"}}]
          result (sut/deserialize-tool-calls (sut/serialize-tool-calls tcs))]
      (is (= 1 (count result)))
      (is (= "c1" (:id (first result))))
      (is (= "repl-eval" (get-in (first result) [:function :name])))))

  (testing "returns nil for nil/blank strings"
    (is (nil? (sut/deserialize-tool-calls nil)))
    (is (nil? (sut/deserialize-tool-calls "")))
    (is (nil? (sut/deserialize-tool-calls "   ")))))

;; ---- Memory message conversion ----

(deftest chat-msg-to-memory-msg-test
  (testing "converts basic user message"
    (let [msg {:role "user" :content "hello"}
          result (sut/chat-msg->memory-msg msg)]
      (is (= "user" (:role result)))
      (is (= "hello" (:text result)))))

  (testing "coerces numeric content to string"
    (let [msg {:role "assistant" :content 42}
          result (sut/chat-msg->memory-msg msg)]
      (is (= "42" (:text result)))))

  (testing "serializes tool_calls"
    (let [msg {:role "assistant" :content nil
               :tool_calls [{:id "c1" :function {:name "web-search" :arguments "{}"}}]}
          result (sut/chat-msg->memory-msg msg)]
      (is (some? (:tool-calls result)))
      (is (string? (:tool-calls result)))))

  (testing "preserves tool_call_id"
    (let [msg {:role "tool" :content "result" :tool_call_id "c1"}]
      (is (= "c1" (:tool-call-id (sut/chat-msg->memory-msg msg)))))))

(deftest memory-msg-to-chat-msg-test
  (testing "converts basic memory message"
    (let [msg {:msg/id "m1" :msg/role "user" :msg/text "hello" :msg/timestamp 1000}
          result (sut/memory-msg->chat-msg msg)]
      (is (= "user" (:role result)))
      (is (= "hello" (:content result)))
      (is (= "m1" (:msg-id result)))
      (is (= 1000 (:timestamp result)))))

  (testing "coerces numeric text to string"
    (let [msg {:msg/role "assistant" :msg/text 42}
          result (sut/memory-msg->chat-msg msg)]
      (is (= "42" (:content result)))))

  (testing "deserializes tool_calls"
    (let [tcs-json (sut/serialize-tool-calls
                     [{:id "c1" :function {:name "repl-eval" :arguments "{}"}}])
          msg {:msg/role "assistant" :msg/text "checking" :msg/tool-calls tcs-json}
          result (sut/memory-msg->chat-msg msg)]
      (is (some? (:tool_calls result)))
      (is (= 1 (count (:tool_calls result)))))))

;; ---- History → memory messages ----

(deftest history-to-memory-msgs-test
  (testing "converts history with indexed timestamps"
    (let [history [{:role "user" :content "hi" :msg-id "m1" :timestamp 1000}
                   {:role "assistant" :content "hello" :msg-id "m2" :timestamp 1001}]
          result (sut/history->memory-msgs history)]
      (is (= 2 (count result)))
      (is (= "user" (:msg/role (first result))))
      (is (= "hi" (:msg/text (first result))))
      (is (= "m1" (:msg/id (first result))))
      (is (= 1000 (:msg/timestamp (first result))))))

  (testing "adds timestamp index when missing"
    (let [history [{:role "user" :content "hi"}]
          result (sut/history->memory-msgs history)]
      (is (= 0 (:msg/timestamp (first result))))))

  (testing "preserves tool_call_id"
    (let [history [{:role "tool" :content "42" :tool_call_id "c1"}]
          result (sut/history->memory-msgs history)]
      (is (= "c1" (:msg/tool-call-id (first result)))))))

;; ---- Split facts and chat ----

(deftest split-facts-and-chat-test
  (testing "separates fact messages from chat messages"
    (let [msgs [{:msg/kind "fact" :msg/text "Paris is the capital of France"}
                {:msg/role "user" :msg/text "hello"}
                {:msg/kind "fact" :msg/text "Water boils at 100C"}]
          [facts chat] (sut/split-facts-and-chat msgs)]
      (is (= 2 (count facts)))
      (is (= 1 (count chat)))))

  (testing "returns empty facts when none present"
    (let [msgs [{:msg/role "user" :msg/text "hi"}]
          [facts chat] (sut/split-facts-and-chat msgs)]
      (is (zero? (count facts)))
      (is (= 1 (count chat))))))

;; ---- Cap history ----

(deftest cap-history-basics
  (testing "trims when over limit"
    (let [state {:history [1 2 3 4 5] :history-limit 3}
          result (sut/cap-history state)]
      (is (= [3 4 5] (:history result)))))

  (testing "no-op when under limit"
    (let [state {:history [1 2] :history-limit 5}]
      (is (= [1 2] (:history (sut/cap-history state))))))

  (testing "no-op when exactly at limit"
    (let [state {:history [1 2 3] :history-limit 3}]
      (is (= [1 2 3] (:history (sut/cap-history state))))))

  (testing "no limit returns state unchanged"
    (let [state {:history [1 2 3 4 5]}]
      (is (= state (sut/cap-history state)))))

  (testing "preserves other state keys"
    (let [state {:history [1 2 3] :history-limit 2 :other "value"}
          result (sut/cap-history state)]
      (is (= "value" (:other result))))))