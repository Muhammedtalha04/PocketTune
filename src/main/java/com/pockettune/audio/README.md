# Audio package

Owns executable discovery, direct no-shell process launches, mpv IPC, startup leases, cleanup, equalizer filters and shared spatial-audio math. Every process/request/wait must be bounded and cancellation-safe. Signed stream URLs and raw tool output must not enter persistent state or normal logs.
