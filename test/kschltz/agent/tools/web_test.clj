(ns kschltz.agent.tools.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as core]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.web :as sut]))

(deftest web-search-tool-defaults
  (testing "web-search-tool has expected metadata"
    (let [tool (sut/web-search-tool)]
      (is (= :web (:type tool)))
      (is (= "web-search" (:name tool)))
      (is (string? (:description tool))))))

(deftest web-search-run-with-mock
  (testing "run executes search and returns pr-str vector"
    (with-redefs {#'sut/duckduckgo-search
                  (fn [_query]
                    [{:title "Test" :url "https://example.com" :snippet "snippet"}])}
      (let [tool (sut/web-search-tool)
            raw  (tools/run tool "clojure")]
        (is (string? raw))
        (is (= "[{:title \"Test\", :url \"https://example.com\", :snippet \"snippet\"}]"
               raw))))))

(deftest web-search-blank-query
  (testing "blank query returns empty vector"
    (let [tool (sut/web-search-tool)]
      (is (= "[]" (tools/run tool "   "))))))

(deftest add-web-search-tool-registers
  (testing "core can register web-search tool"
    (let [ag (core/make-agent {:base-url "http://mock" :model "m"})]
      (core/add-web-search-tool! ag)
      (is (some #(= "web-search" (:name %)) (core/get-tools ag))))))
