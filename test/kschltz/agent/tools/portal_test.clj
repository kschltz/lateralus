(ns kschltz.agent.tools.portal-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.portal :as sut]))

(deftest try-parse-data-parses-structured-strings
  (testing "parses EDN strings"
    (is (= {:data {:model "RTX 4090" :price 1599}
            :parsed? true}
           (#'sut/try-parse-data "{:model \"RTX 4090\" :price 1599}"))))

  (testing "falls back to JSON parsing and keywordizes keys"
    (is (= {:data [{:model "RTX 4090" :price 1599}]
            :parsed? true}
           (#'sut/try-parse-data "[{\"model\":\"RTX 4090\",\"price\":1599}]"))))

  (testing "rejects variable names instead of treating them as data"
    (let [result (#'sut/try-parse-data "rows")]
      (is (= 'rows (:data result)))
      (is (false? (:parsed? result)))
      (is (re-find #"variable name" (:error result)))))

  (testing "keywordizes non-string map keys from decoded tool args"
    (is (= {:data {:rows [{:name "Ada"}]}
            :parsed? true}
           (#'sut/try-parse-data {"rows" [{"name" "Ada"}]})))))

(deftest normalize-visualize-args-coerces-viewer-and-data
  (testing "normalizes map args from tool calls"
    (is (= {:data [{:model "RTX 4090"}]
            :parsed? true
            :parse-error nil
            :viewer :portal.viewer/table
            :title "GPU Prices"}
           (#'sut/normalize-visualize-args
            {:data "[{:model \"RTX 4090\"}]"
             :viewer "table"
             :title "GPU Prices"}))))

  (testing "normalizes bare data values"
    (is (= {:data [:div {:style "color: red"} "hello"]
            :parsed? true
            :parse-error nil}
           (#'sut/normalize-visualize-args
            "[:div {:style \"color: red\"} \"hello\"]")))))

(deftest visualize-tool-registers-openai-tool-definition
  (testing "visualize-tool returns tool metadata and Malli parameters"
    (let [tool (sut/visualize-tool)]
      (is (= :visualize (:type tool)))
      (is (= "visualize" (:name tool)))
      (is (string? (:description tool)))
      (is (= [:map
              [:data :any]
              [:viewer {:optional true} :string]
              [:title {:optional true} :string]]
             (:parameters tool)))))

  (testing "OpenAI tool definition includes visualize function schema"
    (let [tool-def (tools/openai-tool-def (sut/visualize-tool))
          function (:function tool-def)
          params   (:parameters function)
          props    (:properties params)]
      (is (= "function" (:type tool-def)))
      (is (= "visualize" (:name function)))
      (is (= "object" (:type params)))
      (is (contains? props :data))
      (is (= "string" (get-in props [:viewer :type])))
      (is (= "string" (get-in props [:title :type]))))))
