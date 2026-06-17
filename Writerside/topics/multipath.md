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

`CompositeLoom` bonds **finished** `Seam`s (the `Seam → Seam` direction). Its
mirror image is [`meshSeam()`](fabric-kit.md#meshseam-an-n-peer-mesh), which
*builds* a `Seam` out of raw point-to-point links (`Conn → Seam`) — see
[Composing a Seam](composing-a-seam.md) for how the two relate.

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

```kotlin
```
{ src="../../kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt" include-symbol="frameOverTwoSharedPliesIsDeliveredExactlyOnce" }

(`makeLoom` builds a `CompositeLoom` over the two plies with an
`UnconfinedTestDispatcher`; `DelayedWovenLoom` lets the test drive each ply's
lifecycle explicitly.)

## Failover is not a membership event

Tear one ply and a peer still reachable on another stays present — the aggregate
stays `Woven` and `peers` does not flap. Only the *last* surviving ply tearing
drives the session `Torn`.

```kotlin
```
{ src="../../kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt" include-symbol="onePlyTearingDoesNotRemoveAPeerStillOnAnother" }

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
