(ns test-ns
  (:require [clojure.test :refer [with-redefs]]))

(defn foo [] 1)

(deftest test-foo
  (with-redefs [foo (constantly 2)]
    (is (= 2 (foo)))))
