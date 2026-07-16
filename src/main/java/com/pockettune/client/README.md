# Client package

Owns local audio sessions, responsive screens, reusable GUI panels/widgets, the portable overlay and opt-in diagnostics. It may display/request state but must not become authoritative. Render-thread work stays lightweight; network, thumbnail and process work stays asynchronous and bounded.
