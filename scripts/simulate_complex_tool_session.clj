#!/usr/bin/env clojure
;; Simulate a multi-turn user session with complex native tool usage.
;; Usage:
;;   clojure -X:sim
;; Env: LATERALUS_BASE_URL (default http://localhost:11434)
;;      LATERALUS_MODEL (default qwen3.5:397b-cloud)
;;      LATERALUS_HTTP_TIMEOUT_MS (default 600000 for this script)

(ns simulate-complex-tool-session
  (:require [clojure.string :as str]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]))

(def base-url
  (or (System/getenv "LATERALUS_BASE_URL") "http://localhost:11434"))

(def model
  (or (System/getenv "LATERALUS_MODEL") "qwen3.5:397b-cloud"))

(def session-id
  (str "sim-complex-" (System/currentTimeMillis)))

(defn- log-thought [evt]
  (case (:type evt)
    :thinking  (println "  [thinking]" (subs (str (:content evt)) 0 (min 120 (count (str (:content evt))))))
    :tool-call (do (println "  [tool-call]")
                   (doseq [c (:calls evt)]
                     (println "    ->" (or (:tool c) (get-in c [:function :name]))
                              (or (:args c) (get-in c [:function :arguments])))))
    :tool-result (doseq [r (:results evt)]
                   (println "  [tool-result]" (:tool r) "=>"
                            (str/join " " (take 80 (str (or (:error r) (:result r)))))))
    nil))

(defn- user-turn [ag n prompt]
  (println (str "\n" (apply str (repeat 72 "="))))
  (println (str "TURN " n " | USER"))
  (println prompt)
  (println (apply str (repeat 72 "-")))
  (let [t0     (System/currentTimeMillis)
        resp   (core/chat! ag prompt)
        ms     (- (System/currentTimeMillis) t0)]
    (println (str "AGENT (" ms " ms)"))
    (println resp)
    (println (str "History entries: " (count (core/get-history ag))))
    resp))

(defn- run-turns [ag]
  (user-turn ag 1
    "You MUST use the repl-eval tool (not mental math) to evaluate (+ 41 1). Return only the numeric result from the tool.")
  (user-turn ag 2
    "Use repl-eval twice: first evaluate (range 5), then evaluate (count [0 1 2 3 4]). Tell me both results.")
  (user-turn ag 3
    "Use web-search with query \"Clojure nREPL agent\". Summarize the top finding in one sentence.")
  (user-turn ag 4
    "First web-search for \"factorial 6\", then use repl-eval to compute (apply * (range 1 7)). Compare if they match.")
  (user-turn ag 5
    "Without using tools: what was the first arithmetic expression I asked you to evaluate via repl-eval in turn 1?"))

(defn -main [& _]
  (println "Lateralus complex tool session simulation")
  (println "  base-url:" base-url)
  (println "  model:" model)
  (println "  http-timeout-ms:" (or (System/getenv "LATERALUS_HTTP_TIMEOUT_MS") "5000 (set LATERALUS_HTTP_TIMEOUT_MS=600000)"))
  (println "  session-id:" session-id)
  (let [ag (core/make-agent {:base-url       base-url
                              :model          model
                              :turns          30
                              :max-tool-calls 8
                              :max-retries    3
                              :session-id     session-id
                              :sessions-dir   "sessions"
                              :on-thought     log-thought})]
    (core/add-repl-eval-tool! ag)
    (core/add-web-search-tool! ag)
    (println "\nTools:" (mapv :name (core/get-tools ag)))
    (try
      (run-turns ag)

      ;; Persisted memory check
      (println (str "\n" (apply str (repeat 72 "="))))
      (println "SESSION MEMORY (load-recent)")
      (when-let [store (core/get-memory-store ag)]
        (let [msgs (memory/load-recent-messages {:backend    :datalevin
                                                   :session-id session-id
                                                   :connection store
                                                   :limit      20})]
          (println "  stored messages:" (count msgs))
          (doseq [m (take 8 msgs)]
            (println "   " (:msg/timestamp m) (:msg/role m)
                     (subs (:msg/text m) 0 (min 60 (count (:msg/text m))))))))

      (println (str "\n" (apply str (repeat 72 "="))))
      (println "FINAL HISTORY (last 6 chat messages)")
      (doseq [msg (take-last 6 (core/get-history ag))]
        (println (str "  [" (:role msg) "] "
                        (subs (:content msg) 0 (min 100 (count (:content msg)))))))

      (finally
        (core/close-session! ag)
        (println "\nSession closed.")))))
