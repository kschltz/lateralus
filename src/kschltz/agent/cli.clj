(ns kschltz.agent.cli
  "CLI entrypoint for lateralus agent.

  Usage:
    clojure -M:cli \"What is 2+2?\"
    clojure -M:cli -i                     # interactive mode
    echo \"hello\" | clojure -M:cli         # pipe mode"
  (:require [kschltz.agent.core :as core])
  (:gen-class))

(defn- parse-embedding-method
  "Parse embedding method string to keyword (langchain4j|http)."
  [s]
  (when s
    (let [kw (keyword s)]
      (when-not (#{:langchain4j :http} kw)
        (throw (ex-info "Invalid embedding method (use langchain4j or http)" {:method s})))
      kw)))

(defn- resolve-embedding-method
  "Resolve embedding backend: CLI opts beat env var."
  [opts env-getter]
  (or (:embedding-method opts)
      (some-> (env-getter "LATERALUS_EMBEDDING_METHOD")
              parse-embedding-method)))

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

        (#{"-s" "--session"} arg)
        (recur (assoc opts :session (fnext rem)) (nnext rem))

        (#{"-E" "--embedding-method"} arg)
        (let [method (fnext rem)]
          (when (nil? method)
            (throw (ex-info "Missing value for embedding method flag" {:flag arg})))
          (recur (assoc opts :embedding-method (parse-embedding-method method)) (nnext rem)))

        (#{"--embedding-model"} arg)
        (recur (assoc opts :embedding-model (fnext rem)) (nnext rem))

        (#{"--embedding-dims"} arg)
        (let [dims (fnext rem)]
          (when (nil? dims)
            (throw (ex-info "Missing value for --embedding-dims" {:flag arg})))
          (recur (assoc opts :embedding-dims (Integer/parseInt dims)) (nnext rem)))

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
          (when (= ::timeout result)
            (println "[timeout waiting for response]"))
          (recur))))))

(defn- one-shot
  "Send a single prompt and print the response."
  [ag prompt]
  (let [p (core/send-message! ag prompt)
        result (deref p 120000 ::timeout)]
    (if (= ::timeout result)
      (do (println "[timeout]") (System/exit 1))
      (System/exit 0))))

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
          (println "  -s, --session ID    Session ID for memory (env: LATERALUS_SESSION)")
          (println "  -E, --embedding-method METHOD  Embedding backend: langchain4j|http (env: LATERALUS_EMBEDDING_METHOD)")
          (println "      --embedding-model MODEL    Embedding model name (env: LATERALUS_EMBEDDING_MODEL)")
          (println "      --embedding-dims N         Embedding vector dimensions (env: LATERALUS_MEMORY_EMBEDDING_DIMS)")
          (println "  -t, --turns N            Max turns (default: 5)")
          (println "  -r, --retries N          Max retries on tool errors (default: 3)")
          (println "")
          (println "Memory env vars: LATERALUS_SESSIONS_DIR, LATERALUS_EMBEDDING_METHOD,")
          (println "  LATERALUS_EMBEDDING_MODEL, LATERALUS_MEMORY_EMBEDDING_DIMS,")
          (println "  LATERALUS_MEMORY_RELEVANT_LIMIT, LATERALUS_MEMORY_RECENT_LIMIT")
          (println "  -h, --help               Show this help")
          (System/exit 0))

      (:version opts)
      (do (println "lateralus 0.1.0") (System/exit 0))

      :else
      (let [base-url       (or (:base-url opts) (System/getenv "LATERALUS_BASE_URL") "http://localhost:11434")
            model          (or (:model opts) (System/getenv "LATERALUS_MODEL") "deepseek-v4-flash:cloud")
            api-key        (or (:api-key opts) (System/getenv "LATERALUS_API_KEY"))
            turns          (if (:turns opts) (Integer/parseInt (:turns opts)) 5)
            retries        (if (:max-retries opts) (Integer/parseInt (:max-retries opts)) 3)
            session-id     (or (:session opts) (System/getenv "LATERALUS_SESSION"))
            embedding-method (resolve-embedding-method opts #(System/getenv %))
            embedding-model (or (:embedding-model opts)
                                (System/getenv "LATERALUS_EMBEDDING_MODEL"))
            embedding-dims  (or (:embedding-dims opts)
                                (some-> (System/getenv "LATERALUS_MEMORY_EMBEDDING_DIMS")
                                        Integer/parseInt))
            ag             (core/make-agent
                            (cond-> {:base-url    base-url
                                     :api-key     api-key
                                     :model       model
                                     :turns       turns
                                     :max-retries retries
                                     :on-response (fn [r] (println (str "\nagent> " r)))
                                     :on-error    (fn [_a e] (println (str "\nERROR: " (.getMessage e))))
                                     :on-thought  (fn [evt]
                                                    (when (= :tool-call (:type evt))
                                                      (println (str "  [tool-call] " (pr-str (:calls evt)))))
                                                    (when (= :tool-result (:type evt))
                                                      (doseq [r (:results evt)]
                                                        (println (str "  [tool-result] " (:tool r)
                                                                      " => " (or (:error r) (:result r)))))))}
                              session-id (assoc :session-id session-id)
                              embedding-method (assoc :memory-embedding-method embedding-method)
                              embedding-model (assoc :memory-embedding-model embedding-model)
                              embedding-dims (assoc :memory-embedding-dims embedding-dims)))]
        (core/add-repl-eval-tool! ag)
        (core/add-web-search-tool! ag)
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