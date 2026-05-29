(ns kschltz.agent.tools.remember-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.context :as ctx]
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

(defn- search-fn [store session-id]
  (fn [{:keys [query limit]}]
    (memory/retrieve-relevant
      {:backend     :datalevin
       :connection  store
       :session-id  session-id
       :query       query
       :limit       (or limit 5)})))

;; ---- Retrieval tests ----

(deftest remember-retrieves-matching-facts
  (testing "remember tool retrieves facts matching the query"
    (let [session-id (make-session-id)
          store      (test-store session-id)
          ;; Store a fact first
          _         (memory/store-message
                      {:backend    :datalevin
                       :connection store
                       :session-id session-id
                       :message    {:role "assistant"
                                   :text "User prefers dark mode"
                                   :kind "fact"
                                   :topic "preferences"}})
          tool       (remember/remember-tool {:search-fn (search-fn store session-id)})
          result     (tools/parse tool (tools/run tool {:query "dark mode"}))]
      (is (:stored result))
      (is (str/includes? (:content result) "dark mode"))
      (dlevin/close-session-store store))))

(deftest remember-reports-no-matches
  (testing "remember tool reports when no memories match"
    (let [session-id (make-session-id)
          store      (test-store session-id)
          tool       (remember/remember-tool {:search-fn (search-fn store session-id)})
          result     (tools/parse tool (tools/run tool {:query "nonexistent thing"}) )]
      (is (false? (:stored result)))
      (is (str/includes? (:content result) "No matching memories"))
      (dlevin/close-session-store store))))

(deftest remember-without-search-fn
  (testing "graceful error when memory is disabled"
    (let [tool   (remember/remember-tool {})
          result (tools/parse tool (tools/run tool {:query "anything"}))]
      (is (false? (:stored result)))
      (is (= "memory disabled" (:error result))))))

(deftest remember-rejects-blank-query
  (testing "blank query returns validation error"
    (let [tool   (remember/remember-tool {:search-fn (fn [_] [])})
          result (tools/parse tool (tools/run tool {:query ""}))]
      (is (false? (:stored result)))
      (is (str/includes? (:error result) "Invalid remember args")))))

;; ---- Context composition tests (unchanged) ----

(deftest compose-context-includes-memory-block
  (testing "facts appear in [memory] system block, not as chat messages"
    (let [session-id (make-session-id)
          store      (test-store session-id)
          _          (dlevin/store-message! store {:session-id session-id
                                                  :role "assistant"
                                                  :text "Favorite color is teal"
                                                  :kind "fact"
                                                  :topic "preferences"
                                                  :timestamp 1000})
          state     {:session-id           session-id
                     :memory-store          store
                     :memory-backend        :datalevin
                     :history               [{:role "user" :content "hi"}]
                     :memory-max-chars      20
                     :memory-relevant-limit 5
                     :memory-recent-limit   10
                     :memory-strategy       :hybrid}
          ctx       (ctx/compose-context state "color")]
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
    (let [ag (core/make-agent {:base-url     "http://mock"
                               :model        "mock"
                               :sessions-dir test-base-dir})]
      (is (= "default" (core/get-session-id ag)))
      (is (some #(= "remember" (:name %)) (core/get-tools ag)))
      (core/close-session! ag))))

(deftest make-agent-nil-session-has-no-remember
  (testing "explicit nil session-id disables remember tool wiring"
    (let [ag (core/make-agent {:base-url   "http://mock"
                               :model      "mock"
                               :session-id nil})]
      (is (nil? (core/get-memory-store ag)))
      (is (not (some #(= "remember" (:name %)) (core/get-tools ag)))))))