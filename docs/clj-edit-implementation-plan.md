# Implementation Plan: `clj-edit` Tool for Lateralus

## Overview

Add a new `kschltz.agent.tools.rewrite` tool that uses [rewrite-clj](https://github.com/clj-commons/rewrite-clj) to provide **safe, structured Clojure/EDN file editing** — the LLM describes *what* to change, rewrite-clj performs the structural edit without evaluating code.

## Why This, Why Now

| Current approach | Problem |
|---|---|
| REPL eval tool | Runs arbitrary code — high risk, no preservation of comments/formatting |
| String-based `(spit ...)` | Destroys formatting, no structural awareness |
| LLM guesses line edits | Fragile — breaks on moved forms, nested parens |

rewrite-clj solves all three: it parses source into a whitespace-preserving AST zipper, navigates by S-expression, and writes back clean text.

---

## Dependency

```clojure
;; deps.edn — add to :deps
rewrite-clj/rewrite-clj {:mvn/version "1.2.54"}
```

Pure Clojure, ~500KB, no native deps. Already uses edamame internally (no duplication — we already depend on it).

---

## Operations

The tool exposes 6 operations, dispatched via an `:op` parameter:

| Operation | ACL | Description |
|---|---|---|
| `read-structure` | read | Parse file, return top-level form index (names, types, line ranges) |
| `find-form` | read | Find a top-level form by name, return its source text |
| `replace-form` | write | Replace a top-level form's body with new source |
| `insert-form` | write | Insert a new top-level form after a target form |
| `add-require` | write | Add a `require` entry to the `ns` form |
| `remove-form` | write | Remove a top-level form entirely |

**All write operations** validate the result parses as valid Clojure before writing (rewrite-clj round-trip check). If validation fails, the write is rejected and the error is returned to the LLM.

---

## File Organization

```
src/kschltz/agent/tools/
├── rewrite.clj          ← NEW: main tool namespace
├── portal.clj           ← existing
├── remember.clj          ← existing
├── repl.clj              ← existing
└── web.clj               ← existing

test/kschltz/agent/tools/
├── rewrite_test.clj      ← NEW: unit tests
├── portal_test.clj       ← existing
└── ...
```

---

## Detailed Design

### 1. Namespace: `kschltz.agent.tools.rewrite`

```clojure
(ns kschltz.agent.tools.rewrite
  "Source editing tool — rewrite-clj powered structural Clojure/EDN editing.
   Safe alternative to REPL eval for file modifications.
   The LLM describes WHAT to change; rewrite-clj does the structural edit."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [clojure.string :as str]
            [kschltz.agent.tools :as tools]
            [malli.core :as m]))
```

### 2. Malli Schemas

```clojure
(def FilePath
  "Absolute or relative path to a .clj/.cljs/.cljc/.edn file."
  [:string {:min 1}])

(def FormName
  "Name of a top-level form (symbol or string)."
  [:string {:min 1}])

(def ClojureSource
  "Valid Clojure source text."
  [:string {:min 1}])

(def OpType
  "Operation the tool can perform."
  [:enum "read-structure" "find-form" "replace-form"
         "insert-form" "add-require" "remove-form"])

(def CljEditParams
  [:map
   [:op OpType]
   [:path FilePath]
   [:name {:optional true} FormName]        ;; for find/replace/remove/insert-after
   [:source {:optional true} ClojureSource]  ;; for replace-form/insert-form
   [:require-entry {:optional true}          ;; for add-require
    [:map
     [:lib :string]
     [:as {:optional true} :string]
     [:refer {:optional true} [:vector :string]]]]])
```

### 3. Core Operations

#### `read-structure` — Survey a file

```clojure
(defn- op-read-structure
  "Parse file, return index of top-level forms with names, types, and positions."
  [path]
  (let [content (slurp path)
        zloc    (z/of-string content)]
    (loop [loc zloc forms []]
      (if (z/end? loc)
        {:op "read-structure"
         :path path
         :forms forms
         :total (count forms)}
        (let [node (z/node loc)
              sexpr (try (z/sexpr loc) (catch Exception _ ::unparseable))
              form  {:type    (cond (list? sexpr) (str (first sexpr))
                                    :else           (pr-str (type sexpr)))
                     :name    (when (list? sexpr)
                               (str (second sexpr)))  ;; defn name, def name, etc.
                     :line    (-> node meta :row)
                     :end-line nil}]  ;; compute from node span
              next-loc (z/right loc)]
          (recur next-loc (conj forms form)))))))
```

Returns:
```edn
{:op "read-structure"
 :path "src/kschltz/agent/core.clj"
 :forms [{:type "ns"   :name "kschltz.agent.core" :line 1}
         {:type "defn" :name "make-agent"          :line 176}
         {:type "defn" :name "register-tool!"       :line 462}]
 :total 3}
```

#### `find-form` — Locate a specific form

```clojure
(defn- op-find-form
  "Find a top-level form by name, return its source text and location."
  [path name]
  (let [content (slurp path)
        zloc    (z/of-string content)]
    (loop [loc (z/down zloc)]
      (when loc
        (let [sexpr (try (z/sexpr loc) (catch Exception _ nil))]
          (if (and (list? sexpr)
                   (= (str (second sexpr)) name))
            {:op "find-form"
             :path path
             :name name
             :source (z/string loc)
             :line (-> (z/node loc) meta :row)}
            (recur (z/right loc))))))))
```

#### `replace-form` — Replace a form's body

```clojure
(defn- op-replace-form
  "Replace a top-level form identified by name with new source.
   Validates that the result parses before writing."
  [path name new-source]
  (let [content (slurp path)
        zloc    (z/of-string content)]
    (loop [loc (z/down zloc) prev zloc]
      (when loc
        (let [sexpr (try (z/sexpr loc) (catch Exception _ nil))]
          (if (and (list? sexpr)
                   (= (str (second sexpr)) name))
            (let [new-node (z/of-string new-source)
                  replaced (z/replace loc (z/node new-node))
                  result   (z/root-string replaced)]
              ;; Round-trip validation: the result must parse
              (try
                (z/of-string result)
                (spit path result)
                {:op "replace-form"
                 :path path
                 :name name
                 :status "ok"
                 :lines-changed (count (str/split-lines result))}
                (catch Exception e
                  {:op "replace-form"
                   :path path
                   :name name
                   :status "error"
                   :error (str "Result would not parse: " (.getMessage e))})))
            (recur (z/right loc) loc))))))
```

#### `add-require` — Add a require entry to the ns form

```clojure
(defn- op-add-require
  "Add a require entry to the ns form. Idempotent — skips if already present."
  [path require-entry]
  (let [content (slurp path)
        zloc    (z/of-string content)
        ns-loc  (z/down zloc)  ;; first top-level form is ns
        ;; Navigate into :require section, insert new entry
        result  (add-require-entry ns-loc require-entry)]
    (when result
      (let [output (z/root-string result)]
        (spit path output)
        {:op "add-require"
         :path path
         :status "ok"
         :added require-entry}))))
```

#### `insert-form` / `remove-form` — Add or delete top-level forms

Similar zipper navigation pattern. `insert-form` uses `z/insert-right`, `remove-form` uses `z/remove`.

### 4. Tool Registration

```clojure
(defn clj-edit-tool
  "Create a :clj-edit tool for structured Clojure source editing.
   Operations:
     read-structure — List top-level forms in a file
     find-form      — Find a form by name, return its source
     replace-form   — Replace a form's body with new source
     insert-form    — Insert a new top-level form after a target
     add-require    — Add a require entry to the ns form
     remove-form    — Remove a top-level form by name"
  ([]
   (clj-edit-tool {}))
  ([opts]
   {:type        :clj-edit
    :name        (or (:name opts) "clj_edit")
    :description "Structured Clojure/EDN source editing. Read, find, replace, insert, or remove top-level forms in .clj/.cljs/.cljc/.edn files. Preserves comments and formatting."
    :parameters  CljEditParams
    :write-dir   (:write-dir opts)}))  ;; ACL: restrict writes to this dir tree

(defmethod tools/run :clj-edit
  [tool args]
  (let [decoded (tools/coerce-args tool (if (map? args) (pr-str args) args))
        {:keys [op path] :as params} (tools/validate-args tool decoded)]
    (if (:error params)
      (pr-str {:error (:error params)})
      (case op
        "read-structure" (-> (op-read-structure path) pr-str)
        "find-form"      (-> (op-find-form path (:name params)) pr-str)
        "replace-form"   (-> (op-replace-form path (:name params) (:source params)) pr-str)
        "insert-form"    (-> (op-insert-form path (:name params) (:source params)) pr-str)
        "add-require"    (-> (op-add-require path (:require-entry params)) pr-str)
        "remove-form"    (-> (op-remove-form path (:name params)) pr-str)))))
```

### 5. Agent Wiring (`core.clj`)

```clojure
;; In default-agent-tools — add alongside repl, web, etc.
(defn- default-agent-tools
  [memory-store session-id memory-backend]
  (vec (remove nil?
               [(repl/repl-eval-tool)
                (rewrite/clj-edit-tool)          ;; ← NEW
                (web/web-search-tool)
                (portal/visualize-tool)
                (when (and memory-store session-id)
                  (remember/remember-tool ...))])))

;; New registration function
(defn add-clj-edit-tool!
  ([ag]
   (add-clj-edit-tool! ag {}))
  ([ag opts]
   (let [tool (rewrite/clj-edit-tool opts)]
     (register-tool! ag tool)
     tool)))
```

### 6. CLI Flag

```clojure
;; In parse-args (cli.clj), add alongside --visualize, --web-search:
"--clj-edit"  (recur next-rem (assoc opts :clj-edit? true))

;; In run-agent, conditionally add:
(when (:clj-edit? opts)
  (add-clj-edit-tool! ag))
```

---

## Implementation Steps (Ordered Commits)

### Step 1: Add dependency
**Commit:** `feat: add rewrite-clj dependency for structured source editing`

- Add `rewrite-clj/rewrite-clj {:mvn/version "1.2.54"}` to `deps.edn`
- Verify it resolves with `clojure -P`
- No code changes yet

### Step 2: Implement `read-structure` and `find-form`
**Commit:** `feat: clj-edit tool — read-structure and find-form operations`

- Create `src/kschltz/agent/tools/rewrite.clj`
- Implement `op-read-structure` and `op-find-form`
- Add Malli schemas (`CljEditParams`, `OpType`, etc.)
- Add tool registration (`clj-edit-tool`, `defmethod tools/run :clj-edit`)
- Wire into `default-agent-tools` in `core.clj`
- Test: can the LLM list forms and find a specific `defn`?

### Step 3: Implement `replace-form` and `remove-form`
**Commit:** `feat: clj-edit tool — replace-form and remove-form operations`

- Implement `op-replace-form` with round-trip validation
- Implement `op-remove-form`
- Add `add-clj-edit-tool!` to `core.clj`
- Test: replace a function body, remove a top-level def

### Step 4: Implement `insert-form` and `add-require`
**Commit:** `feat: clj-edit tool — insert-form and add-require operations`

- Implement `op-insert-form` (insert after a named form)
- Implement `op-add-require` (navigate to ns :require, insert entry)
- Add idempotency check (skip if require already present)
- Test: add a new defn, add a require entry

### Step 5: Write-access control
**Commit:** `feat: clj-edit tool — restrict writes to project directory`

- Add `:write-dir` option to `clj-edit-tool`
- Write operations (`replace-form`, `insert-form`, `add-require`, `remove-form`) check that the target path is under `:write-dir`
- Read operations (`read-structure`, `find-form`) work on any readable path
- Default `:write-dir` to `System/getProperty "user.dir"`

### Step 6: Unit tests
**Commit:** `test: clj-edit tool unit tests`

- Create `test/kschltz/agent/tools/rewrite_test.clj`
- Test each operation against sample `.clj` files
- Test edge cases: file not found, form not found, invalid new source, idempotent add-require
- Test round-trip: `read-structure` → `find-form` → `replace-form` → `find-form` again

### Step 7: CLI flag and integration test
**Commit:** `feat: add --clj-edit CLI flag, integration test`

- Add `--clj-edit` flag to `parse-args` in `cli.clj`
- Add `add-clj-edit-tool!` to `core.clj` exports
- Write an e2e test that creates an agent, calls `clj_edit` with `read-structure`, then `find-form`

---

## Open Questions

| Question | Options | Recommendation |
|---|---|---|
| Should writes require confirmation? | Auto-write / ask-per-write | Start with auto-write; add `--confirm-writes` flag later |
| File backup before write? | None / `.bak` copy / git stash | Write to temp file then rename (atomic); skip backup for now |
| Allow edits outside project dir? | Yes / No | Read-anywhere, write-under-`:write-dir` only |
| Support EDN files? | `.clj` only / `.clj` + `.edn` | Support all: `.clj`, `.cljs`, `.cljc`, `.edn` |
| Should the LLM see line numbers in `read-structure`? | Yes / No | Yes — helps the LLM orient in large files |
| Multifile edits in one call? | Single op per call / batch | Single op per call — keeps the LLM honest, each edit is verified |

---

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Zipper navigation confusion (LLM picks wrong form) | Return surrounding context in `find-form`; validate by name, not position |
| `add-require` duplicates an existing entry | Check for existing entry before inserting (idempotent) |
| `replace-form` breaks the file | Round-trip validation: parse the result before writing; reject if it doesn't parse |
| Path traversal (`../etc/passwd`) | `:write-dir` ACL restricts writes to project tree |
| Rewrite-clj bug | Well-tested library (used by clj-kondo, clojure-lsp, babashka); pin version in deps.edn |