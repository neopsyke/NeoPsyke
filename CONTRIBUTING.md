# Contributing to NeoPsyke

Thanks for contributing.

NeoPsyke is currently an unreleased prototype. Contributions should prioritize
architectural clarity, explicit behavior, and fast validation over backwards
compatibility.

## Before You Start

- Read [README.md](README.md) for setup and runtime details.
- Read [AGENTS.md](AGENTS.md) for repository-specific engineering rules.
- Use JDK 21+.
- Use the Gradle wrapper: `./gradlew`.

## Ways to Contribute

- Bug fixes
- Architecture and runtime improvements
- Tests and deterministic eval coverage
- Documentation updates
- New action plugins or integrations that fit the existing architecture

If you are proposing a larger design change, open an issue or draft pull
request first so the direction is clear before implementation.

## Development Principles

- Keep changes focused. Do not mix unrelated cleanup into the same change.
- Prefer tuning or refactoring an existing mechanism before adding a new
  heuristic.
- Preserve behavior unless the change is explicitly intended to change it.
- Do not add compatibility shims or aliases for renamed concepts. Clean breaks
  are preferred in this prototype stage.
- Do not commit secrets, API keys, or local machine paths.

## Code and Docs Expectations

- Follow the existing Kotlin style and package structure.
- Keep functions and modules explicit and readable.
- If you change core control flow or runtime behavior, update both:
  - [AGENT_LOGIC_SUMMARY.md](AGENT_LOGIC_SUMMARY.md)
  - [AGENT_LOGIC_DIAGRAM.md](AGENT_LOGIC_DIAGRAM.md)
- If you change deterministic eval behavior, update the relevant Freud
  scenarios and fixtures as part of the same change.

## Build and Test

Run the smallest useful validation first, then expand as needed.

Typical checks:

```bash
./gradlew test
```

For non-trivial work, the preferred deterministic workflow is:

```bash
freud/scripts/feature-loop.sh <feature-id>
```

Useful targeted commands:

```bash
freud/scripts/run-reasoning-pr-gate.sh
freud/scripts/run-scenarios.sh --file freud/scenarios/v1/neopsyke-agent-scenarios.json
PYTHONPATH=. freud/.venv/bin/pytest freud/tests/test_*_py.py freud/tests/test_common.py -v
```

Rules:

- Do not run live/provider-backed evals unless they are explicitly needed and
  the required credentials are available.
- If you cannot run a relevant test, say so clearly in the pull request.

## Branch Naming

Use the format `<type>/<short-description>` with lowercase kebab-case:

```
feat/memory-recall-boost
fix/planner-null-fallback
refactor/prompt-budget
docs/freud-workflow
```

Valid types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`.

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>
```

- **type** (required): one of `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`.
- **scope** (optional but encouraged): the module or area touched (e.g. `memory`,
  `planner`, `ego`, `freud`).
- **summary**: imperative mood, lowercase, no trailing period, under 72 characters.
- **Breaking changes**: append `!` after scope — `feat(planner)!: remove legacy
  action format` — or add a `BREAKING CHANGE:` footer in the commit body.

Examples:

```
feat(memory): add episodic recall confidence threshold
fix(planner): handle null action in fallback path
refactor(ego): simplify reset-for-new-input flow
docs(freud): clarify deterministic workflow
```

## Pull Request Guidance

- Keep pull requests small enough to review.
- Explain what changed, why it changed, and how you validated it.
- Mention any risks, follow-ups, or intentionally deferred work.

## Security

Do not report security issues in public issues or discussions.

See [SECURITY.md](SECURITY.md) for private reporting guidance.

## Code of Conduct

All contributors, maintainers, and reviewers are expected to follow
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
