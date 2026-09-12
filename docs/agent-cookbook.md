# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `Room.resumeToken` + `Room.resume` | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets | `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`), plus their `localFabric` tag | [Rejoin & reconnect](agent-cookbook/session.md#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](agent-cookbook/consensus.md#consensus--turns) |
| a `host == selfId` check plus a re-election when the peer that was hosting walks out mid-lobby | `ElectionLobby.awaitRoom` → `ElectionOutcome.BecameHost` → `start()` on the **same** lobby | [Host election & the lobby](agent-cookbook/session.md#host-election--the-lobby) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| "close a room nobody joined", "reap an abandoned table/lobby", "nobody ever showed up" | `SoloDeadlineDetector` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "hold the seat open" / reconnect grace window on the host, a `pendingSeats` or `disconnectedAt` map | `JoinerReconnectController` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "paused / reconnecting…" presence flag, a `lastSeen` map for greying out a player | `Room.roster` + `Member.liveness` — the level; `Room.events` is the notification | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| that same "paused / reconnecting…" surface for a **game** (not a bare room), a `room.events` → game-presence adapter | `RoomGameSession.presence` via `gameOverRoom` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a "you are offline" / "your connection dropped" indicator, distinguishing *your* outage from *their* outage | `Room.localFabric` + `MembershipEvent.LocalFabricLost` | [Liveness & presence](agent-cookbook/session.md#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| a conditional write to shared state — "claim it only if it's free", "publish only if nobody already did" — or an identity/no-op patch meaning "I decided not to write" | `Quilter.mutateOrSkip { … }` (return `null` to decline) | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| a forwarding hop through the host so two guests can see each other — because a `Quilter` between two joiners never converges, or a peer is in the roster but unreachable | `Room.channel(id)` — the room already relays | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| averaging model updates from many devices without collecting their data — federated learning / federated analytics, "train locally, share only the update" | `FedAvg` + `TrainingUpdate` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| checking two peers hold the same state across a process/socket boundary — hand-hashing a replicated state so you can compare it as one number | `canonicalDigest` | [Replicated data](agent-cookbook/replication.md#replicated-data) |
| splitting a big blob into frames — picking a chunk size, or chasing a `FrameTooLargeException` that only appears once a peer drops | `Room.maxPayloadBytes` / `Seam.maxPayloadBytes` | [Payload limits](agent-cookbook/fabrics.md#payload-limits) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](agent-cookbook/replication.md#dedup) |
| saving bytes so they survive a restart — a write-temp-then-`fsync`-then-atomic-rename dance, a per-platform file helper, an IndexedDB wrapper, "did that write actually land before we crashed?" | `DurableStore` + `StoreKey` | [Durable storage](agent-cookbook/fabrics.md#durable-storage) |
| a Raft node's term, vote and log that must survive a restart — a node that comes back having forgotten who it voted for, an `expect`/`actual` `RaftStorage` per platform, a SQLite or IndexedDB schema for consensus state | `DurableStoreRaftStorage.open(store)` | [Durable consensus state](agent-cookbook/consensus.md#durable-consensus-state) |
| a per-line flush loop in a log/telemetry exporter — or a fix for "capturing logs is slow", "the app stalls when it logs a lot" | `WarpLogRecordExporter.export(records)` + `installLogCapture` | [Telemetry & log capture](agent-cookbook/otel.md#telemetry--log-capture) |
| stamping the session/game/request a log line belongs to — an MDC equivalent, a global holding "the current session" for a log mapper to read, lines from one session tagged with another's id | `withLogContext` | [Telemetry & log capture](agent-cookbook/otel.md#telemetry--log-capture) |
| deleting a telemetry store's files to reset it, or a "clear on next launch" flag so the delete lands before recovery | `WarpTelemetry.clear()` | [Telemetry & log capture](agent-cookbook/otel.md#telemetry--log-capture) |
| your own flag or counter tracking whether telemetry is still being written — "has anything landed since launch?", "are we losing log lines?" | `WarpLogRecordExporter.health` + `LogCaptureInstallation.health` | [Telemetry & log capture](agent-cookbook/otel.md#telemetry--log-capture) |
| a second, longer-retention copy of a replicated log — "keep a year on the server beside an hour on the phone", "gossiped records vanish when the peer forgets them", a hand-rolled tee of what a replica applied | `BoltDecorator` + `AppliedOpSink` | [Archiving what the live replica forgets](agent-cookbook/replication.md#archiving-what-the-live-replica-forgets) |
| reading that archive back — "replay what the phone compacted away", "did I get the whole history or did it stop somewhere?", a resume cursor over an append-only log, a hand-rolled "is my archive intact" check | `Bolt.replay` + `ReplayScope` + the terminal verdict | [Archiving what the live replica forgets](agent-cookbook/replication.md#archiving-what-the-live-replica-forgets) |
| merging several mDNS/Multipeer discovery feeds into one lobby roster | `discoveryRoster` | [Discovery](agent-cookbook/fabrics.md#discovery) |
| a stale-peer sweeper over a discovery list that only ever grows — peers that left still on screen, "nobody is ever removed", a `lastSeen` timeout over *discovered* (not admitted) peers | `PeerDiscoverySource.departures()` — implement it, and hold it to `DiscoverySourceConformanceSuite` | [Peers pile up and are never removed](agent-cookbook/fabrics.md#peers-pile-up-and-are-never-removed) |
| a weighted / fair-share scheduler — "give this group 3× the share", "who runs the next quantum", a hoarder-proof round-robin | `HeddlePolicy` + `HeddleNode` | [Fair share & placement](agent-cookbook/heddle.md#fair-share--placement) |
| an entitlement / quota ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers | `EntitlementLedger` + `HeddleNode.reserve`/`complete` | [Fair share & placement](agent-cookbook/heddle.md#fair-share--placement) |
| minting new quota or re-parenting a group at runtime and needing everyone to agree on the order (no double-mint on a split) | `heddleGoverned` (`GovernedHeddleNode`) | [Fair share & placement](agent-cookbook/heddle.md#fair-share--placement) |
| gating a `WarpNode`'s tasks by a weighted lane — "interactive gets 3× batch" | `HeddleAdmissionControl` + `TaskDescriptor.inLane` | [Fair share & placement](agent-cookbook/heddle.md#fair-share--placement) |
| "only run this on a GPU / in-region peer", a placement predicate over peer capabilities, "can this peer run this task" | `Affinity` + `TaskDescriptor.where` + `CapSet` | [Where a task may run (location eligibility)](agent-cookbook/warp.md#where-a-task-may-run-location-eligibility) |
| a blob cache keyed by a content hash, a "have you got these bytes?" request/response, a manifest of what each peer holds | `Creel` + `BobbinExchange` | [Code mobility](agent-cookbook/warp.md#code-mobility) |
| running code that arrived from another peer — a plugin loader, an `eval`, a bespoke sandbox or timeout-and-kill wrapper | `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch` | [Code mobility](agent-cookbook/warp.md#code-mobility) |
| a `try`/`catch` inside an `onEach { … }.launchIn(scope)` so one bad item cannot kill a long-lived collector — or a fix for "the pump stopped and nothing said so", a `Seam`/`Room` that goes deaf after one throw, an iOS crash traced to an unhandled coroutine exception | `Flow.pumpIn(scope, onFailure, name) { … }` — it owns the upstream half your `try` structurally cannot see | [Long-lived pumps](agent-cookbook/fabrics.md#long-lived-pumps) |
| declaring a `MutableStateFlow<SeamState>` while writing a fabric, or a `closed`/`tornDown` flag beside one — or a fix for "my `close()` publishes `Torn` and something overwrites it", `state.first { it is Torn }` that hangs forever, a closed seam still reporting `Woven`, a seam slot that never frees | `SeamStateGate` — it fuses the latch check and the flow write, and replaces your single-shot flag | [A seam's terminal state](agent-cookbook/fabrics.md#a-seams-terminal-state) |
| turning a peer-supplied id into a roster entry while writing a fabric — `PeerId(bytes.decodeToString())`, a `Set<PeerId>` a callback adds to and removes from, `peers == setOf(selfId)` meaning "the session is over" | `PeerIdentityRegistry` | [A peer id off the wire](agent-cookbook/fabrics.md#a-peer-id-off-the-wire-straight-into-a-setpeerid) |
| a 4-byte length prefix and a reassembly loop over a socket or in-house RPC — "I have a byte stream, kuilt wants a fabric" | `framed()` → `handshaking()` | [Your own transport](agent-cookbook/fabrics.md#your-own-transport) |
| that same plumbing when the transport really is just TCP | `TcpLoom.host` / `TcpLoom.join` | [Plain TCP is already assembled](agent-cookbook/fabrics.md#plain-tcp-is-already-assembled) |
| dealing cards nobody can peek at — a shuffle on one device, "the dealer could cheat", hiding a card from the player holding it | `DealSession` | [Dealing cards nobody can peek at](agent-cookbook/replication.md#dealing-cards-nobody-can-peek-at) |
| a shared random seed nobody could steer — one peer picks a number and broadcasts it | `FairRandom.roll()` | [Nobody chose that number](agent-cookbook/replication.md#nobody-chose-that-number) |
| a server holding the authoritative log while many clients propose into it, and a client that must survive losing its server | `ClusterClient` + `ServerCluster` | [When one of the peers is a server](agent-cookbook/consensus.md#when-one-of-the-peers-is-a-server) |
| a session too big for everyone-talks-to-everyone — N² links, a broadcast sent N times, memory that grows with the room | `GossipSeam` + `deltaTargets = { gossip.activePeers.value }` | [Scaling to many peers](agent-cookbook/replication.md#scaling-to-many-peers) |

## Families

- [Fabrics](agent-cookbook/fabrics.md) — discovery, transports, the seam, payload limits, pumps, durable storage.
- [Session](agent-cookbook/session.md) — rejoin & reconnect, liveness & presence, host election & the lobby.
- [Replication](agent-cookbook/replication.md) — replicated data, scaling to many peers, dealing cards, dedup, archiving what the live replica forgets.
- [Consensus](agent-cookbook/consensus.md) — consensus & turns, when one of the peers is a server, durable consensus state.
- [Heddle](agent-cookbook/heddle.md) — weighted fair share, reserving and charging over a network, minting quota and reshaping with agreement, weighted lanes over warp.
- [Warp](agent-cookbook/warp.md) — where a task may run, code mobility.
- [Otel](agent-cookbook/otel.md) — telemetry & log capture, reporting a failure that will be retried.
