(ns kschltz.agent.cli
  "CLI entry point for the Lateralus agent.

   Parses command-line flags, builds agent config, and runs in one of:
     - interactive mode (-i) — REPL loop with :quit/:history commands
     - daemon mode (-d) — backgrounds the agent loop, nohup-style
     - one-shot mode — single prompt, exit"
  (:require [kschltz.agent.core :as core]
            [kschltz.agent.tools.portal :as portal]
            [kschltz.agent.memory.embedding :as embedding]
            [clojure.string :as str]))

;; ---- Response timeout ----

(def ^:private default-response-timeout-ms
  "Default timeout for deref-ing a response promise."
  (or (some-> (System/getenv "LATERALUS_TIMEOUT_MS") parse-long)
      60000))

(defn- resolve-response-timeout-ms
  [cli-opts]
  (or (:timeout-ms cli-opts) default-response-timeout-ms))

;; ---- Embedding method resolution ----

(defn- parse-embedding-method
  "Parse --embedding-method flag value."
  [s]
  (cond
    (nil? s) nil
    (= (str/lower-case s) "langchain4j") :langchain4j
    (= (str/lower-case s) "http")         :http
    :else (throw (ex-info (str "Unknown embedding method: " s
                               ". Use 'langchain4j' or 'http'.")
                          {:method s}))))

(defn- resolve-embedding-method
  "Resolve embedding method from CLI opts and env."
  [cli-opts env-getter]
  (or (some-> (:embedding-method cli-opts) parse-embedding-method)
      (some-> (env-getter "LATERALUS_EMBEDDING_METHOD") parse-embedding-method)))

(defn- require-flag-value
  "Throw if a flag that requires a value has none."
  [flag value]
  (when (nil? value)
    (throw (ex-info (str "Flag " flag " requires a value") {:flag flag}))))

;; ---- CLI parser ----

(defn- parse-args
  "Parse CLI arguments into an opts map.
   Supports both -x value and --flag value formats."
  [args]
  (loop [[arg value & next-rem] args
         opts {}]
    (if-not arg
      opts
      (cond
        (#{"-i" "--interactive"} arg)
        (recur next-rem (assoc opts :interactive true))

        (#{"-d" "--daemon"} arg)
        (recur next-rem (assoc opts :daemon true))

        (#{"--no-memory"} arg)
        (recur next-rem (assoc opts :memory-enabled false))

        (#{"-h" "--help"} arg)
        (assoc opts :help true)

        (#{"-v" "--version"} arg)
        (assoc opts :version true)

        (#{"-u" "--base-url"} arg)
        (do (require-flag-value arg value)
            (recur next-rem (assoc opts :base-url value)))

        (#{"-k" "--api-key"} arg)
        (do (require-flag-value arg value)
            (recur next-rem (assoc opts :api-key value)))

        (#{"-m" "--model"} arg)
        (do (require-flag-value arg value)
            (recur next-rem (assoc opts :model value)))

        (#{"-s" "--session"} arg)
        (do (require-flag-value arg value)
            (recur next-rem (assoc opts :session value)))

          (#{"-t" "--turns"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :turns (Integer/parseInt value))))

          (#{"-r" "--retries"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :max-retries (Integer/parseInt value))))

          (#{"-E" "--embedding-method"} arg)
          (do (require-flag-value arg value)
              (recur next-rem
                     (assoc opts :embedding-method (parse-embedding-method value))))

          (#{"--embedding-model"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :embedding-model value)))

          (#{"--embedding-dims"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :embedding-dims (Integer/parseInt value))))

          (#{"--sessions-dir"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :sessions-dir value)))

          (#{"--memory-relevant-limit"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :memory-relevant-limit (Integer/parseInt value))))

          (#{"--memory-recent-limit"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :memory-recent-limit (Integer/parseInt value))))

          (#{"--memory-strategy"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :memory-strategy (keyword value))))

          (#{"--history-limit"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :history-limit (Integer/parseInt value))))

          (#{"--memory-max-chars"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :memory-max-chars (Integer/parseInt value))))

          (#{"--max-tool-calls"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :max-tool-calls (Integer/parseInt value))))

          (#{"--timeout"} arg)
          (do (require-flag-value arg value)
              (recur next-rem (assoc opts :timeout-ms (Integer/parseInt value))))

          (.startsWith arg "-")
          (throw (ex-info "Unknown flag" {:flag arg}))

          :else
          (assoc opts :prompt arg)))))

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
  (println "  -d, --daemon                    Run as background daemon (nohup-style)")
  (println "  -m, --model MODEL              LLM model (env: LATERALUS_MODEL)")
  (println "  -u, --base-url URL             LLM API base URL (env: LATERALUS_BASE_URL)")
  (println "  -k, --api-key KEY              API key (env: LATERALUS_API_KEY)")
  (println "  -s, --session ID               Session ID for memory (env: LATERALUS_SESSION)")
  (println "      --no-memory                Disable session memory")
  (println "  -E, --embedding-method METHOD  langchain4j|http (default: langchain4j in-process)")
  (println "      --embedding-model MODEL    (default: all_minilm_l6_v2_q; env: LATERALUS_EMBEDDING_MODEL)")
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
                 (or (let [v (get cli-opts cli-key)]
                       (if (int? v) v (some-> v Integer/parseInt)))
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
             :turns (or (:turns cli-opts) 5)
             :max-retries (or (:max-retries cli-opts) 3)
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

(defn- write-pid-file
  "Write the current process PID to a file for daemon management.
   Returns the PID file path."
  [sessions-dir session-id]
  (let [pid-dir (or sessions-dir ".lateralus")
        pid-file (java.io.File. pid-dir)]
    (.mkdirs pid-file)
    (let [path (str pid-dir "/" session-id ".pid")
          pid   (-> (java.lang.management.ManagementFactory/getRuntimeMXBean)
                    (.getName)
                    (clojure.string/split #"@")
                    first)]
      (spit path pid)
      path)))

(defn- daemon-loop
  "Run agent in daemon mode. Starts the agent loop in a background thread,
   writes a PID file, registers a shutdown hook, and blocks until interrupted."
  [ag opts]
  (let [sessions-dir (or (:sessions-dir (deref ag)) ".lateralus")
        session-id   (or (:session-id (deref ag)) "default")
        pid-path     (write-pid-file sessions-dir session-id)]
    ;; Register shutdown hook for clean exit
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (println (str "\nShutting down lateralus daemon (session: " session-id ")..."))
                          (core/stop! ag)
                          (println "Stopped.")
                          ;; Remove PID file on clean shutdown
                          (try (clojure.java.io/delete-file pid-path)
                               (catch Exception _)))))
    (println (str "Lateralus daemon started (session: " session-id ", PID file: " pid-path ")"))
    (println (str "To stop: kill $(cat " pid-path ") or Ctrl-C"))
    (println "Waiting for messages...")
    ;; Start the agent loop
    (future (core/start! ag))
    ;; Block forever — shutdown hook handles cleanup
    (try
      @(promise)  ;; blocks indefinitely until process is killed
      (catch InterruptedException _
        (println "Interrupted.")))))

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
        (cond
          ;; Interactive mode — REPL loop
          (:interactive opts)
          (do (future (core/start! ag))
              (prompt-loop ag timeout-ms)
              (core/stop! ag))

          ;; Daemon mode — block forever in background, nohup-style
          (:daemon opts)
          (daemon-loop ag opts)

          ;; One-shot with prompt on CLI
          (:prompt opts)
          (do (future (core/start! ag))
              (one-shot ag (:prompt opts) timeout-ms)
              (core/stop! ag))

          ;; One-shot from stdin
          :else
          (if-let [stdin-input (read-line)]
            (do (future (core/start! ag))
                (one-shot ag stdin-input timeout-ms)
                (core/stop! ag))
            (do (println "No prompt provided. Use -i for interactive, -d for daemon mode, or provide a prompt.")
                (System/exit 1))))))))

(defn -main
  "CLI entrypoint."
  [& args]
  (apply run-agent args))