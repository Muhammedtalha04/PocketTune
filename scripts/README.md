# Development scripts

- `setup-dev.ps1`: Windows/WinGet dependency detection, installation, local tool configuration and JUnit verification.
- `setup-dev.sh`: macOS/Linux equivalent using the available system package manager.

Both scripts deliberately avoid launching Minecraft and real media playback. Use `--check-only` (PowerShell: `-CheckOnly`) for a read-only environment audit.
