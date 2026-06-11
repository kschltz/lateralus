(ns kschltz.agent.tools.file-safety-test
  "Unit tests for the shared file-safety helpers used by clj_edit
   and file_edit. Covers facts 4, 7, 8, 9, 10."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [kschltz.agent.tools.file-safety :as fs]))

(defn- temp-file
  "Create a temp file. Variants:
     (temp-file content)            →  prefix=fs-test, ext=.txt
     (temp-file ext content)        →  prefix=fs-test
     (temp-file prefix ext content)"
  ([content] (temp-file "fs-test" ".txt" content))
  ([ext content] (temp-file "fs-test" ext content))
  ([prefix ext content]
   (let [f (java.io.File/createTempFile prefix ext)]
     (when content (spit f content))
     (.getAbsolutePath f))))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "fs-test-dir" "")]
    (.delete f)
    (.mkdirs f)
    (.getAbsolutePath f)))

;; ---- clojure-file? ----

(deftest clojure-file?-detects-all-extensions
  (testing "all four Clojure extensions return true"
    (is (fs/clojure-file? "foo.clj"))
    (is (fs/clojure-file? "foo.cljs"))
    (is (fs/clojure-file? "foo.cljc"))
    (is (fs/clojure-file? "foo.edn")))
  (testing "non-Clojure extensions return false"
    (is (not (fs/clojure-file? "foo.py")))
    (is (not (fs/clojure-file? "foo.md")))
    (is (not (fs/clojure-file? "foo.json"))))
  (testing "case-sensitive (no .CLJ or .CLJS)"
    (is (not (fs/clojure-file? "FOO.CLJ")))
    (is (not (fs/clojure-file? "Foo.Clj"))))
  (testing "full paths with directories"
    (is (fs/clojure-file? "/abs/path/to/src/foo/bar.clj"))
    (is (not (fs/clojure-file? "/abs/path/to/src/foo/bar.py")))))

(deftest file-extension-extracts-correctly
  (testing "lowercase extension including leading dot"
    (is (= ".clj" (fs/file-extension "foo.clj")))
    (is (= ".py" (fs/file-extension "/abs/path/foo.PY")))
    (is (= ".edn" (fs/file-extension "x/y/z.edn"))))
  (testing "no extension"
    (is (nil? (fs/file-extension "Makefile")))
    (is (nil? (fs/file-extension "/abs/path/Makefile"))))
  (testing "hidden files have no extension"
    (is (nil? (fs/file-extension ".gitignore")))))

;; ---- within-write-dir? ----

(deftest within-write-dir?-basic
  (let [dir (temp-dir)
        inside (str dir "/file.clj")
        outside "/some/other/path/file.clj"]
    (is (fs/within-write-dir? inside dir))
    (is (not (fs/within-write-dir? outside dir))))
  (testing "default write-dir is project cwd"
    (is (fs/within-write-dir? (str (System/getProperty "user.dir") "/foo.clj")))))

(deftest within-write-dir?-rejects-prefix-bypass
  (testing "P0-1: /proj/lateralus-evil does NOT match write-dir /proj/lateralus"
    ;; Simulate by creating a real dir and an 'evil' sibling
    (let [parent (temp-dir)
          legit (str parent "/legit")
          evil-sibling (str parent "/legit-evil")
          _ (.mkdirs (io/file legit))
          _ (.mkdirs (io/file evil-sibling))
          inside (str legit "/file.clj")
          outside (str evil-sibling "/file.clj")]
      (is (fs/within-write-dir? inside legit)
          "file inside legit IS within write-dir")
      (is (not (fs/within-write-dir? outside legit))
          "file in 'legit-evil' sibling is REJECTED — prefix bypass prevented")
      (.delete (io/file legit))
      (.delete (io/file evil-sibling)))))

;; ---- blocked-path? ----

(deftest blocked-path?-default-set
  (testing "default blocked paths match"
    (is (fs/blocked-path? "/some/proj/.git/config"))
    (is (fs/blocked-path? "/some/proj/target/uber.jar"))
    (is (fs/blocked-path? "/some/proj/node_modules/lodash/index.js"))
    (is (fs/blocked-path? "/some/proj/.clojure-mcp-light/state"))
    (is (fs/blocked-path? "/some/proj/.mvn/wrapper/maven-wrapper.jar"))
    (is (fs/blocked-path? "/some/proj/dist/bundle.js"))
    (is (fs/blocked-path? "/some/proj/build/classes/Main.class"))))

(deftest blocked-path?-non-blocked
  (testing "regular paths and project names that contain blocked words are NOT blocked"
    (is (not (fs/blocked-path? "/some/proj/src/foo.clj")))
    (is (not (fs/blocked-path? "/some/target-thing/file.clj")))
    (is (not (fs/blocked-path? "/some/distribution/file.clj")))))

(deftest blocked-path?-custom-set
  (testing "custom blocked-set is honored"
    (is (fs/blocked-path? "/proj/secret/file.clj" #{"secret"}))
    (is (not (fs/blocked-path? "/proj/src/file.clj" #{"secret"})))))

;; ---- make-backup! / restore! ----

(deftest make-backup-creates-file
  (let [src (temp-file "original content")
        _ (Thread/sleep 2)
        backup (fs/make-backup! src)]
    (is (some? backup) "returns backup path when source exists")
    (is (.exists (io/file backup)) "backup file exists on disk")
    (is (= "original content" (slurp backup)) "backup content matches source")
    (fs/restore! src)
    (io/delete-file src))

  (testing "returns nil when source does not exist"
    (is (nil? (fs/make-backup! "/no/such/path/here.clj")))))

(deftest list-backups-returns-newest-first
  (let [src (temp-file "x")
        _ (Thread/sleep 5)
        b1 (fs/make-backup! src)
        _ (Thread/sleep 5)
        b2 (fs/make-backup! src)
        _ (Thread/sleep 5)
        b3 (fs/make-backup! src)
        backups (fs/list-backups src)]
    (is (= 3 (count backups)))
    (is (= b3 (first backups)) "newest first")
    (is (= b1 (last backups)) "oldest last")
    (doseq [b backups] (io/delete-file b))
    (io/delete-file src)))

(deftest restore-reverts-from-most-recent
  (let [src (temp-file "v1")
        _ (Thread/sleep 5)
        b1 (fs/make-backup! src)
        _ (spit src "v2")
        _ (Thread/sleep 5)
        b2 (fs/make-backup! src)
        _ (spit src "v3")
        restored (fs/restore! src)]
    (is (some? restored))
    (is (= "v2" (slurp src)) "restored to most recent backup content")
    (is (not (.exists (io/file b2))) "the used backup is deleted")
    (is (.exists (io/file b1)) "older backups are preserved")
    (io/delete-file b1)
    (io/delete-file src)))

(deftest restore-returns-nil-when-no-backups
  (let [src (temp-file "x")]
    (is (nil? (fs/restore! src)))
    (io/delete-file src)))

;; ---- validate-write-target! ----

(deftest validate-write-clojure-only
  (let [write-dir (temp-dir)]
    (testing "rejects non-Clojure files when clojure-only? is true"
      (let [result (fs/validate-write-target! (str write-dir "/test.py")
                                              {:clojure-only? true
                                               :write-dir write-dir
                                               :tool-name "clj_edit"
                                               :use-tool "file_edit"})]
        (is (= :wrong-file-type (:error result)))
        (is (= "file_edit" (:use-tool result)))))

    (testing "accepts Clojure files when clojure-only? is true"
      (is (nil? (fs/validate-write-target! (str write-dir "/test.clj")
                                           {:clojure-only? true
                                            :write-dir write-dir
                                            :tool-name "clj_edit"}))))))

(deftest validate-write-write-dir-containment
  (let [allowed (temp-dir)
        outside "/tmp/should-not-be-allowed.clj"]
    (testing "rejects paths outside write-dir"
      (is (= :outside-write-dir
             (-> (fs/validate-write-target! outside
                                            {:clojure-only? true
                                             :write-dir allowed
                                             :tool-name "clj_edit"})
                 :error))))
    (testing "accepts with :force true"
      (is (nil? (fs/validate-write-target! outside
                                           {:clojure-only? true
                                            :write-dir allowed
                                            :force? true
                                            :tool-name "clj_edit"}))))))

(deftest validate-write-blocks-blocked-paths
  (let [write-dir (temp-dir)]
    (testing ".git under write-dir is blocked even with :force"
      (is (= :blocked-path
             (-> (fs/validate-write-target!
                  (str write-dir "/.git/config")
                  {:clojure-only? false
                   :write-dir write-dir
                   :force? true
                   :tool-name "file_edit"})
                 :error))))))

(deftest validate-write-parent-dir-missing
  (let [write-dir (temp-dir)]
    (testing "parent dir missing without :create? is rejected"
      (is (= :parent-dir-missing
             (-> (fs/validate-write-target!
                  (str write-dir "/nope/sub/file.clj")
                  {:clojure-only? true
                   :write-dir write-dir
                   :tool-name "clj_edit"})
                 :error))))
    (testing "parent dir missing with :create? is accepted"
      (is (nil? (fs/validate-write-target! (str write-dir "/nope/sub/file.clj")
                                           {:clojure-only? true
                                            :write-dir write-dir
                                            :create? true
                                            :tool-name "clj_edit"}))))))

;; ---- validate-read-target! ----

(deftest validate-read-clojure-only
  (let [src (temp-file ".clj" "(ns foo)")]
    (testing "rejects non-Clojure when clojure-only?"
      (is (= :wrong-file-type
             (-> (fs/validate-read-target! (str src ".py")
                                           {:clojure-only? true
                                            :tool-name "clj_edit"})
                 :error))))
    (testing "accepts existing Clojure file when clojure-only?"
      (is (nil? (fs/validate-read-target! src
                                          {:clojure-only? true
                                           :tool-name "clj_edit"}))))
    (io/delete-file src)))

(deftest validate-read-file-not-found
  (testing "clojure-only? with missing file → file-not-found"
    (is (= :file-not-found
           (-> (fs/validate-read-target! "/no/such/file.clj"
                                         {:clojure-only? true})
               :error))))
  (testing "non-clojure-only with missing file → file-not-found"
    (is (= :file-not-found
           (-> (fs/validate-read-target! "/no/such/file.clj"
                                         {:clojure-only? false})
               :error))))
  (testing "file exists is accepted"
    (let [src (temp-file ".txt" "x")]
      (is (nil? (fs/validate-read-target! src
                                          {:clojure-only? false})))
      (io/delete-file src))))
