# Contributing to PocketTune

Contributions are welcome: bug fixes, compatibility improvements, accessibility work, translations, documentation and carefully scoped features.

## Before opening a change

1. Search existing issues and describe the player-visible problem or outcome.
2. Run the one-command setup from the README.
3. Read `AGENTS.md`, `ARCHITECTURE.md` and `DEVELOPMENT.md`.
4. Keep each pull request focused; avoid unrelated formatting or generated-file churn.

## Pull requests

- Explain the root cause and the chosen ownership layer.
- Include deterministic tests for logic, packets, lifecycle or validation changes.
- State what was not tested manually. Never claim Minecraft/mpv testing that was not performed.
- Update configuration and user documentation when defaults or behavior change.
- Ensure `./gradlew clean build --no-daemon` and CI pass.
- Do not commit `run`, `build`, logs, saves, IDE settings, downloaded tools or secrets.

By submitting a contribution, you agree that it is licensed under Apache-2.0, the repository license.

## Attribution in derivatives

Forks and derivative projects are permitted. Redistributions must retain the Apache-2.0 license and applicable `NOTICE` attribution. A README link to the original [PocketTune repository](https://github.com/Muhammedtalha04/PocketTune) and [author profile](https://github.com/Muhammedtalha04) is appreciated.
