# Goal: Lateralus Stuck-Loop & Timeout Recovery

## Articulated Goal

Make the lateralus agent resilient to the four failure modes observed in the bad session transcript: (1) repeated nearly-identical tool calls, (2) silently retried empty tool results, (3) silent visualization failures (Portal not opening), and (4) post-timeout session unresponsiveness. Detect each mechanically rather than relying on LLM text-guidance, and on detection ask the user how to proceed or surface a clear error — never silently spin, never silently fail, never deadlock.

## Shared Understanding

See [`facts.md`](./facts.md) for the 15 testable facts this goal produces. Key constraints from the user:

- **Intervention policy:** stop the turn and ask the user — do not auto-recover via prompt injection.
- **Timeout recovery:** heartbeat from the LLM client (not a Clojure-side watchdog).
- **Detection:** all three signals (hash diversity, embedding similarity, result novelty) layered; AND-2-of-3 to declare stuck.
- **Tests:** a mix of parity, unit, integration, and Portal-mock tests.
- **Out of scope:** memory backend changes, LLM provider swap, new tools.

## Execution Plan

See [`plan.md`](./plan.md) for the 8 ordered steps with verification commands and file touches. Top-level summary:

1. Pure detection fns (`signature-diversity`, `args-similarity`, `result-novelty?`) + unit tests
2. New `:guard` slot interceptor `stuck-loop-detector` wired into the default chain
3. Plugin schema extension (`:stuck-loop` slot) — detector becomes a built-in plugin contribution
4. `LlmClient` protocol gains a heartbeat channel; default impl writes to a shared atom
5. `agent-loop` watchdog checks the heartbeat each tick; aborts stalled requests and resets the queue
6. `visualize` tool returns a structured `{:portal-open? :data-submitted? :data-hash :hint}` map
7. Drop the legacy "Do NOT repeat the exact same call" text hint (mechanical detection replaces it)
8. Run affected test suites, commit on a feature branch, push

## Done Condition

The work is done when:

- All 15 facts in `facts.md` are verifiable by the listed automated checks
- `clojure -M:test -m cognitect.test-runner` passes for the affected namespaces (stuck_loop_test, parity_test, client_test, portal_test, plugin_test, interceptors_test, chain_test) — 0 failures
- The pre-existing `cli_test.clj` Datalevin corruption issue remains documented as out-of-scope (see the `lateralus-interceptor-architecture-progress` memex card), not regressed
- The change is committed and pushed to a feature branch

---

**Launch with:** `/goal goals/lateralus-stuck-loop-recovery/goal.md`
