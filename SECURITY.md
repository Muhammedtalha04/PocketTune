# Security policy

## Supported version

Security fixes target the latest release on the `main` branch.

## Reporting a vulnerability

Please do not publish exploitable details in a public issue. Use GitHub's private vulnerability reporting for this repository. Include the affected version, impact, reproduction conditions and the smallest safe evidence required to verify it. Remove credentials, signed media URLs, private server addresses and personal filesystem paths.

## Security model

PocketTune treats clients and media metadata as untrusted. Server handlers validate loaded chunks, exact speaker placement identity, reach/permissions, operation freshness and bounded values. External tools are launched with direct argument arrays rather than a shell. Native-process count, async queues, caches, packets, debug history and shutdown waits are bounded.

`yt-dlp` and `mpv` are external projects and should be kept current through their official distribution channels.
