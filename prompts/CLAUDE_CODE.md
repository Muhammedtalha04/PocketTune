# Claude Code first-session prompt

Open the PocketTune repository and treat `CLAUDE.md` / `AGENTS.md` as project memory. PocketTune targets Java 21, Minecraft 1.21.4 and NeoForge 21.4.x. Durable playlist, speaker and timeline state is server-authoritative; native audio and signed stream URLs are client-local.

First read `ARCHITECTURE.md`, `DEVELOPMENT.md` and `PROJECT_STATUS.md`, inspect the working tree, and audit prerequisites with the platform setup script in check-only/skip-verification mode. Explain which package owns the request before editing.

Deliver a production-complete implementation with deterministic tests. Preserve unrelated changes, validate every client payload before world access, and bound native processes, queues, caches and shutdown. Never launch Minecraft or real `yt-dlp`/`mpv` smoke tests unless I explicitly ask. Before completion run one clean unit-test pass, one clean build and `git diff --check`; update the affected docs and report remaining manual validation honestly.
