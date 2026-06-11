# Plan — Lateralus File-Editing Reliability

## Solution Approach

Three layered fixes, ordered by dependency:

1. **Shared safety/scaffolding** (Step 1) — Extract the safety checks (write_dir constraint, blocked-paths list, backup/restore) into a single `kschltz.agent.tools.file-safety` namespace that both `clj_edit` and the new `file_edit` can use. Without this, every tool would reinvent the wheel and the two tools would drift apart.

2. **Audit + harden the existing `clj_edit`** (Steps 2–3) — Address fact-16 by walking each of the 6 existing ops against real-world failure modes from the memex cards, fixing bugs, then adding the two creation ops (`create-ns`, `create-file`) and the file-type guard (facts 2, 3, 4, 14).

3. **Build the new `file_edit` general tool** (Steps 4–6) — All 5 ops (`read_file`, `write_file`, `edit_file`, `list_dir`, `show_diff`) with mutual-exclusion routing against `clj_edit` (fact-7) and the ambiguity-error contract (fact-6) and the hand-rolled LCS diff (fact-11).

4. **Sharpen tool descriptions** (Step 7) — So the LLM picks the right tool by reading its description (fact-15).

5. **Tests** (Step 8) — Per-op unit tests for everything new, plus a parity scenario test (fact-12) and the routing-integration test (fact-13).

The order matters: file-safety first (used by both), then clj_edit hardening, then file_edit, then descriptions, then tests.

## Ordered Steps

### Step 1 — Shared `file-safety` namespace
**Files:** `src/kschltz/agent/tools/file_safety.clj` (new), `test/kschltz/agent/tools/file_safety_test.clj` (new)

Pure functions used by both `clj_edit` and `file_edit`:
- `clojure-ext?` — returns true for `.clj`/`.cljs`/`.cljc`/`.edn` (extracted from rewrite.clj's private `clj-ext?`, made public and reusable).
- `within-write-dir?` — true when `path` is under `write-dir` (uses `io/file` + `getCanonicalPath`).
- `blocked-path?` — true when `path` matches any default or user-configured blocked segment (default: `.git`, `target`, `node_modules`, `.clojure-mcp-light`, `.mvn`, `dist`, `build`).
- `make-backup!` — writes `<path>.bak.<unix-ms>` and returns the backup path (or nil if the file didn't exist).
- `restore!` — finds the most recent backup for `path` and reverts, returning the restored path or nil if no backup.
- `list-backups` — returns the sorted list of backup paths for a given source path.
- `validate-write-target!` — combines all four checks (`clojure-ext?` for clj_edit, `within-write-dir?`, `blocked-path?`, and the `:force` flag) and returns nil on success or a structured `{:error ...}` map on failure.

**Verification:** `clojure -M:test -m cognitect.test-runner -n kschltz.agent.tools.file-safety-test` — must pass all assertions covering: each blocked path matches, each non-blocked path doesn't, write-dir containment edge cases, backup creation when file exists and when it doesn't, restore finds most recent, restore returns nil when no backup.

### Step 2 — Audit + fix existing `clj_edit` ops
**Files:** `src/kschltz/agent/tools/rewrite.clj` (modify), `test/kschltz/agent/tools/rewrite_test.clj` (extend)

Per fact-16, audit each of the 6 ops. Concrete bug fixes I expect to need (from memex cards + plan audit):
- **Make `clj-ext?` public** (move into file-safety; rewrite.clj imports it). This is prerequisite for fact-4.
- **Replace the hard-coded "Path must be a .clj, .cljs, .cljc, or .edn file" error string** with a structured `{:error :wrong-file-type :extension "..." :use-tool "file_edit"}` (fact-4).
- **Add file-type guard to `read-structure` and `find-form`** too — currently those work on any file. They should also refuse non-Clojure.
- **Fix `add-require`'s `op-insert-form` regex-replace path** — the `str/replace` fallback when `require-loc`'s `right` isn't a vector is brittle. Test it explicitly with a malformed ns.
- **Tighten `find-any-named-form`** to handle sexp-not-parseable cases (currently silent skip — should error if a form exists but can't be inspected).
- **Add a `:rejects-write-dir?` and `:blocked-paths` integration** to every write op using `file-safety/validate-write-target!`.

**Verification:** existing `rewrite_test.clj` continues to pass; new regression tests for each fix.

### Step 3 — Add `create-ns` and `create-file` ops
**Files:** `src/kschltz/agent/tools/rewrite.clj`, `test/kschltz/agent/tools/rewrite_test.clj`

Add two new ops:
- `create-ns` — args: `{:ns "my.cool.ns" :requires [{:lib "clojure.string"} ...] :source-root "src" :forms ["(defn foo [] :bar)"]}`. Computes path as `<source-root>/<ns-as-path>.clj`, writes `(ns my.cool.ns (:require ...))` followed by the forms, validates parse, creates backup first.
- `create-file` — args: `{:path "/abs/or/rel/path.clj" :source "..."}`. Refuses if path's extension isn't Clojure. Validates parse, creates backup first.

Update `OpType`, `CljEditParams`, the `defmethod tools/run :clj-edit` `case` dispatch, and the tool's `description` to mention these new ops.

**Verification:** new unit tests for both ops cover happy path, missing-path, invalid-extension, parse-failure, and backup-was-created.

### Step 4 — Build `file_edit` tool
**Files:** `src/kschltz/agent/tools/file_edit.clj` (new), `test/kschltz/agent/tools/file_edit_test.clj` (new), `src/kschltz/agent/plugins/file_edit.clj` (new plugin)

New tool namespace with 5 ops:
- `read_file` — args `{:path :offset? :limit?}`. Returns `{:content "..." :total-bytes N :path "..."}`. Allowed for any file extension (no routing refusal on read).
- `write_file` — args `{:path :content :force? :clj-override? :force}`. Refuses Clojure extensions unless `clj-override?` is true. Creates backup. Returns `{:status :ok :bytes-written N :backup-path "..."}`.
- `edit_file` — args `{:path :old_text :new_text :force}`. Reads file, counts `old_text` occurrences. If 0 or >1, returns ambiguity error. If 1, applies, creates backup. Returns `{:status :ok :replaced N :backup-path "..."}`.
- `list_dir` — args `{:path}`. Returns `{:entries [...] :path "..."}`. Validates `path` is a directory.
- `show_diff` — args `{:path :new_contents}` or `{:path :old_text :new_text}`. Computes diff using hand-rolled LCS, returns `{:diff "..." :additions N :deletions M}`.

All writes go through `file-safety/validate-write-target!`.

**Plugin:** `plugins/file_edit.clj` registers the tool via `:plugin/register`. Defaults bundle is updated to include it.

**Verification:** unit tests for each op; routing refusal test (write_file on `.clj` returns use-clj-edit error); ambiguity test (edit_file on `old_text` appearing 0 or >1 times).

### Step 5 — LCS-based diff
**Files:** `src/kschltz/agent/tools/diff.clj` (new), `test/kschltz/agent/tools/diff_test.clj` (new)

`unified-diff` function: takes `old-lines` and `new-lines` (vectors of strings), returns a unified diff string using `@@` hunks. Hand-rolled LCS so no new deps. Also exports `diff-stats` returning `{:additions N :deletions M}`.

**Verification:** unit tests on small inputs (single line add, single line remove, multi-line change, no change, complete replacement).

### Step 6 — Mutual-exclusion routing
**Files:** `src/kschltz/agent/tools/rewrite.clj`, `src/kschltz/agent/tools/file_edit.clj`, `test/kschltz/agent/tools/routing_test.clj` (new)

Add the file-type guard to `clj_edit`'s read-structure and find-form so a `.py` file gets `{:error :wrong-file-type}`. Add the inverse to `file_edit`'s write_file and edit_file so a `.clj` file gets `{:error :use-clj-edit}`. The new `routing_test.clj` covers both directions (fact-13).

**Verification:** `clojure -M:test -m cognitect.test-runner -n kschltz.agent.tools.routing-test`.

### Step 7 — Sharpen tool descriptions + register file_edit
**Files:** `src/kschltz/agent/tools/rewrite.clj`, `src/kschltz/agent/tools/file_edit.clj`, `src/kschltz/agent/plugins/defaults.clj`

Update `clj_edit`'s `description` to say "the preferred tool for Clojure file changes that need to persist" (fact-15). Update `file_edit`'s description to say "for non-Clojure files. For Clojure/EDN files, use clj_edit instead." Update `plugins/defaults.clj` to include `file_edit/plugin` in the default bundle.

**Verification:** grep-check that the descriptions contain the new wording. Existing tests still pass.

### Step 8 — Multi-step parity scenario
**Files:** `test/kschltz/agent/file_editing_parity_test.clj` (new)

Scripted LLM scenario: 4 sequential tool calls to `clj_edit`:
1. `read-structure` on a sample file
2. `find-form` for a specific defn
3. `add-require` to add a new import
4. `replace-form` to update the defn body

After the scenario, assert:
- The on-disk file has the new require
- The replaced defn body is present
- Comments and whitespace outside the changed forms are preserved (round-trip via rewrite-clj)
- A backup file `<path>.bak.<unix-ms>` exists

**Verification:** `clojure -M:test -m cognitect.test-runner -n kschltz.agent.file-editing-parity-test` — must pass.

### Step 9 — Run full test suite + commit
**Verification:** all affected test namespaces pass (`file_safety_test`, `rewrite_test`, `file_edit_test`, `diff_test`, `routing_test`, `file_editing_parity_test`, plus the existing stuck-loop recovery tests). Commit on a feature branch. Push.

## Risks & Open Questions

- **Audit findings (Step 2) may uncover more than 2–3 bugs.** I'm budgeting for a small handful of fixes based on memex cards; if the audit surfaces more, scope may grow. The `replace-form` and `add-require` paths are the most likely trouble spots based on the implementation plan's design.
- **The LCS diff (Step 5) needs to be reasonable but not world-class.** No new deps, no Myers algorithm — a simple O(n·m) line-based diff is fine for files under a few thousand lines. Large files are not the primary use case.
- **`create-ns` path derivation is project-convention-dependent.** `my.cool.ns` → `src/my/cool/ns.clj` is the most common but not universal. The `:source-root` arg makes it configurable, but tests should cover the common case + at least one alternate (e.g. `test/` root).
- **Tool-description tuning is a soft signal.** The LLM is statistical; perfect descriptions don't guarantee perfect routing. The hard-refusal safety net (Step 6) is what actually enforces correctness, and the descriptions just make the common path smooth.

## Out of Scope (per interview)

- No changes to `repl-eval` (it stays as the in-memory / one-off tool)
- No changes to the self-modification protocol
- No rewrite of the existing 6 clj-edit ops (we add to them, not rewrite)
- No OS-level sandboxing
