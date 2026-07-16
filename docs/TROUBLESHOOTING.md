# Troubleshooting

## PocketTune cannot find yt-dlp or mpv

Run the setup audit from the repository, or verify both commands in the same environment that starts Minecraft:

```powershell
.\setup.ps1 -CheckOnly -SkipVerification
```

Restart Minecraft after changing `PATH`. If the launcher does not inherit it, set absolute paths in `pockettune-common.toml`. Dedicated servers need `yt-dlp`; they do not need `mpv`.

## A track resolves but produces no sound

Update both tools, confirm the client can run them, and check PocketTune's bounded error category in the GUI/log. Try another supported YouTube URL to separate tool/network availability from media availability. Do not post signed stream URLs or complete raw process output in public issues.

## Playback pauses on screens

With pause behavior enabled, only a session that starts from the real ESC pause menu remains paused through its Options/Mods child screens. Inventory, chest, crafting, PocketTune and directly opened mod screens continue playing. Return to gameplay to resume an ESC pause session.

## Speaker reappears after pickup or break

Enable `blockInteractionDebug.enabled` and its HUD. Capture the ordered client/server interaction, request/result, removal callback, BlockEntity lifecycle and final BlockState comparison. Include PocketTune/NeoForge versions, game mode and whether the server is integrated or dedicated in a bug report.

## Debug rings are missing or expensive

Enable `testMode.enabled` and the individual visualization layers. Stay within `maximumDistance`; increase `ringSegments` for clarity or reduce speaker count/segments and increase refresh interval for performance.

## Reporting a reproducible bug

Use the GitHub bug template. Include steps, expected/actual behavior, environment, relevant bounded PocketTune logs and whether the issue occurs in a clean profile. Remove account names, filesystem paths, server addresses and tokens.
