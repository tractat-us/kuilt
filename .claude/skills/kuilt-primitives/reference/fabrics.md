# kuilt-primitives — fabrics

Routes for moving frames: discovery, a peer id off the wire, framing a byte stream, TCP, a cancelled dial, sending to yourself, payload limits, long-lived pumps, a seam's terminal state, durable storage.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|merging discovery feeds into one roster; a `try`/`catch` per browse feed|`discoveryRoster` (`onSourceFailure`)|`fabrics.md#discovery`|
|a sweeper over a discovery list — departed peers on screen|`PeerDiscoverySource.departures()` + `DiscoverySourceConformanceSuite`|`fabrics.md#peers-pile-up-and-are-never-removed`|
|a peer-supplied id straight into a `Set<PeerId>`|`PeerIdentityRegistry`|`fabrics.md#a-peer-id-off-the-wire-straight-into-a-setpeerid`|
|a 4-byte length prefix, a reassembly loop over a socket|`framed()` → `handshaking()`|`fabrics.md#your-own-transport`|
|that, over plain TCP|`TcpLoom.host`/`.join`|`fabrics.md#plain-tcp-is-already-assembled`|
|a bare `webSocketSession`; a socket a cancelled dial left open|`ownedWebSocketSession`|`fabrics.md#a-cancelled-dial`|
|looping a frame back to yourself, a `peers.forEach { sendTo(it, …) }` fan-out|`broadcast`; `sendTo(selfId, …)` throws|`fabrics.md#sending-to-yourself`|
|a hard-coded chunk size, a `FrameTooLargeException`|`Room`/`Seam.maxPayloadBytes`, then `PayloadTooLarge`|`fabrics.md#payload-limits`|
|a `try` inside `onEach { … }.launchIn(scope)`, a pump that dies silently|`Flow.pumpIn` (`PumpFailure.ITEM`/`UPSTREAM`, `PumpVerdict`)|`fabrics.md#long-lived-pumps`|
|a `MutableStateFlow<SeamState>` or a `closed` flag|`SeamStateGate`|`fabrics.md#a-seams-terminal-state`|
|bytes outliving the process, an `fsync`-rename dance; which kuilt wrote them|`DurableStore` + `StoreKey` (`InMemoryDurableStore`, one per platform); `KuiltVersion.VERSION` (`:kuilt-base`)|`fabrics.md#durable-storage`|
