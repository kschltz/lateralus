(ns kschltz.agent.cli
  "CLI entrypoint for lateralus agent.

  Usage:
    clojure -M:cli \"What is 2+2?\"
    clojure -M:cli -i                     # interactive mode
    echo \"hello\" | clojure -M:cli         # pipe mode"
  (:require [kschltz.agent.core :as core]
            [kschltz.agent.memory.embedding :as embedding])
  (:gen-class))

(def ^:const default-response-timeout-ms 60000)

(defn- resolve-response-timeout-ms
  "Response wait timeout (ms). CLI --timeout beats LATERALUS_TIMEOUT_MS env."
  [opts & {:keys [env-getter] :or {env-getter #(System/getenv %)}}]
  (or (:timeout-ms opts)
      (some-> (env-getter "LATERALUS_TIMEOUT_MS") parse-long)
      default-response-timeout-ms))

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

(defn- require-flag-value
  [flag value]
  (when (nil? value)
    (throw (ex-info (str "Missing value for flag " flag) {:flag flag}))))

(defn- parse-args
  "Parse CLI arguments into an opts map."
  [args]
  (loop [opts {:interactive false}
         rem  args]
    (if-let [arg (first rem)]
      (let [value (fnext rem)
            next-rem (nnext rem)]
        (cond
          (#{"-i" "--interactive"} arg)
          (recur (assoc opts :interactive true) (next rem))

          (#{"-h" "--help"} arg)
          (assoc opts :help true)

          (#{"-v" "--version"} arg)
          (assoc opts :version true)

          (#{"-u" "--base-url"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :base-url value) next-rem))

          (#{"-k" "--api-key"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :api-key value) next-rem))

          (#{"-m" "--model"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :model value) next-rem))

          (#{"-s" "--session"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :session value) next-rem))

          (#{"--no-memory"} arg)
          (recur (assoc opts :session nil :memory-enabled false) (next rem))

          (#{"-t" "--turns"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :turns value) next-rem))

          (#{"-r" "--retries"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :max-retries value) next-rem))

          (#{"-E" "--embedding-method"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :embedding-method (parse-embedding-method value))
                     next-rem))

          (#{"--embedding-model"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :embedding-model value) next-rem))

          (#{"--embedding-dims"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :embedding-dims (Integer/parseInt value)) next-rem))

          (#{"--sessions-dir"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :sessions-dir value) next-rem))

          (#{"--memory-relevant-limit"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :memory-relevant-limit (Integer/parseInt value))
                     next-rem))

          (#{"--memory-recent-limit"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :memory-recent-limit (Integer/parseInt value))
                     next-rem))

          (#{"--memory-strategy"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :memory-strategy (keyword value)) next-rem))

          (#{"--history-limit"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :history-limit (Integer/parseInt value)) next-rem))

          (#{"--memory-max-chars"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :memory-max-chars (Integer/parseInt value)) next-rem))

          (#{"--max-tool-calls"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :max-tool-calls (Integer/parseInt value)) next-rem))

          (#{"--timeout"} arg)
          (do (require-flag-value arg value)
              (recur (assoc opts :timeout-ms (Integer/parseInt value)) next-rem))

          (.startsWith arg "-")
          (throw (ex-info "Unknown flag" {:flag arg}))

          :else
          (assoc opts :prompt arg)))
      opts)))

(defn- prompt-loop
  "Interactive REPL loop. Reads from stdin, sends to agent, prints response."
  [ag timeout-ms]
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
              result (deref p timeout-ms ::timeout)]
          (when (= ::timeout result)
            (println "[timeout waiting for response]"))
          (recur))))))

(defn- one-shot
  "Send a single prompt and print the response."
  [ag prompt timeout-ms]
  (let [p (core/send-message! ag prompt)
        result (deref p timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (println "[timeout]") (System/exit 1))
      (System/exit 0))))

(defn- print-help
  []
  (println "Usage: lateralus [prompt] [options]")
  (println "")
  (println "Options:")
  (println "  -i, --interactive              Interactive loop")
  (println "  -m, --model MODEL              LLM model (env: LATERALUS_MODEL)")
  (println "  -u, --base-url URL             LLM API base URL (env: LATERALUS_BASE_URL)")
  (println "  -k, --api-key KEY              API key (env: LATERALUS_API_KEY)")
  (println "  -s, --session ID               Session ID for memory (env: LATERALUS_SESSION)")
  (println "      --no-memory                Disable session memory")
  (println "  -E, --embedding-method METHOD  langchain4j|http (default: langchain4j in-process)")
  (println "      --embedding-model MODEL    (default: all-minilm-l6-v2-q; env: LATERALUS_EMBEDDING_MODEL)")
  (println "      --embedding-dims N         (env: LATERALUS_MEMORY_EMBEDDING_DIMS)")
  (println "      --sessions-dir PATH        (env: LATERALUS_SESSIONS_DIR)")
  (println "      --memory-relevant-limit N  (env: LATERALUS_MEMORY_RELEVANT_LIMIT)")
  (println "      --memory-recent-limit N    (env: LATERALUS_MEMORY_RECENT_LIMIT)")
  (println "      --memory-strategy STR      hybrid|... (env: LATERALUS_MEMORY_STRATEGY)")
  (println "      --history-limit N          (env: LATERALUS_HISTORY_LIMIT)")
  (println "      --memory-max-chars N       (env: LATERALUS_MEMORY_MAX_CHARS)")
  (println "      --max-tool-calls N         (env: LATERALUS_MAX_TOOL_CALLS)")
  (println "  -t, --turns N                  Max turns (default: 5)")
  (println "  -r, --retries N                Max retries on tool errors (default: 3)")
  (println "      --timeout MS               Response wait timeout in ms (default: 60000; env: LATERALUS_TIMEOUT_MS)")
  (println "  -h, --help                     Show this help")
  (println "  -v, --version                  Show version"))

(defn build-make-agent-opts
  "Merge parsed CLI opts with environment variables into a make-agent opts map."
  [cli-opts & {:keys [env-getter] :or {env-getter #(System/getenv %)}}]
  (let [int-or (fn [cli-key env-key]
                 (or (get cli-opts cli-key)
                     (some-> (env-getter env-key) Integer/parseInt)))
        str-or (fn [cli-key env-key default]
                 (or (get cli-opts cli-key) (env-getter env-key) default))
        emb-method (or (resolve-embedding-method cli-opts env-getter)
                       :langchain4j)
        emb-model (or (:embedding-model cli-opts)
                      (env-getter "LATERALUS_EMBEDDING_MODEL")
                      (case emb-method
                        :langchain4j embedding/default-langchain4j-model
                        :http embedding/default-http-model
                        embedding/default-langchain4j-model))]
    (cond-> {:base-url (str-or :base-url "LATERALUS_BASE_URL" "http://localhost:11434")
             :api-key (or (:api-key cli-opts) (env-getter "LATERALUS_API_KEY"))
             :model (str-or :model "LATERALUS_MODEL" "deepseek-v4-flash:cloud")
             :turns (or (some-> (:turns cli-opts) Integer/parseInt) 5)
             :max-retries (or (some-> (:max-retries cli-opts) Integer/parseInt) 3)
             :on-response (fn [r] (println (str "\nagent> " r)))
             :on-error (fn [_a e] (println (str "\nERROR: " (.getMessage e))))
             :on-thought (fn [evt]
                           (when (= :tool-call (:type evt))
                             (println (str "  [tool-call] " (pr-str (:calls evt)))))
                           (when (= :tool-result (:type evt))
                             (doseq [r (:results evt)]
                               (println (str "  [tool-result] " (:tool r)
                                             " => " (or (:error r) (:result r)))))))}
      (false? (:memory-enabled cli-opts))
      (assoc :memory-enabled false :session-id nil)
      (contains? cli-opts :session)
      (assoc :session-id (:session cli-opts))
      (env-getter "LATERALUS_SESSION")
      (assoc :session-id (env-getter "LATERALUS_SESSION"))
      ;; else omit :session-id — make-agent defaults to "default"
      (not (false? (:memory-enabled cli-opts)))
      (assoc :memory-embedding-method emb-method
             :memory-embedding-model emb-model)
      (int-or :embedding-dims "LATERALUS_MEMORY_EMBEDDING_DIMS")
      (assoc :memory-embedding-dims (int-or :embedding-dims "LATERALUS_MEMORY_EMBEDDING_DIMS"))
      (:sessions-dir cli-opts)
      (assoc :sessions-dir (:sessions-dir cli-opts))
      (int-or :memory-relevant-limit "LATERALUS_MEMORY_RELEVANT_LIMIT")
      (assoc :memory-relevant-limit (int-or :memory-relevant-limit "LATERALUS_MEMORY_RELEVANT_LIMIT"))
      (int-or :memory-recent-limit "LATERALUS_MEMORY_RECENT_LIMIT")
      (assoc :memory-recent-limit (int-or :memory-recent-limit "LATERALUS_MEMORY_RECENT_LIMIT"))
      (:memory-strategy cli-opts)
      (assoc :memory-strategy (:memory-strategy cli-opts))
      (int-or :history-limit "LATERALUS_HISTORY_LIMIT")
      (assoc :history-limit (int-or :history-limit "LATERALUS_HISTORY_LIMIT"))
      (int-or :memory-max-chars "LATERALUS_MEMORY_MAX_CHARS")
      (assoc :memory-max-chars (int-or :memory-max-chars "LATERALUS_MEMORY_MAX_CHARS"))
      (int-or :max-tool-calls "LATERALUS_MAX_TOOL_CALLS")
      (assoc :max-tool-calls (int-or :max-tool-calls "LATERALUS_MAX_TOOL_CALLS")))))

(defn run-agent
  "Run the Lateralus agent from CLI argument strings."
  [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (do (print-help) (System/exit 0))

      (:version opts)
      (do (println "lateralus 0.1.0") (System/exit 0))

      :else
      (let [timeout-ms (resolve-response-timeout-ms opts)
            ag (core/make-agent (build-make-agent-opts opts))]
        (if (:interactive opts)
          (do
            (future (core/start! ag))
            (prompt-loop ag timeout-ms)
            (core/stop! ag))
          (if (:prompt opts)
            (do
              (future (core/start! ag))
              (one-shot ag (:prompt opts) timeout-ms)
              (core/stop! ag))
            (if-let [stdin-input (read-line)]
              (do
                (future (core/start! ag))
                (one-shot ag stdin-input timeout-ms)
                (core/stop! ag))
              (do
                (println "No prompt provided. Use -i for interactive mode or provide a prompt.")
                (System/exit 1)))))))))

(defn -main
  "CLI entrypoint."
  [& args]
  (apply run-agent args))