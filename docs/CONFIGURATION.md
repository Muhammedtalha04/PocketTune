# Configuration

PocketTune uses NeoForge configuration ownership so machine paths, player preferences and server rules cannot overwrite one another.

## Client configuration

File: `config/pockettune-client.toml`. It can also be edited from **Mods → PocketTune → Config**.

| Group | Controls |
|---|---|
| `interface` | GUI notifications and duration, complete ESC pause-chain behavior, cover quality/cache, portable overlay and F1 visibility |
| `audioRuntime` | Hard local limit for concurrent mpv controllers (default 8, range 1–32) |
| `spatialAudio` | Full-volume distance, maximum wall reduction, occlusion smoothing and out-of-range grace |
| `testMode` | Range boundaries, attenuation layers, wall rays, measurement HUD, refresh/density/distance limits |
| `blockInteractionDebug` | Pickup/event/packet/BlockEntity/BlockState HUD and optional bounded log history |

Test and block-interaction debug modes are disabled by default. Enable them only while diagnosing behavior; high visualization density can add client render work.

## Common machine configuration

File: `config/pockettune-common.toml`.

```toml
[externalTools]
ytDlpPathOverride = ""
mpvPathOverride = ""
```

These paths belong to the current computer, not a world. Empty values search `PATH` and supported Windows WinGet package folders. A non-empty invalid override produces an explicit error and does not silently fall back. Relative paths resolve from the current Minecraft game directory.

The one-command development setup writes absolute overrides only into ignored `run` and `run-client` directories; it never changes a user's normal Minecraft installation.

## Server configuration

File: `<world>/serverconfig/pockettune-server.toml`.

| Key | Meaning |
|---|---|
| `speakerDefaults.defaultVolumePercent` | Volume assigned to newly created speakers, 0–100 |
| `speakerDefaults.maximumRangeBlocks` | Authoritative maximum range, 4–128 blocks |

The server constrains new, loaded and portable state to the active maximum. Clients cannot raise a speaker beyond it with packets.

## Recommended production values

Start with defaults. Lower the server maximum range and local mpv controller cap on dense public servers. Keep debug logging disabled unless collecting a short diagnostic trace; logs intentionally omit signed stream URLs and raw external-tool output.
