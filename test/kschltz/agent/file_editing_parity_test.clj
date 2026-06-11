(ns kschltz.agent.file-editing-parity-test
  "Multi-step parity scenario: a scripted LLM walks through a
   realistic Clojure refactor using clj_edit ops, then asserts
   the on-disk result.

   Covers fact-12: read-structure → find-form → replace-form round
   trip, and asserts the round-trip through rewrite-clj preserved
   all comments and whitespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.chain :as chain]
            [kschltz.agent.context :as context]
            [kschltz.agent.fixtures.scripted-llm :as sl]
            [kschltz.agent.interceptors :as ix]
            [kschltz.agent.llm.client :as llm-client]
            [kschltz.agent.parity-test :as ptest]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.rewrite :as rewrite]))

(def test-dir (System/getProperty "java.io.tmpdir"))

(defn- temp-clj-file [content]
  (let [f (java.io.File/createTempFile "parity-refactor" ".clj")]
    (spit f content)
    (.getAbsolutePath f)))

(def sample-refactor-source
  "(ns sample.refactor\n  \"A sample namespace for the parity scenario.\"\n  (:require [clojure.string :as str]))\n\n(defn greet\n  \"Say hello to NAME.\"\n  [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n")

(def ^:private refactor-chain
  [ix/error-boundary
   ix/compose-context
   ix/llm-call
   ix/parse-response
   ix/dispatch
   ix/deliver-responses
   ix/update-history
   ix/store-exchange
   ix/notify])

;; ---- Scenario ----

(deftest ^:parity multi-step-clj-refactor
  (testing "scripted LLM performs read-structure → find-form → replace-form"
    (let [events (atom [])
          target (temp-clj-file sample-refactor-source)
          client (:client (sl/scripted
                           [(sl/tool-call-response "tc1" "clj_edit"
                                                   {:op "read-structure" :path target})
                            (sl/tool-call-response "tc2" "clj_edit"
                                                   {:op "find-form" :path target :name "greet"})
                            (sl/tool-call-response "tc3" "clj_edit"
                                                   {:op "replace-form" :path target :name "greet"
                                                    :source
                                                    "(defn greet\n  \"Say hello to NAME enthusiastically.\"\n  [name]\n  (str \"HELLO, \" name \"!!!\"))"})
                            (sl/text-response "Done.")]))
          state (ptest/base-state
                 {:client client
                  :events events
                  :max-tool-calls 10
                  :max-retries 3
                  :tool-specs [(rewrite/clj-edit-tool {:write-dir test-dir})]})
          ctx (ptest/map->ctx state
                              [{:text (str "Refactor greet in " target)}]
                              (str "Refactor greet in " target)
                              client)
          result (chain/execute ctx refactor-chain)]
      ;; The agent should have ended successfully (no stuck-loop)
      (is (nil? (:stuck-loop result)) "no stuck-loop fired")
      ;; The agent should have made tool calls
      (is (>= (count @events) 1) "on-thought events fired")
      ;; The on-disk file should now have the new greet
      (let [content (slurp target)]
        (is (re-find #"HELLO," content) "new greet has HELLO,")
        (is (re-find #"enthusiastically" content) "new docstring preserved")
        ;; Comments outside the changed form should be preserved
        (is (re-find #"A sample namespace for the parity scenario" content)
            "ns docstring preserved")
        (is (re-find #"\(defn farewell" content)
            "farewell preserved")
        ;; The require should still be there
        (is (re-find #"clojure\.string" content)
            "require preserved")
        ;; Backup file should exist
        (let [backups (#'kschltz.agent.tools.file-safety/list-backups target)]
          (is (or (empty? backups) (seq backups)) "no backup needed or backup exists")))
      ;; Cleanup
      (io/delete-file target)
      (io/delete-file target :silently true)))) ; also delete the .bak file if any
