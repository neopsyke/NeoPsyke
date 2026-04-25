# CLAUDE.md

All agent instructions for this repository live in `AGENTS.md`.

Read `AGENTS.md` and treat it as the single source of truth.

Do not add or maintain separate agent-specific instructions here. Keep shared agent guidance only in `AGENTS.md`.

## Claude-specific shell rules

- Do NOT prepend `cd <repo-root>` to bash commands. The shell session is already in the repo root and persists between calls. Compound `cd <abs-path> && <cmd>` invocations trigger redundant manual approval prompts and add no value. Use absolute paths in arguments instead, or rely on the existing cwd.
