# PocketTune agent guide

This file is the repository-wide source of truth for Codex, Claude Code, Cursor, Windsurf, Cline and other coding agents.

## Read first

1. `ARCHITECTURE.md` for module ownership and trust boundaries.
2. `DEVELOPMENT.md` for commands, standards and definition of done.
3. `PROJECT_STATUS.md` for the current release and manual-test boundary.

## Working rules

- Inspect `git status` before editing and preserve unrelated/user changes.
- Make the smallest complete production change in the owning package; no demos, placeholders, fake APIs or TODO implementations.
- Treat all client packets as untrusted. Validate identity, loaded chunks, reach/permission, bounds and operation freshness before world mutation.
- Keep network/process/filesystem work off Minecraft render and server tick threads.
- Keep processes, executors, queues, caches, payloads, logs and shutdown waits bounded.
- Never log signed media URLs, raw external-tool output, tokens or private filesystem paths.
- Use deterministic JUnit tests by default. Do not launch Minecraft or real `yt-dlp`/`mpv` smoke tests unless the user explicitly asks.
- During iteration run focused tests; before handoff run one clean `test`, one clean `build`, and `git diff --check`.
- Update documentation when user behavior, config, architecture, setup or release state changes.

## Definition of done

The implementation is complete, failure/cancellation paths are handled, relevant tests pass, the production JAR builds, documentation matches behavior, and no local runtime/build/secret files are included.
