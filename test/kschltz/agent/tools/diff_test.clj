(ns kschltz.agent.tools.diff-test
  "Unit tests for the hand-rolled diff."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.tools.diff :as diff]))

(deftest diff-ops-single-line-add
  (testing "add one line in the middle"
    (let [ops (diff/diff-ops ["a" "c"] ["a" "b" "c"])]
      (is (= 3 (count ops)))
      (is (some #(= :equal (first %)) ops))
      (is (some #(= :insert (first %)) ops)))))

(deftest diff-ops-single-line-remove
  (testing "remove one line in the middle"
    (let [ops (diff/diff-ops ["a" "b" "c"] ["a" "c"])]
      (is (= 3 (count ops)))
      (is (some #(= :delete (first %)) ops)))))

(deftest diff-ops-no-change
  (testing "no change returns all :equal"
    (let [ops (diff/diff-ops ["a" "b" "c"] ["a" "b" "c"])]
      (is (every? #(= :equal (first %)) ops)))))

(deftest diff-stats-counts
  (testing "diff-stats counts additions and deletions"
    (is (= {:additions 1 :deletions 0}
           (diff/diff-stats ["a" "c"] ["a" "b" "c"])))
    (is (= {:additions 0 :deletions 1}
           (diff/diff-stats ["a" "b" "c"] ["a" "c"])))
    (is (= {:additions 2 :deletions 2}
           (diff/diff-stats ["a" "b" "c" "d"] ["a" "x" "y" "d"])))))

(deftest unified-diff-includes-hunk-marker
  (testing "unified-diff output contains @@ markers"
    (let [out (diff/unified-diff ["a" "b" "c"] ["a" "x" "c"])]
      (is (re-find #"@@" out) "contains hunk markers")
      (is (re-find #"\+" out) "contains + lines")
      (is (re-find #"-" out) "contains - lines"))))

(deftest unified-diff-empty-for-identical
  (testing "identical inputs produce no hunks (empty output or no @@)"
    (let [out (diff/unified-diff ["a" "b"] ["a" "b"])]
      ;; The output may contain the trailing context lines but should
      ;; be effectively empty (no real +/- changes).
      (is (not (re-find #"^[+-][^+-]" out))
          "no real + or - content lines"))))

(deftest unified-diff-handles-empty-old
  (testing "all-insert case (empty old, lines new)"
    (let [out (diff/unified-diff [] ["a" "b"])]
      (is (re-find #"\+a" out))
      (is (re-find #"\+b" out)))))

(deftest unified-diff-size-guard
  (testing "P3-10: huge inputs return :too-large before O(n*m) blowup"
    (let [;; Simulate "huge" by overriding the env-backed cap to 10
          big-xs (vec (repeat 100 "line"))
          big-ys (vec (repeat 100 "different line"))
          cap-fn (fn [] 10)]
      (with-redefs [kschltz.agent.tools.diff/max-diff-lines 10]
        (let [out (kschltz.agent.tools.diff/unified-diff big-xs big-ys)]
          (is (re-find #":too-large" out)
              "returns :too-large marker")
          (is (re-find #":line-count 200" out)
              "reports actual line count")
          (is (re-find #":max 10" out)
              "reports the cap"))))))

(deftest unified-diff-under-cap-runs
  (testing "inputs under the cap compute the diff normally"
    (let [out (kschltz.agent.tools.diff/unified-diff
               ["a" "b" "c"] ["a" "x" "c"])]
      (is (string? out))
      (is (re-find #"@@" out) "produces hunk markers"))))

(deftest unified-diff-handles-empty-new
  (testing "all-delete case (lines old, empty new)"
    (let [out (diff/unified-diff ["a" "b"] [])]
      (is (re-find #"-a" out))
      (is (re-find #"-b" out)))))
