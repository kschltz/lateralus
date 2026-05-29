#!/usr/bin/env clojure
;; Live Ollama demo — cloud models ONLY (no local GGUF).
;; Usage:
;;   LATERALUS_MODEL=deepseek-v4-flash:cloud clojure -X:cloud-demo
;; Env:
;;   LATERALUS_BASE_URL  (default http://localhost:11434)
;;   LATERALUS_MODEL     (default deepseek-v4-flash:cloud)
;;   LATERALUS_HTTP_TIMEOUT_MS (default 600000)

(ns live-ollama-cloud-demo
  (:require [clojure.string :as str]
            [kschltz.agent.core :as core]
            [kschltz.agent.memory :as memory]))

(def base-url
  (or (System/getenv "LATERALUS_BASE_URL") "http://localhost:11434"))

(def model
  (or (System/getenv "LATERALUS_MODEL") "deepseek-v4-flash:cloud"))

(def session-id
  (str "cloud-demo-" (System/currentTimeMillis)))

(defn- cloud-model? [m]
  (str/ends-with? (str m) ":cloud"))

(defn- log-thought [evt]
  (case (:type evt)
    :tool-call
    (doseq [c (:calls evt)]
      (println "  [tool-call]" (or (:tool c) (get-in c [:function :name]))
               (subs (str (or (:args c) (get-in c [:function :arguments]))) 0 120)))
    :tool-result
    (doseq [r (:results evt)]
      (println "  [tool-result]" (:tool r) "=>"
               (subs (str (or (:error r) (:result r))) 0 100)))
    nil))

(defn- turn [ag n prompt]
  (println (str "\n--- TURN " n " ---"))
  (println "USER:" prompt)
  (let [t0 (System/currentTimeMillis)
        resp (core/chat! ag prompt)]
    (println (str "AGENT (" (- (System/currentTimeMillis) t0) " ms):"))
    (println resp)
    resp))

(defn -main [& _]
  (when-not (cloud-model? model)
    (binding [*out* *err*]
      (println "ERROR: LATERALUS_MODEL must be a :cloud model (e.g. deepseek-v4-flash:cloud).")
      (println "  Got:" model))
    (System/exit 1))
  (println "Lateralus live Ollama cloud demo")
  (println "  base-url:" base-url)
  (println "  model:" model " (cloud only)")
  (println "  session-id:" session-id)
  (let [ag (core/make-agent {:base-url     base-url
                             :model        model
                             :turns        15
                             :max-tool-calls 6
                             :session-id   session-id
                             :on-thought   log-thought})]
    (println "  tools:" (mapv :name (core/get-tools ag)))
    (try
      (turn ag 1
            "Use repl-eval to compute (+ 10 32). Reply with the tool result only.")
      (turn ag 2
            "Use repl-eval to evaluate (class (+ 1 1)), then say the class name in plain English.")
      (turn ag 3
            "Use web-search with query \"metosin malli clojure\". One sentence summary of the top hit.")
      (println "\n--- SESSION MEMORY ---")
      (when-let [store (core/get-memory-store ag)]
        (let [msgs (memory/load-recent-messages {:backend :datalevin
                                                 :session-id session-id
                                                 :connection store
                                                 :limit 6})]
          (println "  stored:" (count msgs) "messages")))
      (finally
        (core/close-session! ag)
        (println "\nDone.")))))
