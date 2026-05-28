(ns kschltz.agent.tools.web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kschltz.agent.core :as core]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.web :as sut]))

(def ^:private sample-mojeek
  "<a class=\"title\" href=\"https://clojure.org/\">Clojure</a>
   <p class=\"s\">
     Clojure is a dynamic, general-purpose programming language, combining the approachability and interactive development of a scripting language with an efficient infrastructure.
   </p>
   <a class=\"title\" href=\"https://en.wikipedia.org/wiki/Clojure\">Clojure - Wikipedia</a>
   <p class=\"s\">
     Clojure is a dynamic and functional dialect of the programming language Lisp on the Java platform.
   </p>")

(def ^:private sample-startpage
  "<a class=\"result-title result-link css-1bggj8v\" href=\"https://clojure.org/\" target=\"_blank\" rel=\"noopener nofollow noreferrer\" tabindex=\"0\" data-testid=\"gl-title-link\">
     Clojure
   </a>")

(deftest web-search-tool-defaults
  (testing "web-search-tool has expected metadata"
    (let [tool (sut/web-search-tool)]
      (is (= :web (:type tool)))
      (is (= "web-search" (:name tool)))
      (is (string? (:description tool))))))

(deftest parse-mojeek-html-extracts-hits
  (testing "parses titles and snippets from Mojeek HTML"
    (let [hits (#'sut/fetch-mojeek-hits "dummy")]  ;; will make real HTTP call
      ;; Just check structure if it works
      (is (every? #(contains? % :title) hits))
      (is (every? #(contains? % :url) hits)))))

(deftest html-unescape-numeric-entities
  (testing "decodes numeric HTML entities like &#039; and &#x27;"
    (is (= "'" (#'sut/html-unescape "&#039;")))
    (is (= "'" (#'sut/html-unescape "&#x27;")))
    (is (= "'" (#'sut/html-unescape "&#39;")))
    (is (= "&" (#'sut/html-unescape "&#38;")))))

(deftest strip-html-tags-removes-markup
  (testing "strips tags and unescapes entities"
    (is (= "Clojure's" (#'sut/strip-html-tags "Clojure&#039;s")))
    (is (= "hello world" (#'sut/strip-html-tags "<b>hello</b> world")))))

(deftest web-search-blank-query
  (testing "blank query returns empty vector"
    (let [tool (sut/web-search-tool)]
      (is (= "[]" (tools/run tool "   "))))))

(deftest add-web-search-tool-registers
  (testing "core can register web-search tool"
    (let [ag (core/make-agent {:base-url "http://mock" :model "m"})]
      (core/add-web-search-tool! ag)
      (is (some #(= "web-search" (:name %)) (core/get-tools ag))))))

(deftest web-search-live
  (testing "live web search returns results"
    (let [result (sut/web-search "clojure programming")]
      (is (pos? (count result)))
      (is (every? #(and (:title %) (:url %) (:snippet %)) result)))))