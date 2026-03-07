# CLAUDE.md

ç

## Claude Code Specifics
- Use `./gradlew test` for full verification.
- When Freud is needed, use `freud/scripts/feature-loop.sh <feature-id>`.
- Commit only when explicitly asked.
- Do not push to remote unless explicitly asked.
- When resuming from a compacted session, read the transcript at the path provided before proceeding.

## Memory Policy
- Auto-memory is for **personal/local preferences only** (output style, terminal setup, etc.).
- **Never** store project rules, architecture facts, or coding patterns in auto-memory.
- All project knowledge belongs in `AGENTS.md` (shared across all coding agents).
- If you learn something worth remembering about the project, propose adding it to `AGENTS.md` instead.
