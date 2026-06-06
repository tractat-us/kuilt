# kuilt-raft-test

Test doubles for `kuilt-raft` contracts. Add this as a `testImplementation` dependency so your tests can drive a `RaftNode` without standing up real consensus.

```kotlin
testImplementation("us.tractat.kuilt:kuilt-raft-test:<version>")
```

## FakeRaftNode — one-liner setup

```kotlin
val node = FakeRaftNode()   // selfId=NodeId("self"), Follower role
node.setRole(RaftRole.Leader)
node.pushCommitted("set x=1".encodeToByteArray())
val entry = node.committed.first()
// entry.command == "set x=1".encodeToByteArray()
```

Role helpers: `setRole(role)`, `setLeader(nodeId)`, `setCommitIndex(index)`.

Committed entry helpers: `pushCommitted(entry)`, `pushCommitted(command)` (auto-increments index).

Trace helpers: `emitTrace(event)`.

Proposal behavior: `node.proposeBehavior = { _ -> throw LeadershipLostException() }` — override to inject specific outcomes; default is contract-faithful (throws `NotLeaderException` unless Leader, else appends to `committed`).

Outgoing inspection: `node.proposals: List<ByteArray>` — all commands passed to `propose()`, in call order.

Lifecycle: `close()` (idempotent; completes `committed`/`trace` flows), `node.closed: Boolean`.

## Why this module

`:kuilt-test` doubles only `:kuilt-core` types; `:kuilt-session-test` doubles `:kuilt-session` types. `FakeRaftNode` lives here — in its own module — so the dependency arrow stays clean: `kuilt-raft-test` depends on `kuilt-raft`; the other test-double modules stay in their own lanes.
