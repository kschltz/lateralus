# Session Memory System — MVI Spec

## Architecture

Hybrid memory = **top-Y relevant entries** + **last-N recent messages**, deduped and sorted chronologically. Implemented in `kschltz.agent.memory/compose` with strategy `:hybrid` (default).

## Storage

- **One Datalevin store per session**, absolute path: `<LATERALUS_SESSIONS_DIR>/<session-id>/`
- Default sessions root: `./sessions/` (override with `LATERALUS_SESSIONS_DIR`)
- Created on `make-agent` when `:session-id` is non-nil
- **Datalog store** (`data.mdb`) for message entities
- **Separate vector index** (`vectors/`) for HNSW semantic search
- Embeddings computed in-process via LangChain4j ONNX models (default: `all-minilm-l6-v2-q`)
- Optional HTTP embedding via OpenAI-compatible `POST /v1/embeddings` (`LATERALUS_EMBEDDING_METHOD=http`)

## Datalevin Schema

```clojure
{:session/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :session/model      {:db/valueType :db.type/string}
 :session/emb-method {:db/valueType :db.type/string}  ;; "langchain4j-in-process" | "openai-compatible-http"
 :session/emb-model  {:db/valueType :db.type/string}
 :msg/id             {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :msg/session        {:db/valueType :db.type/string}
 :msg/role           {:db/valueType :db.type/string}   ;; "user" | "assistant" | "tool"
 :msg/text           {:db/valueType :db.type/string}
 :msg/timestamp      {:db/valueType :db.type/long}
 :msg/indexed        {:db/valueType :db.type/boolean}   ;; true after vector index write; false = pending reindex
 :msg/tool-name      {:db/valueType :db.type/string}
 :msg/tool-result    {:db/valueType :db.type/string}
 :msg/tool-calls     {:db/valueType :db.type/string}  ;; JSON OpenAI tool_calls
 :msg/tool-call-id   {:db/valueType :db.type/string}
 :msg/kind           {:db/valueType :db.type/string}   ;; "fact" for remember tool
 :msg/topic          {:db/valueType :db.type/string}
 :msg/tags           {:db/valueType :db.type/string}}   ;; JSON array of strings
```

Vectors are **not** stored on entities. They live in a separate LMDB KV store indexed by `:msg/id`.

`:msg/indexed` tracks vector-index consistency: written `false` at Datalog commit, flipped `true` after successful `add-vec`. On session startup, `reindex-pending!` scans for `:msg/indexed false` and retries vector indexing — recovering messages orphaned by crashes between the two writes.

## Session ID

- User-provided via CLI `-s` / `LATERALUS_SESSION` or `make-agent {:session-id "..."}`
- **Default**: omitted `:session-id` in `make-agent` uses `"default"` and opens memory
- **Opt-out**: pass `:session-id nil` or `:memory-enabled false` to disable memory
- CLI without `-s` passes explicit `nil` session-id (memory disabled unless `LATERALUS_SESSION` is set)

## Remember Tool

The `remember` tool stores explicit facts (`:msg/kind "fact"`) in session memory.

| Arg | Required | Description |
|-----|----------|-------------|
| `:content` | yes | Full fact text (no truncation) |
| `:topic` | no | Grouping label (e.g. `"preferences"`) |
| `:tags` | no | Vector of tag strings |

**Response**: `{:type "memory" :stored true :msg-id "..." :content "..."}` (full content echoed back).

Facts are injected into the LLM prompt as a system message:

```
[memory]
- preferences: User prefers dark mode
- Standalone fact without topic
[/memory]
```

Facts are **not** truncated by `LATERALUS_MEMORY_MAX_CHARS`. They are excluded from normal chat-role messages in context assembly.

Registered by default in `make-agent` when memory is enabled (along with `repl-eval` and `web-search`).

## Embedding Configuration via CLI

The following CLI flags control embedding behavior:

| Flag | Description | Valid Values | Env Var |
|------|-------------|--------------|---------|
| `-E`, `--embedding-method` | Embedding backend | `langchain4j` or `http` | `LATERALUS_EMBEDDING_METHOD` |
| `--embedding-model` | Model name | string | `LATERALUS_EMBEDDING_MODEL` |
| `--embedding-dims` | Vector dimensions | integer | `LATERALUS_MEMORY_EMBEDDING_DIMS` |

**Precedence**: CLI flags > environment variables > core defaults.

**Examples**:
```bash
# Use HTTP embeddings
clojure -M:cli -s my-session -E http --embedding-model nomic-embed-text "hello"

# Use LangChain4j in-process (default)
clojure -M:cli -s my-session -E langchain4j --embedding-model all-minilm-l6-v2-q "hello"

# Override embedding dimensions
clojure -M:cli -s my-session --embedding-dims 768 --embedding-model custom-model "hello"
```

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
facts    = composed entries with :msg/kind "fact"
chat     = composed entries excluding facts
LLM messages = [memory block from facts] + chat + current turn
```

Agent state holds the memory store at `:memory-store` (access via `get-memory-store`).

## Configuration

| Option / Env | Default |
|--------------|---------|
| `LATERALUS_SESSIONS_DIR` / `:sessions-dir` | `sessions` |
| `LATERALUS_EMBEDDING_METHOD` / `:memory-embedding-method` / CLI `-E`, `--embedding-method` | `:langchain4j` (or `:http`) |
| `LATERALUS_EMBEDDING_MODEL` / `:memory-embedding-model` / CLI `--embedding-model` | `all-minilm-l6-v2-q` |
| `LATERALUS_MEMORY_EMBEDDING_DIMS` / `:memory-embedding-dims` / CLI `--embedding-dims` | `384` |
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
| Embedding vector (when embed succeeds) | Ephemeral retry nudges inside a turn |
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
| Live LangChain4j integration (slow) | `memory.embedding-integration-test` |
| LangChain4j in-process | `memory.embedding-test` |
| Remember tool | `tools.remember-test` |

## Dependencies

- `datalevin/datalevin` — Datalog DB + vector search
- `dev.langchain4j/langchain4j-embeddings-all-minilm-l6-v2-q` — default in-process embeddings
- `metosin/malli` — schema validation on HTTP/store boundaries
- `hato/hato` — HTTP client for optional `/v1/embeddings`
