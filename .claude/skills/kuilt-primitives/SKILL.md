---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, shared-state, telemetry or scheduling code in a repo that depends on kuilt — it probably already ships the primitive you'd hand-roll. Fires on rejoin/reconnect/resume after a drop, a grace window or held seat, a room/lobby/table container, a seat or peer roster, an id every peer must agree on, presence/heartbeat/liveness/lastSeen/idle-reaper, a seat-release countdown, your own outage versus a peer's, reaping a room nobody joined, host election, propose/commit turns, retry back-off, dedup/seenIds, max payload size, chunking a blob, FrameTooLargeException, sending a frame to yourself, frames lost before you subscribed, a last write lost to close, state that must converge across peers (last-write-wins, sets, shared JSON), capping or trimming a replicated log, a conditional write, two guests that cannot see each other, a room too big for all-to-all (N², gossip), federated learning, a server holding the log clients propose into, peer discovery, ghost peers, a bad browse feed, writing a fabric (a peer id off the wire, a seam's terminal state, a byte stream or TCP, a socket a cancelled dial left open), a long-lived flow pump (launchIn), dealing cards nobody can peek at, an unbiasable random value, fair-share scheduling, quota ledgers, placement by peer capability, a content-hash blob cache, running code another peer sent you, on-device log capture, slow logging, per-session log context, durable storage, sealing bytes to a public key, and an archive the live replica forgets.
---

# kuilt primitives — check before you build

Read the **one** cookbook file your symptom names.

## Don't build this yourself

Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a rejoin/reconnect loop, a resume token, a grace window; why a rejoin was refused|`Room.resumeToken` + `Room.resume`; `ResumeResult.JoinerOutcome` (`code.retryable`)|`session.md#rejoin--reconnect`|
|a device key/did:key kept across launches, recognising a returning device; a "remember this device" pass, revoking it across restarts; who joins|`DeviceKeyStore.platform` (JVM; `DeviceKeyStoreConformanceSuite`) → `DeviceKey` + `DidKey`; `Grant` + `GrantTrust`/`RevocationList.open`; `AdmissionPolicy`/`HostAdmission`|`session.md#device-identity--return`|
|a "why did we drop" classifier; a retry/back-off loop; a per-session id every peer agrees on|`MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`); `ExponentialBackoff`; `Room.roomId`/`RoomFactory.host(…, roomId = …)`|`session.md#rejoin--reconnect`|
|a heartbeat, an idle reaper, "is this peer alive"; reaping a room nobody joined; a `pendingSeats` map|`HeartbeatPartitionDetector`; `SoloDeadlineDetector`; `JoinerReconnectController`|`session.md#liveness--presence`|
|a "reconnecting…" flag, a `lastSeen` map, a seat-release countdown; that surface for a **game**; "you are offline" vs "theirs"|`Room.roster` + `Member.liveness` + `Liveness.Partitioned(since, windowExpiresAt)` (not `Room.events`); `RoomGameSession.presence`/`.roster` via `gameOverRoom`; `Room.localFabric` + `MembershipEvent.LocalFabricLost`/`LocalFabricRestored`|`session.md#liveness--presence`|
|a per-member reader as `room.incoming.filter { it.sender == peer }`, a member's first frame lost before its collector subscribed|`Room.incomingFrom` (`FramesLost`, never silent)|`session.md#per-member-frames`|
|a `delay` between a last send and `leave`/`close`|`Room`/`Seam.flush` → `FlushOutcome` (often `Unsupported`: keep your delay or ack)|`session.md#a-last-frame-before-leaving`|
|a `host == selfId` check, re-electing when the host leaves mid-lobby|`SeamRoomFactory.electLobby` → `ElectionLobby.awaitRoom` → `ElectionOutcome.BecameHost` → `start()`|`session.md#host-election--the-lobby`|
|a propose→commit turn/session facade, host election with a term|`GameSession` + `TurnSequencer`|`consensus.md#consensus--turns`|
|a server holding the log clients propose into, a client outliving its server|`ClusterClient` (`clusterClient`/`clusterClientWithNode`) + `ServerCluster` (`serverCluster`)|`consensus.md#when-one-of-the-peers-is-a-server`|
|a Raft term/vote/log surviving a restart, an `expect`/`actual` `RaftStorage`|`DurableStoreRaftStorage.open(store)` + `RaftStorageConformanceSuite`|`consensus.md#durable-consensus-state`|
|a last-write-wins register, a grow-only or add/remove set, a shared JSON document; trimming a log that only grows; wanting the *edits* not the value|`LWWRegister`/`LWWMap`, `GSet`/`GCounter`/`PNCounter`, `ORSet`/`ORMap`, `BoundedCounter`, `Causal`, `CausalMap`, `EphemeralMap`, `JsonCrdt` (`Patch`); `Rga.dropWindow` + `VersionVector.contiguous`; `OpLogCrdt` (`operations`/`classify`/`dotOf`)|`replication.md#replicated-data`|
|replicating a CRDT by hand; "claim it only if it's free"; a hop so two guests can see each other; averaging model updates without collecting the data; comparing two peers' state as one number; a write lost to close|`Quilter`; `Quilter.mutateOrSkip { … }`; `Room.channel(id)`; `FedAvg` + `TrainingUpdate`; `canonicalDigest`; `Quilter.drainAndClose`|`replication.md#replicated-data`|
|a session too big for all-to-all — N² links|`GossipSeam` + `deltaTargets = { gossip.activePeers.value }` (`recommendedActiveViewSize`, `FullFanout`, `hostedOverlay`)|`replication.md#scaling-to-many-peers`|
|dealing cards nobody can peek at — "the dealer could cheat"|`DealSession` + `SraScheme()`|`replication.md#dealing-cards-nobody-can-peek-at`|
|a shared random nobody could steer — a seed, a first player, a die roll|`FairRandom.roll()`|`replication.md#nobody-chose-that-number`|
|a `seenIds` set|`GSet`/kuilt dedup|`replication.md#dedup`|
|a longer-lived copy of a replicated log; replaying it, a resume cursor, "did it stop?"|`BoltDecorator` + `AppliedOpSink` (`WarpLogRecordExporter(appliedOps = …)`); `Bolt.replay` + `ReplayScope` → `CleanTail`/`Truncated`; `Bolt.durability()`|`replication.md#archiving-what-the-live-replica-forgets`|
|merging discovery feeds into one roster; a `try`/`catch` per browse feed|`discoveryRoster` (`onSourceFailure`)|`fabrics.md#discovery`|
|a sweeper over a discovery list — peers that left still on screen|`PeerDiscoverySource.departures()` + `DiscoverySourceConformanceSuite`|`fabrics.md#peers-pile-up-and-are-never-removed`|
|a peer-supplied id straight into a `Set<PeerId>`|`PeerIdentityRegistry`|`fabrics.md#a-peer-id-off-the-wire-straight-into-a-setpeerid`|
|a 4-byte length prefix, a reassembly loop over a socket|`framed()` → `handshaking()`|`fabrics.md#your-own-transport`|
|that, over plain TCP|`TcpLoom.host`/`.join`|`fabrics.md#plain-tcp-is-already-assembled`|
|a bare `webSocketSession`; a socket a cancelled dial left open|`ownedWebSocketSession`|`fabrics.md#a-cancelled-dial`|
|looping a frame back to yourself, a `peers.forEach { sendTo(it, …) }` fan-out|`broadcast`; `sendTo(selfId, …)` throws|`fabrics.md#sending-to-yourself`|
|a hard-coded chunk size, a `FrameTooLargeException`|`Room`/`Seam.maxPayloadBytes`, then `PayloadTooLarge`|`fabrics.md#payload-limits`|
|a `try` inside `onEach { … }.launchIn(scope)`, a pump that dies silently|`Flow.pumpIn` — `PumpFailure.ITEM` vs `UPSTREAM`; `onFailure` returns `PumpVerdict.CONTINUE`/`STOP`|`fabrics.md#long-lived-pumps`|
|a `MutableStateFlow<SeamState>`, or a `closed`/`tornDown` flag beside one|`SeamStateGate`|`fabrics.md#a-seams-terminal-state`|
|bytes outliving the process — an `fsync`-rename dance, an IndexedDB wrapper|`DurableStore` + `StoreKey` (`InMemoryDurableStore`, one per platform)|`fabrics.md#durable-storage`|
|encrypting to a public key, bytes only one recipient can open|`SealedBox.seal`/`open` + `SealPublicKey`/`SealPrivateKey`|`replication.md#sealed-boxes`|
|a fair-share scheduler, "3× the share"; a quota ledger, "reserve then charge once"; minting quota at runtime; gating a `WarpNode` by lane; config trees, reloads, a lease per slot or why it waits, orphans, a restart|`HeddlePolicy.pick` + `HeddleNode`, offline `FairShareAllocator`; `EntitlementLedger` + `HeddleNode.reserve`/`complete`; `heddleGoverned`; `HeddleAdmissionControl` + `TaskDescriptor.inLane`; `Topology.from`/`lower`/`diff`/`attachRuntimeChild`, `LocalHeddle` + `apply`/`reconcileLeases`/`persist`/`restore`|`heddle.md#fair-share--placement`|
|"only run on a GPU / in-region peer", a placement predicate|`Affinity` + `TaskDescriptor.where` + `CapSet`|`warp.md#where-a-task-may-run-location-eligibility`|
|a content-hash blob cache; running code another peer sent you, an `eval`|`Creel` + `BobbinExchange`; `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch`|`warp.md#code-mobility`|
|a flush-per-line log exporter, "logging stalls the app"; stamping a line's session/game; resetting a telemetry store; is telemetry still landing|`WarpLogRecordExporter.export(records)` + `installLogCapture`; `withLogContext { … }`, **not** `CaptureConfig.attributeMapper`; `WarpTelemetry.clear()`; `WarpTelemetry.health` + `LogCaptureInstallation.health` (`CaptureHealth.droppedEvents`)|`otel.md#telemetry--log-capture`|

## Fetching a route

`<path>`: `docs/agent-cookbook/<jump-to file>` or the index `docs/agent-cookbook.md`.
In kuilt, read it directly; consumers take the first working rung:

1. `git -C ../kuilt fetch -q origin main && git -C ../kuilt show origin/main:<path>` (not the checkout)
2. `gh api -H 'Accept: application/vnd.github.raw' "repos/tractat-us/kuilt/contents/<path>?ref=main"`
3. `curl -fsSL https://raw.githubusercontent.com/tractat-us/kuilt/main/<path>`

**Editing this skill:** description ≤ 1,536 chars, body ≤ 8 KiB.
Move prose to `docs/agent-cookbook*`, never drop a route.
