# Multipath

A peer does not have to pick a single transport. `CompositeLoom` (`kuilt-core`,
package `us.tractat.kuilt.core.composite`) is a `Loom` that bonds several other
`Loom`s — its *plies* — into one logical session. A phone can reach the others
over a relay WebSocket **and** a direct LAN link at the same time, and the two
paths behave as one `Seam`.

Because the result is an ordinary `Seam`, everything layered above — `kuilt-raft`,
`kuilt-crdt`, `kuilt-session` — is unchanged. The bonding lives *below* the
contract, so a path failing over is invisible to consensus and replication: no
election, no membership churn, no full-state resync.

## Bonding two transports

Give `CompositeLoom` a list of `(PlyId, Loom)` and `weave()`/`host()`/`join()`
returns a single `Seam` over the union of plies:

```kotlin
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.composite.CompositeLoom

val loom = CompositeLoom(
    listOf(
        PlyId("ws")  to wsLoom,   // relay WebSocket
        PlyId("lan") to lanLoom,  // direct LAN/TCP
    ),
)
val seam = loom.join(tag)         // one bonded Seam
```

What the composite does underneath:

- mints **one stable `selfId`** that survives plies attaching and detaching, so a
  path change is not an identity change;
- broadcasts a `PlyFrame.Announce` on each ply so the far side **collapses a
  remote multi-homed peer to one entry** in `peers`;
- **sends over every live ply** and **dedupes + reorders** inbound frames by
  `(originId, originSeq)`, dropping the redundant copy from the second path.

## Exactly-once delivery across plies

A broadcast goes out over every ply, so the same frame arrives over both. The
inbound gate delivers it exactly once:

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt#frameOverTwoSharedPliesIsDeliveredExactlyOnce -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt
// Test: frameOverTwoSharedPliesIsDeliveredExactlyOnce
// Both plies shared by host and joiner: a broadcast goes over both plies.
// The inbound gate must deduplicate, delivering exactly one copy.
val plyA = DelayedWovenLoom()
val plyB = DelayedWovenLoom()
val loom = makeLoom(PlyId("a") to plyA, PlyId("b") to plyB)
val host = loom.host(Pattern("host"))
val joiner = loom.join(InMemoryTag("join"))

// Mark all per-ply seams woven so identity reconciliation completes.
plyA.wovenSeams.forEach { it.markWoven() }
plyB.wovenSeams.forEach { it.markWoven() }

// Wait for peers to be reconciled (Announce exchange must complete).
host.peers.first { it.size == 2 }

host.broadcast(byteArrayOf(5))

val received = joiner.incoming.take(1).toList()
assertEquals(1, received.size, "exactly one delivery despite two plies carrying it")
assertEquals(5, received.single().payload.single())
```

(`makeLoom` builds a `CompositeLoom` over the two plies with an
`UnconfinedTestDispatcher`; `DelayedWovenLoom` lets the test drive each ply's
lifecycle explicitly.)

## Failover is not a membership event

Tear one ply and a peer still reachable on another stays present — the aggregate
stays `Woven` and `peers` does not flap. Only the *last* surviving ply tearing
drives the session `Torn`.

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt#onePlyTearingDoesNotRemoveAPeerStillOnAnother -->
```kotlin
// Source: https://github.com/tractat-us/kuilt/blob/main/kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt
// Test: onePlyTearingDoesNotRemoveAPeerStillOnAnother
val plyA = DelayedWovenLoom()
val plyB = DelayedWovenLoom()
val loom = makeLoom(PlyId("a") to plyA, PlyId("b") to plyB)
val host = loom.host(Pattern("host"))
val joiner = loom.join(InMemoryTag("join"))

plyA.wovenSeams.forEach { it.markWoven() }
plyB.wovenSeams.forEach { it.markWoven() }

// Wait for both peers to be fully reconciled on both plies.
val peers = host.peers.first { it.size == 2 }
assertEquals(2, peers.size)

// Tear the joiner's plyB link; the joiner is still reachable on plyA.
// plyB.wovenSeams contains seams for both host and joiner. Close the joiner's
// (the second one weaved on plyB).
plyB.wovenSeams.last().close(CloseReason.RemoteRequested)

// Membership must stay at 2 (no flap) and aggregate must stay Woven.
assertEquals(2, host.peers.value.size, "joiner still reachable via plyA")
assertIs<SeamState.Woven>(host.state.value, "aggregate stays Woven when one ply tears")
```

## Attaching and detaching plies live

The ply set need not be fixed at `weave()`. Construct `CompositeLoom` from a
`StateFlow<List<Pair<PlyId, Loom>>>` of the *desired* set and emit a new list to
attach or detach a ply on a live session — an overlay (a LAN radio, a WebRTC
link) that lights up when peers come into proximity and drops when they leave.
The fixed-list constructor is the degenerate single-emission case.

## Feeding the layers above

Because a composite is just a `Seam`, hand it to consensus or replication exactly
as you would any other:

```kotlin
val replicator = SeamReplicator(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,                 // the composite Seam
    initial = GCounter.ZERO,
    messageSerializer = ReplicatorMessage.serializer(GCounter.serializer()),
    scope = coroutineScope,
)
```

Raft sees one `NodeId`, the replicator tracks one peer, and a WebSocket→TCP
failover reaches them as nothing at all.

Capabilities deliberately deferred until a consumer needs them —
application-layer gateway forwarding and primary-ply-per-peer send — are recorded
in the ply roadmap in the repository (`docs/ply-roadmap.md`).
