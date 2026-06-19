# kuilt examples

Runnable, compiled examples showing how to compose kuilt modules. Each file is a
self-contained JVM test (`@Test`), compiled from `examples/src/test/kotlin/`.

```
./gradlew :examples:test                              # all examples
./gradlew :examples:test --tests "*TicTacToeChatTest*"  # one example
```

---

## Game & consensus

| File | What it teaches |
|------|-----------------|
| `TicTacToeTest.kt` | `TurnSequencer` + `FakeRaftNode` — typed game moves committed through a Raft log (no live network) |
| `TicTacToeChatTest.kt` | Full two-peer game via `gameNode` (roster-given bootstrap) with a convergent `Rga`+`Quilter` chat log riding `appChannel` on the same fabric |

## CRDT zoo (offline / value-object)

| File | What it teaches |
|------|-----------------|
| `ChatTest.kt` | `Rga` as a standalone value object — two replicas merge chat messages by hand with `Rga.piece`, no network |
| `CausalAddWinsTest.kt` | The `Causal` + `DotSet` lattice primitive — the engine behind ORSet, MVRegister, and ORMap |

## CRDT + live replication (`Quilter` over `InMemoryLoom`)

| File | What it teaches |
|------|-----------------|
| `GCounterReactionTallyTest.kt` | `GCounter` — grow-only per-replica reaction tally, converges via `Quilter.mutate` |
| `VoteTallyTest.kt` | `PNCounter` — upvote/downvote tally with decrement, net count converges |
| `SeatReservationTest.kt` | `BoundedCounter` — fixed seat pool split into per-replica quotas; quota transfer when one side runs low |
| `SharedTitleTest.kt` | `LWWRegister` — last-writer-wins shared title; timestamp+replicaId tie-break |
| `PresenceRosterLWWMapTest.kt` | `LWWMap` — presence roster; each peer owns its own key slot, no conflicts |
| `PresenceTest.kt` | `EphemeralMap` — "who's typing" indicator with TTL expiry and graceful departure |
| `GrowOnlyTagSetTest.kt` | `GSet` — additive tag set; join is set union, no tombstones |
| `AddWinsTagSetTest.kt` | `ORSet` — add-wins label set; concurrent re-adds survive concurrent removes |
| `TombstonedTagSetTest.kt` | `TwoPhaseSet` — permanently tombstoned label set; remove always wins |
| `MemberRosterORMapTest.kt` | `ORMap<String, GCounter>` — member roster with nested CRDT values; add-wins on keys, per-member tallies |
| `CollabDocTest.kt` | `JsonCrdt` — concurrent edits to different fields of a shared JSON document merge without conflict |
| `ConcurrentEditTest.kt` | `MVRegister` — multi-value register; surfaces concurrent writes so the app can resolve the conflict |
| `RgaCollabEditTest.kt` | `Rga` + `Quilter` over a live seam — insert/delete at arbitrary positions, convergent collaborative text editing |

## Session & presence

| File | What it teaches |
|------|-----------------|
| `RelayRoomTest.kt` | `KtorRoomHost` relay-room topology — a single-voter Raft node behind a real WebSocket relay, learner admitted via `changeMembership` |

## Cluster / server-side E2E (real Ktor WebSocket sockets)

| File | What it teaches |
|------|-----------------|
| `ServerClusterE2ETest.kt` | `ServerCluster` + `ClusterClient` M=1 — propose commits through a real WebSocket relay |
| `ServerClusterM3E2ETest.kt` | `ServerCluster` M=3 — three-voter consensus over real sockets |
| `ClusterClientProductionPathE2ETest.kt` | `clusterClient` production extension — single-endpoint proposal over a real admission handshake |
| `ClusterClientFailoverE2ETest.kt` | Cross-relay failover — relay A killed mid-session; client reconnects to relay B and continues proposing |
| `ClusterClientMultiClientHardeningE2ETest.kt` | Two concurrent learner clients + forced leader change + entry-relay kill mid-propose |
| `ConcurrentAdmissionE2ETest.kt` | Two clients connect simultaneously — `admitLearner` retries on `MembershipChangeInProgressException` |
| `ExactlyOnceHappyPathTest.kt` | M=1 exactly-once deduplication — a retry with the same `requestId` coalesces to the original committed entry |
| `NoDoubleApplyFailoverTest.kt` | Exactly-once under mid-flight leader change + entry-server death — round-robin retry, `ClientSessionTable` dedup |
| `ResumeTokenFailoverTest.kt` | `ResumeToken` behaviour under real relay change — documents degradation to fresh-join when the new relay has no window |
