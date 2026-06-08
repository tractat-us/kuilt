# kuilt-raft log compaction & InstallSnapshot design

**Date:** 2026-06-06  
**Status:** approved  
**Module:** `:kuilt-raft`  
**Issue:** #114 (§7 — log compaction and InstallSnapshot RPC)

## Problem

Without log compaction, `RaftStorage` grows without bound, and a node offline
across a compaction boundary cannot rejoin — the leader no longer holds entries
at the follower's required `prevLogIndex`. This is a hard prerequisite for any
long-lived deployment. The capability adds three things: a way for the consumer
to hand kuilt-raft a state-machine snapshot, log-prefix discard, and an
`InstallSnapshot` RPC so a lagging node can be caught up from a snapshot instead
of from a replayed log it can no longer obtain.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Snapshot ownership | Opaque `ByteArray`, **consumer-owned** | kuilt-raft never deserializes `command`; only the consumer can collapse N entries into compact state. Raft-managed snapshotting would ship the RPC plumbing but not bound storage. |
| Outbound surface | `snapshots: MutableStateFlow<Snapshot?>` | Conflated state is push (consumer's clock) **and** pull (raft samples on its clock) in one construct. Conflation is exactly right — an older snapshot is strictly dominated. |
| Inbound surface | Unified `committed: Flow<Committed>` (`Entry` \| `Install`) | A reset must arrive *in order* w.r.t. entries; one ordered stream makes that free. Two flows hand the ordering hazard to every consumer. |
| Trigger | Consumer-driven, derived from existing `commitIndex` | No new "log pressure" API — the consumer computes backlog from `commitIndex − appliedIndex`. |
| InstallSnapshot transfer | **Chunked** (offset / done) | Real fabrics (e.g. Android Nearby Connections, ~32 KiB BYTES payloads) cap message size. Single-shot would silently drop oversized frames. |
| Chunk size source | `RaftTransport.maxPayloadBytes: Int?` (defaulted), capped by `RaftConfig` | The fabric is the only component that knows its framing limit. Deployer-configured size is a multi-fabric footgun. |
| Compaction trigger ownership | Consumer (raft does not enforce a hard ceiling) | YAGNI; a raft→consumer "please snapshot ≥ K" back-channel is deferred. |

The breaking change to `committed`/`committedFrom` (element type `LogEntry` →
`Committed`) is accepted under the pre-1.0 aggressive-merge posture; the old type
literally cannot express a reset, so this is a strict improvement.

## Public API

### New types

```kotlin
/** A state-machine snapshot. Opaque to raft; the same payload flows both directions. */
public data class Snapshot(
    val throughIndex: Long,   // highest committed index this state reflects (consumer supplies)
    val state: ByteArray,     // opaque application bytes — raft never deserializes
)   // value equality over throughIndex + content (mirrors LogEntry)

/** One ordered "apply instruction" delivered to the consumer's state machine. */
public sealed interface Committed {
    public data class Entry(val entry: LogEntry) : Committed       // apply this entry
    public data class Install(val snapshot: Snapshot) : Committed  // discard state, reset to these bytes
}
```

### `RaftNode` changes

```kotlin
public interface RaftNode {
    // ⚠ breaking: element type LogEntry → Committed
    public val committed: Flow<Committed>
    public fun committedFrom(fromIndex: Long): Flow<Committed>
    //   fromIndex ≤ compactionFloor ⇒ emits Install(latest snapshot) first, then Entry(floor+1)…tail

    // ★ new
    public val snapshots: MutableStateFlow<Snapshot?>   // OUTBOUND publish; null ⇒ compaction disabled
    public val compactionFloor: StateFlow<Long>         // lastIncludedIndex of latest compaction (0 = none)

    // unchanged: role, leader, commitIndex, trace, propose, awaitLeadership, close
}
```

`snapshots` is a node-owned property the consumer writes (`node.snapshots.value = …`),
chosen over a construction parameter for discoverability. Leaving it `null`
preserves today's behavior exactly, so existing consumers that don't compact are
unaffected beyond the `Committed` wrapper. No-ops (`LogEntry.isNoOp`) remain
withheld from both flows — only `Entry` and `Install` surface.

### Consumer usage

```kotlin
// APPLY (every node) — one ordered stream:
node.committed.collect { when (it) {
    is Committed.Entry   -> { sm.apply(it.entry); applied = it.entry.index }
    is Committed.Install -> { sm.resetTo(it.snapshot.state); applied = it.snapshot.throughIndex }
}}

// SNAPSHOT (consumer's clock) — trigger derived from commitIndex, zero new API:
node.commitIndex.map { it - applied }.filter { it >= SNAPSHOT_EVERY }.collect {
    node.snapshots.value = Snapshot(applied, sm.serialize())   // push; raft pulls on its clock
}

// RESTART — restore own durable state, then resume gap-free:
sm.restoreFromMyDurableSnapshot()
node.committedFrom(applied + 1).collect { /* same when as APPLY */ }
```

### `RaftTransport` change

```kotlin
public interface RaftTransport {
    /** Max bytes a single sendTo() can carry, or null if effectively unbounded (e.g. WebSocket). */
    public val maxPayloadBytes: Int? get() = null   // defaulted — non-breaking for existing transports
}
```

## Storage

```kotlin
public data class SnapshotMeta(val lastIncludedIndex: Long, val lastIncludedTerm: Long)
public class StoredSnapshot(val meta: SnapshotMeta, val state: ByteArray)

public interface RaftStorage {
    // existing term/vote/log methods …
    public suspend fun saveSnapshot(meta: SnapshotMeta, state: ByteArray)
    public suspend fun loadSnapshot(): StoredSnapshot?
    public suspend fun discardLogPrefix(throughIndex: Long)   // remove entries with index ≤ throughIndex
}
```

**Crash-safety ordering:** `saveSnapshot` MUST be durable *before* `discardLogPrefix`
runs. A crash between the two leaves the snapshot plus the full log — redundant but
safe and recoverable. Never discard before the snapshot is durable.
`InMemoryRaftStorage` implements all three trivially.

## Engine mechanics

New actor-only state: `snapshotIndex` (lastIncludedIndex) and `snapshotTerm`
(lastIncludedTerm), both `0` when nothing is compacted.

**Boundary math.**

```
lastLogIndex = log.last?.index ?: snapshotIndex
lastLogTerm  = log.last?.term  ?: snapshotTerm
termAt(i): i == snapshotIndex ⇒ snapshotTerm
           i <  snapshotIndex ⇒ unknown (compacted away)
           else               ⇒ search log
```

**Leader replication (`sendAppendEntries(peer)`).** With `ni = nextIndex[peer]`:

- `ni ≤ snapshotIndex` → the needed prefix is gone → start/continue an
  `InstallSnapshot` transfer to `peer`; return.
- `ni − 1 == snapshotIndex` → `prevLogTerm = snapshotTerm` (boundary case).
- otherwise → normal `AppendEntries`. The §5.3 fast-backup `conflictIndex` is
  floored at `snapshotIndex + 1` (the leader cannot probe a compacted prefix).

**Compaction.** A launched collector on `snapshots` sends an actor command when
`.value` changes; the actor reads `snapshots.value` and, if
`throughIndex ∈ (snapshotIndex+1)..commitIndex`:

1. `lastIncludedTerm = termAt(throughIndex)` (present — it's committed and beyond the current snapshot).
2. `storage.saveSnapshot(SnapshotMeta(throughIndex, lastIncludedTerm), state)`.
3. `storage.discardLogPrefix(throughIndex)`; drop in-memory `log` entries ≤ throughIndex.
4. `snapshotIndex/snapshotTerm = throughIndex/lastIncludedTerm`; `compactionFloor.value = snapshotIndex`.
5. emit `RaftTraceEvent.Compacted`.

Publishing `throughIndex > commitIndex` (shouldn't happen — the consumer only
applies committed entries) is ignored until commit catches up.

## Chunked InstallSnapshot

```kotlin
data class InstallSnapshot(
    val term: Long, val leaderId: NodeId,
    val lastIncludedIndex: Long, val lastIncludedTerm: Long,
    val offset: Long, val data: ByteArray, val done: Boolean,
) : RaftMessage
data class InstallSnapshotResponse(
    val term: Long,
    val nextOffset: Long,   // byte count the follower has stored — resyncs the leader after a dropped chunk
) : RaftMessage
```

**Transfer protocol — one chunk in flight per peer (await-ack-then-next).**

- **Chunk size:** `chunkBytes = (transport.maxPayloadBytes ?: cfg.snapshotChunkCeiling)`,
  capped by `cfg.snapshotChunkCeiling`, minus `HEADER_BUDGET` (CBOR framing + the
  RPC's non-`data` fields), so the encoded message stays under the fabric limit.
- **Leader:** per-peer transfer state `{meta, bytes, nextOffset}` (leader-only,
  like `nextIndex`/`matchIndex`; cleared on step-down). On
  `InstallSnapshotResponse`: if `term > currentTerm` step down; else send the
  chunk at `nextOffset`; when the `done` chunk is acked set
  `matchIndex[peer] = lastIncludedIndex`, `nextIndex[peer] = lastIncludedIndex + 1`,
  clear the transfer, resume normal `AppendEntries`.
- **Follower:** standard term checks (reply with `currentTerm` and ignore if
  `term < currentTerm`; step down if `term > currentTerm`); reset election timer;
  set leader. `offset == 0` starts a fresh in-memory reassembly buffer keyed by
  `(lastIncludedIndex, lastIncludedTerm)`. In-order append; reply
  `nextOffset = buffer.size`. On `done`:
  1. `storage.saveSnapshot(meta, fullBytes)`.
  2. If a local entry at `lastIncludedIndex` matches `lastIncludedTerm`, keep the
     suffix (`discardLogPrefix(lastIncludedIndex)`); otherwise discard the whole log.
  3. `snapshotIndex/snapshotTerm = meta`; `commitIndex ⇧ lastIncludedIndex`.
  4. emit `Committed.Install(Snapshot(lastIncludedIndex, fullBytes))`; `compactionFloor.value ⇧`.

In-memory reassembly is sufficient: chunking is driven by *frame* limits, not
memory limits, so a bounded snapshot still fits in RAM.

## Recovery

On `init`, the engine loads snapshot metadata from storage → `snapshotIndex`,
`snapshotTerm`, `commitIndex = snapshotIndex` (a snapshot is by definition
committed), and `log = entries > snapshotIndex`. The engine does **not** re-emit
an `Install` on restart (`committed` is replay=0); the consumer restores its own
durable state and resumes via `committedFrom(appliedIndex + 1)`. `committedFrom`
with `fromIndex ≤ snapshotIndex` emits `Install(loadSnapshot())` first, then
entries from `snapshotIndex + 1`, then tails — gap-free and dedup'd at the seam,
exactly as the no-compaction path does today.

## Config & trace

- `RaftConfig.snapshotChunkCeiling: Int` — default 16 KiB (conservative under the
  ~32 KiB Nearby BYTES limit after framing); a deployer may shrink it, never exceed it.
- `RaftTransport.maxPayloadBytes: Int?` — defaulted `null` (unbounded).
- Trace vocabulary gains `Compacted`, `InstallSnapshot(offset, done)`, and
  `InstallSnapshotAccepted` for debug/TLC trace parity.

## Testing strategy

TDD: the headline reproduction is written **red first** — *a node offline across a
compaction boundary cannot rejoin* — then the fix makes it pass.

- **Unit:** boundary math (`prevLogTerm` at `snapshotIndex`; the
  `ni ≤ snapshotIndex ⇒ InstallSnapshot` branch); `InMemoryRaftStorage`
  snapshot/discard round-trips; reassembly (in-order append, dropped-chunk resync
  via `nextOffset`, conflict-discard vs keep-suffix on `done`).
- **Simulation (`RaftSimulation`):** offline-rejoin-after-compaction;
  partition + compaction; a deliberately tiny `maxPayloadBytes` forcing many
  chunks; leader-change-mid-transfer.
- **Property-based:** Leader Completeness still holds with compaction;
  State-Machine-Safety — applying the `Committed` stream is equivalent to a full
  log replay; coroutine determinism on the install path under wasmJs / K-N
  (the `UNDISPATCHED` subscribe discipline already used by `committedFrom`).

## Out of scope (follow-up issues)

- Streaming reassembly to disk for snapshots that exceed memory.
- Snapshot compression.
- §6 dynamic membership / joint consensus.
