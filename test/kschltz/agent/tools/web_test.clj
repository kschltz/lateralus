(ns kschltz.agent.tools.web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as core]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.web :as sut]))

(def ^:private sample-ddg-lite
  "<a rel=\"nofollow\" href=\"https://en.wikipedia.org/wiki/Factorial\" class='result-link'>Factorial - Wikipedia</a>
   <td class='result-snippet'>
     The <b>factorial</b> of a non-negative integer n is the product of all positive integers less than or equal to n.
   </td>
   <a rel=\"nofollow\" href=\"https://example.com/factorial\" class='result-link'>Factorial Calculator</a>
   <td class='result-snippet'>
     Free online factorial calculator.
   </td>")

(deftest web-search-tool-defaults
  (testing "web-search-tool has expected metadata"
    (let [tool (sut/web-search-tool)]
      (is (= :web (:type tool)))
      (is (= "web-search" (:name tool)))
      (is (string? (:description tool))))))

(deftest parse-ddg-lite-html-extracts-hits
  (testing "parses result links and snippets from DDG Lite HTML"
    (let [hits (sut/parse-ddg-lite-html sample-ddg-lite)]
      (is (= 2 (count hits)))
      (is (= "Factorial - Wikipedia" (:title (first hits))))
      (is (= "https://en.wikipedia.org/wiki/Factorial" (:url (first hits))))
      (is (str/includes? (:snippet (first hits)) "factorial"))
      (is (not (str/includes? (:snippet (first hits)) "<b>")))
      (is (= "Factorial Calculator" (:title (second hits)))))))

(deftest web-search-blank-query
  (testing "blank query returns empty vector"
    (let [tool (sut/web-search-tool)]
      (is (= "[]" (tools/run tool "   "))))))

(deftest add-web-search-tool-registers
  (testing "core can register web-search tool"
    (let [ag (core/make-agent {:base-url "http://mock" :model "m"})]
      (core/add-web-search-tool! ag)
      (is (some #(= "web-search" (:name %)) (core/get-tools ag))))))
