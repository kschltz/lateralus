(ns kschltz.agent.nrepl-server
  "nREPL server for the agent framework.

  Provides an nREPL server that can be started/stopped from the main
  application. This enables the :nrepl tool mode to evaluate code
  remotely via the nREPL protocol.

  Uses Clojure agent reference types for state management.

  Usage:
    (start!)    ; Start server on default port 59500
    (stop!)     ; Stop server
    (running?)  ; Check if server is running")

(require '[nrepl.server :as server])

(defonce ^:private agent-server (agent nil))

(defonce ^:private server-running (agent false))

(defn start!
  "Start the nREPL server on the given port (default 59500).
   Returns the server instance."
  ([]
   (start! 59500))
  ([port]
   (if @server-running
     (throw (ex-info "Server already running" {:port port}))
     (let [server-opts {:port port
                        :bind "127.0.0.1"}
           srv (server/start-server server-opts)]
       (send server-running (fn [_] true))
       (send agent-server  (fn [_] srv))
       (await server-running agent-server)
       (println (str "nREPL server started on port " port))
       srv))))

(defn stop!
  "Stop the nREPL server if running. Returns nil."
  []
  (when @agent-server
    (server/stop-server @agent-server)
    (send server-running (fn [_] false))
    (send agent-server  (fn [_] nil))
    (await server-running agent-server)
    (println "nREPL server stopped")))

(defn running?
  "Check if the nREPL server is currently running."
  []
  @server-running)

(defn -main
  "CLI entry point to start the nREPL server."
  [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 59500)]
    (start! port)
    (println "Server running. Press Ctrl+C to stop.")
    (try
      (while @server-running
        (Thread/sleep 1000))
      (catch InterruptedException _
        (println "Interrupted, shutting down...")
        (stop!)))))