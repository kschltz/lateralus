(ns kschltz.agent.tools.file-edit-test
  "Unit tests for the general file_edit tool."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tools]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.file-edit]
            [kschltz.agent.tools.file-edit :as fe]
            [kschltz.agent.tools.file-safety :as fs]))

(def test-dir (System/getProperty "java.io.tmpdir"))

;; Configure file_edit to allow writes under /tmp
(use-fixtures :each
  (fn [f]
    (fe/set-write-dir! test-dir)
    (f)))

(defn- temp-file
  "Create a temp file.
     (temp-file content)            →  prefix=fe-test, ext=.txt
     (temp-file ext content)        →  prefix=fe-test
     (temp-file prefix ext content)"
  ([content] (temp-file "fe-test" ".txt" content))
  ([ext content] (temp-file "fe-test" ext content))
  ([prefix ext content]
   (let [f (java.io.File/createTempFile prefix ext)]
     (when content (spit f content))
     (.getAbsolutePath f))))

(defn- cleanup! [path]
  (when path (try (io/delete-file path) (catch Exception _))))

;; ---- read_file ----

(deftest read-file-happy-path
  (let [f (temp-file "line1\nline2\nline3")]
    (let [r (tools/parse (fe/file-edit-tool {:write-dir test-dir}) (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "read_file" :path f}))]
      (is (= "read_file" (:op r)))
      (is (re-find #"line1" (:content r)))
      (is (re-find #"line2" (:content r)))
      (is (re-find #"line3" (:content r)))
      (is (= 3 (:total-lines r))))
    (cleanup! f)))

(deftest read-file-with-offset-and-limit
  (let [f (temp-file (str/join "\n" (map #(str "line" %) (range 1 11))))
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "read_file" :path f
                                                                             :offset 3 :limit 2}))]
    (is (re-find #"line3" (:content r)))
    (is (re-find #"line4" (:content r)))
    (is (not (re-find #"line5" (:content r))))
    (cleanup! f)))

(deftest read-file-missing-file
  (let [r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir})
                                  {:op "read_file" :path "/no/such/file"}))]
    (is (= :file-not-found (:error r)))))

;; ---- list_dir ----

(deftest list-dir-happy-path
  (let [d (str test-dir "/fe-list-test")
        _ (.mkdirs (io/file d))
        _ (spit (str d "/a.txt") "a")
        _ (spit (str d "/b.txt") "bb")
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "list_dir" :path d}))]
    (is (= "list_dir" (:op r)))
    (is (= 2 (:count r)))
    (is (= #{"a.txt" "b.txt"} (set (map :name (:entries r)))))
    (cleanup! (str d "/a.txt"))
    (cleanup! (str d "/b.txt"))
    (.delete (io/file d)))

  (testing "non-existent dir is an error"
    (let [r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "list_dir" :path "/no/such/dir"}))]
      (is (= :file-not-found (:error r))))))

;; ---- write_file ----

(deftest list-dir-not-recursive
  (testing "P0-2: list_dir returns only IMMEDIATE children, not subdirectory contents"
    (let [d (str test-dir "/fe-list-not-recursive")
          sub (str d "/sub")
          _ (.mkdirs (io/file d))
          _ (.mkdirs (io/file sub))
          _ (spit (str d "/top.txt") "t")
          _ (spit (str sub "/nested.txt") "n")
          r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir})
                                    {:op "list_dir" :path d}))]
      (is (= 2 (:count r)) "returns 2 entries (top.txt + sub/)")
      (is (= #{"top.txt" "sub"} (set (map :name (:entries r))))
          "nested.txt does NOT appear (it's inside sub/)")
      (is (some #(and (:is-dir %) (= "sub" (:name %))) (:entries r))
          "the 'sub' directory IS included as a directory entry")
      (cleanup! (str d "/top.txt"))
      (cleanup! (str sub "/nested.txt"))
      (.delete (io/file sub))
      (.delete (io/file d))))) (deftest write-file-creates-new
                                 (let [f (str test-dir "/fe-write-test.txt")
                                       _ (cleanup! f)
                                       r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                                                      (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "write_file" :path f
                                                                                                            :content "hello\nworld"}))]
                                   (is (= :ok (:status r)))
                                   (is (= 11 (:bytes-written r)))
                                   (is (= "hello\nworld" (slurp f)))
                                   (cleanup! f)))

(deftest write-file-overwrites
  (let [f (temp-file "old content")
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "write_file" :path f
                                                                             :content "new"}))]
    (is (= :ok (:status r)))
    (is (= "new" (slurp f)))
    (cleanup! f)))

(deftest write-file-creates-backup
  (let [f (temp-file "original")
        _ (Thread/sleep 2)
        _ (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "write_file" :path f
                                                                :content "updated"})
        backups (fs/list-backups f)]
    (is (seq backups) "at least one backup exists")
    (cleanup! f)
    (doseq [b backups] (cleanup! b))))

(deftest write-file-refuses-clojure
  (let [f (str test-dir "/fe-clj-test.clj")
        _ (cleanup! f)
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "write_file" :path f
                                                                             :content "(ns foo)"}))]
    (is (= :use-clj-edit (:error r)))
    (is (= "clj_edit" (:use-tool r)))
    (is (not (.exists (io/file f))) "file is NOT created on refusal")
    (cleanup! f)))

(deftest write-file-allows-clj-override
  (let [f (str test-dir "/fe-clj-override.clj")
        _ (cleanup! f)
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "write_file" :path f
                                                                             :content "(ns foo)"
                                                                             :clj-override true}))]
    (is (= :ok (:status r)))
    (is (.exists (io/file f)))
    (cleanup! f)))

;; ---- edit_file ----

(deftest edit-file-replaces-unique-match
  (let [f (temp-file "hello world\nfoo bar\nbaz qux")
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "edit_file" :path f
                                                                             :old_text "foo bar"
                                                                             :new_text "FOO BAR"}))]
    (is (= :ok (:status r)))
    (is (re-find #"FOO BAR" (slurp f)))
    (cleanup! f)))

(deftest edit-file-rejects-no-match
  (testing "P2-6: 0 matches returns :no-match (NOT :ambiguous-match)"
    (let [f (temp-file "hello world")
          r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "edit_file" :path f
                                                                               :old_text "nonexistent"
                                                                               :new_text "x"}))]
      (is (= :no-match (:error r)))
      (is (= 0 (:occurrences r)))
      (is (string? (:suggestion r)))
      (cleanup! f))))

(deftest edit-file-rejects-ambiguous-multiple-matches
  (let [f (temp-file "abc\nabc\nabc")
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "edit_file" :path f
                                                                             :old_text "abc"
                                                                             :new_text "x"}))]
    (is (= :ambiguous-match (:error r)))
    (is (= 3 (:occurrences r)))
    (is (string? (:suggestion r)))
    (cleanup! f)))

(deftest edit-file-creates-backup
  (let [f (temp-file "original content")
        _ (Thread/sleep 2)
        _ (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "edit_file" :path f
                                                                :old_text "content" :new_text "CONTENT"})
        backups (fs/list-backups f)]
    (is (seq backups))
    (cleanup! f)
    (doseq [b backups] (cleanup! b))))

;; ---- show_diff ----

(deftest show-diff-no-write
  (let [f (temp-file "line1\nline2")
        r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                       (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "show_diff" :path f
                                                                             :new_contents "line1\nLINE2"}))]
    (is (string? (:diff r)))
    (is (re-find #"LINE2" (:diff r)))
    ;; File on disk is unchanged
    (is (= "line1\nline2" (slurp f)))
    (cleanup! f)))

;; ---- Routing: file_edit refuses .clj ----

(deftest file-edit-read-clj-allowed
  (testing "read_file CAN read Clojure files (only writes are gated)"
    (let [f (temp-file ".clj" "(ns foo)")]
      (let [r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                           (tools/run (fe/file-edit-tool {:write-dir test-dir}) {:op "read_file" :path f}))]
        (is (= "read_file" (:op r))))
      (cleanup! f))))

;; ---- Tool definition sanity ----

;; ---- restore_file / list_backups ----

(deftest restore-file-restores-from-backup
  (testing "restore_file reverts a file to its most recent backup"
    (let [f (temp-file "v1")
          _ (Thread/sleep 5)
          b (kschltz.agent.tools.file-safety/make-backup! f) ; "v1" backup
          _ (spit f "v2")
          r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir})
                                    {:op "restore_file" :path f}))]
      (is (= :ok (:status r)))
      (is (= "v1" (slurp f)) "file restored to v1 content")
      (cleanup! f)
      (cleanup! b))))

(deftest restore-file-no-backup
  (testing "restore_file on a file with no backup returns :no-backup"
    (let [f (temp-file "x")
          r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir})
                                    {:op "restore_file" :path f}))]
      (is (= :no-backup (:error r)))
      (cleanup! f))))

(deftest list-backups-returns-sorted
  (testing "list_backups returns backup paths newest-first"
    (let [f (temp-file "v1")
          _ (Thread/sleep 5)
          b1 (kschltz.agent.tools.file-safety/make-backup! f)
          _ (Thread/sleep 5)
          b2 (kschltz.agent.tools.file-safety/make-backup! f)
          r (tools/parse (fe/file-edit-tool {:write-dir test-dir})
                         (tools/run (fe/file-edit-tool {:write-dir test-dir})
                                    {:op "list_backups" :path f}))]
      (is (= 2 (:count r)))
      (is (= b2 (first (:backups r))) "newest first")
      (is (= b1 (second (:backups r))))
      (cleanup! f)
      (doseq [b [b1 b2]] (cleanup! b)))))

(deftest file-edit-tool-metadata
  (testing "tool has the right type, name, and parameters"
    (let [tool (fe/file-edit-tool {:write-dir test-dir})]
      (is (= :file-edit (:type tool)))
      (is (= "file_edit" (:name tool)))
      (is (string? (:description tool)))
      (is (re-find #"non-Clojure" (:description tool))
          "description mentions non-Clojure files")
      (is (re-find #"clj_edit" (:description tool))
          "description points to clj_edit"))))
