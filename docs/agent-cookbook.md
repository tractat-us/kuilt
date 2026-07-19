# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `ResumeToken` + `SeamRoom` resume | [Rejoin & reconnect](#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](#consensus--turns) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](#replicated-data) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](#dedup) |

## Rejoin & reconnect

<!-- filled by Task 2 -->

## Replicated data

<!-- filled by Task 3 -->

## Liveness & presence

<!-- filled by Task 4 -->

## Consensus & turns

<!-- filled by Task 5 -->

## Dedup

<!-- filled by Task 5 -->
