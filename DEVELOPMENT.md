# Development guide

## Prerequisites

- Git
- JDK 21
- `yt-dlp` and `mpv` for client/media work
- Minecraft assets and NeoForge dependencies downloaded by Gradle

Run `setup.ps1` on Windows or `setup.sh` on macOS/Linux. The scripts install missing prerequisites where the platform package manager supports them, create ignored development-only tool overrides and run deterministic JUnit tests. They do not launch Minecraft.

## Common commands

| Goal | Windows | macOS/Linux |
|---|---|---|
| Environment audit | `.\setup.ps1 -CheckOnly` | `./setup.sh --check-only` |
| Unit tests | `.\gradlew.bat test --no-daemon` | `./gradlew test --no-daemon` |
| Clean production build | `.\gradlew.bat clean build --no-daemon` | `./gradlew clean build --no-daemon` |
| Client (manual only) | `.\gradlew.bat runClient` | `./gradlew runClient` |
| Dedicated server (manual only) | `.\gradlew.bat runServer` | `./gradlew runServer` |

The production JAR is generated under `build/libs`. Default verification must never run `runClient`, `runServer`, `mpvLifecycleTest` or `playlistResolverTest`; those tasks can start a game or real external media process and require explicit operator intent.

## Workflow

1. Read `AGENTS.md` and `ARCHITECTURE.md`.
2. Inspect `git status` and preserve unrelated work.
3. Reproduce or model the behavior at the smallest owning layer.
4. Implement the complete change without placeholder paths or duplicate sources of truth.
5. Add deterministic tests for state, validation, races and failure paths.
6. Run targeted tests while iterating, then one clean `test` and `build` before handoff.
7. Run `git diff --check`; update user-facing/config/architecture documentation when behavior changed.

## Code standards

- Java 21, UTF-8, four-space indentation and package-private helpers where public API is unnecessary.
- Prefer immutable records/value objects and explicit ownership over global mutable state.
- Keep network codecs bounded and symmetrical. Reject invalid values before world access.
- Do not block Minecraft render/server threads with network, process or filesystem work.
- Shutdown and cancellation paths must be idempotent and deadline-bounded.
- User-facing text belongs in language resources when it is stable interface copy.
- Logs should identify a category/session, not leak signed media URLs, raw tool output or private paths.

## Manual validation

Maintainers should manually verify interactive rendering, real playback and multiplayer only after automated checks pass. The checklist in `PROJECT_STATUS.md` covers placement/break behavior, ESC child menus, GUI scale, portable transfer, first-track startup and debug overlays.

## Release checklist

1. Update `mod_version`, changelog and status.
2. Run `clean test` and `clean build` once with configuration cache disabled.
3. Inspect the JAR for NeoForge metadata, `META-INF/LICENSE_pockettune` and `META-INF/NOTICE_pockettune`.
4. Confirm sources/tests/local runtime files are not packaged.
5. Record the SHA-256 and attach the exact tested JAR to the GitHub release.
