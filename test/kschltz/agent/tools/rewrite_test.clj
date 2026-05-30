(ns kschltz.agent.tools.rewrite-test
  "Unit tests for the clj-edit rewrite-clj tool."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [kschltz.agent.tools.rewrite :as rewrite]
            [kschltz.agent.tools :as tools]))

(def test-dir (System/getProperty "java.io.tmpdir"))

(defn- temp-file
  ([content] (temp-file ".clj" content))
  ([ext content]
   (let [f (java.io.File/createTempFile "clj-edit-test" ext)]
     (spit f content)
     (.getAbsolutePath f))))

(defn- cleanup! [path]
  (when path (try (io/delete-file path) (catch Exception _))))

(def sample-src
  "(ns sample.core\n  (:require [clojure.string :as str]))\n\n(defn greet [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n\n(def default-name \"world\")")

(def sample-require-src
  "(ns sample.core\n  (:require [clojure.string :as str]))\n\n(defn greet [name]\n  (str/hello name))")

(deftest test-clj-edit-tool-creation
  (testing "tool has correct type and name"
    (let [tool (rewrite/clj-edit-tool)]
      (is (= :clj-edit (:type tool)))
      (is (= "clj_edit" (:name tool))))))

(deftest test-read-structure
  (testing "enumerates top-level forms"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "read-structure" :path f})
          parsed (tools/parse tool result)]
      (is (= "read-structure" (:op parsed)))
      (is (pos? (:total parsed)))
      (cleanup! f))))

(deftest test-find-form
  (testing "finds a defn by name"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "find-form" :path f :name "greet"})
          parsed (tools/parse tool result)]
      (is (= "find-form" (:op parsed)))
      (is (= "greet" (:name parsed)))
      (is (some? (:source parsed)))
      (cleanup! f))))

(deftest test-replace-form
  (testing "replaces a defn body"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          new-src "(defn greet [name]\n  (str \"Hi, \" name))"
          result (tools/run tool {:op "replace-form" :path f :name "greet" :source new-src})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (let [content (slurp f)]
        (is (re-find #"Hi," content)))
      (cleanup! f))))

(deftest test-remove-form
  (testing "removes a defn by name"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "remove-form" :path f :name "farewell"})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-insert-form
  (testing "inserts a new form after a target"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          new-fn "(defn shout [name]\n  (str/upper-case (greet name)))"
          result (tools/run tool {:op "insert-form" :path f :name "farewell" :source new-fn})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-add-require
  (testing "adds a new require entry"
    (let [f (temp-file sample-require-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "add-require" :path f
                                  :require-entry {:lib "clojure.set" :as "set"}})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-find-then-replace-roundtrip
  (testing "find a form, replace it, find it again"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      (let [find-result (tools/parse tool (tools/run tool {:op "find-form" :path f :name "greet"}))]
        (is (re-find #"Hello" (:source find-result))))
      (tools/run tool {:op "replace-form" :path f :name "greet"
                        :source "(defn greet [name]\n  (str \"Hey, \" name))"})
      (let [find-result (tools/parse tool (tools/run tool {:op "find-form" :path f :name "greet"}))]
        (is (re-find #"Hey" (:source find-result))))
      (cleanup! f))))
