(ns kschltz.agent.tools.file-safety
  "Shared safety scaffolding for file-editing tools.

   Both `clj_edit` (rewrite-clj-powered Clojure editing) and the new
   `file_edit` (general file tool) use these helpers to enforce:
     - Clojure-file detection (for routing between the two tools)
     - write_dir containment (writes only under the allowed root)
     - blocked-paths list (refuse to touch .git, target/, etc.)
     - per-write auto-backup with restore

   All functions are pure (no I/O side effects except backup/restore
   which are explicitly named `!`). The two file tools compose these
   checks; the two tools NEVER bypass them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---- File-type detection ----

(def clojure-extensions
  "Set of file extensions that identify a Clojure-family source file."
  #{".clj" ".cljs" ".cljc" ".edn"})

(defn clojure-file?
  "True when `path` ends in a Clojure-family extension (.clj, .cljs,
   .cljc, .edn). Case-sensitive on purpose — Clojure tooling expects
   lowercase extensions and we don't want to silently route a file
   the LLM named .CLJ to the wrong tool."
  [path]
  (boolean
   (some #(str/ends-with? path %)
         clojure-extensions)))

(defn file-extension
  "Return the lowercase extension of `path` (including the leading dot),
   or nil if there is none. Hidden files like .gitignore have no
   extension (the dot is part of the basename, not a separator)."
  [path]
  (let [basename (if (>= (.lastIndexOf path "/") 0)
                   (subs path (inc (.lastIndexOf path "/")))
                   path)
        dot-idx (.lastIndexOf basename ".")]
    (when (and (>= dot-idx 0)
               ;; Not a hidden file: dot must NOT be at position 0
               (not (zero? dot-idx)))
      (str/lower-case (subs basename dot-idx)))))

;; ---- write_dir containment ----

(defn- canonical
  "Canonical path string. Falls back to absolute path if getCanonicalPath
   throws (which it does for non-existent paths on some platforms)."
  [p]
  (try (.getCanonicalPath (io/file p))
       (catch Exception _
         (.getAbsolutePath (io/file p)))))

(defn within-write-dir?
  "True when `path` is at or under `write-dir` (default: project cwd).
   Both paths are canonicalized before comparison so symlinks and
   `..` segments are normalized."
  ([path] (within-write-dir? path nil))
  ([path write-dir]
   (let [write-dir (or write-dir (System/getProperty "user.dir"))
         p (canonical path)
         d (canonical write-dir)]
     (or (str/starts-with? p d)
         ;; Allow exact match (starts-with of d by d)
         (= p d)))))

;; ---- Blocked-paths list ----

(def default-blocked-paths
  "Default set of path segments (directory names) that are always
   blocked from writes, even with :force true. These are well-known
   build/dependency/version-control directories where writes would
   corrupt state or trigger expensive no-op rebuilds."
  #{".git"
    "target"
    "node_modules"
    ".clojure-mcp-light"
    ".mvn"
    "dist"
    "build"
    ".idea"
    ".vscode"})

(defn blocked-path?
  "True when any segment of `path` is in `blocked-set` (default:
   `default-blocked-paths`). The check is on directory *names*, not
   full paths, so a project named `target-thing` is NOT blocked but
   `<project>/target/foo` IS."
  ([path] (blocked-path? path default-blocked-paths))
  ([path blocked-set]
   (boolean
    (some (fn [seg] (contains? blocked-set seg))
          (str/split (canonical path) #"/")))))

;; ---- Backup / restore ----

(defn backup-suffix
  "The suffix used for backup files: `.bak.<unix-ms>`."
  []
  (str ".bak." (System/currentTimeMillis)))

(defn make-backup!
  "Create a backup of the file at `path` if it exists. Returns the
   backup path (string) or nil if the source file didn't exist.
   The backup lives in the same directory as the source file."
  [path]
  (let [src (io/file path)]
    (when (.exists src)
      (let [backup-path (str path (backup-suffix))
            backup (io/file backup-path)]
        (.mkdirs (.getParentFile backup))
        (io/copy src backup)
        backup-path))))

(defn list-backups
  "Return the list of backup paths for `path`, sorted by unix-ms
   suffix DESCENDING (newest first). The unix-ms is extracted from
   the `.bak.<digits>` suffix and sorted numerically — a plain string
   sort would put `.bak.9` after `.bak.10` lexically."
  [path]
  (let [dir (.getAbsolutePath (io/file (.getParent (io/file path))))
        base (.getName (io/file path))
        pattern (re-pattern (str "^" (java.util.regex.Pattern/quote base)
                                 "\\.bak\\.(\\d+)$"))
        candidates (when (.exists (io/file dir))
                     (->> (file-seq (io/file dir))
                          (filter #(.isFile %))
                          (keep (fn [f]
                                  (when-let [[_ ms] (re-find pattern (.getName f))]
                                    [(Long/parseLong ms) (.getAbsolutePath f)])))))]
    (vec (map second (sort (fn [[a _] [b _]] (compare b a)) candidates)))))

(defn restore!
  "Revert the file at `path` from its most recent backup. Returns the
   restored path, or nil if no backup exists. The backup file is
   removed after a successful restore."
  [path]
  (when-let [backups (seq (list-backups path))]
    (let [latest (first backups)
          src (io/file path)
          backup (io/file latest)]
      (io/copy backup src)
      (.delete backup)
      latest)))

;; ---- Combined validation ----

(defn validate-write-target!
  "Combined validation for any write operation. Returns nil on success
   or a structured `{:error ...}` map on failure.

   Checks (in order, first failure short-circuits):
     1. `clojure-only?` — if true, refuse non-Clojure files
     2. `path` is under `write-dir` (when `force?` is false)
     3. `path` is not in the blocked-paths list
     4. The parent directory exists or can be created (for create ops)

   `opts` keys:
     :clojure-only?   — bool, default false
     :force?          — bool, default false (bypasses write_dir check)
     :write-dir       — string, default $cwd
     :blocked-set     — set, default default-blocked-paths
     :create?         — bool, default false (allows non-existent parents)
     :tool-name       — string, used in error messages
     :use-tool        — string, included in error when clojure-only? fails"
  [path {:keys [clojure-only? force? write-dir blocked-set create?
                tool-name use-tool]
         :or   {clojure-only? false
                force? false
                write-dir (System/getProperty "user.dir")
                blocked-set default-blocked-paths
                create? false
                tool-name "file_edit"}}]
  (cond
    ;; 1. Clojure-only check (for clj_edit)
    (and clojure-only? (not (clojure-file? path)))
    {:error :wrong-file-type
     :path path
     :extension (file-extension path)
     :tool tool-name
     :use-tool use-tool
     :message (str (or use-tool "the other file tool")
                   " should be used for non-Clojure files")}

    ;; 2. Write-dir containment (unless forced)
    (and (not force?) (not (within-write-dir? path write-dir)))
    {:error :outside-write-dir
     :path path
     :write-dir write-dir
     :tool tool-name
     :message (str "Path is outside write-dir. Pass :force true to override.")}

    ;; 3. Blocked-path check (even :force does not bypass)
    (blocked-path? path blocked-set)
    {:error :blocked-path
     :path path
     :tool tool-name
     :message (str "Path matches a blocked directory. Refusing to write.")}

    ;; 4. Parent directory must exist (unless creating)
    (let [parent (.getParentFile (io/file path))]
      (and parent
           (not (.exists parent))
           (not create?)))
    {:error :parent-dir-missing
     :path path
     :parent (.getAbsolutePath (.getParentFile (io/file path)))
     :tool tool-name
     :message (str "Parent directory does not exist. Pass :create true to create it.")}

    :else nil))

(defn validate-read-target!
  "Read operations are less restrictive: no write-dir, no blocked-path,
   no force required. Only the clojure-only check applies (for clj_edit's
   read-structure / find-form on non-Clojure files). Returns nil on
   success or a structured `{:error ...}` map."
  [path {:keys [clojure-only? tool-name use-tool]
         :or   {clojure-only? false
                tool-name "file_edit"}}]
  (cond
    (and clojure-only? (not (clojure-file? path)))
    {:error :wrong-file-type
     :path path
     :extension (file-extension path)
     :tool tool-name
     :use-tool use-tool
     :message (str (or use-tool "the other file tool")
                   " should be used for non-Clojure files")}

    (not (.exists (io/file path)))
    {:error :file-not-found
     :path path
     :tool tool-name
     :message "File does not exist."}

    :else nil))
