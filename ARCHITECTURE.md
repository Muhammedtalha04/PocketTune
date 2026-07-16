# PocketTune architecture

PocketTune is a NeoForge 1.21.4 mod with a server-authoritative model and client-local audio. This document is the map for maintainers and AI agents; implementation detail belongs close to the code.

## System boundaries

```text
YouTube URL
   │
   ▼
server PlaylistResolutionService ── yt-dlp metadata
   │ validated queue/state packets
   ▼
SpeakerBlockEntity / portable DataComponent
   │ synchronized state
   ▼
client playback manager ── yt-dlp stream URL ── mpv IPC/audio
   │
   ├─ responsive speaker GUI
   ├─ portable overlay
   └─ opt-in debug visualizations
```

The server owns URLs, metadata, queue order, speaker settings, playback intent and the authoritative timeline. A client may request a mutation, but the server revalidates identity, loaded chunk, distance, permissions, bounds and operation freshness. Signed media URLs and native audio processes remain local to each client.

## Source map

| Package | Ownership |
|---|---|
| `com.pockettune` | Registration and top-level lifecycle wiring |
| `block`, `block.entity` | Speaker interaction, persistence, server ticking and state publication |
| `model` | Validated value objects, portable state, timeline and settings |
| `network`, `network.payload` | Versioned payload codecs and server/client handlers |
| `service` | Bounded asynchronous playlist resolution |
| `audio` | Tool lookup, process control, mpv IPC, equalizer and spatial math |
| `client.audio` | Local session ownership, inventory playback and controller transfer |
| `client.screen`, `client.gui` | Responsive screens, panels, widgets and notifications |
| `client.gui.overlay` | Portable now-playing HUD layout and rendering |
| `client.debug` | Opt-in spatial and block-interaction diagnostics |
| `config` | NeoForge client/common/server config definitions and constraints |

Resources under `src/main/resources` contain assets, language files, models, loot tables and pack metadata. Tests mirror production packages under `src/test/java`.

## Critical lifecycles

### URL resolution

1. The client sends a bounded URL request with block position and placement UUID.
2. The server checks speaker identity, loaded chunk, edit permissions, reach and request rate.
3. `PlaylistResolutionService` validates the YouTube URL and runs `yt-dlp` in a bounded executor.
4. The matching operation may atomically replace/append the validated queue; stale results are discarded.
5. State and one-shot GUI feedback are sent to relevant clients.

### Audio process

1. Synced playable state creates or transfers one local controller.
2. Tool paths are resolved without a shell; startup is capacity-bounded and cancellation-aware.
3. mpv starts muted and paused, becomes ready, seeks once, validates progress, then unmutes.
4. Range, attenuation, wall occlusion and equalizer update through bounded IPC requests.
5. Stop, unload, logout, world change, controller transfer and JVM shutdown share idempotent cleanup.

### Portable pickup

Pickup is a server transaction: validate the exact speaker placement, consume the related vanilla interaction, remove the block first, then grant the portable state. Placement receives a new placement UUID so delayed packets cannot mutate a different speaker at the same coordinates.

## Design invariants

- Never trust client positions, indices, enum ordinals, durations, completion reports or operation IDs.
- Never load a chunk because an untrusted packet named a position.
- Never invoke external tools through a shell or expose raw signed URLs/process output in logs.
- Native processes, executors, caches, packet payloads and retained debug history must be bounded.
- GUI actions only express intent; durable state changes happen on the logical server.
- Server config limits loaded and portable state, including after runtime reload.
- Minecraft, real `yt-dlp` and real `mpv` smoke tests are opt-in manual actions, not default CI.

## Adding a feature

Start from the owner package, add a model-level invariant, add or revise a versioned payload only when needed, then add deterministic tests. Update this document only if module ownership or a trust boundary changes.
