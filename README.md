# kschltz/lateralus

Clojure agent with session memory (Datalevin + LangChain4j embeddings), REPL eval, web search, and explicit `remember` facts.

## Quick start

```bash
cd lateralus

# One-shot prompt (default session "default", memory on)
clojure -M:run-m "What is 2+2?"

# Interactive
clojure -M:run-m -i

# Named session
clojure -M:run-m -s my-project -i

# Same CLI via :cli alias
clojure -M:cli -h
```

`clojure -M:run-m` and `clojure -M:cli` both use `kschltz.lateralus/-main`, which delegates to the agent CLI.

## CLI options

| Flag | Env var | Description |
|------|---------|-------------|
| `-m`, `--model` | `LATERALUS_MODEL` | LLM model |
| `-u`, `--base-url` | `LATERALUS_BASE_URL` | API base URL |
| `-k`, `--api-key` | `LATERALUS_API_KEY` | API key |
| `-s`, `--session` | `LATERALUS_SESSION` | Session id (overrides default) |
| `--no-memory` | — | Disable memory |
| `-E`, `--embedding-method` | `LATERALUS_EMBEDDING_METHOD` | `langchain4j` or `http` |
| `--embedding-model` | `LATERALUS_EMBEDDING_MODEL` | Embedding model name |
| `--embedding-dims` | `LATERALUS_MEMORY_EMBEDDING_DIMS` | Vector dimensions |
| `--sessions-dir` | `LATERALUS_SESSIONS_DIR` | Session storage root |
| `--memory-relevant-limit` | `LATERALUS_MEMORY_RELEVANT_LIMIT` | Relevant recall count |
| `--memory-recent-limit` | `LATERALUS_MEMORY_RECENT_LIMIT` | Recent message count |
| `--memory-strategy` | `LATERALUS_MEMORY_STRATEGY` | e.g. `hybrid` |
| `--history-limit` | `LATERALUS_HISTORY_LIMIT` | In-agent history cap |
| `--memory-max-chars` | `LATERALUS_MEMORY_MAX_CHARS` | Prompt truncation (not DB) |
| `--max-tool-calls` | `LATERALUS_MAX_TOOL_CALLS` | Tool rounds per message |
| `-t`, `--turns` | — | Max turns (default 5) |
| `-r`, `--retries` | — | Tool error retries (default 3) |
| `-i`, `--interactive` | — | Interactive loop |
| `-h`, `--help` | — | Help |
| `-v`, `--version` | — | Version |

Precedence: CLI flag → environment variable → core default.

Without `-s`, `make-agent` uses session `"default"` and enables memory. Use `--no-memory` to disable.

Default tools: `repl-eval`, `web-search`, `remember` (when memory is on).

## Development

```bash
clojure -T:build test
```

Memory system details: [docs/memory-system-mvi.md](docs/memory-system-mvi.md).

## License

Copyright © 2026 Kschultz

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)
