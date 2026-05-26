(ns kschltz.agent.delimiter-repair-test
  "Tests for automatic Clojure delimiter repair."
  (:require [clojure.test :refer [deftest testing is]]
            [kschltz.agent.delimiter-repair :as dr]))

(def missing-paren "(let [x 1] (inc x")
(def extra-paren "(let [x 1] (inc x)))")
(def valid-code "(let [x 1] (inc x))")
(def complex-missing "(defn foo [x] (if (> x 0) (inc x)")

(deftest delimiter-error-detection
  (testing "valid code has no delimiter errors"
    (is (not (dr/delimiter-error? valid-code)))
    (is (not (dr/delimiter-error? "(+ 1 2 3)"))))

  (testing "missing close paren is detected"
    (is (dr/delimiter-error? missing-paren))
    (is (dr/delimiter-error? complex-missing)))

  (testing "extra close paren is detected"
    (is (dr/delimiter-error? extra-paren))))

(deftest repair-code-basic
  (testing "clean code passes through unchanged"
    (is (= valid-code (dr/repair-code valid-code))))

  (testing "missing close paren is repaired to valid code"
    (let [result (dr/repair-code missing-paren)]
      (is (not (dr/delimiter-error? result)))
      (is (= "(let [x 1] (inc x))" result))))

  (testing "extra close paren is repaired to valid code"
    (let [result (dr/repair-code extra-paren)]
      (is (not (dr/delimiter-error? result)))
      (is (= valid-code result)))))

(deftest repair-or-original-fallback
  (testing "returns original when repair fails"
    (is (= "not code at all" (dr/repair-or-original "not code at all"))))

  (testing "returns repaired when successful"
    (is (= "(+ 1 2 3)" (dr/repair-or-original "(+ 1 2 3"))))

  (testing "returns original when already valid"
    (is (= "(+ 1 2 3)" (dr/repair-or-original "(+ 1 2 3)")))))