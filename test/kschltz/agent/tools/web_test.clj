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

(defn- mock-http-client
  [handler]
  (reify sut/HttpClient
    (get-body [_ url extra-headers]
      (handler url extra-headers))))

(deftest web-search-tool-defaults
  (testing "web-search-tool has expected metadata"
    (let [tool (sut/web-search-tool)]
      (is (= :web (:type tool)))
      (is (= "web-search" (:name tool)))
      (is (string? (:description tool))))))

(deftest web-search-client-uses-injected-http-client
  (testing "parses titles and snippets from injected Mojeek HTML"
    (let [requests (atom [])
          client   (mock-http-client
                    (fn [url extra-headers]
                      (swap! requests conj {:url url :headers extra-headers})
                      sample-mojeek))
          hits     (sut/search (sut/web-search-client client) "clojure")]
      (is (= ["Clojure" "Clojure - Wikipedia"] (mapv :title hits)))
      (is (= ["https://clojure.org/" "https://en.wikipedia.org/wiki/Clojure"]
             (mapv :url hits)))
      (is (str/includes? (:snippet (first hits)) "dynamic"))
      (is (str/includes? (:url (first @requests)) "mojeek.com/search")))))

(deftest web-search-client-falls-back-to-startpage
  (testing "uses Startpage when Mojeek has no hits"
    (let [client (mock-http-client
                  (fn [url _extra-headers]
                    (cond
                      (str/includes? url "mojeek.com") ""
                      (str/includes? url "startpage.com") sample-startpage
                      :else (throw (ex-info "unexpected URL" {:url url})))))
          hits   (sut/search (sut/web-search-client client) "clojure")]
      (is (= [{:title "Clojure"
               :url "https://clojure.org/"
               :snippet ""}]
             hits)))))

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

(deftest web-search-rejects-invalid-query
  (testing "protocol search input is Malli-instrumented"
    (let [client (mock-http-client (fn [_ _] sample-mojeek))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/search (sut/web-search-client client) ""))))))

(deftest web-search-tool-uses-injected-client
  (testing "tool execution can inject a deterministic WebSearch client"
    (let [client (mock-http-client (fn [_ _] sample-mojeek))
          tool   (sut/web-search-tool {:client (sut/web-search-client client)})
          result (tools/parse tool (tools/run tool {:query "clojure"}))]
      (is (= "Clojure" (:title (first result)))))))

(deftest add-web-search-tool-registers
  (testing "core can register web-search tool"
    (let [ag (core/make-agent {:base-url "http://mock" :model "m"})]
      (core/add-web-search-tool! ag)
      (is (some #(= "web-search" (:name %)) (core/get-tools ag))))))

(deftest web-search-protocol-validates-output
  (testing "record search output is Malli-validated"
    (let [client (mock-http-client (fn [_ _] sample-mojeek))]
      (with-redefs [sut/fetch-mojeek-hits (fn [_ _]
                                            [{:title "Missing URL and snippet"}])]
        (is (thrown? clojure.lang.ExceptionInfo
                     (sut/search (sut/web-search-client client) "clojure")))))))
