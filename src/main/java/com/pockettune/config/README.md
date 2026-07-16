# Config package

Client config owns local UI/audio/debug preferences, common config owns machine-local executable paths, and server config owns gameplay limits. Preserve those ownership boundaries and constrain loaded/portable state when server limits change.
