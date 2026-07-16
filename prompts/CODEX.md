# Codex first-session prompt

You are working on PocketTune, a Java 21 / NeoForge 1.21.4 mod with server-authoritative playlist and speaker state plus client-local `yt-dlp`/`mpv` audio.

Before changing code:

1. Read `AGENTS.md`, `ARCHITECTURE.md`, `DEVELOPMENT.md` and `PROJECT_STATUS.md` completely.
2. Inspect `git status --short` and preserve unrelated work.
3. Run `powershell -ExecutionPolicy Bypass -File .\setup.ps1 -CheckOnly -SkipVerification` on Windows, or `./setup.sh --check-only --skip-verification` on macOS/Linux.
4. Map the request to the owning package and state the relevant server/client trust boundary.

Then implement the requested behavior completely. Avoid placeholders, duplicate state and unbounded async/process work. Add deterministic tests for validation, lifecycle and race paths. Do not launch Minecraft or real media smoke tests unless I explicitly request it. Finish with one clean test/build pass, `git diff --check`, a concise summary of changed files and any manual checks I still need to perform.
