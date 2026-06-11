(ns kschltz.agent.tools.rewrite-test
  "Unit tests for the clj-edit rewrite-clj tool."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tools.rewrite :as rewrite]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.file-safety :as file-safety]))

(def test-dir (System/getProperty "java.io.tmpdir"))

(defn- temp-file
  ([content] (temp-file ".clj" content))
  ([ext content]
   (let [f (java.io.File/createTempFile "clj-edit-test" ext)]
     (spit f content)
     (.getAbsolutePath f))))

(defn- cleanup! [path]
  (when path (try (io/delete-file path) (catch Exception _))))

(def sample-src
  "(ns sample.core\n  (:require [clojure.string :as str]))\n\n(defn greet [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n\n(def default-name \"world\")")

(def sample-require-src
  "(ns sample.core\n  (:require [clojure.string :as str]))\n\n(defn greet [name]\n  (str/hello name))")

(deftest test-clj-edit-tool-creation
  (testing "tool has correct type and name"
    (let [tool (rewrite/clj-edit-tool)]
      (is (= :clj-edit (:type tool)))
      (is (= "clj_edit" (:name tool))))))

(deftest test-read-structure
  (testing "enumerates top-level forms"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "read-structure" :path f})
          parsed (tools/parse tool result)]
      (is (= "read-structure" (:op parsed)))
      (is (pos? (:total parsed)))
      (cleanup! f))))

(deftest test-find-form
  (testing "finds a defn by name"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "find-form" :path f :name "greet"})
          parsed (tools/parse tool result)]
      (is (= "find-form" (:op parsed)))
      (is (= "greet" (:name parsed)))
      (is (some? (:source parsed)))
      (cleanup! f))))

(deftest test-replace-form
  (testing "replaces a defn body"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          new-src "(defn greet [name]\n  (str \"Hi, \" name))"
          result (tools/run tool {:op "replace-form" :path f :name "greet" :source new-src})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (let [content (slurp f)]
        (is (re-find #"Hi," content)))
      (cleanup! f))))

(deftest test-remove-form
  (testing "removes a defn by name"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "remove-form" :path f :name "farewell"})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-insert-form
  (testing "inserts a new form after a target"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          new-fn "(defn shout [name]\n  (str/upper-case (greet name)))"
          result (tools/run tool {:op "insert-form" :path f :name "farewell" :source new-fn})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-add-require
  (testing "adds a new require entry"
    (let [f (temp-file sample-require-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          result (tools/run tool {:op "add-require" :path f
                                  :require-entry {:lib "clojure.set" :as "set"}})
          parsed (tools/parse tool result)]
      (is (= "ok" (:status parsed)))
      (cleanup! f))))

(deftest test-find-then-replace-roundtrip
  (testing "find a form, replace it, find it again"
    (let [f (temp-file sample-src)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      (let [find-result (tools/parse tool (tools/run tool {:op "find-form" :path f :name "greet"}))]
        (is (re-find #"Hello" (:source find-result))))
      (tools/run tool {:op "replace-form" :path f :name "greet"
                       :source "(defn greet [name]\n  (str \"Hey, \" name))"})
      (let [find-result (tools/parse tool (tools/run tool {:op "find-form" :path f :name "greet"}))]
        (is (re-find #"Hey" (:source find-result))))
      (cleanup! f))))

;; ---- Audit-fix regression tests (fact-16) ----

(deftest clj-edit-refuses-non-clojure-with-structured-error
  (testing "clj_edit on a .py file returns :wrong-file-type with use-tool hint"
    (let [f (temp-file ".py" "print('hi')")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      ;; read-structure on .py
      (let [r (tools/parse tool (tools/run tool {:op "read-structure" :path f}))]
        (is (= :wrong-file-type (:error r)))
        (is (= "file_edit" (:use-tool r))))
      ;; find-form on .py
      (let [r (tools/parse tool (tools/run tool {:op "find-form" :path f :name "foo"}))]
        (is (= :wrong-file-type (:error r))))
      (cleanup! f))))

(deftest clj-edit-blocks-blocked-paths
  (testing "clj_edit on a .clj file inside .git/ is blocked even with :force"
    (let [git-dir (str test-dir "/.git")
          _ (.mkdirs (io/file git-dir))
          git-clj (str git-dir "/config.clj")
          _ (spit git-clj "(ns config)")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      ;; Use replace-form which goes through the write path
      (let [r (tools/parse tool
                          (tools/run tool {:op "replace-form" :path git-clj
                                            :name "config"
                                            :source "(ns config-edited)"}))]
        (is (= :blocked-path (:error r))))
      (.delete (io/file git-clj))
      (.delete (io/file git-dir)))))

;; ---- create-ns ----

(deftest create-ns-happy-path
  (testing "create-ns creates a new file with ns + requires + forms"
    (let [src-root (str test-dir "/src")
          _ (.mkdirs (io/file src-root))
          path (str src-root "/sample/created.clj")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      (let [r (tools/parse tool
                           (tools/run tool {:op "create-ns"
                                            :ns "sample.created"
                                            :source-root src-root
                                            :requires [{:lib "clojure.string"}]
                                            :forms ["(defn hi [] :hello)"]}))]
        (is (= :ok (:status r)))
        (is (= path (:path r)))
        (is (.exists (io/file path)))
        (is (re-find #"\(ns sample\.created" (slurp path)))
        (is (re-find #"clojure\.string" (slurp path)))
        (is (re-find #"defn hi" (slurp path))))
      (cleanup! path)
      (.delete (io/file (str src-root "/sample")))
      (.delete (io/file src-root)))))

(deftest create-ns-rejects-existing-file
  (testing "create-ns refuses to overwrite an existing file"
    (let [f (temp-file sample-src)
          ns-name (clojure.string/replace (.getName (io/file f)) #"\.clj$" "")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          r (tools/parse tool (tools/run tool {:op "create-ns" :ns ns-name
                                            :source-root (.getParent (io/file f))}))]
      (is (= :file-exists (:error r)))
      (cleanup! f))))

(deftest create-ns-missing-ns-arg
  (testing "create-ns with no :ns returns missing-arg error"
    (let [tool (rewrite/clj-edit-tool {:write-dir test-dir})
          r (tools/parse tool (tools/run tool {:op "create-ns"}))]
      (is (= :missing-arg (:error r))))))

(deftest create-ns-validates-parse
  (testing "create-ns with unparseable :forms returns parse-failed"
    (let [src-root (str test-dir "/src")
          _ (.mkdirs (io/file src-root))
          path (str src-root "/bad.clj")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          r (tools/parse tool (tools/run tool {:op "create-ns"
                                               :ns "bad"
                                               :source-root src-root
                                               :forms ["(this is not valid"]}))]
      (is (= :parse-failed (:error r)))
      (is (not (.exists (io/file path))) "file is NOT created when parse fails")
      (.delete (io/file src-root)))))

;; ---- create-file ----

(deftest create-file-happy-path
  (testing "create-file creates a new file at the given path"
    (let [f (str test-dir "/new-file.clj")
          _ (cleanup! f)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})]
      (let [r (tools/parse tool
                           (tools/run tool {:op "create-file" :path f
                                            :source "(ns new.file)\n"}))]
        (is (= :ok (:status r)))
        (is (= f (:path r)))
        (is (.exists (io/file f)))
        (is (= "(ns new.file)\n" (slurp f))))
      (cleanup! f))))

(deftest create-file-rejects-existing
  (testing "create-file refuses to overwrite"
    (let [f (temp-file "(ns existing)")
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          r (tools/parse tool (tools/run tool {:op "create-file" :path f
                                               :source "(ns nope)"}))]
      (is (= :file-exists (:error r)))
      (cleanup! f))))

(deftest create-file-refuses-non-clojure
  (testing "create-file on .py path returns :wrong-file-type"
    (let [f (str test-dir "/new.py")
          _ (cleanup! f)
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          r (tools/parse tool (tools/run tool {:op "create-file" :path f
                                               :source "print('hi')"}))]
      (is (= :wrong-file-type (:error r)))
      (cleanup! f))))

;; ---- Backup is created on write ops ----

(deftest replace-form-creates-backup
  (testing "replace-form creates a backup file before writing"
    (let [f (temp-file sample-src)
          _ (Thread/sleep 2) ; ensure timestamp differs
          tool (rewrite/clj-edit-tool {:write-dir test-dir})
          _ (tools/run tool {:op "replace-form" :path f :name "greet"
                             :source "(defn greet [name] :hi)"})]
      (let [backups (file-safety/list-backups f)]
        (is (seq backups) "at least one backup exists"))
      (cleanup! f))))
