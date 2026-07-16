# Production package map

PocketTune's top-level classes register the mod, content, networking, configs and lifecycle hooks. Feature logic belongs in the focused child packages; do not grow top-level coordinators into general service locators.

- `block`: world interaction and speaker BlockEntity ownership
- `model`: validated state/value objects
- `network`: payload codecs and handlers
- `service`: bounded server-side resolution
- `audio`: tool/process/spatial primitives
- `client`: local playback, screens, overlays and diagnostics
- `config`: NeoForge config definitions and runtime constraints

See the repository `ARCHITECTURE.md` for cross-package data flow and invariants.
