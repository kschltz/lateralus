(ns kschltz.lateralus
  "Lateralus application entry point.

  Run via:
    clojure -M:run-m -h
    clojure -M:run-m -i -s my-session -m model-name
    java -jar lateralus.jar \"your prompt\" -s session-id"
  (:require [kschltz.agent.cli :as agent-cli])
  (:gen-class))

(defn greet
  "Callable entry point for tooling (e.g. clojure -X:run-x)."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn run-agent
  "Run the Lateralus agent. Accepts the same CLI flags as kschltz.agent.cli."
  [& args]
  (apply agent-cli/run-agent args))

(defn -main
  "Application entry point — runs the Lateralus agent CLI."
  [& args]
  (apply run-agent args))
