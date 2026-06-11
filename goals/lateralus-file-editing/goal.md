# Goal: Lateralus File-Editing Reliability

## Status: 🟡 Ready to implement (branch to be created)

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

See [`plan.md`](./plan.md) for the 9 ordered steps with verification commands and file touches. Top-level summary:

1. Shared `file-safety` namespace (write_dir, blocked-paths, backup/restore)
2. Audit + fix the 6 existing `clj_edit` ops against real-world failure modes
3. Add `create-ns` and `create-file` ops to `clj_edit`
4. Build the new `file_edit` tool with 5 ops (read/write/edit/list/diff)
5. Hand-rolled LCS-based diff (no new deps)
6. Wire mutual-exclusion routing (clj_edit ↔ file_edit)
7. Sharpen tool descriptions + register `file_edit` in defaults bundle
8. Multi-step parity scenario test
9. Run full affected test suite, commit on a feature branch, push

## Done Condition

The work is done when:

- All 14 facts in `facts.md` are verifiable by automated checks
- `clojure -M:test -m cognitect.test-runner` passes for the affected namespaces (`file_safety_test`, `rewrite_test`, `file_edit_test`, `diff_test`, `routing_test`, `file_editing_parity_test`, plus pre-existing ones)
- A documented audit log of the 6 existing `clj_edit` ops lists which were broken, how they were fixed, and the regression tests for each fix
- The change is committed and pushed to a feature branch
