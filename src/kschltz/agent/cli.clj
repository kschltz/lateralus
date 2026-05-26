(ns kschltz.agent.cli
  "CLI entrypoint for lateralus agent.

  Usage:
    clojure -M:cli \"What is 2+2?\"
    clojure -M:cli -i                     # interactive mode
    echo \"hello\" | clojure -M:cli         # pipe mode"
  (:require [kschltz.agent.core :as core])
  (:gen-class))

(defn- parse-args
  "Parse CLI arguments into an opts map."
  [args]
  (loop [opts {:interactive false}
         rem  args]
    (if-let [arg (first rem)]
      (cond
        (#{"-i" "--interactive"} arg)
        (recur (assoc opts :interactive true) (next rem))

        (#{"-h" "--help"} arg)
        (assoc opts :help true)

        (#{"-v" "--version"} arg)
        (assoc opts :version true)

        (#{"-t" "--turns"} arg)
        (recur (assoc opts :turns (fnext rem)) (nnext rem))

        (#{"-r" "--retries"} arg)
        (recur (assoc opts :max-retries (fnext rem)) (nnext rem))

        (#{"-m" "--model"} arg)
        (recur (assoc opts :model (fnext rem)) (nnext rem))

        (.startsWith arg "-")
        (let [key-name (subs arg 1)]
          (recur (assoc opts (keyword key-name) (fnext rem)) (nnext rem)))

        :else
        (assoc opts :prompt arg))
      opts)))

(defn- prompt-loop
  "Interactive REPL loop. Reads from stdin, sends to agent, prints response."
  [ag]
  (println "Lateralus agent ready. Type :quit to exit, :history for chat history.")
  (loop []
    (print "\nuser> ")
    (flush)
    (when-let [input (read-line)]
      (cond
        (.equalsIgnoreCase (.trim input) ":quit")
        nil

        (.equalsIgnoreCase (.trim input) ":history")
        (do
          (doseq [msg (core/get-history ag)]
            (println (str "[" (:role msg) "] " (:content msg))))
          (recur))

        (.equalsIgnoreCase (.trim input) ":config")
        (do
          (println (core/get-config ag))
          (recur))

        (.equalsIgnoreCase (.trim input) ":tools")
        (do
          (doseq [t (core/get-tools ag)]
            (println (str "- " (:name t) ": " (:description t))))
          (recur))

        (empty? (.trim input))
        (recur)

        :else
        (let [p (core/send-message! ag input)
              result (deref p 300000 ::timeout)]
          (if (= ::timeout result)
            (println "[timeout waiting for response]")
            (println (str "\nagent> " result)))
          (recur))))))

(defn- one-shot
  "Send a single prompt and print the response."
  [ag prompt]
  (let [p (core/send-message! ag prompt)
        result (deref p 120000 ::timeout)]
    (if (= ::timeout result)
      (do (println "[timeout]") (System/exit 1))
      (do (println result) (System/exit 0)))))

(defn -main
  "CLI entrypoint."
  [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (do (println "Usage: lateralus [prompt] [options]")
          (println "")
          (println "Options:")
          (println "  -i, --interactive    Start interactive loop")
          (println "  -m, --model MODEL   LLM model (env: LATERALUS_MODEL)")
          (println "  -u, --base-url URL  LLM API base URL (env: LATERALUS_BASE_URL)")
          (println "  -k, --api-key KEY   API key (env: LATERALUS_API_KEY)")
          (println "  -s, --session ID    Session ID for memory")
          (println "  -t, --turns N       Max turns (default: 5)")
          (println "  -r, --retries N    Max retries on tool errors (default: 3)")
          (println "  -h, --help          Show this help")
          (System/exit 0))

      (:version opts)
      (do (println "lateralus 0.1.0") (System/exit 0))

      :else
      (let [base-url  (or (:base-url opts) (System/getenv "LATERALUS_BASE_URL") "http://localhost:11434")
            model      (or (:model opts) (System/getenv "LATERALUS_MODEL") "deepseek-v4-flash:cloud")
            api-key    (or (:api-key opts) (System/getenv "LATERALUS_API_KEY"))
            turns      (if (:turns opts) (Integer/parseInt (:turns opts)) 5)
            retries    (if (:max-retries opts) (Integer/parseInt (:max-retries opts)) 3)
            session-id (or (:session opts) (System/getenv "LATERALUS_SESSION"))
            ag         (core/make-agent {:base-url    base-url
                                          :api-key     api-key
                                          :model       model
                                          :turns       turns
                                          :session-id  session-id
                                          :max-retries retries
                                          :on-response (fn [r] (println (str "\nagent> " r)))
                                          :on-error    (fn [_a e] (println (str "\nERROR: " (.getMessage e))))
                                          :on-thought  (fn [evt]
                                                         (when (= :tool-call (:type evt))
                                                           (println (str "  [tool-call] " (pr-str (:calls evt)))))
                                                         (when (= :tool-result (:type evt))
                                                           (doseq [r (:results evt)]
                                                             (println (str "  [tool-result] " (:tool r) " => " (or (:error r) (:result r)))))))})]
        (core/add-repl-eval-tool! ag)
        (if (:interactive opts)
          (do
            (future (core/start! ag))
            (prompt-loop ag)
            (core/stop! ag))
          (if (:prompt opts)
            (do
              (future (core/start! ag))
              (one-shot ag (:prompt opts))
              (core/stop! ag))
            (do
              ;; No prompt and not interactive — check stdin
              (if-let [stdin-input (read-line)]
                (do
                  (future (core/start! ag))
                  (one-shot ag stdin-input)
                  (core/stop! ag))
                (do
                  (println "No prompt provided. Use -i for interactive mode or provide a prompt.")
                  (System/exit 1))))))))))