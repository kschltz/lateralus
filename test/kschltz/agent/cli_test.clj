(ns kschltz.agent.cli-test
  "CLI behavior tests (Phase 5): session opt-in mirrors -main wiring."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.cli :as cli]
            [kschltz.agent.core :as core]))

(deftest parse-args-session-flag
  (testing "-s / --session sets :session"
    (is (= "my-session" (:session (#'cli/parse-args ["-s" "my-session" "hello"]))))
    (is (= "prompt text" (:prompt (#'cli/parse-args ["-s" "sid" "prompt text"]))))))

(deftest cli-memory-opt-in
  (testing "memory is disabled without -s or LATERALUS_SESSION (CLI default)"
    (let [session-id (or nil (System/getenv "LATERALUS_SESSION"))]
      (when (nil? session-id)
        (let [ag (core/make-agent {:base-url "http://mock" :model "mock"})]
          (is (nil? (core/get-memory-store ag))
              "make-agent without session-id should not open memory")))))
  (testing "memory is enabled when session-id is provided like CLI -s"
    (let [sid (str "cli-opt-in-" (System/nanoTime))
          ag  (core/make-agent {:base-url    "http://mock"
                                :model       "mock"
                                :session-id  sid
                                :sessions-dir "test-sessions-cli"})]
      (is (some? (core/get-memory-store ag)))
      (is (= sid (core/get-session-id ag)))
      (core/close-session! ag))))
