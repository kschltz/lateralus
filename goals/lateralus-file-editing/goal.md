# Goal: Lateralus File-Editing Reliability

## Status: ✅ IMPLEMENTED (branch `feat/file-editing-reliability`, 6 commits, pushed)

## Articulated Goal

Make the lateralus agent reliable at making file changes that persist. The existing `clj_edit` (rewrite-clj) tool covers the Clojure case in theory but you report it does not work properly in practice; there is also no tool at all for non-Clojure files. This goal **audits and hardens** the existing `clj_edit`, **adds the missing creation ops** (so the LLM can build a new Clojure file from scratch), and **adds a new general `file_edit` tool** for everything else — with mutual-exclusion routing so the LLM picks the right tool without ambiguity. `repl-eval` stays as-is (in-memory / one-off work).

## Shared Understanding

See [`facts.md`](./facts.md) for the 14 testable facts this goal produces. Key constraints from the user:

- **Scope:** improve clj-edit (incl. creation from scratch) + add a general write tool.
- **Routing:** hard refusal — `file_edit` refuses Clojure files, `clj_edit` refuses non-Clojure.
- **Safety:** write_dir constraint + blocked-paths + auto-backup with restore.
- **Tests:** per-op unit + multi-step parity + routing integration.
- **Out of scope:** don't touch `repl-eval`, don't change the self-mod protocol, don't rewrite existing 6 ops, no OS-level sandboxing.

## Execution Plan

See [`plan.md`](./plan.md) for the 9 ordered steps with verification commands and file touches.

## Implementation Summary

All 8 implementation steps from the plan (file-safety, clj_edit hardening, create-ns/create-file, LCS diff, file_edit, mutual-exclusion routing, plugin registration, parity scenario) were completed across **6 commits** on branch `feat/file-editing-reliability` (pushed to `origin`):

| Commit | Step | What |
|---|---|---|
| `45a89c1` | 1 | `file_safety.clj` (16 tests, 57 assertions) |
| `dda611e` | 2-3 | Hardened `clj_edit` + `create-ns` / `create-file` (18 tests, 35 assertions) |
| `b0d335a` | 5 | Hand-rolled LCS unified diff in `diff.clj` (8 tests, 17 assertions) |
| `a58ad64` | 4, 6, 7 | `file_edit` tool with 5 ops + mutual-exclusion routing + descriptions (16 tests, 42 assertions) |
| `dded1b0` | 8 | Plugin registration + parity scenario test (1 test, 8 assertions) |

**Test totals on this branch (affected namespaces):**

| Namespace | Tests | Assertions | Failures |
|---|---|---|---|
| tools.file-safety-test | 16 | 57 | 0 |
| tools.rewrite-test | 18 | 35 | 0 |
| tools.diff-test | 8 | 17 | 0 |
| tools.file-edit-test | 16 | 42 | 0 |
| file-editing-parity-test | 1 | 8 | 0 |
| **NEW tests total** | **59** | **159** | **0** |
| Plus adjacent (interceptors, parity, chain, context, tools, portal) | 78 | 251 | 0 |

## Done Condition

✅ All 14 facts in `facts.md` are verifiable by automated checks.
✅ `clojure -M:test -m cognitect.test-runner` passes for the new and adjacent namespaces (0 failures).
✅ Existing 6 clj_edit ops audited; comment in source explains the fixes (e.g. `parse-forms` cond bug, `:wrong-file-type` vs `:use-clj-edit` routing, write-dir enforcement, auto-backup).
✅ Changes committed and pushed to `feat/file-editing-reliability` branch.

## Known Limitations / Follow-up

- The hand-rolled LCS diff produces correct hunk content but the `@@ -A,B +C,D @@` line numbers don't always match `git diff`'s exact format. Tests assert functional correctness, not byte-for-byte format match. A v2 could use a proper Myers diff for prettier output.
- The `clojure-only?` semantics in `kschltz.agent.tools.file-safety/validate-write-target!` are inverted relative to what file_edit needs (file_edit wants to refuse Clojure, not refuse non-Clojure). I worked around this by adding an explicit pre-check in `op-write-file` / `op-edit-file` rather than changing the file-safety API. A cleaner refactor would add a `:refuse-clojure?` flag to file-safety.
