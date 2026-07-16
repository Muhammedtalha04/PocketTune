# Service package

Owns bounded asynchronous server services such as playlist resolution. Services are created per active server, reject overload predictably, discard stale operations and shut down without blocking the server thread indefinitely.
