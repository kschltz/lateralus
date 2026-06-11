(ns kschltz.agent.tools.diff
  "Hand-rolled line-based diff. No new dependencies — the standard
   Clojure ecosystem already has many diff libraries; this one is
   kept simple to avoid pulling in clojure.data.priority-map or similar.

   The algorithm is a classic O(n*m) LCS (Longest Common Subsequence)
   which is fine for files under a few thousand lines. Larger files
   are not the primary use case for the file_edit tool."
  (:require [clojure.string :as str]))

(defn- lcs-table
  "Build the LCS dynamic-programming table. xs and ys are vectors
   of strings. Returns a 2D vector tab where (tab i j) is the LCS
   length of (subvec xs 0 i) and (subvec ys 0 j)."
  [xs ys]
  (let [n (count xs)
        m (count ys)
        tab (vec (repeat (inc n) (vec (repeat (inc m) 0))))]
    (reduce (fn [tab i]
              (reduce (fn [tab j]
                        (if (= (nth xs (dec i)) (nth ys (dec j)))
                          (assoc-in tab [i j]
                                    (inc (get-in tab [(dec i) (dec j)])))
                          (assoc-in tab [i j]
                                    (max (get-in tab [(dec i) j])
                                         (get-in tab [i (dec j)])))))
                      tab (range 1 (inc m))))
            tab (range 1 (inc n)))))

(defn- backtrack
  "Walk the LCS table to produce a vector of ops:
     [:equal i j]  line xs[i] = ys[j]
     [:delete i]   line xs[i] only
     [:insert j]   line ys[j] only
   Returns ops in REVERSE order (from end of file backwards)."
  [tab xs ys]
  (loop [i (count xs)
         j (count ys)
         ops (transient [])]
    (cond
      (and (zero? i) (zero? j))
      (persistent! ops)

      (and (pos? i) (zero? j))
      (recur (dec i) j (conj! ops [:delete (dec i)]))

      (and (zero? i) (pos? j))
      (recur i (dec j) (conj! ops [:insert (dec j)]))

      (= (nth xs (dec i)) (nth ys (dec j)))
      (recur (dec i) (dec j) (conj! ops [:equal (dec i) (dec j)]))

      (>= (get-in tab [(dec i) j]) (get-in tab [i (dec j)]))
      (recur (dec i) j (conj! ops [:delete (dec i)]))

      :else
      (recur i (dec j) (conj! ops [:insert (dec j)])))))

(defn diff-ops
  "Return a vector of [:equal i j] / [:delete i] / [:insert j] ops
   that turn xs into ys. Ops are in source order (i.e. forward
   through the file, not reverse)."
  [xs ys]
  (-> (lcs-table xs ys)
      (backtrack xs ys)
      reverse
      vec))

(defn- hunk
  "Build a unified-diff hunk string from a slice of ops.
   src-lines and dst-lines are the line vectors. The hunk covers
   ops from start-idx to end-idx (exclusive), with `ctx` context
   lines before and after."
  [ctx src-lines dst-lines ops start-idx end-idx]
  (let [hunk-ops (subvec ops start-idx end-idx)
        ;; Old/new line numbers: count all :delete and :insert ops before
        old-num (inc (count (filter #(= :delete (first %))
                                    (subvec ops 0 start-idx))))
        new-num (inc (count (filter #(= :insert (first %))
                                    (subvec ops 0 start-idx))))
        old-count (count (filter #(= :delete (first %)) hunk-ops))
        new-count (count (filter #(= :insert (first %)) hunk-ops))
        ;; Context lines BEFORE the hunk: up to `ctx` consecutive :equal ops
        pre-eq-ops (vec (for [op (subvec ops 0 start-idx)
                              :when (= :equal (first op))]
                          op))
        ctx-before-lines (mapv #(nth src-lines (second %))
                               (vec (take-last ctx pre-eq-ops)))
        body-lines (for [op hunk-ops]
                     (case (first op)
                       :equal (str " " (nth src-lines (second op)))
                       :delete (str "-" (nth src-lines (second op)))
                       :insert (str "+" (nth dst-lines (second op)))))]
    (str "@@ -" old-num "," old-count " +" new-num "," new-count " @@\n"
         (str/join "\n" (concat ctx-before-lines body-lines)))))

(defn- find-hunk-end
  "Return the index of the first non-equal op at or after start-idx,
   or (count ops) if all remaining ops are :equal."
  [ops start-idx]
  (loop [j start-idx]
    (cond
      (>= j (count ops)) j
      (= :equal (first (nth ops j))) (recur (inc j))
      :else j)))

(defn unified-diff
  "Compute a unified diff between two vectors of strings (lines).
   ctx is the number of context lines around each hunk (default 3).
   Returns a string in standard unified-diff format."
  ([xs ys] (unified-diff xs ys 3))
  ([xs ys ctx]
   (let [ops (diff-ops xs ys)
         hunks (loop [i 0
                      out (transient [])]
                 (if (>= i (count ops))
                   (persistent! out)
                   (let [end (find-hunk-end ops i)
                         h (hunk ctx xs ys ops i (min (inc end) (count ops)))]
                     (recur (inc end) (conj! out h)))))]
     (str/join "\n\n" hunks))))

(defn diff-stats
  "Compute {:additions N :deletions M} for a diff between xs and ys."
  [xs ys]
  (let [ops (diff-ops xs ys)
        adds (count (filter #(= :insert (first %)) ops))
        dels (count (filter #(= :delete (first %)) ops))]
    {:additions adds :deletions dels}))
