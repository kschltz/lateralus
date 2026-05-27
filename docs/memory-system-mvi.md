# Session Memory System — MVI Spec

## Architecture

Hybrid memory = **top-Y relevant entries** + **last-N recent messages**, deduped and sorted chronologically. Implemented in `kschltz.agent.memory/compose` with strategy `:hybrid` (default).

## Storage

- **One Datalevin store per session**, absolute path: `<LATERALUS_SESSIONS_DIR>/<session-id>/`
- Default sessions root: `./sessions/` (override with `LATERALUS_SESSIONS_DIR`)
- Created on `make-agent` when `:session-id` is non-nil
- **Datalog store** (`data.mdb`) for message entities
- **Separate vector index** (`vectors/`) for HNSW semantic search
- Embeddings computed via OpenAI-compatible `POST /v1/embeddings` (e.g. Ollama)

## Datalevin Schema

```clojure
{:session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :session/model      {:db/valueType :db.type/string}
 :session/emb-method {:db/valueType :db.type/string}  ;; "openai-compatible-http"
 :session/emb-model  {:db/valueType :db.type/string}
 :msg/id             {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :msg/session        {:db/valueType :db.type/string}
 :msg/role           {:db/valueType :db.type/string}   ;; "user" | "assistant" | "tool"
 :msg/text           {:db/valueType :db.type/string}
 :msg/timestamp      {:db/valueType :db.type/long}
 :msg/tool-name      {:db/valueType :db.type/string}
 :msg/tool-result    {:db/valueType :db.type/string}
 :msg/tool-calls     {:db/valueType :db.type/string}  ;; JSON OpenAI tool_calls
 :msg/tool-call-id   {:db/valueType :db.type/string}}
```

Vectors are **not** stored on entities. They live in a separate LMDB KV store indexed by `:msg/id`.

## Session ID

- User-provided via CLI `-s` / `LATERALUS_SESSION` or `make-agent {:session-id "..."}`
- Memory is **opt-in** — omitted `:session-id` disables memory entirely

## Session Lifecycle

| Event | Behavior |
|-------|----------|
| `make-agent` + `:session-id` | Open/create store, write session metadata, hydrate `:history` from last N persisted messages |
| Each completed exchange | Store user + tool summary(s) + assistant text; embed and index when possible |
| Each LLM call | `compose-context` → retrieve relevant + merge with in-agent history via `:hybrid` |
| `reset!` | Clear runtime state; **keep** memory store open |
| `close-session!` | Close Datalevin connection; disk data preserved |

## Context Assembly

```
composed = memory/compose :hybrid
             relevant = vector-search(current_query, top=Y)
             recent   = last N messages from agent :history (with :msg-id for dedup)
           dedupe by :msg/id, sort by :msg/timestamp
LLM messages = composed + current turn (tool rounds appended ephemerally in-turn)
```

Agent state holds the memory store at `:memory-store` (access via `get-memory-store`).

## Configuration

| Option / Env | Default |
|--------------|---------|
| `LATERALUS_SESSIONS_DIR` / `:sessions-dir` | `sessions` |
| `LATERALUS_EMBEDDING_MODEL` / `:memory-embedding-model` | `nomic-embed-text` |
| `LATERALUS_MEMORY_EMBEDDING_DIMS` / `:memory-embedding-dims` | `384` |
| `LATERALUS_MEMORY_RELEVANT_LIMIT` | `5` |
| `LATERALUS_MEMORY_RECENT_LIMIT` | `10` |
| `LATERALUS_MEMORY_STRATEGY` | `:hybrid` |
| `LATERALUS_HISTORY_LIMIT` | `50` |
| `LATERALUS_MEMORY_MAX_CHARS` / `:memory-max-chars` | `500` (LLM prompt only; DB stores full text) |

## Wiring — Multimethods

Namespace: `kschltz.agent.memory`

```clojure
(create-session {:backend :datalevin ...})
(store-message  {:backend :datalevin ...})
(retrieve-relevant {:backend :datalevin ...})
(load-recent-messages {:backend :datalevin ...})
(close-session {:backend :datalevin ...})
(compose {:strategy :hybrid ...})
```

## What Gets Persisted

Chronological order per exchange:

1. User message (`:role "user"`)
2. Assistant message with `tool_calls` when the model invoked tools (`:msg/tool-calls` JSON)
3. Tool result message(s) (`:role "tool"`, `:msg/tool-call-id`, full `:msg/text`)
4. Final assistant text response (`:role "assistant"`)

| Stored (full text in Datalevin) | Not stored |
|--------|--------------|
| Full chronological transcript above | LLM reasoning/thinking |
| Embedding vector (when API succeeds) | Ephemeral retry nudges inside a turn |
| Session + embedding model metadata | |

When building the LLM prompt (`compose-context`, in-turn messages), text longer than `LATERALUS_MEMORY_MAX_CHARS` (default 500) is truncated with a `…` suffix. The database always keeps the full message.

## Embedding Failures

When embedding or vector indexing fails:
- Message is still written to Datalog (durable text)
- Warning logged to stdout
- `:on-memory-event` callback fired with `{:type :memory-not-indexed ...}`
- Semantic search falls back to most-recent messages for that session

## Test Coverage (Phase 5)

| Area | Test namespace |
|------|----------------|
| Hybrid compose / dedup | `core-test`, `memory-e2e-test` |
| Vector ranking order | `memory.datalevin-test` |
| CLI session opt-in | `cli-test` |
| Session resume in prompt | `memory-e2e-test`, `core-test` |
| Embed failure + fallback | `http-test`, `memory.datalevin-test`, `core-test` |
| End-to-end prompt shape | `memory-e2e-test`, `e2e-test` |
| Stub embeddings (CI) | `memory-e2e-test`, `memory.datalevin-test` |
| Live embed (optional) | `real-e2e-test` when Ollama is up |

## Dependencies

- `datalevin/datalevin` — Datalog DB + vector search
- `metosin/malli` — schema validation on HTTP/store boundaries
- `hato/hato` — HTTP client for `/v1/embeddings`
