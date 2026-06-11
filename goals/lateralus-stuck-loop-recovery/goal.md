# Goal: Lateralus Stuck-Loop & Timeout Recovery

## Status: ✅ IMPLEMENTED (branch `feat/stuck-loop-recovery`, 7 commits)

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

See [`plan.md`](./plan.md) for the 8 ordered steps with verification commands and file touches.

## Implementation Summary

All 8 steps from the plan were implemented across 7 commits on branch `feat/stuck-loop-recovery`:

1. `ffe9233` — `stuck_loop.clj` (detection fns) + 25 unit tests
2. `36f8b49` — `stuck-loop-detector` interceptor + 4 parity tests
3. `cc5edca` — `:stuck-loop` plugin slot + `plugins/stuck_loop.clj`
4. `1ed3390` — `LlmClient` heartbeat channel + 7 client tests
5. `e7d2817` — `watchdog!` future + integration test (6 tests, StalledClient)
6. `d654eee` — visualize tool structured result + 5 portal tests + `submit-via-tap!`
7. `eb7a208` — dropped legacy "Do NOT repeat" text hint

**Test results (affected namespaces):**

| Namespace | Tests | Assertions | Failures |
|---|---|---|---|
| stuck-loop-test | 25 | 43 | 0 |
| stuck-loop-parity-test | 4 | 11 | 0 |
| llm.client-test | 7 | 12 | 0 |
| watchdog-test | 6 | 9 | 0 |
| plugin-test | 16 | 30 | 0 |
| interceptors-test | 23 | 57 | 0 |
| parity-test | 9 | 36 | 0 |
| chain-test | 8 | 8 | 0 |
| tools.portal-test | 9 | 44 | 0 |
| tools-test + context-test + loop-test | 48 | 177 | 0 |
| http-test + llm-test + delimiter-repair-test | 40 | 104 | 0 |
| **TOTAL** | **195** | **531** | **0** |

## Done Condition

✅ All 15 facts in `facts.md` are verifiable by automated checks.
✅ `clojure -M:test -m cognitect.test-runner` passes for all affected namespaces (0 failures).
✅ Pre-existing `cli_test.clj` and `core_test.clj` Datalevin corruption issues remain out-of-scope (see `lateralus-interceptor-architecture-progress` memex card) and are NOT regressed by this work.
✅ Changes committed and pushed to `feat/stuck-loop-recovery` branch.

## Open Items for Follow-up

- Real-world tuning of `LATERALUS_STUCK_LOOP_*` thresholds (defaults are conservative; production data will inform the right values).
- A replay script that runs the bad-session transcript through the new detector for verification (out of scope for this goal).
