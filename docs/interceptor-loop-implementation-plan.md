# Interceptor Architecture — Everything Is an Interceptor

Status: proposed (v2 — supersedes the Pedestal-dependency draft)
Scope: new `kschltz.agent.chain` (engine), new `kschltz.agent.interceptors.*`,
rewrite of `kschltz.agent.loop`, surface changes in `kschltz.agent.core`

## Thesis

Pedestal's architecture — a context map flowing through a queue of
`{:enter :leave :error}` stages with dynamic queue manipulation — but **our
own engine, not the Pedestal dependency**. And taken to its conclusion:
every plugin and inner working of the agent is an interceptor.

There is exactly one extension mechanism. The agent core becomes:

```
engine (≈100 lines) + ctx schema + a default chain of interceptors
```

Everything currently hardwired into `loop.clj`/`core.clj` becomes a stage in
that chain:

| Today | Becomes |
|---|---|
| `context/compose-context` call inside `llm-call` | `compose-context` interceptor (enter) |
| memory recall / retrieval injection | `memory-recall` interceptor (enter) — the RAG slot |
| `llm/call` + retry/trim logic | `llm-call` interceptor delegating to `LlmClient` protocol |
| tool-call parse/validate/execute inner loop | `dispatch` + per-tool interceptors via re-enqueue |
| `add-repl-eval-tool!`, `add-web-search-tool!`, … (8 ad-hoc installers) | plugins = interceptor bundles (see below) |
| `store-exchange` persistence | `store-exchange` interceptor (leave) |
| history capping + agent-state delta | `update-history` interceptor (leave) |
| `:on-response`/`:on-error`/`:on-thought` callbacks | `notify` interceptor (leave + error) |
| `safety/` checks | `guardrail` interceptor(s) at named slots |
| scattered `try/catch` | `error-boundary` interceptor (`:error`) |

## 1. The engine — `kschltz.agent.chain`

Own implementation, Pedestal semantics. Target ≤ ~120 lines, zero new deps.

```clojure
(ns kschltz.agent.chain)

;; An interceptor is a plain map:
;;   {:name kw, :enter (fn [ctx] ctx'), :leave (fn [ctx] ctx'), :error (fn [ctx ex] ctx')}
;; All keys optional except :name.

(defn enqueue   [ctx interceptors] ...) ; append to ::queue
(defn terminate [ctx]              ...) ; drop ::queue, begin unwinding ::stack
(defn execute   [ctx interceptors] ...) ; run to completion, return final ctx
```

Semantics (identical to Pedestal, synchronous only):
- `:enter` in queue order; each executed interceptor is pushed onto `::stack`
- queue empty (or `terminate`) → `:leave` in reverse stack order
- exception in any stage → switch to error mode: walk the stack calling
  `:error` until one returns a ctx without `::error` (handled), then resume
  `:leave` from there
- interceptors may `enqueue` more work mid-flight — this is how the tool
  loop and retries are expressed

Engine state lives under namespaced keys (`::queue`, `::stack`, `::error`)
so it never collides with domain keys. Property: `execute` is a pure
function of `(ctx, interceptors)` given pure stages — trivially testable.

Why own it rather than depend on `io.pedestal/pedestal.interceptor`:
- the artifact drags in core.async and Pedestal's logging; we need none of it
- we want schema instrumentation *inside* the engine (below), which is
  awkward to bolt onto Pedestal
- the contract is small and frozen; the cost is ~120 lines + tests once

## 2. The ctx — one map, Malli-schemed

Created per exchange in the (thin) outer loop, threaded through everything:

```clojure
{:agent/ref          ag        ; clojure.core agent, for sends only
 :agent/state        state     ; immutable snapshot at exchange start
 :agent/state-delta  {}        ; staged mutations, merged back by outer loop
 :exchange/items     items     ; drained queue items {:text :promise :handler}
 :exchange/user-text "..."
 :exchange/response  nil
 :exchange/error     nil
 :turn/messages      []        ; accumulating OpenAI messages
 :turn/transcript    []
 :turn/depth         0
 :turn/retries       0
 :llm/request        nil       ; composed messages + params
 :llm/response       nil       ; raw provider response
 :llm/api-error      nil
 :tool/calls         nil
 :tool/results       nil
 :memory/recalled    nil       ; RAG: facts/messages injected this turn
 :memory/stored      nil}
```

`Ctx` Malli schema in `kschltz.agent.interceptors.schema`. The engine
optionally validates ctx after every stage when `:chain/instrument? true`
(dev/test default, off in prod) — every interceptor gets input/output
checking for free, consistent with existing Malli usage.

Per project convention, anything that touches the network stays behind a
protocol with Malli-instrumented implementation fns:
- `LlmClient` (wraps today's `llm/call`)
- `WebSearch` (wraps `tools/web.clj` internals)
- `Embedder` (wraps the LangChain4j embedding calls used by recall)
The interceptors hold only protocol references taken from `:agent/state`,
so every stage is testable with in-memory fakes.

## 3. Plugins = interceptor bundles

The unification the previous draft missed. A plugin is data:

```clojure
{:plugin/name  :web-search
 :interceptors {:enrich   []                    ; e.g. RAG-style pre-LLM work
                :tools    [web-search-tool-ix]  ; tool interceptors (see §4)
                :persist  []                    ; post-response leave work
                :observe  [search-metrics-ix]}} ; tracing/metrics
```

Named slots in the default chain (in enter order):

```
:guard → :enrich → :compose → :llm → :dispatch/tools → :finalize
                                          (leave order)
:observe ← :notify ← :persist ← :history
```

`make-agent` takes `:plugins [...]`; chain assembly is a pure fold of
plugin maps into the default chain. The existing `add-*-tool!` fns become
one-line plugin constructors (kept as deprecated aliases for one release):

- `remember` plugin → tool interceptor + `:persist` hook (fact storage) +
  `:enrich` hook (retrieval injection pre-filter — today's `5497b47` logic)
- `repl` / `clj-edit` / `portal` / `web` plugins → tool interceptors only
- memory itself becomes the first-class `memory` plugin: `memory-recall`
  in `:enrich`, `store-exchange` in `:persist`
- `safety/` becomes a `guardrail` plugin in `:guard` (and optionally a
  post-LLM screen before `:dispatch`)

Consequence: `core.clj` no longer knows what a "tool" or "memory" is. It
knows how to assemble a chain and run it.

## 4. Tools as interceptors

A tool is an interceptor whose `:enter` runs when `dispatch` routes a
tool-call to it. Tool metadata (OpenAI function def, Malli arg schema)
lives on the interceptor map:

```clojure
{:name        :tool/web-search
 :tool/def    {...openai function def...}
 :tool/schema [:map [:query :string]]
 :enter       (fn [ctx] (run-search ctx))}   ; reads :tool/current-call, writes :tool/results
```

`dispatch` is the loop brain, replacing `loop/recur` with re-enqueueing:

```clojure
{:name :dispatch
 :enter (fn [ctx]
          (cond
            ;; tool calls + depth budget → enqueue the matching tool
            ;; interceptors, then loop back through llm-call + dispatch
            (pending-tool-calls? ctx)
            (-> ctx (update :turn/depth inc)
                    (chain/enqueue (concat (route-tools ctx)
                                           [tool-error-retry llm-call
                                            parse-response dispatch])))
            (depth-exhausted? ctx) (chain/enqueue ctx [wrap-up])
            (api-error? ctx)       (retry-or-fail ctx)
            (blank? ctx)           (retry-or-finish ctx)
            :else                  (finalize-response ctx)))}
```

Validation/coercion (today's `tools/coerce-args`, `validate-args`) becomes a
generic `tool-validate` interceptor enqueued before each tool interceptor —
tools never see invalid args.

What this buys over the current code:
- `:leave` stages run exactly once no matter how many tool iterations
  happened — the stack unwind replaces manual transcript plumbing
- one `:error` chain replaces three scattered `try/catch` blocks
- a tracing plugin sees *every* stage uniformly, including each tool call
- adding a capability never means editing `loop.clj` again

## 5. Migration phases (each lands green)

### Phase 1 — engine + schema

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/chain.clj` | NEW — `execute`, `enqueue`, `terminate`, `::queue`/`::stack`/`::error` machinery, `:chain/instrument?` hook |
| `src/kschltz/agent/interceptors/schema.clj` | NEW — `Ctx` Malli schema, `Interceptor` schema (`[:map [:name :keyword] [:enter {:optional true} fn?] ...]`) |
| `test/kschltz/agent/chain_test.clj` | NEW — engine semantics tests |
| `deps.edn` | unchanged (no new deps — that's the point) |

**Acceptance criteria**
- [ ] `(chain/execute ctx [a b c])` calls `:enter` in `a b c` order and `:leave` in `c b a` order; verified with an event-recording interceptor fixture
- [ ] An interceptor returning `(chain/enqueue ctx [d])` runs `d` after the current queue; `d`'s `:leave` unwinds in correct stack position
- [ ] `(chain/terminate ctx)` skips all remaining `:enter`s; already-entered interceptors still get `:leave`
- [ ] Exception in `:enter` of `b`: `c` never enters; `:error` walked `b → a`; if `a`'s `:error` returns ctx without `::error`, `a`'s `:leave` does NOT re-run (Pedestal semantics) and execution completes
- [ ] Unhandled `::error` at end of stack → `execute` rethrows (ex-info wrapping original, with `:chain/stage` and `:interceptor/name` in ex-data)
- [ ] With `:chain/instrument? true`, an interceptor returning a ctx violating `Ctx` throws with the offending interceptor name; with flag off, zero Malli calls (verify via `with-redefs` counter)
- [ ] Engine namespace ≤ ~150 lines, no new entries in `deps.edn`
- [ ] `clojure -M:test` green; no existing test touched

### Phase 2 — extract stages (no cutover)

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/llm.clj` | Add `LlmClient` protocol; default impl wraps existing `provider-dispatch`-based `llm/call`; impl fns Malli-instrumented |
| `src/kschltz/agent/tools/web.clj` | Already protocol-shaped (`web-search-client`, `default-http-client`) — expose as `WebSearch` protocol, instrument |
| `src/kschltz/agent/memory/embedding.clj` | Wrap LangChain4j calls in `Embedder` protocol, instrument |
| `src/kschltz/agent/interceptors.clj` | NEW — interceptor defs delegating to existing helpers: `compose-context` → `context/compose-context` + `sanitize-context-messages`; `llm-call` → `LlmClient`; `parse-response` → `loop/parse-tool-calls-native` (make public or move); `execute-tools` → `loop/execute-tool-calls` + `format-tool-results-native` + `truncate-tool-result`; `store-exchange` → `loop/store-exchange`; `update-history` → `loop/history-entries-for-exchange` + `context/cap-history`; `deliver-responses` → `loop/deliver-response`; `notify` → `loop/fire-on-thought` / `fire-memory-event`; `dispatch`, `api-error-retry`, `tool-error-retry`, `wrap-up`, `error-boundary` |
| `src/kschltz/agent/loop.clj` | Only visibility changes (`defn-` → `defn` for reused helpers); behavior untouched |
| `test/kschltz/agent/interceptors_test.clj` | NEW — per-interceptor unit tests on synthetic ctx maps |

**Acceptance criteria**
- [ ] Every interceptor in the §3 inventory exists as a var satisfying the `Interceptor` schema
- [ ] Each interceptor unit-tested with a synthetic ctx and fake protocols — no network, no Datalevin, no real embedder anywhere in these tests
- [ ] `LlmClient`/`WebSearch`/`Embedder` impl fns reject schema-invalid input/output when instrumented (one negative test each)
- [ ] `llm-call` interceptor converts provider exceptions into `:llm/api-error` (never throws); `error-boundary` is the only stage that handles raw throws
- [ ] `dispatch` covered for all four branches: pending tool calls, depth exhausted, blank response, normal finalize
- [ ] Existing test suite still green — `loop.clj` public behavior byte-identical (no call sites changed)

### Phase 3 — parity harness

**Codepaths**

| Path | Change |
|---|---|
| `test/kschltz/agent/parity_test.clj` | NEW — runs each scripted scenario through BOTH `loop/llm-turn` and `chain/execute` with the new chain |
| `test/kschltz/agent/fixtures/scripted_llm.clj` | NEW — `LlmClient` fake returning a pre-scripted response sequence; records calls |

**Scenario corpus** (each one asserts on `{:response :transcript}` equality AND the ordered `:on-thought`/`:on-response`/`:on-error` event sequence):
1. text-only response
2. single tool call → text
3. multi-step tool chain (3 iterations)
4. tool throwing → corrective retry path (`tool-error-retry`)
5. API error → context-trim retry → success (`api-error-retry`, exercises `context/truncate-chat-message` trimming rules)
6. API error → retries exhausted → terminal error text
7. empty/blank response → retry → success
8. depth exhaustion → `wrap-up` prompt → final text
9. tool call with Malli-invalid args (`tools/validate-args` rejection surfaced to model)

**Acceptance criteria**
- [ ] All 9 scenarios: old path and new chain produce equal `:response`, equal `:transcript` (ordered), equal callback event sequences
- [ ] Scripted client asserts the *requests* sent to the LLM are identical between paths (message lists after compose/trim) — catches silent context drift
- [ ] Memory side effects compared against an in-memory Datalevin (`memory/datalevin.clj` supports tmp-dir conns): same facts/messages stored, same order
- [ ] Parity suite tagged `^:parity`, runs in default `clojure -M:test`

### Phase 4 — cutover

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/loop.clj` | `process-messages` builds initial ctx and calls `chain/execute`; `llm-turn` becomes a thin adapter over the chain (kept, deprecated); `agent-loop`, `drain-queue!`, `queue-wait` untouched |
| `src/kschltz/agent/core.clj` | `make-agent` stores assembled default chain in state under `:agent/chain` |

**Acceptance criteria**
- [ ] Full existing test suite green, including the `test-sessions-*` integration tags, with the chain as the live path
- [ ] Phase 3 parity suite now redundant by construction (old path delegates to chain) but still green
- [ ] Concurrency invariant: chain never `send`s to the agent except via `notify`; all state mutation flows through `:agent/state-delta` merged by `agent-loop` — verified by a test running `send-message!` concurrently with an in-flight exchange and asserting no queued message is lost (regression guard for the `a30f2e8` queue race)
- [ ] `:on-thought` event ordering unchanged in an end-to-end run against the scripted client
- [ ] No reflection warnings introduced (`clojure -M:test` with `*warn-on-reflection*`)

### Phase 5 — pluginization

**Codepaths**

| Path | Change |
|---|---|
| `src/kschltz/agent/plugin.clj` | NEW — `Plugin` Malli schema, `assemble-chain` (pure fold of plugins into slot template) |
| `src/kschltz/agent/plugins/{memory,remember,repl,clj_edit,web,portal,safety}.clj` | NEW — plugin constructors; bodies delegate to existing `tools/*` and `safety/pre_filter.clj` (`check-input` → `:guard` slot) and memory recall (`:enrich`) / `store-exchange` (`:persist`) |
| `src/kschltz/agent/core.clj` | `make-agent` accepts `:plugins`; `default-agent-tools` + the 8 `add-*-tool!`/`register-tool!` installers reimplemented as plugin registration, kept as deprecated aliases |
| `README.md`, `CHANGELOG.md` | Plugin authoring section; migration notes |

**Acceptance criteria**
- [ ] `assemble-chain` is pure: same plugins → same chain (test with `=` on interceptor `:name` sequence); slot order fixed as documented in §3, within-slot order = declaration order
- [ ] Invalid plugin map (bad slot name, non-interceptor entry) fails fast at `make-agent` with a Malli explanation, not at first exchange
- [ ] Each converted plugin has a test proving equivalence with its old installer: agent built via `add-web-search-tool!` and via `:plugins [(plugins.web/plugin)]` expose identical tool defs and produce identical results against the scripted client
- [ ] `safety` plugin: an injection-flagged input (per `pre_filter/check-input`) terminates the chain in `:guard` — no LLM call recorded by the scripted client; `:on-error`/refusal delivery still fires via `:leave` unwind
- [ ] Memory plugin off (`:plugins` without it) → no Datalevin conn opened (verify via fake conn factory)
- [ ] Deprecated aliases log a single deprecation warning and pass all pre-existing tests unmodified
- [ ] `core.clj` contains no direct requires of `tools/*` or `safety/*` namespaces (grep gate)
- [ ] README documents: writing a plugin, slot table, ordering rules; CHANGELOG entry

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Hand-rolled engine bugs (unwind/error edge cases) | Engine is frozen-contract, ~120 lines, exhaustively tested in Phase 1; semantics copied from Pedestal's documented behavior |
| Retry semantics drift (trim rules, shared retry counters) | Phase 3 parity corpus covers every branch of current `llm-turn` cond |
| Callback ordering changes | Parity tests assert event sequences, not just final output |
| Chain mutating the agent mid-exchange | Chain only touches the snapshot; all mutation staged in `:agent/state-delta`, merged by the outer loop |
| Plugin slot explosion / unclear ordering | Fixed, documented slot names; within a slot, plugin declaration order; no priority numbers |
| Instrumentation overhead | Schema checks gated by `:chain/instrument?`, default off in prod |

## Out of scope (deliberately)

- Async/non-blocking execution (engine contract allows adding it later;
  don't build it now)
- Interceptorizing the outer drain/sleep tick — simple and
  concurrency-sensitive; leave it
- Any change to memory/storage behavior or on-disk format
