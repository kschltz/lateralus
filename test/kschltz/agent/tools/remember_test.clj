(ns kschltz.agent.tools.remember-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]
            [kschltz.agent.memory.datalevin :as dlevin]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.remember :as remember]))

(def ^:private test-base-dir "test-sessions-remember")

(defn- make-session-id []
  (str "remember-" (System/nanoTime)))

(defn- delete-tree [dir]
  (when (.exists dir)
    (doseq [file (.listFiles dir)]
      (if (.isDirectory file) (delete-tree file) (.delete file)))
    (.delete dir)))

(defn- cleanup-dirs []
  (doseq [dir-path [test-base-dir "sessions"]]
    (let [dir (io/file dir-path)]
      (when (.exists dir) (delete-tree dir)))))

(use-fixtures :each (fn [f] (cleanup-dirs) (f) (cleanup-dirs)))

(defn- test-embedding-fn [text]
  (let [h (hash text)]
    (vec (for [i (range 384)] (double (+ 0.01 (* (mod (+ h i) 1000) 0.001)))))))

(defn- test-store [session-id]
  (dlevin/create-session-store session-id
                               {:embedding-fn test-embedding-fn
                                :sessions-dir test-base-dir}))

(defn- store-fact! [store session-id fact]
  (memory/store-message
    {:backend :datalevin
     :connection store
     :session-id session-id
     :message (cond-> {:role "assistant"
                       :text (:content fact)
                       :kind "fact"}
              (:topic fact) (assoc :topic (:topic fact))
              (seq (:tags fact)) (assoc :tags (:tags fact)))}))

(deftest remember-stores-fact-with-kind
  (testing "remember tool stores fact with :msg/kind fact"
    (let [session-id (make-session-id)
          store (test-store session-id)
          tool (remember/remember-tool {:store-fact! #(store-fact! store session-id %)})
          raw (tools/run tool {:content "User prefers dark mode"})
          result (tools/parse tool raw)]
      (is (:stored result))
      (let [recent (dlevin/load-recent-messages! store session-id 5)]
        (is (= 1 (count recent)))
        (is (= "fact" (:msg/kind (first recent))))
        (is (= "User prefers dark mode" (:msg/text (first recent)))))
      (dlevin/close-session-store store))))

(deftest remember-returns-full-content
  (testing "remember tool returns full content without truncation"
    (let [long-content (apply str (repeat 600 "x"))
          tool (remember/remember-tool
                 {:store-fact! (fn [fact] {:msg-id "m1" :stored true :indexed true})})
          result (tools/parse tool (tools/run tool {:content long-content}))]
      (is (= long-content (:content result)))
      (is (not (str/ends-with? (:content result) "…"))))))

(deftest remember-persists-topic-and-tags
  (testing "optional topic and tags are persisted"
    (let [session-id (make-session-id)
          store (test-store session-id)
          tool (remember/remember-tool {:store-fact! #(store-fact! store session-id %)})
          _ (tools/run tool {:content "Codeword is banana"
                             :topic "secrets"
                             :tags ["game" "codeword"]})
          msg (first (dlevin/load-recent-messages! store session-id 5))]
      (is (= "secrets" (:msg/topic msg)))
      (is (= "[\"game\",\"codeword\"]" (:msg/tags msg)))
      (dlevin/close-session-store store))))

(deftest remember-without-store-fn
  (testing "graceful error when memory is disabled"
    (let [tool (remember/remember-tool {})
          result (tools/parse tool (tools/run tool {:content "fact"}))]
      (is (false? (:stored result)))
      (is (= "memory disabled" (:error result))))))

(deftest remember-rejects-blank-content
  (testing "blank content returns validation error"
    (let [tool (remember/remember-tool {:store-fact! (fn [_] {:msg-id "m"})})
          result (tools/parse tool (tools/run tool {:content ""}))]
      (is (false? (:stored result)))
      (is (str/includes? (:error result) "Invalid remember args")))))

(deftest compose-context-includes-memory-block
  (testing "facts appear in [memory] system block, not as chat messages"
    (let [session-id (make-session-id)
          store (test-store session-id)
          _ (dlevin/store-message! store {:session-id session-id
                                          :role "assistant"
                                          :text "Favorite color is teal"
                                          :kind "fact"
                                          :topic "preferences"
                                          :timestamp 1000})
          state {:session-id session-id
                 :memory-store store
                 :memory-backend :datalevin
                 :history [{:role "user" :content "hi"}]
                 :memory-max-chars 20
                 :memory-relevant-limit 5
                 :memory-recent-limit 10
                 :memory-strategy :hybrid}
          ctx (core/compose-context state "color")]
      (is (= "system" (:role (first ctx))))
      (is (str/includes? (:content (first ctx)) "[memory]"))
      (is (str/includes? (:content (first ctx)) "Favorite color is teal"))
      (is (str/includes? (:content (first ctx)) "preferences:"))
      (is (not (some #(and (= "assistant" (:role %))
                           (str/includes? (:content %) "Favorite color is teal"))
                    (rest ctx))))
      (dlevin/close-session-store store))))

(deftest make-agent-registers-remember-tool
  (testing "make-agent registers remember with default session"
    (let [ag (core/make-agent {:base-url "http://mock"
                               :model "mock"
                               :sessions-dir test-base-dir})]
      (is (= "default" (core/get-session-id ag)))
      (is (some #(= "remember" (:name %)) (core/get-tools ag)))
      (core/close-session! ag))))

(deftest make-agent-nil-session-has-no-remember
  (testing "explicit nil session-id disables remember tool wiring"
    (let [ag (core/make-agent {:base-url "http://mock"
                               :model "mock"
                               :session-id nil})]
      (is (nil? (core/get-memory-store ag)))
      (is (not (some #(= "remember" (:name %)) (core/get-tools ag)))))))
