(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'net.clojars.kschltz/lateralus)
(def version "0.1.0-SNAPSHOT")
(def main 'kschltz.lateralus)
(def class-dir "target/classes")

;; Datalevin needs module opens for NIO mmap; Java 24+ also needs native-access.
;; https://github.com/datalevin/datalevin/blob/master/doc/install.md
(def datalevin-jvm-opts
  ["--add-opens=java.base/java.nio=ALL-UNNAMED"
   "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
   "--enable-native-access=ALL-UNNAMED"])

(def datalevin-manifest-add-opens
  "java.base/java.nio ALL-UNNAMED java.base/sun.nio.ch ALL-UNNAMED")

(def uber-file (format "target/%s-%s.jar" lib version))
(def launcher-file "target/lateralus")

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test :jvm-base]})
        cmds     (b/java-command
                  {:basis     basis
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file uber-file
         :basis (b/create-basis {})
         :class-dir class-dir
         :src-dirs ["src"]
         :ns-compile [main]
         :manifest {"Add-Opens" datalevin-manifest-add-opens}))

(defn- write-launcher! [jar-path launcher-path]
  (let [launcher-dir (.getParentFile (java.io.File. launcher-path))
        jar-rel      (-> (.toPath launcher-dir)
                         (.relativize (.toPath (java.io.File. jar-path)))
                         str)
        script       (str "#!/usr/bin/env bash\n"
                          "set -euo pipefail\n"
                          "DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n"
                          "JAR=\"$DIR/" jar-rel "\"\n"
                          "exec java "
                          (str/join " " datalevin-jvm-opts)
                          " -jar \"$JAR\" \"$@\"\n")]
    (spit launcher-path script)
    (.setExecutable (java.io.File. launcher-path) true true)
    (println "Wrote launcher" launcher-path)))

(defn- build-uber! [opts]
  (println "\nCopying source...")
  (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
  (println (str "\nCompiling " main "..."))
  (b/compile-clj opts)
  (println "\nBuilding JAR..." (:uber-file opts))
  (b/uber opts)
  (write-launcher! (:uber-file opts) launcher-file)
  (println "Datalevin native libs and JVM flags are bundled; use" launcher-file "or java -jar"))

(defn uber "Build the uberjar (includes Datalevin natives + launcher script)." [opts]
  (b/delete {:path "target"})
  (build-uber! (uber-opts opts))
  opts)

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (build-uber! (uber-opts opts))
  opts)
