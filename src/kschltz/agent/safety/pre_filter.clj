(ns kschltz.agent.safety.pre-filter
  "Pre-filter for retrieved text before context-window injection.

   Mirrors Springdrift's dprime/deterministic.gleam three-layer model:
   1. Normalisation — defeat unicode/leet/whitespace evasion
   2. Structural injection scoring — boundary + imperative + system-target + role-play
   3. Payload signatures — base64, code fences, XML/HTML instruction tags

   The result is one of:
     :pass      — proceed, no signal
     :escalate  — log and proceed with a flag
     :block     — drop the message, do not inject into context

   All functions are pure. No I/O, no atoms, no LLM. The caller is
   responsible for what to do with the result."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Result types
;; ---------------------------------------------------------------------------

(defrecord PreFilterResult
  ;; :result    — one of :pass :escalate :block
  ;; :rule-id   — which detector fired, "" if :pass
  ;; :reason    — short string for logs
  ;; :context   — long string for forensics
  ;; :score     — numeric (only :structural-injection has a score)
  [result rule-id reason context score])

(defn pass [] (->PreFilterResult :pass "" "" "" 0))
(defn escalate [rule-id reason context]
  (->PreFilterResult :escalate rule-id reason context 0))
(defn block [rule-id reason context score]
  (->PreFilterResult :block rule-id reason context score))

(defn escalation?
  "True if the result requires operator attention but text may still proceed."
  [{:keys [result]}]
  (= :escalate result))

(defn blocked?
  "True if the result requires the text to be dropped entirely."
  [{:keys [result]}]
  (= :block result))

;; ---------------------------------------------------------------------------
;; Layer 1: Normalisation
;; ---------------------------------------------------------------------------

(def ^:private zero-width-chars
  ;; U+200B zero-width space
  ;; U+200C zero-width non-joiner
  ;; U+200D zero-width joiner
  ;; U+FEFF zero-width no-break space (BOM)
  ;; U+00AD soft hyphen
  "\u200B\u200C\u200D\uFEFF\u00AD")

(defn- strip-confusables
  "Replace leet-speak and zero-width characters. Order matters:
   zero-width first (so they don't break the leet replacements),
   then leet, then case-fold."
  [text]
  (-> text
      (str/replace #"[​‌‍﻿­]" "")  ; zero-width chars
      (str/replace "0" "o")
      (str/replace "1" "l")
      (str/replace "3" "e")
      (str/replace "@" "a")
      (str/replace "$" "s")
      str/lower-case
      (#(str/replace % #"\s+" " "))  ; collapse whitespace
      str/trim))

;; ---------------------------------------------------------------------------
;; Layer 2: Structural injection scoring
;; ---------------------------------------------------------------------------

(def ^:private boundary-markers
  ["---" "###" "===" "***" "[inst]" "[/inst]"
   "\u003C\u003C" "\u003E\u003E" "\u003C\u003C\u003C" "\u003E\u003E\u003E"
   "system:" "user:" "assistant:" "```"])

(def ^:private imperative-verbs
  ["ignore" "disregard" "forget" "override" "bypass" "skip" "suppress"
   "abandon" "discard" "dismiss" "overwrite" "replace" "reset" "clear"
   "drop" "remove" "delete" "eliminate" "nullify" "violate" "break"
   "escape" "circumvent" "evade"])

(def ^:private system-targets
  ["previous instructions" "prior instructions" "above instructions"
   "earlier instructions" "prior directives" "original directives"
   "system prompt" "initial prompt" "original prompt" "your instructions"
   "your rules" "your guidelines" "your constraints" "your programming"
   "your training" "safety rules" "safety guidelines" "safety constraints"
   "content policy" "ethical guidelines" "no restrictions" "no rules"
   "no constraints" "no limits" "without restrictions" "without safety"
   "without constraints" "unrestricted" "unfiltered" "uncensored"])

(def ^:private role-play-patterns
  ["you are now " "from now on you are " "you are a " "pretend to be "
   "pretend you are " "pretend that you" "act as " "behave as " "become "
   "you must " "you will now " "you should now " "new role:"
   "new instructions:" "new persona:"])

(defn- has-boundary?
  "True if any boundary marker appears in the normalised text."
  [text]
  (boolean (some #(str/includes? text %) boundary-markers)))

(defn- count-imperatives
  "Count how many distinct imperative verbs appear. Capped at 2 by caller."
  [text]
  (count (filter #(str/includes? text %) imperative-verbs)))

(defn- has-system-target?
  [text]
  (boolean (some #(str/includes? text %) system-targets)))

(defn- has-role-play?
  "Match at start of text or after a boundary marker. Avoids
   mid-sentence false positives like 'you are a researcher'."
  [text]
  (boolean
   (some (fn [pattern]
           (or (str/starts-with? text pattern)
               (str/includes? text (str "\n" pattern))
               (str/includes? text (str "\n " pattern))
               (str/includes? text (str "--- " pattern))
               (str/includes? text (str "---" pattern))
               (str/includes? text (str "### " pattern))
               (str/includes? text (str ". " pattern))))
         role-play-patterns)))

(defn- has-multiple-instructions?
  "Three or more distinct imperative verbs in the same text."
  [text]
  (>= (count-imperatives text) 3))

(defn- injection-score
  "Weighted structural injection score. Mirrors Springdrift's
   compute_injection_score:
     boundary       = 2 points
     imperative     = 2 points each, capped at 2 hits
     system-target  = 2 points
     role-play      = 3 points
     multi-instr    = 1 point
   Max possible: 10. ≥6 = block, ≥4 = escalate."
  [text]
  (let [imperative-count (min (count-imperatives text) 2)]
    (cond-> 0
      (has-boundary? text)        (+ 2)
      (pos? imperative-count)     (+ (* 2 imperative-count))
      (has-system-target? text)   (+ 2)
      (has-role-play? text)       (+ 3)
      (has-multiple-instructions? text) (+ 1))))

(defn- structural-injection-check
  "Layer 2. Returns a PreFilterResult. Score thresholds are operator-tunable."
  [text {:keys [block-threshold escalate-threshold]
         :or {block-threshold 6 escalate-threshold 4}}]
  (let [score (injection-score text)]
    (cond
      (>= score block-threshold)
      (block "structural-injection"
             (str "Structural injection pattern (score: " score ")")
             (str "Text scored " score " on structural injection heuristics. "
                  "Inspect for embedded instructions.")
             score)

      (>= score escalate-threshold)
      (escalate "structural-injection"
                (str "Suspicious structural pattern (score: " score ")")
                (str "Text scored " score " — below block threshold but above "
                     "escalate. Worth a glance."))

      :else
      (pass))))

;; ---------------------------------------------------------------------------
;; Layer 3: Payload signature detection
;; ---------------------------------------------------------------------------

(def ^:private base64-pattern
  ;; 40+ chars of base64 alphabet, optional padding. Fail-open on invalid.
  #"[A-Za-z0-9+/]{40,}={0,2}")

(def ^:private code-fence-system-keyword-pattern
  #"```[\s\S]*(?:system|ignore|override|bypass)[\s\S]*```")

(def ^:private xml-injection-pattern
  ;; Tags that look like instruction injection.
  #"<(?:system|instruction|prompt|override|inject)[^>]*>")

(defn- payload-signature-check
  "Layer 3. Returns a PreFilterResult. Always :escalate, never :block —
   payload signatures can be legitimate (long base64 URLs, code in
   documentation, XML for non-injection purposes)."
  [original-text]
  (cond
    (re-find base64-pattern original-text)
    (escalate "payload-base64"
              "Potential base64-encoded payload"
              "Found a 40+ char base64-shaped block in the text.")

    (re-find code-fence-system-keyword-pattern original-text)
    (escalate "payload-code-fence"
              "Code fence containing system-level keywords"
              (str "Markdown code fence contains words like 'system', 'ignore', "
                   "'override', 'bypass'."))

    (re-find xml-injection-pattern original-text)
    (escalate "payload-xml-injection"
              "XML/HTML tag resembling instruction injection"
              (str "Found an XML/HTML tag like <system>, <instruction>, <prompt>, "
                   "<override>, or <inject>."))

    :else
    (pass)))

;; ---------------------------------------------------------------------------
;; Layer composition
;; ---------------------------------------------------------------------------

(defn check-input
  "Run all three layers against a piece of text. Stops at first :block.
   Escalations accumulate so the operator sees all suspicious signals.

   For autonomous (scheduler/comms) input, the heuristic detectors are
   appropriate. For interactive operator input, you can skip them — the
   operator is trusted, and they may legitimately discuss safety topics.

   Args:
     text   — the text to filter
     opts   — keyword map:
       :source                 :autonomous (default) | :interactive
       :block-threshold        structural score ≥ this = :block  (default 6)
       :escalate-threshold     structural score ≥ this = :escalate (default 4)
       :enable-payload?        run layer 3 (default true)
       :enable-structural?     run layer 2 (default true when :autonomous)

   Returns: PreFilterResult."
  [text {:keys [source block-threshold escalate-threshold
                enable-payload? enable-structural?]
         :or   {source :autonomous
                block-threshold 6
                escalate-threshold 4
                enable-payload? true
                enable-structural? true}}]
  (let [normalised (strip-confusables text)
        effective-structural? (if (some? enable-structural?)
                                enable-structural?
                                (= :autonomous source))
        results (cond-> []
                  effective-structural?
                  (conj (structural-injection-check
                         normalised
                         {:block-threshold block-threshold
                          :escalate-threshold escalate-threshold}))

                  enable-payload?
                  (conj (payload-signature-check text)))]
    (cond
      ;; Any :block short-circuits
      (some blocked? results)
      (first (filter blocked? results))

      ;; No block, but at least one :escalate — return the first one.
      (some escalation? results)
      (first (filter escalation? results))

      :else
      (pass))))

(defn check-input-interactive
  "Variant for operator-typed text. Runs only the structural pre-filter
   with role-play detector active. The operator is trusted to discuss
   injection patterns, safety systems, etc. without false-positive blocks."
  [text opts]
  (check-input text
               (assoc opts
                      :source :interactive
                      :enable-structural? true)))

(defn all-results
  "Run check-input and return ALL the per-layer results, not just the
   first :block or :escalate. Useful for forensic logging."
  [text opts]
  (let [normalised (strip-confusables text)
        source (get opts :source :autonomous)
        enable-structural? (if (contains? opts :enable-structural?)
                             (:enable-structural? opts)
                             (= :autonomous source))
        enable-payload? (get opts :enable-payload? true)]
    (cond-> []
      enable-structural? (conj (structural-injection-check
                                normalised
                                {:block-threshold (get opts :block-threshold 6)
                                 :escalate-threshold (get opts :escalate-threshold 4)}))
      enable-payload?    (conj (payload-signature-check text)))))