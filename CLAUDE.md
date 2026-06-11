# kb — Kanban Board Workflow

This project uses `kb` for task management. The board is a communication tool — it tells the story of your work.

# Clojure Paren Repair — MANDATORY

When editing any `.clj` / `.cljs` / `.cljc` / `.edn` file, **never manually repair unbalanced delimiters**. Run `clj-paren-repair <files>` on every touched file before testing. See the `clojure-paren-repair` skill for full details.

```bash
clj-paren-repair src/kschltz/agent/stuck_loop.clj
```

If it reports `Failed: N > 0`, do NOT try to fix manually — report to the user.

## Working with the Board

1. `kb status` — see all lanes and cards
2. `kb pull --lane in-progress --agent claude` — claim the next card
3. Work in the card's worktree (shown in pull output)
4. `kb decision <id> "what I tried, learned, decided"` — record a decision (primary progress signal)
5. `kb note <id> "msg"` — log a quick note or status update
6. `kb ask <id> "question"` — ask the human (blocks card until answered)
7. `kb log <id>` — check for human notes or answers before each major step
8. `kb heartbeat <id>` — signal you are still working
9. `kb advance <id>` — move card to next lane (advisory by default — warnings shown, not blocking)
10. `kb done <id>` — move card to final lane

## How to Record Decisions

Before advancing to a new lane, record what you did and why:
  `kb decision 042 "Tried X. Failed because Y. Decided Z."`

Decisions are the primary progress signal. They tell the story of your work.
Notes are for quick updates. Decisions are for reasoning.

## Rules

- Always work inside the card's worktree, not the main tree
- Use `kb context <id>` for lean card context (add `--full` for comprehensive)
- Record decisions before advancing — `has-decision-record?` is the gate
- Lane advisories guide you but don't block by default (only `strict: true` lanes block)
- Gate failures are advisory by default — read warnings, decide if you should fix
- Advance through lanes sequentially — do not skip lanes

## Quick Reference

| Command | Description |
|---------|-------------|
| `kb status` | Show all lanes and cards |
| `kb pull [--lane L] [--agent A]` | Claim next card |
| `kb show <id>` | Card details (narrative-first) |
| `kb story <id>` | Card story — decisions by lane |
| `kb context <id>` | Lean card context (add `--full` for comprehensive) |
| `kb whoami` | Which card am I working on? |
| `kb decision <id> "what I tried/learned/decided"` | Record a decision |
| `kb note <id> "msg"` | Log a quick note |
| `kb ask <id> "question"` | Ask the human (blocks card) |
| `kb advance <id>` | Move to next lane |
| `kb reject <id> --reason R` | Send card back |
| `kb diff <id>` | Show diff vs base branch |
