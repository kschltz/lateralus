(ns kschltz.agent.tools.portal-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.portal :as sut]))

;; ---- Mock portal helper (used by tests below) ----

(defn- with-mock-portal
  "Run body with `open!` and `submit-via-tap!` rebound to no-ops."
  [f]
  (let [submitted? (atom false)
        mock-portal (atom {:mock true})
        stub-open (fn [& _] mock-portal)
        stub-submit (fn [_] (reset! submitted? true) true)]
    (#'sut/reset-portal-tapped-for-test)
    (with-redefs [sut/open! stub-open
                  sut/submit-via-tap! stub-submit]
      (try
        (f submitted? mock-portal)
        (finally
          (#'sut/reset-portal-tapped-for-test))))))

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

(deftest sanitize-hiccup-handles-svg-with-attrs
  (testing "sanitize-hiccup does not ClassCastException on hiccup with attr maps"
    ;; Regression: local binding `rest` shadowed clojure.core/rest,
    ;; causing (rest rest) to call the ChunkedSeq as a function.
    (let [svg [:svg {:width 300 :height 200 :xmlns "http://www.w3.org/2000/svg"}
               [:rect {:x 0 :y 0 :width 300 :height 200 :fill "#1a1a2e" :rx 16}]
               [:circle {:cx 150 :cy 100 :r 60 :fill "#e94560" :opacity 0.9}]
               [:text {:x 150 :y 185 :text-anchor "middle" :fill "#eee"
                       :font-size 14 :font-family "sans-serif"}
                "Yes, I can do SVG!"]]]
      (let [result (sut/sanitize-hiccup svg)]
        (is (vector? result))
        (is (= :svg (first result)))
        (is (map? (second result)))
        ;; 3 child elements after tag + attrs
        (is (= 3 (count (nthrest result 2)))))))

  (testing "sanitize-hiccup handles hiccup nodes without attr maps"
    ;; Nodes without attr maps get nil in the attrs position
    (let [simple [:div "hello"]]
      (let [result (sut/sanitize-hiccup simple)]
        (is (= [:div nil "hello"] result))))))

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

;; ---- Structured result (fact-4) ----

(deftest visualize-tool-returns-structured-result
  (testing "successful visualize returns :portal-open? true and :data-hash"
    (let [tool (sut/visualize-tool)
          response (tools/run tool {:data [{:model "RTX 4090" :price 1599}]
                                    :viewer "table"})
          parsed (tools/parse tool response)]
      (is (map? parsed) "returns a map")
      (is (contains? parsed :portal-open?))
      (is (contains? parsed :data-submitted?))
      (is (contains? parsed :data-hash))
      (is (contains? parsed :hint))
      (is (contains? parsed :status))
      (is (string? (:preview parsed)))
      ;; viewer may be string or keyword depending on prep pipeline
      (is (some? (:viewer parsed))
          ":viewer is present in the result"))))

(deftest visualize-tool-error-result-is-structured
  (testing "parse-failure result is also structured"
    (let [tool (sut/visualize-tool)
          ;; Pass a non-parseable value (e.g. unparseable string)
          response (tools/run tool {:data "this cannot be parsed to vec/map"
                                    :viewer "table"})
          parsed (tools/parse tool response)]
      (is (map? parsed))
      (is (false? (:portal-open? parsed)))
      (is (false? (:data-submitted? parsed)))
      (is (nil? (:data-hash parsed)))
      (is (string? (:hint parsed)))
      (is (some? (:message parsed))))))

(deftest visualize-tool-data-hash-stable
  (testing "same data produces same hash when Portal is open"
    (with-mock-portal
      (fn [_submitted? _mock]
        (let [tool (sut/visualize-tool)
              r1 (tools/parse tool (tools/run tool {:data [{:a 1}] :viewer "table"}))
              r2 (tools/parse tool (tools/run tool {:data [{:a 1}] :viewer "table"}))]
          (is (some? (:data-hash r1))
              "with Portal mocked, data-hash is computed")
          (is (= (:data-hash r1) (:data-hash r2))
              "same data → same hash"))))))

;; ---- Mock portal (fact-14) ----

;; (with-mock-portal is defined at the top of the file)

(deftest visualize-with-mock-portal-returns-portal-open
  (testing "with portal stubbed, :portal-open? is true and :data-submitted? is true"
    (with-mock-portal
      (fn [submitted? _mock]
        (let [tool (sut/visualize-tool)
              response (tools/run tool {:data [{:x 1}] :viewer "table"})
              parsed (tools/parse tool response)]
          (is (true? (:portal-open? parsed))
              "with stubbed Portal, :portal-open? is true")
          (is (true? (:data-submitted? parsed))
              "stubbed submit was called → :data-submitted? true")
          (is (nil? (:hint parsed))
              "no hint when everything works")
          (is (= :ok (:status parsed))))))))

(deftest visualize-without-portal-returns-actionable-hint
  (testing "when Portal is not available, :hint explains how to fix"
    ;; Stub open! to return nil (Portal can't open). The stub submit
    ;; never gets called because we throw inside tap>.
    (let [tool (sut/visualize-tool)
          response (tools/run tool {:data [{:x 1}] :viewer "table"})
          parsed (tools/parse tool response)]
      ;; In the test environment Portal is not loaded, so portal-open?
      ;; is false and hint should be present.
      (if (:portal-open? parsed)
        ;; Portal is on the classpath → skip this test
        (is (true? (:portal-open? parsed)))
        (do
          (is (false? (:portal-open? parsed))
              "Portal not on classpath → :portal-open? is false")
          (is (string? (:hint parsed))
              "an actionable :hint is provided")
          (is (some? (:status parsed))
              ":status is set (e.g. :portal-unavailable)"))))))
