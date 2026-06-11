# Plan — Lateralus Stuck-Loop & Timeout Recovery

## Solution Approach

The session transcript showed four failure modes that all reduce to one root cause: **the agent loop has no progress detection and no resilient recovery from protocol-level failures**. This plan layers three independent safeguards into the new interceptor architecture:

1. **Stuck-loop detection** — a `:guard` slot interceptor that observes recent tool calls/results and declares the agent stuck when at least two of three signals agree (low hash diversity, high embedding similarity, low result novelty). On detection: terminate the turn, surface a structured event, and ask the user how to proceed.

2. **Heartbeat-based timeout recovery** — extend the `LlmClient` protocol with a heartbeat channel; the default client writes timestamps to a shared atom while a request is in flight; the agent loop checks this atom each tick and aborts a stalled request with a `{:type :session-unresponsive}` event when no heartbeat has arrived within the threshold.

3. **Visualize tool self-diagnostics** — restructure the `:visualize` tool's return value into `{:portal-open? :data-submitted? :data-hash :hint}` so the agent (and the user) can see *why* Portal didn't render anything.

All three are additive: existing behavior is preserved when nothing trips them. Detection knobs are env-var configurable per `fact-9`.

## Ordered Steps

### Step 1 — Stuck-loop detection fn (no chain integration yet)
**Files:** `src/kschltz/agent/stuck_loop.clj` (new), `test/kschltz/agent/stuck_loop_test.clj` (new)

Three pure functions, each testable in isolation:
- `(signature-diversity calls) → 0.0–1.0` — fraction of unique (tool+args) hashes across the recent window. Low diversity ⇒ likely looping.
- `(args-similarity calls) → 0.0–1.0` — average cosine similarity of args vectors (simple bag-of-words or char-trigram — no need for real embeddings; this is a fast signal, not a precise one). High similarity ⇒ near-duplicate queries.
- `(result-novelty? results) → 0.0–1.0` — fraction of new bytes in the latest result vs the prior N results. Empty results and bit-identical repeats score 0.0.

A fourth fn `(stuck? [diversity sim novelty] opts) → bool-or-reason` combines them with the AND-2-of-3 rule from `fact-1`.

**Verification:** `clojure -M:test -m cognitect.test-runner --var kschltz.agent.stuck-loop-test` — must pass all 15+ assertions across single-call, all-distinct, all-identical, mixed-with-empties, and same-result-twice cases (covers `fact-13`).

### Step 2 — Wire detector into interceptor chain
**Files:** `src/kschltz/agent/interceptors.clj`, `src/kschltz/agent/exchange.clj`

Add `def stuck-loop-detector` to `interceptors.clj`. Its `:enter` fn:
1. Reads recent tool calls from `:turn/messages` (last N entries, where N = `LATERALUS_STUCK_LOOP_WINDOW`).
2. Reads recent results from `:turn/transcript`.
3. Calls the three signal fns.
4. If `(stuck? ...)` is truthy:
   - `assoc :exchange/response` with a structured payload `{:type :stuck-loop :recent-calls [...] :reason "..." :signals {:diversity D :similarity S :novelty N}}`.
   - `assoc :exchange/error` so `error-boundary` and `deliver-responses` surface it.
   - `chain/terminate` the queue so no more stages run.

Insert into `default-exchange-chain` in `exchange.clj` *between* `ix/dispatch` and `ix/deliver-responses` — this way it runs once per turn after all tool calls have happened.

**Verification:** Re-run parity tests + a new parity scenario where scripted LLM calls `web-search` 5 times with the same query → chain must end with `:exchange/error :type :stuck-loop` instead of hitting `max-tool-calls`. (Covers `fact-8`, `fact-11`.)

### Step 3 — Plugin schema extension for stuck-loop slot
**Files:** `src/kschltz/agent/plugin.clj`, `src/kschltz/agent/plugins/defaults.clj`

Add `:stuck-loop` to `default-slot-order` in `plugin.clj`. The built-in `stuck-loop-detector` interceptor moves from being hard-coded in the exchange chain to being contributed by `plugins/defaults.clj` as a `:stuck-loop` slot interceptor. The exchange chain (or a future `assemble-chain` invocation) still includes it by default; plugins can override. (Covers `fact-10`.)

**Verification:** Existing `plugin_test.clj` continues to pass; new test asserts `assemble-chain [defaults-plugin]` includes a `:stuck-loop` slot interceptor.

### Step 4 — Heartbeat channel on LlmClient
**Files:** `src/kschltz/agent/llm/client.clj`

Extend the protocol with a third arity: `(start-heartbeat! [this] ref-or-channel)` returning a heartbeat ref (atom) the caller can `deref`. The `DefaultLlmClient` impl uses an internal atom; the `call` method writes `(System/currentTimeMillis)` to it on entry and uses a future to write periodically (every 5s) until the call returns.

The interceptor `llm-call` calls `start-heartbeat!` on the client before `call`, stores the ref on ctx, and clears it after. This keeps the loop's check independent of the interceptor's try/catch. (Covers `fact-5`, `fact-6`.)

**Verification:** New unit test in `client_test.clj`: a `ScriptedLlmClient` that returns a slow response (uses `Thread/sleep`) — assert the heartbeat ref is updated within 1s of the call starting. A test that returns immediately — assert the ref has at least one heartbeat write.

### Step 5 — Agent-loop watchdog
**Files:** `src/kschltz/agent/loop.clj`

In `agent-loop`, after `(let [next-state (try ...)]` but *before* `process-messages` is called, check the agent state's `:llm/heartbeat-ref` against `LATERALUS_LLM_HEARTBEAT_TIMEOUT_S` (default 60s). If the ref exists and `(System/currentTimeMillis) - @ref > threshold`:
1. Call `(cancel client ref)` on the client (best-effort — may be a no-op for HTTP).
2. `send ag assoc :message-queue []` to reset the queue (so the user can send a new message).
3. Fire `:on-thought {:type :session-unresponsive :stalled-for-ms N}` and `:on-error` with a synthesized exception.
4. `recur` (continue the loop) — do NOT stop the agent. (Covers `fact-7`.)

**Verification:** Integration test: build an agent with a `ScriptedLlmClient` that never writes a heartbeat, set `LATERALUS_LLM_HEARTBEAT_TIMEOUT_S` to 2s for the test, call `process-messages` directly, assert it returns within ~3s with `:exchange/error` containing `:session-unresponsive`. (Covers `fact-12`.)

### Step 6 — Visualize tool structured result
**Files:** `src/kschltz/agent/tools/portal.clj`

Replace the `defmethod tools/run :visualize` body so it returns:
```clojure
{:status (if portal-opened? :ok :portal-unavailable)
 :portal-open? portal-opened?
 :data-submitted? (true? submit-ok)
 :data-hash (some-> data-hash)
 :data-type (.getSimpleName (class data))
 :hint hint-str}
```
where:
- `portal-opened?` is true iff `ensure-tap-portal!` actually returned a portal session (it was lazy-loaded successfully).
- `data-hash` is a SHA-256 of `(pr-str data)` so the agent can confirm round-trip integrity.
- `hint-str` is "Open a Portal window to view the data" when `portal-open?` is false; nil otherwise.

The `defmethod tools/parse :visualize` is unchanged (still reads EDN). (Covers `fact-4`.)

**Verification:** Existing visualize tests pass; new test in `portal_test.clj` asserts the structured map is returned. A new test that calls visualize with Portal deliberately not on the classpath asserts `:portal-open?` is `false` and `:hint` is non-nil. (Covers `fact-14`.)

### Step 7 — Remove text-only "don't repeat" guidance
**Files:** `src/kschltz/agent/loop.clj`, `src/kschltz/agent/interceptors.clj`

In `tool-error-retry` (interceptors.clj), drop the "Do NOT repeat the exact same call" line from the corrective user-message. Replace with: "The mechanical stuck-loop detector will fire if you repeat similar calls; the corrective guidance has moved to the detector." This avoids giving the LLM a false sense of protection. (Covers `fact-15`.)

**Verification:** Parity tests for error-retry still pass; text-difference check shows the line is gone.

### Step 8 — Run full test suite + commit
**Verification:** `clojure -M:test -m cognitect.test-runner` for the new + affected test namespaces (stuck_loop_test, parity_test, client_test, portal_test, plugin_test, interceptors_test, chain_test). Document the pre-existing `cli_test.clj` failures (Datalevin session store corruption, see `lateralus-interceptor-architecture-progress` memex card) as out-of-scope per `fact` 8 (out-of-scope section in interview).

Commit on a feature branch with a clear message referencing the goal. Push.

## Risks & Open Questions

- **Embedding-based signal (Step 1) is expensive.** Even with bag-of-words, calling it 5+ times per turn adds latency. If profiling shows it's hot, swap to a simpler LSH (locality-sensitive hash) of n-gram shingles — same idea, no vector math.
- **Heartbeat cancellation for HTTP is best-effort.** Hato doesn't expose a cancel handle for synchronous calls. The watchdog resets the queue and surfaces the event, but the in-flight HTTP request continues until the server responds or the JVM exits. The user perceives responsiveness immediately, but the socket is leaked. Acceptable trade-off for v1; can move to async HTTP in a follow-up.
- **Stuck-loop detector needs the right threshold.** Fact-9 env knobs are defaults; real-world tuning will happen in production. A `tools/stuck_loop_bench.clj` script (out of scope here) could replay the bad transcript and tune values.
- **Structured visualize result changes the tool's response shape.** Any caller that was string-matching on the old `{:status :ok ...}` shape will still work (the new shape is a superset) but the agent's `defmethod tools/parse :visualize` consumer might want to re-check what it reads. Audit: grep for `visualize` in the codebase.
- **The "ask the user for guidance" intervention (per interview answer) is the right call but is harder to test mechanically than "inject corrective text".** A test that simulates a stuck loop must assert that the response is delivered AND that the agent's `:on-thought` event for `:stuck-loop` is fired, not that some follow-up retry happens. Manual smoke test on a real session is the ultimate verification.

## Out of Scope (per interview)

- Memory backend / Datalevin changes
- LLM provider changes (`:openai-compatible` stays default)
- New tools
- Pre-existing test-runner Datalevin corruption (separate `cli_test.clj` issue)
