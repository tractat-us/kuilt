# Multipath

Your users should not notice when the network path changes. `CompositeLoom`
(`kuilt-core`, package `us.tractat.kuilt.core.composite`) lets one peer use
multiple transports at once and exposes them as one logical `Seam`.

A phone can reach others over a relay WebSocket **and** a direct LAN link at
the same time, while the app above still sees one session.

Because the result is an ordinary `Seam`, everything layered above — `kuilt-raft`,
`kuilt-crdt`, `kuilt-session` — is unchanged. The bonding lives *below* the
contract, so a path failing over is invisible to consensus and replication: no
election, no membership churn, no full-state resync.

`CompositeLoom` bonds **finished** `Seam`s (the `Seam → Seam` direction). Its
mirror image is [`meshSeam()`](fabric-kit.md#meshseam-an-n-peer-mesh), which
*builds* a `Seam` out of raw point-to-point links (`Connection → Seam`) — see
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
stays `Woven` and `peers` does not flap. Lose every ply at once and the
aggregate degrades to `Weaving` — recoverable, not the terminal `Torn` — so a
ply reattaching (or an existing one reconnecting) can restore `Woven` with no
membership event. `Torn` is reserved for closing the session; a transient
all-plies-down moment on its own never tears it.

```kotlin
```
{ src="../../kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt" include-symbol="onePlyTearingDoesNotRemoveAPeerStillOnAnother" }

## Attaching and detaching plies live

The ply set need not be fixed at `weave()`. Construct `CompositeLoom` from a
`StateFlow<List<Pair<PlyId, Loom>>>` of the *desired* set and emit a new list to
attach or detach a ply on a live session — an overlay (a LAN radio, a WebRTC
link) that lights up when peers come into proximity and drops when they leave.
The fixed-list constructor is the degenerate single-emission case.

Sometimes a connection you asked for just won't come up: the radio is off, the
permission hasn't been granted yet, the far side isn't listening. Once the
session is running that's survivable — the other connections in the same list
still come up, and the one that failed is simply tried again the next time you
publish a list. To see *why* it failed, pass `onPlyFailure` when you build the
`CompositeLoom`; it hands your logger a `PlyReconcileException` naming the
connection and the cause. kuilt keeps no logger of its own, so that hook is the
only place this surfaces.

Starting the session is stricter: if any connection in the *first* list can't be
made, `weave()` itself fails and you get no session at all — so `onPlyFailure`
never sees it, and with the fixed-list constructor there is no later list to
retry from. It is still tidy about it: the connections that *did* come up before
the failure are closed on the way out, so a failed start leaves nothing open
behind it. Put only connections you expect to work in the list you start with,
and add the opportunistic ones by publishing a new list afterwards.

## Feeding the layers above

Because a composite is just a `Seam`, hand it to consensus or replication exactly
as you would any other:

```kotlin
val replicator = Quilter(
    replica = ReplicaId(seam.selfId.value),
    seam = seam,                 // the composite Seam
    initial = GCounter.ZERO,
    messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
    scope = coroutineScope,
)
```

Raft sees one `NodeId`, the replicator tracks one peer, and a WebSocket→TCP
failover reaches them as nothing at all.

Capabilities deliberately deferred until a consumer needs them —
application-layer gateway forwarding and primary-ply-per-peer send — are recorded
in the ply roadmap in the repository (`docs/ply-roadmap.md`).
