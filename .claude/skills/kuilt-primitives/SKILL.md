---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, shared-state, telemetry or scheduling code in a repo that depends on kuilt — it probably already ships the primitive you'd hand-roll. Fires on rejoin/reconnect/resume after a drop, a grace window or held seat, a room/lobby/table container, a seat or peer roster, an id every peer must agree on, presence/heartbeat/liveness/lastSeen/idle-reaper, a countdown to a seat's release, your own outage versus a peer's, reaping a room nobody joined, host election, propose/commit turns, retry back-off, dedup/seenIds, max payload size, chunking a blob, FrameTooLargeException, sending a frame to yourself, state that must converge across peers (last-write-wins, sets, shared JSON), capping or trimming a replicated log, a conditional write, two guests that cannot see each other, a room too big for everyone-talks-to-everyone (N², gossip), federated learning, a server holding the log clients propose into, peer discovery, ghost peers, one bad browse feed killing the rest, writing a fabric (a peer id off the wire, a seam's terminal state, bridging a byte stream or TCP), a long-lived flow pump (launchIn), dealing cards nobody can peek at, an unbiasable random value, fair-share scheduling, quota ledgers, placement by peer capability, a content-hash blob cache, running code another peer sent you, on-device log capture, slow logging, per-session log context, durable storage surviving a crash, and an archive of history the live replica forgets. Routes to the existing primitive.
---

# kuilt primitives — check before you build

kuilt almost certainly already ships the networking, session, shared-state, telemetry or
scheduling thing you are about to write. Read the **one** file your symptom's route names.

## Don't build this yourself

Each `Jump to` is a file under `docs/agent-cookbook/` — prepend that directory for the repo path.
Symptoms sharing a section share a row, and within one the Nth `;`-separated symptom group pairs
with the Nth `;`-separated primitive group; `docs/agent-cookbook.md` splits them out.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a rejoin / reconnect loop, a resume token, a grace window; why a rejoin was refused|`Room.resumeToken` + `Room.resume`; `ResumeResult.JoinerOutcome` (`code.retryable`)|`session.md#rejoin--reconnect`|
|a "why did we drop" classifier; a retry/back-off loop; a per-session id every peer agrees on|`MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`); `ExponentialBackoff`; `Room.roomId` or `RoomFactory.host(pattern, roomId = …)`|`session.md#rejoin--reconnect`|
|a heartbeat, an idle reaper, "is this peer alive"; reaping a room nobody joined; a `pendingSeats` map|`HeartbeatPartitionDetector`; `SoloDeadlineDetector`; `JoinerReconnectController`|`session.md#liveness--presence`|
|a "reconnecting…" flag, a `lastSeen` map, a seat-release countdown; that surface for a **game**; "you are offline" vs "theirs"|`Room.roster` + `Member.liveness` + `Liveness.Partitioned(since, windowExpiresAt)` (not `Room.events`); `RoomGameSession.presence`/`.roster` via `gameOverRoom`; `Room.localFabric` + `MembershipEvent.LocalFabricLost`/`LocalFabricRestored`|`session.md#liveness--presence`|
|a `host == selfId` check, re-electing when the host walks out mid-lobby|`SeamRoomFactory.electLobby` → `ElectionLobby.awaitRoom` → `ElectionOutcome.BecameHost` → `start()`|`session.md#host-election--the-lobby`|
|a propose→commit turn/session facade, host election with a term|`GameSession` + `TurnSequencer`|`consensus.md#consensus--turns`|
|a server holding the log clients propose into, a client outliving its server|`ClusterClient` (`clusterClient`/`clusterClientWithNode`) + `ServerCluster` (`serverCluster`)|`consensus.md#when-one-of-the-peers-is-a-server`|
|a Raft term/vote/log surviving a restart, an `expect`/`actual` `RaftStorage`|`DurableStoreRaftStorage.open(store)` + `RaftStorageConformanceSuite`|`consensus.md#durable-consensus-state`|
|a last-write-wins register, a grow-only or add/remove set, a shared JSON document; trimming a log that only grows; wanting the *edits* not the value|`LWWRegister`/`LWWMap`, `GSet`/`GCounter`/`PNCounter`, `ORSet`/`ORMap`, `BoundedCounter`, `Causal`, `EphemeralMap`, `JsonCrdt` (`Patch`); `Rga.dropWindow` (+ `VersionVector.contiguous`); `OpLogCrdt` (`operations()`/`classify()`/`dotOf()`)|`replication.md#replicated-data`|
|replicating a CRDT by hand; "claim it only if it's free"; a hop so two guests can see each other; averaging model updates without collecting the data; comparing two peers' state as one number|`Quilter`; `Quilter.mutateOrSkip { … }` (`null` declines); `Room.channel(id)`; `FedAvg` + `TrainingUpdate`; `canonicalDigest`|`replication.md#replicated-data`|
|a session too big for everyone-talks-to-everyone — N² links, memory growing with the room|`GossipSeam` + `deltaTargets = { gossip.activePeers.value }` (`recommendedActiveViewSize`, `FullFanout`, `hostedOverlay`)|`replication.md#scaling-to-many-peers`|
|dealing cards nobody can peek at — "the dealer could cheat"|`DealSession` + `SraScheme()` (a `CommutativeScheme`)|`replication.md#dealing-cards-nobody-can-peek-at`|
|a shared random nobody could steer — a seed, a first player, a die roll|`FairRandom.roll()`|`replication.md#nobody-chose-that-number`|
|a `seenIds` set|`GSet` / kuilt dedup|`replication.md#dedup`|
|a longer-retention copy of a replicated log; replaying it, a resume cursor, "did it stop?"|`BoltDecorator` + `AppliedOpSink` (`WarpLogRecordExporter(appliedOps = …)`); `Bolt.replay` + `ReplayScope` → `CleanTail`/`Truncated`; `Bolt.durability()`|`replication.md#archiving-what-the-live-replica-forgets`|
|merging discovery feeds into one roster; a `try`/`catch` per browse feed|`discoveryRoster` (`onSourceFailure`) — sources are isolated already|`fabrics.md#discovery`|
|a sweeper over a discovery list — peers that left still on screen|`PeerDiscoverySource.departures()` + `DiscoverySourceConformanceSuite`|`fabrics.md#peers-pile-up-and-are-never-removed`|
|a peer-supplied id straight into a `Set<PeerId>`|`PeerIdentityRegistry`|`fabrics.md#a-peer-id-off-the-wire-straight-into-a-setpeerid`|
|a 4-byte length prefix and a reassembly loop over a socket|`framed()` → `handshaking()`|`fabrics.md#your-own-transport`|
|that same plumbing when the transport really is just TCP|`TcpLoom.host` / `TcpLoom.join`|`fabrics.md#plain-tcp-is-already-assembled`|
|looping a frame back to yourself, a `peers.forEach { sendTo(it, …) }` fan-out|`broadcast`; `peers` includes `selfId`, and `sendTo(selfId, …)` throws|`fabrics.md#sending-to-yourself`|
|a hard-coded chunk size, a `FrameTooLargeException`|`Room.maxPayloadBytes`/`Seam.maxPayloadBytes`, then `PayloadTooLarge`|`fabrics.md#payload-limits`|
|a `try` inside `onEach { … }.launchIn(scope)`, a pump that dies silently|`Flow.pumpIn(scope, onFailure, name) { … }` — `PumpFailure.ITEM` vs `UPSTREAM`|`fabrics.md#long-lived-pumps`|
|a `MutableStateFlow<SeamState>`, or a `closed`/`tornDown` flag beside one|`SeamStateGate`|`fabrics.md#a-seams-terminal-state`|
|bytes outliving the process — an `fsync`-rename dance, an IndexedDB wrapper|`DurableStore` + `StoreKey` (`InMemoryDurableStore`, `FileChannelDurableStore`, `NSFileManagerDurableStore`, `IndexedDbDurableStore`)|`fabrics.md#durable-storage`|
|a fair-share scheduler, "3× the share"; a quota ledger, "reserve then charge once"; minting quota at runtime; gating a `WarpNode` by lane|`HeddlePolicy.pick` + `HeddleNode`; `EntitlementLedger` + `HeddleNode.reserve`/`complete`; `heddleGoverned` (`GovernedHeddleNode`); `HeddleAdmissionControl` + `TaskDescriptor.inLane`|`heddle.md#fair-share--placement`|
|"only run on a GPU / in-region peer", a placement predicate|`Affinity` + `TaskDescriptor.where` + `CapSet`|`warp.md#where-a-task-may-run-location-eligibility`|
|a content-hash blob cache; running code another peer sent you, an `eval`|`Creel` + `BobbinExchange`; `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch`|`warp.md#code-mobility`|
|a flush-per-line log exporter, "logging stalls the app"; stamping a line's session/game; resetting a telemetry store; a counter of whether telemetry still lands|`WarpLogRecordExporter.export(records)` + `installLogCapture`; `withLogContext(attributes) { … }`, **not** `CaptureConfig.attributeMapper`; `WarpTelemetry.clear()`; `WarpLogRecordExporter.health` (`ExporterHealth`) + `LogCaptureInstallation.health` (`CaptureHealth.droppedEvents`)|`otel.md#telemetry--log-capture`|

## Fetching a route

Fetch the **one** file a route names, never the whole cookbook. `<path>` is
`docs/agent-cookbook/<jump-to file>`, or `docs/agent-cookbook.md` for the index. Inside kuilt,
read `<path>` directly; from a consumer repo take the first rung that works:

1. `git -C ../kuilt fetch -q origin main && git -C ../kuilt show origin/main:<path>` — kuilt
   side-by-side. Read `origin/main`, never its working tree: that can sit weeks behind.
2. `gh api -H 'Accept: application/vnd.github.raw' "repos/tractat-us/kuilt/contents/<path>?ref=main"`
   — no checkout; quote it, zsh globs the `?`. Works on a private repo; `blob` does not.
3. `curl -fsSL https://raw.githubusercontent.com/tractat-us/kuilt/main/<path>` — no `gh` either.

**Editing this skill:** the `description:` is **eager** and truncated at 1,536 characters, so a
phrase past that routes nothing — **adding a trigger means removing one**, never append.
`verifySkillDescriptionBudget` enforces that cap and the `: `/` #` that break the frontmatter's
YAML; the argument is in kuilt's root `CLAUDE.md`.
