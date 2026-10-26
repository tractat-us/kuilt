# Module kuilt-websocket

Connect peers through a WebSocket relay, and let each device report when its own network becomes
unusable. The caller supplies a Ktor HTTP client; JVM and Android callers can also host the relay.

## Device connectivity

Pass a caller-owned observer to `KtorClientLoom`'s `connectivity` parameter:
`appleConnectivityObserver()` on iOS/macOS, `androidConnectivityObserver(context)` on Android,
or `browserConnectivityObserver()` on wasmJs. Keep the observer and close it when its last consumer
is done. Closing a seam does not stop an observer shared with another loom.

The Apple observer starts a Network.framework path monitor. Before its first callback the reading
is `null`, so `Seam.capability` reports `Unknown`. Satisfied paths report `Available`; unsatisfied
paths report `Unavailable`; paths requiring a connection and invalid statuses report `Unknown`.
Closing the observer cancels the monitor and freezes the last reading, including when callbacks
arrive during cancellation. A new observer is needed to resume monitoring.

These readings describe the device's path, not relay health or guaranteed internet access. Leaving
`connectivity` unwired keeps capability unknown on every platform. The desktop JVM has no portable
OS observer and deliberately keeps that default.
