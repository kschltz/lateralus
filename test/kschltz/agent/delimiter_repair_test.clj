(ns kschltz.agent.delimiter-repair-test
  "Tests for automatic Clojure delimiter repair."
  (:require [clojure.test :refer [deftest testing is]]
            [kschltz.agent.delimiter-repair :as dr]))

(def missing-paren "(let [x 1] (inc x")
(def extra-paren "(let [x 1] (inc x)))")
(def valid-code "(let [x 1] (inc x))")
(def complex-missing "(defn foo [x] (if (> x 0) (inc x)")
(def broken-add "(+ 1 2 3")

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
    (is (= "(+ 1 2 3)" (dr/repair-or-original broken-add))))

  (testing "returns original when already valid"
    (is (= "(+ 1 2 3)" (dr/repair-or-original "(+ 1 2 3)")))))

(deftest prepare-for-eval-flags-repair
  (testing "prepare-for-eval marks repaired code"
    (let [{:keys [code repaired?]} (dr/prepare-for-eval "(let [x 1] (inc x")]
      (is (= "(let [x 1] (inc x))" code))
      (is repaired?)))

  (testing "prepare-for-eval leaves valid code unchanged"
    (let [{:keys [code repaired?]} (dr/prepare-for-eval "(inc 1)")]
      (is (= "(inc 1)" code))
      (is (not repaired?)))))
