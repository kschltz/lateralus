(ns kschltz.agent.tools.file-edit
  "General file editing tool for non-Clojure files.

   Operations:
     - read_file   : read a file (offset/limit optional)
     - write_file  : create or fully overwrite a file
     - edit_file   : find unique old_text, replace with new_text
     - list_dir    : list directory entries
     - show_diff   : compute unified diff without writing

   Per the file-editing-reliability goal: this tool hard-refuses
   Clojure files (routing them to the clj_edit tool). It enforces
   write_dir, blocks sensitive paths, and auto-backups before writes.
   See kschltz.agent.tools.file-safety for the shared scaffolding."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kschltz.agent.tools :as tools]
            [kschltz.agent.tools.diff :as diff]
            [kschltz.agent.tools.file-safety :as fs]
            [malli.core :as m]))

(def ^:private file-edit-write-dir (atom nil))

(defn set-write-dir!
  "Set the global write_dir used by all file_edit operations.
   Pass nil to use cwd default."
  [d]
  (reset! file-edit-write-dir d)
  nil)

(def OpType
  [:enum "read_file" "write_file" "edit_file" "list_dir" "show_diff"])

(def ReadFileParams
  [:map
   [:op [:= "read_file"]]
   [:path [:string {:min 1}]]
   [:offset {:optional true} :int]
   [:limit {:optional true} :int]])

(def WriteFileParams
  [:map
   [:op [:= "write_file"]]
   [:path [:string {:min 1}]]
   [:content [:string {:min 1}]]
   [:force {:optional true} :boolean]
   [:clj-override {:optional true} :boolean]])

(def EditFileParams
  [:map
   [:op [:= "edit_file"]]
   [:path [:string {:min 1}]]
   [:old_text [:string {:min 1}]]
   [:new_text [:string {:min 1}]]
   [:force {:optional true} :boolean]])

(def ListDirParams
  [:map
   [:op [:= "list_dir"]]
   [:path [:string {:min 1}]]])

(def ShowDiffParams
  [:map
   [:op [:= "show_diff"]]
   [:path [:string {:min 1}]]
   [:new_contents {:optional true} [:string {:min 1}]]])

(def FileEditParams
  [:map
   [:op OpType]
   [:path {:optional true} [:string {:min 1}]]
   [:content {:optional true} [:string {:min 1}]]
   [:old_text {:optional true} [:string {:min 1}]]
   [:new_text {:optional true} [:string {:min 1}]]
   [:new_contents {:optional true} [:string {:min 1}]]
   [:offset {:optional true} :int]
   [:limit {:optional true} :int]
   [:force {:optional true} :boolean]
   [:clj-override {:optional true} :boolean]])

;; ---- Helpers ----

(defn- read-file-lines
  "Read a file, optionally offset and limit (1-based line numbers)."
  [path offset limit]
  (let [content (slurp path)
        lines (str/split-lines content)]
    (cond
      (and offset limit) (vec (take limit (drop (dec offset) lines)))
      offset (vec (drop (dec offset) lines))
      limit (vec (take limit lines))
      :else lines)))

(defn- count-occurrences
  "Count occurrences of `needle` in `haystack`."
  [needle haystack]
  (when (and needle haystack)
    (loop [i 0
           found 0]
      (let [idx (.indexOf haystack needle (long i))]
        (if (neg? idx)
          found
          (recur (+ idx (count needle)) (inc found)))))))

;; ---- Op: read_file ----

(defn- op-read-file [{:keys [path offset limit]}]
  (or (fs/validate-read-target! path {:clojure-only? false
                                      :tool-name "file_edit"})
      (let [lines (read-file-lines path offset limit)
            total (count (str/split-lines (slurp path)))]
        {:op "read_file"
         :path path
         :content (str/join "\n" lines)
         :lines-returned (count lines)
         :total-lines total})))

;; ---- Op: list_dir ----

(defn- op-list-dir [{:keys [path]}]
  (or (when-not (.exists (io/file path))
        {:error :file-not-found :path path :op "list_dir"
         :message (str "Directory does not exist: " path)})
      (when-not (.isDirectory (io/file path))
        {:error :not-a-directory :path path :op "list_dir"
         :message (str "Not a directory: " path)})
      (let [entries (->> (file-seq (io/file path))
                         (filter #(and (.isFile %) (not= path (.getPath %))))
                         (mapv (fn [f]
                                 {:name (.getName f)
                                  :path (.getAbsolutePath f)
                                  :size (.length f)
                                  :is-dir false})))]
        {:op "list_dir"
         :path path
         :entries entries
         :count (count entries)})))

;; ---- Op: write_file ----

(defn- op-write-file [{:keys [path content force clj-override]} tool]
  (let [write-dir (or (:write-dir tool) @file-edit-write-dir)
        ;; Hard-refuse Clojure files unless :clj-override is true
        refuse-clj? (and (fs/clojure-file? path) (not clj-override))]
    (or (when refuse-clj?
          {:error :use-clj-edit
           :path path
           :tool "file_edit"
           :use-tool "clj_edit"
           :message "Clojure/EDN files must use the clj_edit tool. Pass :clj-override true to override."})
        (fs/validate-write-target! path
                                   {:clojure-only? false
                                    :tool-name "file_edit"
                                    :force? force
                                    :write-dir write-dir
                                    :create? true})
        (let [_ (fs/make-backup! path)
              parent (.getParentFile (io/file path))
              _ (when (and parent (not (.exists parent)))
                  (.mkdirs parent))
              _ (spit path content)
              bytes-written (count (.getBytes content "UTF-8"))]
          {:op "write_file"
           :status :ok
           :path path
           :bytes-written bytes-written
           :lines-written (count (str/split-lines content))}))))

;; ---- Op: edit_file ----

(defn- op-edit-file [{:keys [path old_text new_text force]} tool]
  (let [write-dir (or (:write-dir tool) @file-edit-write-dir)
        ;; Hard-refuse Clojure files (read is fine, write is not)
        refuse-clj? (and (fs/clojure-file? path) (not force))]
    (or (when refuse-clj?
          {:error :use-clj-edit
           :path path
           :tool "file_edit"
           :use-tool "clj_edit"
           :message "Clojure/EDN files must use the clj_edit tool. Pass :force true to override."})
        (fs/validate-write-target! path
                                   {:clojure-only? false
                                    :tool-name "file_edit"
                                    :force? force
                                    :write-dir write-dir})
        (let [content (slurp path)
              n (count-occurrences old_text content)]
          (cond
            (zero? n)
            {:op "edit_file"
             :error :ambiguous-match
             :occurrences 0
             :old-text-preview (subs old_text 0 (min 80 (count old_text)))
             :suggestion "old_text not found in file"}

            (> n 1)
            {:op "edit_file"
             :error :ambiguous-match
             :occurrences n
             :old-text-preview (subs old_text 0 (min 80 (count old_text)))
             :suggestion (str n " matches; provide more context")}

            :else
            (let [_ (fs/make-backup! path)
                  new-content (str/replace-first content old_text new_text)
                  _ (spit path new-content)]
              {:op "edit_file"
               :status :ok
               :path path
               :replaced 1
               :old-length (count old_text)
               :new-length (count new_text)}))))))

;; ---- Op: show_diff ----

(defn- op-show-diff [{:keys [path new_contents]}]
  (or (fs/validate-read-target! path {:clojure-only? false
                                      :tool-name "file_edit"})
      (let [old-content (slurp path)
            old-lines (str/split-lines old-content)
            new-lines (str/split-lines new_contents)
            diff-text (diff/unified-diff old-lines new-lines)
            stats (diff/diff-stats old-lines new-lines)]
        {:op "show_diff"
         :path path
         :diff diff-text
         :additions (:additions stats)
         :deletions (:deletions stats)
         :old-length (count old-content)
         :new-length (count new_contents)})))

;; ---- Tool Registration ----

(defn file-edit-tool
  "Create a :file-edit tool for general file operations.
   Operations: read_file, write_file, edit_file, list_dir, show_diff.
   Hard-refuses Clojure files (use clj_edit for those)."
  ([] (file-edit-tool {}))
  ([opts]
   {:type        :file-edit
    :name        (or (:name opts) "file_edit")
    :description (str "General file editing tool for non-Clojure files. "
                      "Operations: read_file, write_file, edit_file, list_dir, show_diff. "
                      "For Clojure/EDN files (.clj/.cljs/.cljc/.edn), use clj_edit instead — "
                      "this tool hard-refuses them with a structured error. "
                      "Respects write_dir (default: $cwd) and blocks writes to .git/, target/, "
                      "node_modules/, etc. Auto-backups before every write; use restore! to revert.")
    :parameters  FileEditParams
    :write-dir   (or (:write-dir opts) (System/getProperty "user.dir"))}))

(defmethod tools/run :file-edit
  [tool args]
  (let [raw     (cond (map? args) args
                      (string? args) (try (clojure.edn/read-string args)
                                          (catch Exception _ {:op "unknown"}))
                      :else {})
        decoded (or (tools/coerce-args tool (if (map? args) (pr-str args) (str args)))
                    raw)
        {:keys [op path content old_text new_text new_contents
                offset limit force clj-override]} (if (map? decoded) decoded raw)]
    (case op
      "read_file"  (pr-str (op-read-file {:path path :offset offset :limit limit}))
      "write_file" (pr-str (op-write-file {:path path :content content
                                           :force force :clj-override clj-override}
                                          tool))
      "edit_file"  (pr-str (op-edit-file {:path path :old_text old_text
                                          :new_text new_text :force force}
                                         tool))
      "list_dir"   (pr-str (op-list-dir {:path path}))
      "show_diff"  (pr-str (op-show-diff {:path path :new_contents new_contents}))
      (pr-str {:error (str "Unknown operation: " op)}))))

(defmethod tools/parse :file-edit
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))
