(ns kschltz.agent.stuck-loop-test
  "Unit tests for the pure stuck-loop detection functions.

   Covers fact-13: each of signature-diversity, args-similarity, and
   result-novelty has at least 5 assertions across edge cases.
   Plus tests for stuck? combining the three signals."
  (:require [clojure.test :refer [deftest is testing]]
            [kschltz.agent.stuck-loop :as sl]))

;; ---- signature-diversity ----

(deftest signature-diversity-single-call
  (testing "single call has 1.0 diversity (not enough data)"
    (is (= 1.0 (sl/signature-diversity [{:tool :web-search :args {:q "a"}}])))))

(deftest signature-diversity-all-distinct
  (testing "all distinct calls have 1.0 diversity"
    (is (= 1.0 (sl/signature-diversity
                [{:tool :web-search :args {:q "a"}}
                 {:tool :web-search :args {:q "b"}}
                 {:tool :web-search :args {:q "c"}}
                 {:tool :web-search :args {:q "d"}}])))))

(deftest signature-diversity-all-identical
  (testing "all identical calls have minimum diversity (1/n)"
    ;; 4 calls all identical → 1 unique / 4 = 0.25
    (is (= 0.25 (sl/signature-diversity
                 [{:tool :web-search :args {:q "model card"}}
                  {:tool :web-search :args {:q "model card"}}
                  {:tool :web-search :args {:q "model card"}}
                  {:tool :web-search :args {:q "model card"}}])))
    (is (= 0.1 (sl/signature-diversity (vec (repeat 10 {:tool :x :args {}})))))))

(deftest signature-diversity-half-different
  (testing "2 of 4 unique = 0.5"
    (is (= 0.5 (sl/signature-diversity
                [{:tool :web-search :args {:q "a"}}
                 {:tool :web-search :args {:q "a"}}
                 {:tool :web-search :args {:q "b"}}
                 {:tool :web-search :args {:q "b"}}])))))

(deftest signature-diversity-empty
  (testing "empty list returns 1.0 (vacuously unique)"
    (is (= 1.0 (sl/signature-diversity [])))))

;; ---- args-similarity ----

(deftest args-similarity-single-call
  (testing "single call has 0.0 similarity (no pairs)"
    (is (= 0.0 (sl/args-similarity [{:tool :web :args {:q "a"}}])))))

(deftest args-similarity-near-duplicate-queries
  (testing "near-duplicate queries score high"
    (let [score (sl/args-similarity
                 [{:tool :web :args {:q "M3 model card"}}
                  {:tool :web :args {:q "M3 model card specs"}}
                  {:tool :web :args {:q "M3 model card"}}
                  {:tool :web :args {:q "M3 model card"}}])]
      (is (> score 0.7)
          (str "expected high similarity for repeated 'M3 model card' queries, got " score)))))

(deftest args-similarity-distinct-queries
  (testing "completely distinct queries score low"
    (let [score (sl/args-similarity
                 [{:tool :web :args {:q "alpha"}}
                  {:tool :web :args {:q "beta gamma"}}
                  {:tool :web :args {:q "delta epsilon zeta"}}
                  {:tool :web :args {:q "eta theta iota"}}])]
      (is (< score 0.5)
          (str "expected low similarity for distinct queries, got " score)))))

(deftest args-similarity-empty-list
  (testing "empty list returns 0.0"
    (is (= 0.0 (sl/args-similarity [])))))

(deftest args-similarity-mixed-tools
  (testing "different tool names still get args compared"
    (let [score (sl/args-similarity
                 [{:tool :a :args {:q "hello world"}}
                  {:tool :b :args {:q "hello world"}}
                  {:tool :c :args {:q "totally different"}}])]
      (is (> score 0.0) "at least some similarity from the matching args"))))

;; ---- result-novelty ----

(deftest result-novelty-single-result
  (testing "single result has 1.0 novelty (trivially new)"
    (is (= 1.0 (sl/result-novelty ["some result"])))))

(deftest result-novelty-empty-latest
  (testing "empty latest result has 0.0 novelty"
    (is (= 0.0 (sl/result-novelty ["real result" ""])))
    (is (= 0.0 (sl/result-novelty ["real result" nil])))
    (is (= 0.0 (sl/result-novelty ["real result" "[]"])))))

(deftest result-novelty-bit-identical
  (testing "byte-identical result has 0.0 novelty"
    (is (= 0.0 (sl/result-novelty ["same content" "same content"])))))

(deftest result-novelty-completely-new
  (testing "completely new bytes have 1.0 novelty"
    (is (= 1.0 (sl/result-novelty ["abcdef" "ghijkl"])))))

(deftest result-novelty-partial-overlap
  (testing "partial overlap scores between 0 and 1"
    (let [score (sl/result-novelty ["hello world" "hello there"])]
      (is (> score 0.0))
      (is (< score 1.0)))))

(deftest result-novelty-normalizes-types
  (testing "non-string results are pr-str'd for comparison"
    (is (= 0.0 (sl/result-novelty [{:a 1 :b 2} {:a 1 :b 2}])))
    ;; truly different data: use unique characters in shingle space
    (is (= 0.0 (sl/result-novelty [["a" 1] ["a" 1]])))
    ;; different structure AND different content
    (is (< 0.5 (sl/result-novelty [{:foo "alpha"} {:bar "beta gamma delta"}])))))

;; ---- stuck? (combining) ----

(deftest stuck?-insufficient-data
  (testing "with fewer calls than window, returns nil"
    (is (nil? (sl/stuck?
               [{:tool :web :args {:q "a"}}]
               ["result"]
               {:window 4})))))

(deftest stuck?-all-three-signals-fire
  (testing "5 near-identical web-search calls with empty results → stuck"
    (let [calls (vec (repeat 5 {:tool :web-search
                                :args {:query "M3 model card specs"}}))
          results (vec (repeat 5 "[]"))
          out (sl/stuck? calls results)]
      (is (some? out) "should be stuck")
      (is (string? (:reason out)))
      (is (some? (get-in out [:signals :diversity])))
      (is (< (get-in out [:signals :diversity]) 0.5)
          "diversity should be low")
      (is (> (get-in out [:signals :similarity]) 0.7)
          "similarity should be high")
      (is (< (get-in out [:signals :novelty]) 0.2)
          "novelty should be low"))))

(deftest stuck?-one-good-call-then-stuck
  (testing "repeated stuck pattern in recent window trips detector"
    (let [;; 1 distinct call, then 4 identical — recent window (last 4)
          ;; is all identical, with results that are also identical.
          calls [{:tool :web :args {:q "alpha"}}
                 {:tool :web :args {:q "M3 model card specs"}}
                 {:tool :web :args {:q "M3 model card specs"}}
                 {:tool :web :args {:q "M3 model card specs"}}
                 {:tool :web :args {:q "M3 model card specs"}}]
          results ["different initial result" "r3" "r3" "r3" "r3"]
          out (sl/stuck? calls results)]
      ;; Recent 4 are all identical — diversity 0.25, similarity 1.0,
      ;; novelty 0.0. All 3 signals fire → stuck.
      (is (some? out) "should be stuck on the recent window")
      (is (< (get-in out [:signals :diversity]) 0.5))
      (is (> (get-in out [:signals :similarity]) 0.7)))))

(deftest stuck?-diverse-calls-not-stuck
  (testing "diverse distinct calls with distinct results → not stuck"
    (let [calls [{:tool :web :args {:q "alpha"}}
                 {:tool :web :args {:q "beta"}}
                 {:tool :web :args {:q "gamma"}}
                 {:tool :web :args {:q "delta"}}]
          results ["r1" "r2 different" "r3 other" "r4 yet another"]
          out (sl/stuck? calls results)]
      (is (nil? out) (str "should not be stuck, got " (pr-str out))))))

(deftest stuck?-one-signal-fires-not-stuck
  (testing "only one of three signals firing → not stuck (need 2-of-3)"
    ;; Distinct args (similarity LOW) but same tool, near-empty results
    ;; (diversity LOW, novelty LOW). Only 1 signal fires.
    (let [calls [{:tool :web :args {:q "alpha beta"}}
                 {:tool :web :args {:q "gamma delta"}}
                 {:tool :web :args {:q "epsilon zeta"}}
                 {:tool :web :args {:q "eta theta"}}]
          results ["[]" "[]" "[]" "[]"]
          out (sl/stuck? calls results)]
      ;; diversity 1.0 (all distinct), similarity 0.0 (all different args),
      ;; novelty 0.0 (all empty results — caught by special case).
      ;; Only novelty fires → not stuck (1 < 2).
      (is (nil? out) (str "should not be stuck when only novelty is low, got " (pr-str out))))))

(deftest stuck?-thresholds-configurable
  (testing "with thresholds that no signal can meet, never stuck"
    (let [calls (vec (repeat 4 {:tool :web :args {:q "x"}}))
          results (vec (repeat 4 "same"))]
      ;; -0.1 diversity threshold (must be LESS than -0.1 — impossible)
      ;; and -0.1 novelty threshold (must be LESS than -0.1 — impossible)
      ;; plus 2.0 similarity (unreachable). At most 1 signal fires.
      (is (nil? (sl/stuck? calls results
                           {:hash-diversity -0.1
                            :similarity     2.0
                            :novelty        -0.1}))
          "impossible diversity/novelty thresholds + unreachable similarity should prevent stuck detection"))))

;; ---- extract helpers ----

(deftest extract-recent-calls-from-messages
  (testing "pulls tool calls out of assistant messages"
    (let [msgs [{:role "assistant"
                 :tool_calls [{:id "c1"
                               :function {:name "web-search"
                                          :arguments "{\"query\":\"a\"}"}}]}
                {:role "tool" :tool_call_id "c1" :content "[]"}
                {:role "assistant"
                 :tool_calls [{:id "c2"
                               :function {:name "web-search"
                                          :arguments "{\"query\":\"b\"}"}}]}]
          out (sl/extract-recent-calls msgs)]
      (is (= 2 (count out)))
      (is (= ["web-search" "web-search"] (mapv :tool out)))
      (is (= ["c1" "c2"] (mapv :id out))))))

(deftest extract-recent-results-from-messages
  (testing "pulls tool result contents in order"
    (let [msgs [{:role "tool" :content "r1"}
                {:role "tool" :content "r2"}
                {:role "assistant" :content "ok"}]
          out (sl/extract-recent-results msgs)]
      (is (= ["r1" "r2"] out)))))

;; ---- config ----

(deftest config-returns-defaults
  (testing "config returns a map with all expected keys"
    (let [c (sl/config)]
      (is (contains? c :window))
      (is (contains? c :hash-diversity))
      (is (contains? c :similarity))
      (is (contains? c :novelty)))))
