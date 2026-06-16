# Design — kuilt-raft: exactly-once forwarded proposals (client-serial dedup)

- **Issue:** #484 (`needs-design`)
- **Builds on:** #483 (propose-forwarding) — assumes its `RaftMessage.Forward`/`ForwardResponse` exist.
- **Part of:** epic #474.
- **Status:** approved design, pre-implementation.

## Problem

Propose-forwarding (#483) is at-most-once-per-*send* with no dedup: nothing
auto-retries, and a lost/cancelled forward surfaces as `LeadershipLostException`
for the caller to retry. The moment a caller retries a proposal after a torn
connection or a leader change, the same command can be appended twice — the old
leader committed and applied it, the ack was lost, and the retry lands on a new
leader that already has the entry replicated:

```
incarnation 1: client → leader  propose(move, reqId=42)
               leader appends @idx5, commits, applies     ✓ state changed
               ack lost (torn conn / leader change)
               client retries → NEW leader  propose(move, reqId=42)
   naive:      new leader appends again @idx6 → move applied TWICE
   dedup:      new leader recognises reqId=42 → no second apply
```

The standard fix is Raft §8 / Ongaro §6.3 client-serial dedup — **not**
suppressing retries.

## What makes kuilt different

kuilt-raft's state machine is **external**. `propose(command: ByteArray): LogEntry`
takes opaque bytes; raft never interprets them. The consumer applies
`RaftNode.committed` and **owns the snapshot** (`snapshots.value = Snapshot(idx, bytes)`).
So the dedup table can live on either side of that line. This design puts the
*authoritative* table on the consumer side and keeps raft a best-effort fast path.

## Decision — placement C: raft plumbs, consumer persists

1. **Raft always stamps** every application proposal with a `DedupKey(clientId, requestId)`
   and threads it **unchanged** through the forward hop. (Contrast #483's
   `clientRequestId`, a per-hop correlation nonce minted fresh at each hop — that
   one cannot dedup end-to-end. This is a new, stable, end-to-end field.)
2. **Raft runs a best-effort, non-durable leader-side dedup cache.** A retry with a
   key the current leader already has in flight or recently committed coalesces onto
   the existing result instead of appending again. This alone closes the in-process
   retry races (lost ack, same-incarnation leader change with a warm cache) — the
   core #484 cases. Suppressing a duplicate append is strictly good for *every*
   consumer, including idempotent CRDT callers (less log, never wrong).
3. **Enforcement durability is the consumer's choice.** The authoritative
   `lastAppliedSerial: Map<ClientId, Long>` table lives in the consumer's state
   machine and rides its existing snapshot. A `TurnSequencer`-style consumer
   implements the apply-time skip (catches cold-cache leader changes and cross-crash
   retries); a naturally-idempotent CRDT consumer simply ignores the key.

"Send bytes with no dedup" therefore becomes "**ignore the key**" — there is no
separate unkeyed `propose` path and no nullable opt-in flag (which would be the
"optional ≠ tuning" footgun: a forgotten flag silently losing exactly-once). Raft's
cost when nobody dedups is one counter bump plus a small key on the wire.

### Why not A or B

- **A (raft-owned, dedup-at-append, raft snapshots its own session table)** is the
  strongest guarantee but forces raft to own a piece of *compactable durable state* —
  today raft delegates **all** snapshot state to the consumer. That invariant is
  load-bearing and worth keeping.
- **B (consumer-owned, dedup-at-apply, key inside the command bytes)** keeps raft
  fully opaque but buries the end-to-end id in app bytes, so raft can't run the
  cheap leader fast path and every duplicate must round-trip the whole log before
  being dropped at apply. C is B plus the leader fast path, with the id promoted to
  a first-class field.

## API surface

### Identity — a `RaftNode` construction parameter

```kotlin
/** Opaque, stable-per-owner client identity. A GUID by default. */
@Serializable
public value class ClientId(public val value: String)

public fun CoroutineScope.raftNode(
    clusterConfig: ClusterConfig,
    transport: RaftTransport,
    storage: RaftStorage,
    raftConfig: RaftConfig = RaftConfig(),
    clientId: ClientId? = null,   // null → engine mints an ephemeral GUID from raftConfig.random
    onMetric: ((RaftMetric) -> Unit)? = null,
): RaftNode
```

- **Auto (default, `clientId = null`):** the engine mints a **fresh GUID per
  incarnation** from `raftConfig.random` (the same injected, test-seeded RNG used
  for election timeouts — never the global `Random`). Ephemeral: it dies with the
  process. Gives exactly-once for **in-process** retries; **fails safe to
  at-least-once** across a proposer crash (a restarted node is a *new* client, so
  its `requestId` can't alias the dead incarnation's serials).
- **Durable (caller passes a stable `ClientId`):** a client that knows its prior
  iteration is dead **takes ownership** of its retries by reusing the same id. This
  gives true cross-crash exactly-once **and** keeps the session table bounded (its
  `lastAppliedSerial` entry updates in place rather than accumulating).

`clientId` selects *which identity*, not *whether dedup runs* — dedup runs either
way — so a nullable default is tuning, not the banned functional-path toggle.

### The aliasing trap (why per-incarnation identity is mandatory)

A *stable* clientId paired with a *non-durable* (in-memory, reset-on-restart)
counter is **worse than no dedup**: incarnation 2's `reqId=1` aliases incarnation
1's committed `reqId=1`, so the leader returns the *old* result and the new command
is **silently dropped**. The fresh-GUID-per-incarnation default disarms this: each
incarnation is a distinct client, so a reused low serial can never alias. The only
way to safely reuse a serial across a crash is to *also* carry a stable clientId and
a durable high-water-mark — the explicit durable rung.

### Proposal serials

```kotlin
public suspend fun propose(command: ByteArray): LogEntry            // auto serial
public suspend fun propose(command: ByteArray, requestId: Long): LogEntry  // caller-pinned serial
```

- `propose(command)` stamps `requestId = atomicCounter.incrementAndGet()` on this
  node's `clientId`. Monotonic per client.
- `propose(command, requestId)` lets a durable client replay the *same* serial on a
  post-crash retry. The caller owns and persists its high-water-mark.

`requestId` is a non-nullable positional in the explicit overload (not a nullable
field), so the call site visibly chooses pinned-serial semantics.

### Wire & log

```kotlin
public data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
    val isNoOp: Boolean = false,
    val config: ConfigPayload? = null,
    val dedupKey: DedupKey? = null,   // non-null for stamped application entries; null for no-op/config
)

@Serializable
public data class DedupKey(val clientId: ClientId, val requestId: Long)
```

- `dedupKey` is structured metadata on the envelope; `command` stays purely opaque.
  Both the leader cache (raft) and the consumer's apply loop read it from
  `Committed.Entry.entry.dedupKey`.
- `RaftMessage.Forward` (from #483) gains a `dedupKey: DedupKey` field, threaded
  through every hop **unchanged**. A forwarding node must not re-stamp it.

## Snapshot / InstallSnapshot integration

Nothing new in raft. The authoritative `lastAppliedSerial` table is part of the
consumer's state-machine state, so it is serialized into the consumer's snapshot
bytes (`Snapshot.state`) exactly like the rest of its state. A follower that joins
mid-stream and receives `Committed.Install` resets its state machine — table
included — from those bytes. Raft's existing §7 chunked `InstallSnapshot` path
carries it for free.

The best-effort **leader cache is ephemeral** and deliberately *not* snapshotted: a
new leader starts cold and rebuilds it as proposals arrive. The consumer's durable
table is the backstop for the cold-cache window.

## Session lifecycle — no GC in v1 (documented)

The auto/ephemeral path mints a never-reused GUID per incarnation, so the consumer's
table gains a dead entry per restart, forever — unbounded over a long-lived cluster.
v1 **does not** garbage-collect it. Rationale:

- **LRU/TTL is an availability hazard here, not a memory optimization.** A flapping
  client retrying in a hot loop would churn the cache and evict *live* clients; an
  evicted live client's next retry then double-applies. Eviction trades a silent
  correctness regression for bounded memory — the wrong trade.
- The escape hatch is already in the API: **long-lived clients pass a stable
  `clientId`**, whose entry updates in place, bounding the table to the count of
  *distinct logical clients* rather than incarnations.

Docs will state this plainly and recommend stable ids for long-lived clients. A
size/age cap is a post-1.0 follow-up issue, not a v1 blocker.

## Failure modes

| Scenario | Outcome |
|---|---|
| Lost ack, same incarnation, warm leader cache | Retry coalesced at leader; no second append. |
| Leader change, cold cache, consumer enforces | Duplicate may append; consumer skips it at apply (applied once). |
| Leader change, cold cache, consumer does **not** enforce | Duplicate appends and applies; acceptable only for idempotent consumers. |
| Proposer crash, auto/ephemeral id | New incarnation = new client; in-flight straddling command may double-apply (at-least-once). Never a silent drop. |
| Proposer crash, durable stable id + replayed serial | True exactly-once across the crash. |

The cardinal invariant: the system **never** degrades to silently dropping a real
command. Every failure mode degrades, at worst, to at-least-once.

## Out of scope (v1)

- Session-table GC / expiry (post-1.0 follow-up).
- Raft-owned durable session table (placement A).
- Persisting the auto serial counter to `RaftStorage` (would give the default path
  cross-crash dedup without a stable id; deferred — durability is the explicit rung).

## Testing

- **Lost-ack coalescing:** propose, drop the response, retry with the same key on the
  same leader → one log entry, one apply.
- **Leader-change dedup:** propose, force a leader change before the ack, retry on the
  new leader with a consumer that enforces → at most one apply (duplicate entry
  tolerated, duplicate *apply* forbidden).
- **Aliasing safety:** restart a node on the auto path → fresh GUID → a low serial
  does **not** alias the previous incarnation; the new command applies (no silent
  drop).
- **Durable cross-crash:** stable `clientId` + replayed `requestId` → exactly-once
  across a simulated restart.
- **Forward threading:** assert the `dedupKey` arriving at the leader byte-equals the
  one stamped at the proposer (no re-stamp across hops).
- **Snapshot carry:** install a snapshot into a fresh follower → its consumer table
  reflects `lastAppliedSerial`; a stale retry is recognised post-install.
- All raft tests stay on `StandardTestDispatcher` + `FakeRaftNode`; RNG is seeded.
