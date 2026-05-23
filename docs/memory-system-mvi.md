# Session Memory System — MVI Spec

## Architecture

Hybrid memory = **top-Y relevant entries** + **last-N recent messages**, deduped (chronological position wins).

## Storage

- **One Datalevin store per session**, path: `./sessions/<session-id>/`
- Created on `agent/start!` with session metadata
- Single entity per message: text attrs + `:db.type/vec` on same entity
- Single transaction per message exchange

## Datalevin Schema

```clojure
{:session/id       {:db/valueType :db.type/string
                     :db/unique   :db.unique/identity}
   :session/model    {:db/valueType :db.type/string}
   :session/emb-method {:db/valueType :db.type/string}
   :session/emb-model  {:db/valueType :db.type/string}
   :msg/session      {:db/valueType :db.type/string}  ;; session-id ref
   :msg/role         {:db/valueType :db.type/string}  ;; "user" | "assistant" | "tool"
   :msg/text         {:db/valueType :db.type/string
                      :db/embedding true
                      :db.embedding/domains ["messages"]
                      :db.embedding/autoDomain true}
   :msg/timestamp    {:db/valueType :db.type/long}
   :msg/msg-vec      {:db/valueType :db.type/vec
                      :db.vec/domains ["messages"]}
   :msg/tool-name    {:db/valueType :db.type/string}  ;; optional, for tool responses
   :msg/tool-result  {:db/valueType :db.type/string}}  ;; optional, for tool responses
```

Store opts:
```clojure
{:embedding-opts {:provider :default        ;; Datalevin built-in ONNX/llama.cpp
                  :metric-type :cosine}
 :embedding-domains {"messages" {:provider :default :metric-type :cosine}}
 :vector-opts {:dimensions EMBEDDING_DIM :metric-type :cosine}
 :vector-domains {"messages" {:dimensions EMBEDDING_DIM :metric-type :cosine}}}
```

## Session ID

- User-provided, or time-based UUID default
- Passed to `start!` opts: `{:session-id "my-session"}`

## Session Lifecycle

- **Start**: `start!` creates Datalevin store, writes metadata
- **Each message/response/tool-response**: transact to Datalevin (text + auto-embedding)
- **LLM context assembly**: `[top-Y relevant (excluding overlap)] + [last-N recent]`
- **Dedup**: chronological position wins — drop from top-Y if present in last-N

## Context Assembly

```
chat-history = dedupe(relevant_memories + recent_messages)
             where:
               relevant_memories = embedding-neighbors(query=current_msg, top=Y, session=scoped)
               recent_messages   = last N messages from agent state
               dedupe keeps chronological position, drops from relevant set
```

## Wiring — Multimethods

### 1. Vector Storage/Search (`memory/store`)

```clojure
(defmulti store :backend)        ;; dispatch on :datalevin, future backends
(defmulti retrieve :backend)     ;; dispatch on :backend
(defmulti create-session :backend) ;; dispatch on :backend
```

Default: `:datalevin`

### 2. LLM Completion (`llm/call`)

```clojure
(defmulti call :provider)        ;; dispatch on :openai-compatible, :ollama, etc.
```

Default: `:openai-compatible` (current `http.clj`)

### 3. Memory Strategy (`memory/compose`)

```clojure
(defmulti compose :strategy)     ;; dispatch on :hybrid, future strategies
```

Default: `:hybrid` (top-Y + last-N, deduped)

## Dependencies

- `datalevin/datalevin` — Datalog DB + vector search + built-in embeddings
- `org.clojure/clojure` 1.12.5+ (already in deps.edn)