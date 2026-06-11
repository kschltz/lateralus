(ns kschltz.agent.stuck-loop
  "Stuck-loop detection — pure functions for deciding whether the
   agent is making forward progress with its tool calls.

   Three independent signals, each returning a 0.0–1.0 score:
     - signature-diversity  : unique (tool, args) hash count / total
     - args-similarity      : average pairwise similarity of args
     - result-novelty       : fraction of new bytes in latest result

   The detector declares the agent stuck when AT LEAST TWO of the
   three signals cross the configured threshold. This AND-2-of-3 rule
   (per the goal) prevents false positives from any single noisy
   signal while still catching the common stuck patterns.

   All functions are pure and side-effect free. The chain/wiring
   code lives in `interceptors.clj`; this ns is the math.

   Configuration (env vars, read on first call):
     LATERALUS_STUCK_LOOP_WINDOW            (default 4)
     LATERALUS_STUCK_LOOP_HASH_DIVERSITY    (default 0.5)
     LATERALUS_STUCK_LOOP_SIMILARITY        (default 0.7)
     LATERALUS_STUCK_LOOP_NOVELTY           (default 0.2)"
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; ---- Config ----

(defn- read-env-long
  "Read an env var as long. Returns nil if unset, blank, or unparseable."
  [k]
  (when-let [v (System/getenv k)]
    (when-not (str/blank? v)
      (try (Long/parseLong v)
           (catch Exception _ nil)))))

(defn- read-env-double
  "Read an env var as double. Returns nil if unset, blank, or unparseable."
  [k]
  (when-let [v (System/getenv k)]
    (when-not (str/blank? v)
      (try (Double/parseDouble v)
           (catch Exception _ nil)))))

(def ^:private defaults
  {:window          4
   :hash-diversity  0.5
   :similarity      0.7
   :novelty         0.2})

(defonce ^:private config-cache (atom nil))

(defn config
  "Effective configuration for stuck-loop detection. Reads env vars on
   first call, caches the result. Returns a map with :window,
   :hash-diversity, :similarity, :novelty keys."
  []
  (or @config-cache
      (let [m (merge defaults
                     (cond-> {}
                       (read-env-long "LATERALUS_STUCK_LOOP_WINDOW")
                       (assoc :window (read-env-long "LATERALUS_STUCK_LOOP_WINDOW"))
                       (read-env-double "LATERALUS_STUCK_LOOP_HASH_DIVERSITY")
                       (assoc :hash-diversity (read-env-double "LATERALUS_STUCK_LOOP_HASH_DIVERSITY"))
                       (read-env-double "LATERALUS_STUCK_LOOP_SIMILARITY")
                       (assoc :similarity (read-env-double "LATERALUS_STUCK_LOOP_SIMILARITY"))
                       (read-env-double "LATERALUS_STUCK_LOOP_NOVELTY")
                       (assoc :novelty (read-env-double "LATERALUS_STUCK_LOOP_NOVELTY"))))]
        (reset! config-cache m)
        m)))

;; ---- SHA-256 helper (no external dep) ----

(defn- sha256-hex
  "SHA-256 hex digest of s. Pure Java, no extra deps."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; ---- Signal 1: signature diversity ----

(defn call-signature
  "Stable signature string for a single tool call. Used both for
   deduplication and as input to the diversity metric."
  [{:keys [tool args]}]
  (str (or tool "?") "\u0000" (pr-str args)))

(defn signature-diversity
  "Fraction of unique call signatures in the recent window.
   1.0 = every call different, 0.0 = every call identical.
   With <2 calls, returns 1.0 (not enough data to declare looping)."
  [calls]
  (let [calls (vec calls)
        n (count calls)]
    (if (< n 2)
      1.0
      (/ (count (distinct (map call-signature calls)))
         (double n)))))

;; ---- Signal 2: args similarity ----

(defn- shingle-vec
  "Character trigrams of s, lowercased. Returns a frequency map.
   Cheap bag-of-words-style signal that does not require a vector index."
  [s]
  (let [s (.toLowerCase (str s "  "))]
    (frequencies
     (for [i (range (- (count s) 2))]
       (subs s i (+ i 3))))))

(defn- cosine
  "Cosine similarity of two frequency maps."
  [a b]
  (let [dot   (reduce + (map (fn [[k v]] (* v (get b k 0))) a))
        norm-a (Math/sqrt (reduce + (map #(* (val %) (val %)) a)))
        norm-b (Math/sqrt (reduce + (map #(* (val %) (val %)) b)))]
    (if (or (zero? norm-a) (zero? norm-b))
      0.0
      (/ dot (* norm-a norm-b)))))

(defn args-similarity
  "Average pairwise cosine similarity of args shingles in the window.
   1.0 = every args vector is near-duplicate; 0.0 = all distinct.
   With <2 calls, returns 0.0."
  [calls]
  (let [calls (vec calls)
        n (count calls)
        shingles (mapv (comp shingle-vec pr-str :args) calls)]
    (if (< n 2)
      0.0
      (let [pairs (for [i (range n) j (range (inc i) n)] [i j])]
        (/ (reduce + (map (fn [[i j]] (cosine (nth shingles i)
                                              (nth shingles j)))
                          pairs))
           (double (count pairs)))))))

;; ---- Signal 3: result novelty ----

(defn- normalize-result
  "Coerce a tool result to a string for byte-comparison. Maps/vectors
   are pr-str'd; nil becomes the empty string; strings stay as-is."
  [x]
  (cond
    (nil? x) ""
    (string? x) x
    :else (pr-str x)))

(defn result-novelty
  "Fraction of new bytes in the latest result vs the prior window.
   1.0 = completely new content, 0.0 = byte-identical to a prior result.
   Empty results always count as no novelty.
   With <2 results, returns 1.0 (first result is trivially new)."
  [results]
  (let [results (vec results)
        n (count results)]
    (cond
      (< n 2) 1.0
      ;; Empty / blank results are NOT novel — they signal "no progress"
      (str/blank? (normalize-result (peek results))) 0.0
      (let [latest (normalize-result (peek results))]
        (or (= latest "[]")
            (= latest "nil")
            (= latest "()")
            (= latest "#{}")
            (clojure.string/starts-with? latest "[]"))) 0.0
      :else
      (let [latest (normalize-result (peek results))
            prior  (mapv normalize-result (butlast results))
            ;; Use char trigram shingles for novelty — fraction of
            ;; latest trigrams not seen in any prior result. This
            ;; captures "new substring content" rather than just
            ;; character-level overlap (English text always shares
            ;; characters, so byte-diff is meaningless for prose).
            latest-shingles (set (keys (shingle-vec latest)))
            prior-shingles (set (apply concat (map (comp keys shingle-vec) prior)))
            new-shingles (set/difference latest-shingles prior-shingles)]
        (if (empty? latest-shingles)
          0.0
          (/ (count new-shingles) (double (count latest-shingles))))))))

;; ---- Stuck? decision ----

(defn- low? [x threshold] (and (some? x) (< x threshold) (not= x :na)))
(defn- high? [x threshold] (and (some? x) (> x threshold) (not= x :na)))

(defn stuck?
  "Return nil if the agent is making progress; return a map
   {:reason :signals {...} :recent-calls [...]} if it is stuck.

   Signals crossing thresholds (default values from `config`):
     - hash-diversity LOW   (default < 0.5)
     - args-similarity HIGH (default > 0.7)
     - result-novelty  LOW  (default < 0.2)

   Stuck when AT LEAST TWO of the three signal predicates agree.
   With fewer than `window` calls, returns nil (not enough data)."
  [calls results & [opts]]
  (let [cfg (merge (config) opts)
        window (long (:window cfg))
        recent-calls  (vec (take-last window calls))
        recent-results (vec (take-last window results))
        n (count recent-calls)]
    (when (>= n window)
      (let [div (signature-diversity recent-calls)
            sim (args-similarity recent-calls)
            nov (result-novelty recent-results)
            d-low (low? div (:hash-diversity cfg))
            s-high (high? sim (:similarity cfg))
            n-low (low? nov (:novelty cfg))
            triggered (+ (if d-low 1 0)
                         (if s-high 1 0)
                         (if n-low 1 0))]
        (when (>= triggered 2)
          {:reason (cond
                     (and d-low n-low) "repeated tool calls producing no new information"
                     (and d-low s-high) "near-duplicate tool calls"
                     (and s-high n-low) "similar tool calls yielding similar results"
                     d-low "low call-signature diversity"
                     s-high "high args similarity"
                     n-low "low result novelty")
           :signals {:diversity div
                     :similarity sim
                     :novelty nov
                     :thresholds {:hash-diversity (:hash-diversity cfg)
                                  :similarity (:similarity cfg)
                                  :novelty (:novelty cfg)}}
           :recent-calls recent-calls})))))

;; ---- Internal: extract tool calls / results from turn messages ----

(defn extract-recent-calls
  "Pull tool calls out of an OpenAI-format messages vec.
   Reads assistant messages with :tool_calls and returns
   [{:tool name :args (decoded map)}]."
  [messages]
  (vec
   (for [msg messages
         :when (and (= "assistant" (:role msg))
                    (seq (:tool_calls msg)))
         tc (:tool_calls msg)
         :let [args-str (get-in tc [:function :arguments])
               args (try (clojure.edn/read-string args-str)
                         (catch Exception _ args-str))]]
     {:tool (get-in tc [:function :name])
      :args args
      :id   (:id tc)})))

(defn extract-recent-results
  "Pull tool result strings out of an OpenAI-format messages vec.
   Returns the :content of each :role \"tool\" message in order."
  [messages]
  (vec
   (for [msg messages
         :when (= "tool" (:role msg))]
     (:content msg))))

;; ---- Internal: test-only config reset (used by tests) ----

(defn reset-config-cache!
  "Clear the cached config so the next call re-reads env vars.
   Used by tests that mutate `LATERALUS_STUCK_LOOP_*` env vars."
  []
  (reset! config-cache nil)
  nil)
