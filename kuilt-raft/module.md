# Module kuilt-raft

Raft consensus over a `Seam`: leader election + PreVote, log replication, log
compaction with chunked `InstallSnapshot`, dynamic membership, linearizable reads
(`readIndex()`), and graceful leadership transfer (`transferLeadership()`).

## Proposing from any peer

`RaftNode.propose` may be called on **any** role. The leader appends directly; a
follower, candidate, or learner forwards the command to the current leader and
suspends until it commits (Raft §8). If no leader is known yet the call waits,
cancellably, until one is elected.

## Exactly-once forwarded proposals (§8)

A forwarded `propose` retried after a lost ack or a leader change must not be
appended or applied twice — without forbidding the retry. kuilt stamps every
application proposal with a stable `DedupKey(clientId, requestId)` and gives you
three rungs of guarantee:

1. **Auto (default).** Pass no `clientId`; the node mints
   `ClientId.auto(thisNodeId, raftConfig.random)` — `"$nodeId-$randomHex"`. The
   `NodeId` prefix keeps two nodes distinct even under the same seeded test RNG
   (a bare random GUID would alias) and is readable in logs. Auto serials are
   monotonic; this gives at-least-once forwarding with best-effort dedup, but no
   guarantee across a process restart (the suffix changes).
2. **Durable.** Pass a **stable** `ClientId` the caller persists itself and replay
   the *same* `requestId` (the `propose(command, requestId)` overload) on a
   post-crash retry. The key survives the crash, so the consumer skips the entry
   it already applied — exactly-once end-to-end.
3. **Ignore.** Treat the key as absent (internal no-op/config entries carry a
   `null` `dedupKey`); the entry always applies.

**Who enforces what.** The proposer stamps the key once; it rides the forward hop
**unchanged** (the leader never re-stamps). A best-effort, non-durable leader-side
cache coalesces the common lost-ack retry on a still-leading node. The
**authoritative** table is the consumer's: fold `ClientSessionTable.shouldApply`
into your apply loop and serialize it **into your own snapshot**
(`toBytes`/`fromBytes`). Because that table rides the consumer's snapshot, raft's
`InstallSnapshot` carries it with **zero raft-side change**.

**Collision detection.** A committed entry under this node's own `clientId`
bearing a serial it never issued proves another live writer shares the identity. A
**durable** id fails loud with `ClientIdCollisionException` (two processes were
handed one id — an operational error; do not retry under it). An **auto** id
silently re-mints a fresh suffix and logs a warning.

**No GC (v1).** The session table never evicts: ephemeral clients accumulate dead
entries. The remedy is self-bounding — long-lived clients reuse a stable
`ClientId` so their entry updates in place rather than growing the map.

See `docs/superpowers/specs/2026-06-16-raft-exactly-once-dedup-design.md` for the
full design rationale.
