---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, shared-state, telemetry or scheduling code in a repo that depends on kuilt — it likely ships it already. Fires on rejoin/reconnect/resume after a drop, a grace window/held seat, a room/lobby/table container, a seat/peer roster, an id all peers agree on, presence/heartbeat/liveness/lastSeen/idle-reaper, a seat-release countdown, your outage vs a peer's, reaping a room nobody joined, host election, propose/commit turns, retry back-off, dedup/seenIds, max payload size, chunking a blob, FrameTooLargeException, sending a frame to yourself, frames lost before you subscribed, a last write lost to close, state that must converge across peers (last-write-wins, sets, shared JSON), trimming a replicated log, a conditional write, two guests who can't see each other, a room too big for all-to-all (N², gossip), federated learning, a server holding the log clients propose into, peer discovery, ghost peers, a bad browse feed, writing a fabric (a peer id off the wire, a seam's terminal state, a byte stream/TCP, a socket a cancelled dial left open), a long-lived flow pump (launchIn), dealing cards nobody can peek at, an unbiasable random value, fair-share scheduling, quota ledgers, a total that must not wrap, placement by peer capability, a content-hash blob cache, running code another peer sent you, on-device log capture, slow logging or metrics, per-session log context, durable storage, a kuilt version stamp, sealing bytes to a public key, and an archive the live replica forgets.
---

# kuilt primitives — check before you build

kuilt very likely ships the thing you are about to write. The four below are the ones
written by hand most often, so they route from here. For anything else, open the one
reference file your family names: it lists every route, row by row.

## The four everyone hand-rolls

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a rejoin/reconnect loop, a resume token, a grace window|nothing to call: the room says Hello again; `DeviceKeyStore` on the factory|`session.md#rejoin--reconnect`|
|a last-write-wins register, a set, a shared JSON document; replicating a CRDT by hand|`LWWRegister`/`LWWMap`, `GSet`, `ORSet`/`ORMap`, `JsonCrdt`; `Quilter`|`replication.md#replicated-data`|
|a `try` inside `onEach { … }.launchIn(scope)`, a pump that dies silently|`Flow.pumpIn` (`PumpFailure.ITEM`/`UPSTREAM`)|`fabrics.md#long-lived-pumps`|
|a hard-coded chunk size, a `FrameTooLargeException`|`Room`/`Seam.maxPayloadBytes`, then `PayloadTooLarge`|`fabrics.md#payload-limits`|

## Everything else — read your family's file

Paths are relative to this skill's directory. Each file is a few rows; read it whole.

|Family|Read|What it routes, and its top symbols|
|---|---|---|
|Session|`reference/session.md`|rejoin and why a drop happened, a device's key and its pass back in, liveness and presence, per-member frames, a last frame, host election — `Room`, `DeviceKeyStore`, `Grant`, `AdmissionPolicy`, `HeartbeatPartitionDetector`, `JoinerReconnectController`, `Room.incomingFrom`, `Room.flush`, `SeamRoomFactory.electLobby`|
|Replication|`reference/replication.md`|converging state, a room too big for all-to-all, dealing, fair random, dedup, overflow-safe totals, archives, sealed boxes — the CRDT zoo, `Quilter`, `GossipSeam`, `DealSession`, `FairRandom`, `mulDiv`/`WideSum`, `BoltDecorator`, `SealedBox`|
|Fabrics|`reference/fabrics.md`|discovery, a peer id off the wire, framing a stream, TCP, a cancelled dial, sending to yourself, a seam's terminal state, durable storage — `discoveryRoster`, `PeerIdentityRegistry`, `framed()`, `TcpLoom`, `ownedWebSocketSession`, `SeamStateGate`, `DurableStore`, `KuiltVersion`|
|Consensus|`reference/consensus.md`|propose/commit turns, a server holding the log, Raft state across a restart — `GameSession`, `TurnSequencer`, `ClusterClient`, `ServerCluster`, `DurableStoreRaftStorage`|
|Heddle|`reference/heddle.md`|fair-share scheduling and quota ledgers — `LocalHeddle`, `FairSharePool`, `HeddleNode`, `heddleGoverned`|
|Otel|`reference/otel.md`|log capture, log context, telemetry health, batched metrics — `installLogCapture`, `withLogContext`, `WarpTelemetry`, `WarpMetricExporter`|
|Warp|`reference/warp.md`|where a task may run, a blob cache, running code another peer sent — `Affinity`, `Creel`, `WasmRuntime`|

## Fetching a route

`<path>`: `docs/agent-cookbook/<jump-to file>` or the index `docs/agent-cookbook.md`.
In kuilt, read it directly; consumers take the first working rung:

1. `git -C ../kuilt fetch -q origin main && git -C ../kuilt show origin/main:<path>` (not the checkout)
2. `gh api -H 'Accept: application/vnd.github.raw' "repos/tractat-us/kuilt/contents/<path>?ref=main"`
3. `curl -fsSL https://raw.githubusercontent.com/tractat-us/kuilt/main/<path>`

`<path>` may also be `.claude/skills/kuilt-primitives/reference/<family>.md` on `main`, for a copy that lacks it.

**Editing this skill:** description ≤ 1,536 chars, this body ≤ 8 KiB, each `reference/` file ≤ 4 KiB.
A new route is a row in its family's file; past 4 KiB, split it into `reference/<family>/<section>.md`.
Name every reference file here. Move prose to `docs/agent-cookbook*`, never drop a route.
