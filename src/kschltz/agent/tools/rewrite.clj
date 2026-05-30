(ns kschltz.agent.tools.rewrite
  "Source editing tool — rewrite-clj powered structural Clojure/EDN editing.
   The LLM describes WHAT to change; rewrite-clj does the structural edit,
   preserving comments and whitespace."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [kschltz.agent.tools :as tools]
            [malli.core :as m]))

(def OpType
  [:enum "read-structure" "find-form" "replace-form"
   "insert-form" "add-require" "remove-form"])

(def RequireEntry
  [:map
   [:lib :string]
   [:as {:optional true} :string]
   [:refer {:optional true} [:vector :string]]])

(def CljEditParams
  [:map
   [:op OpType]
   [:path [:string {:min 1}]]
   [:name {:optional true} [:string {:min 1}]]
   [:source {:optional true} [:string {:min 1}]]
   [:require-entry {:optional true} RequireEntry]])

;; ---- Helpers ----

(defn- clj-ext? [path]
  (some #(str/ends-with? path %) [".clj" ".cljs" ".cljc" ".edn"]))

(defn- read-file [path]
  (let [f (io/file path)]
    (when (.canRead f) (slurp f))))

(defn- writable? [path write-dir]
  (if write-dir
    (str/starts-with? (.getCanonicalPath (io/file path))
                      (.getCanonicalPath (io/file write-dir)))
    true))

(defn- validate-write! [path write-dir]
  (cond
    (not (writable? path write-dir))
    {:error (str "Path outside write directory: " write-dir)}
    (not (clj-ext? path))
    {:error "Path must be a .clj, .cljs, .cljc, or .edn file"}
    :else nil))

(defn- validate-parse! [source]
  (edamame/parse-string-all source {:all true :read-cond :allow})
  source)

(defn- write-validated! [path result-str]
  (try
    (validate-parse! result-str)
    (spit path result-str)
    {:status "ok" :lines-changed (count (str/split-lines result-str))}
    (catch Exception e
      {:error (str "Result would not parse: " (.getMessage e))})))

(defn- require-entry-str [{:keys [lib as refer]}]
  (str "[" lib
       (when as (str " :as " as))
       (when (seq refer) (str " :refer [" (str/join " " refer) "]"))
       "]"))

;; ---- Parsing ----

(defn- parse-forms [source]
  (let [forms (edamame/parse-string-all source
                                        {:all true :read-cond :allow :auto-resolve name})]
    (vec
     (for [form forms]
       (let [form-type (cond (list? form) (str (first form)
                                    (vector? form) "vector"
                                    (map? form) "map"
                                    (set? form) "set"
                                    :else (pr-str (type form))))
             form-name (when (list? form)
                         (let [fst (first form)]
                           (when (symbol? fst)
                             (let [nm (second form)]
                               (when (or (symbol? nm) (string? nm))
                                 (str nm))))))]
         {:type form-type :name form-name})))))

(defn- form-index [path]
  (let [source (read-file path)]
    (if-not source
      {:op "read-structure" :path path :error "File not found or not readable"}
      (try
        (let [forms (parse-forms source)]
          {:op "read-structure" :path path :forms forms :total (count forms)})
        (catch Exception e
          {:op "read-structure" :path path :error (.getMessage e)})))))

;; ---- Zipper Navigation ----

(defn- find-form-by-name [zloc name-sym]
  (when-let [found (z/find-value zloc z/next name-sym)]
    (let [parent (z/up found)]
      (when parent
        (let [parent-sexpr (try (z/sexpr parent) (catch Exception _ nil))]
          (when (and (list? parent-sexpr)
                     (some #{(first parent-sexpr)}
                           '(defn defn- def defonce defmacro
                              defmethod defmulti defrecord
                              defprotocol deftype ns definterface)))
            parent))))))

(defn- find-any-named-form [zloc name-str]
  (let [name-sym (symbol name-str)]
    (or (find-form-by-name zloc name-sym)
        (loop [loc (z/next zloc)]
          (when-not (z/end? loc)
            (let [node (z/node loc)]
              (if (n/whitespace? node)
                (recur (z/next loc))
                (let [sexpr (try (z/sexpr loc) (catch Exception _ nil))]
                  (if (and (list? sexpr) (= name-sym (second sexpr)))
                    (z/up loc)
                    (recur (z/next loc)))))))))))

;; ---- Operations ----

(defn- op-read-structure [path] (form-index path))

(defn- op-find-form [path name]
  (let [source (read-file path)]
    (if-not source
      {:op "find-form" :path path :name name :error "File not found"}
      (try
        (let [zloc (z/of-string source)
              form-loc (find-any-named-form zloc name)]
          (if form-loc
            {:op "find-form" :path path :name name
             :source (z/string form-loc)
             :line (-> (z/node form-loc) meta :row)}
            {:op "find-form" :path path :name name
             :error (str "Form '" name "' not found")}))
        (catch Exception e
          {:op "find-form" :path path :name name :error (.getMessage e)})))))

(defn- op-replace-form [path name new-source write-dir]
  (or (validate-write! path write-dir)
      (let [source (read-file path)]
        (if-not source
          {:op "replace-form" :path path :name name :error "File not found"}
          (try
            (let [zloc (z/of-string source)
                  form-loc (find-any-named-form zloc name)]
              (if-not form-loc
                {:op "replace-form" :path path :name name
                 :error (str "Form '" name "' not found")}
                (let [new-node (z/of-string new-source)
                      replaced (z/replace form-loc (z/node new-node))
                      result (z/root-string replaced)
                      outcome (write-validated! path result)]
                  (merge {:op "replace-form" :path path :name name} outcome))))
            (catch Exception e
              {:op "replace-form" :path path :name name :error (.getMessage e)}))))))

(defn- op-insert-form [path after-name new-source write-dir]
  (or (validate-write! path write-dir)
      (let [source (read-file path)]
        (if-not source
          {:op "insert-form" :path path :name after-name :error "File not found"}
          (try
            (let [zloc (z/of-string source)
                  form-loc (find-any-named-form zloc after-name)]
              (if-not form-loc
                {:op "insert-form" :path path :name after-name
                 :error (str "Form '" after-name "' not found")}
                (let [new-node (z/node (z/of-string (str "\n\n" new-source)))
                      inserted (z/insert-right form-loc new-node)
                      result (z/root-string inserted)
                      outcome (write-validated! path result)]
                  (merge {:op "insert-form" :path path :name after-name} outcome))))
            (catch Exception e
              {:op "insert-form" :path path :name after-name :error (.getMessage e)}))))))

(defn- op-remove-form [path name write-dir]
  (or (validate-write! path write-dir)
      (let [source (read-file path)]
        (if-not source
          {:op "remove-form" :path path :name name :error "File not found"}
          (try
            (let [zloc (z/of-string source)
                  form-loc (find-any-named-form zloc name)]
              (if-not form-loc
                {:op "remove-form" :path path :name name
                 :error (str "Form '" name "' not found")}
                (let [removed (z/remove form-loc)
                      result (str/trimr (z/root-string removed))]
                  (spit path result)
                  {:op "remove-form" :path path :name name :status "ok"})))
            (catch Exception e
              {:op "remove-form" :path path :name name :error (.getMessage e)}))))))

(defn- op-add-require [path require-entry write-dir]
  (or (validate-write! path write-dir)
      (let [source (read-file path)
            {:keys [lib]} require-entry
            entry-sym (require-entry-str require-entry)]
        (if-not source
          {:op "add-require" :path path :lib lib :error "File not found"}
          (if (str/includes? source lib)
            {:op "add-require" :path path :lib lib :status "already-present"}
            (try
              (let [zloc (z/of-string source)
                    require-loc (z/find-value zloc z/next :require)]
                (if-not require-loc
                  (let [ns-loc (z/find-value zloc z/next 'ns)]
                    (if-not ns-loc
                      {:op "add-require" :path path :lib lib :error "No ns form found"}
                      (let [ns-name-loc (z/right ns-loc)
                            req-str (str "(:require " entry-sym ")")
                            new-req (z/of-string req-str)
                            inserted (z/insert-right ns-name-loc (z/node new-req))
                            result (z/root-string inserted)
                            outcome (write-validated! path result)]
                        (merge {:op "add-require" :path path :lib lib} outcome))))
                  (let [req-next (z/right require-loc)]
                    (if (and req-next (= :vector (n/tag (z/node req-next))))
                      (let [entry-node (z/node (z/of-string entry-sym))
                            inserted (z/append-child req-next entry-node)
                            result (z/root-string inserted)
                            outcome (write-validated! path result)]
                        (merge {:op "add-require" :path path :lib lib} outcome))
                      (let [result (str/replace source
                                                #"(:require\s*\[)"
                                                (str "(:require [" entry-sym "\n               "))
                            outcome (write-validated! path result)]
                        (merge {:op "add-require" :path path :lib lib} outcome))))))
              (catch Exception e
                {:op "add-require" :path path :lib (:lib require-entry)
                 :error (.getMessage e)})))))))

;; ---- Tool Registration ----

(defn clj-edit-tool
  "Create a :clj-edit tool for structured Clojure/EDN source editing.
   Operations: read-structure, find-form, replace-form, insert-form, add-require, remove-form."
  ([] (clj-edit-tool {}))
  ([opts]
   {:type        :clj-edit
    :name        (or (:name opts) "clj_edit")
    :description "Structured Clojure/EDN source editing. Read, find, replace, insert, or remove top-level forms. Preserves comments and formatting."
    :parameters  CljEditParams
    :write-dir   (or (:write-dir opts) (System/getProperty "user.dir"))}))

(defmethod tools/run :clj-edit
  [tool args]
  (let [raw     (cond (map? args) args
                      (string? args) (try (clojure.edn/read-string args)
                                          (catch Exception _ {:op "unknown"}))
                      :else {})
        decoded (or (tools/coerce-args tool (if (map? args) (pr-str args) (str args)))
                    raw)
        {:keys [op path name source require-entry]} (if (map? decoded) decoded raw)
        write-dir (:write-dir tool)]
    (case op
      "read-structure" (pr-str (op-read-structure path))
      "find-form"      (pr-str (op-find-form path name))
      "replace-form"   (pr-str (op-replace-form path name source write-dir))
      "insert-form"    (pr-str (op-insert-form path name source write-dir))
      "add-require"    (pr-str (op-add-require path require-entry write-dir))
      "remove-form"    (pr-str (op-remove-form path name write-dir))
      (pr-str {:error (str "Unknown operation: " op)}))))

(defmethod tools/parse :clj-edit
  [_ response]
  (try
    (clojure.edn/read-string response)
    (catch Exception _ response)))
