# Replication

Several devices can change the same piece of shared state at the same time — a score, a room
roster, a shared document — and this page is about making sure everyone ends up looking at the
same thing afterward, without a server or any one device having the final say. That starts with
a handful of devices agreeing on a value, then grows to a crowd of them without the bookkeeping
growing just as fast. It also covers dealing out cards in a game so that nobody — not even
whoever is running the app — can see a card before its owner does, making sure the same message
never gets acted on twice, and keeping a longer history of what happened than any single device
holds on to.

## Replicated data

Need a value that stays in sync across peers with no server to arbitrate conflicts? Don't
hand-roll merge logic — the CRDT zoo already has a lattice for the shape you need, and
`Quilter` already knows how to ship deltas over a `Seam`. The five below cover the cases
that come up constantly; the full 14-type zoo (`GCounter`, `TwoPhaseSet`, `MVRegister`,
`LWWMap`, `ORMap`, `BoundedCounter`, `Rga`, `Causal`, `JsonCrdt`, `EphemeralMap`, and more)
is documented in [`Writerside/topics/crdt-overview.md`](../../Writerside/topics/crdt-overview.md).

**Intent:** a shared value where the most recent write wins, converging across peers with no server to arbitrate.
**Primitive:** `LWWRegister` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWRegister -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

val left = LWWRegister.empty<String>().set(a, timestamp = 1L, value = "v1")
val right = LWWRegister.empty<String>().set(b, timestamp = 2L, value = "v2")

check(left.piece(right).value == "v2")  // ts=2 wins
check(right.piece(left).value == "v2")  // commutative
```

**Intent:** a set that only ever grows — completed tasks, acknowledged events, registered participants — with no remove needed and no server to arbitrate.
**Primitive:** `GSet` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

**Intent:** a shared counter that peers increment and decrement independently — a score, a budget, a tally — converging to the same net value with no coordinator.
**Primitive:** `PNCounter` (`us.tractat.kuilt.crdt`).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#samplePNCounter -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

var counter = PNCounter.ZERO
counter = counter.piece(counter.increment(a, 10))
counter = counter.piece(counter.decrement(b, 3))

check(counter.value == 7L)
```

**Intent:** a shared set where peers add and remove concurrently, and a concurrent re-add should beat a concurrent remove rather than silently vanishing.
**Primitive:** `ORSet` (`us.tractat.kuilt.crdt`).

`add`/`remove` return a `Patch` — the one element they touched — so a write costs the same whether
the set holds ten entries or ten thousand. Ship it with `quilter.mutate { it.add(replica, x) }`;
absorb it locally with `set.piece { it.add(replica, x) }`.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSet -->
```kotlin
// Two peers have converged: "alice" is present on both, added by B.
var alpha = ORSet.empty<String>().piece { it.add(b, "alice") }
var bravo = alpha

// A re-adds "alice" and puts only the change on the wire. The delta names A's new dot
// *and* B's older one, which the re-add supersedes — so both peers drop the old dot.
val readd = alpha.add(a, "alice")
alpha = alpha.piece(readd)
bravo = bravo.piece(readd)
check(alpha == bravo)

// A concurrent add beats a concurrent remove: B's re-add mints a dot A's remove never saw.
val concurrent = alpha.add(b, "alice")
check(alpha.piece(alpha.remove("alice")).piece(concurrent).contains("alice"))
```

**Intent:** drop many elements — or empty the set entirely — without paying a causal merge per element.
**Primitive:** `ORSet.removeAll` (`us.tractat.kuilt.crdt`).

Absorbing a patch is a join over the whole set, so `elements.fold(set) { s, e -> s.piece { it.remove(e) } }`
is Θ(n·N) — measured at ~3.5 s over a 10,000-element set. `removeAll` pays one join for the batch
(~4 ms) and is otherwise indistinguishable: same dots retired, same retained context, same bytes. The
retained context is what keeps the emptied set **dominant** over a peer that re-merges its old copy.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORSetBulkRemoval -->
```kotlin
// One patch drops all three — the same dots the per-element loop would have retired, so one
// causal join does the work of three.
set = set.piece { it.removeAll(set.elements) }
check(set.elements.isEmpty())

// The retained context is the point: a peer re-merging its pre-removal copy stays empty
// rather than resurrecting everyone.
check(set.piece(snapshot).elements.isEmpty())
```

**Intent:** a shared **map** whose keys peers add and remove concurrently, each key holding a value that merges in its own right — a roster, a task board, a nested document.
**Primitive:** `ORMap` (`us.tractat.kuilt.crdt`).

Same story one level up: `put`/`remove` return a `Patch` carrying one key, and a put's delta carries
**only the value you passed** — the receiver re-does the merge against its own copy, so a nested
`ORMap<K, ORSet<X>>` ships one element rather than the whole roster.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleORMap -->
```kotlin
// Two peers have converged: "team" already holds a long roster, put there by B.
var alpha = ORMap.empty<String, GSet<String>>()
    .piece { it.put(b, "team", GSet.of("alice", "bob", "carol", "dan")) }
var bravo = alpha

// A adds one member and puts only the change on the wire. The delta carries A's one name —
// not the merged roster — because the receiver re-does that merge against its own copy.
val hire = alpha.put(a, "team", GSet.of("erin"))
check(hire.delta["team"] == GSet.of("erin"))
```

**Intent:** a shared **settings**-shaped map — key → latest value, last writer wins per key, concurrent edits resolved rather than surfaced.
**Primitive:** `LWWMap` (`us.tractat.kuilt.crdt`).

`set`/`remove` return a one-cell `Patch`; a removal ships a *tombstone cell*, never an empty map
(an empty map is the lattice identity and would say nothing at all).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleLWWMap -->
```kotlin
// Two peers have converged on a settings map.
var alpha = LWWMap.empty<String, String>()
    .piece { it.set(a, timestamp = 1L, key = "lang", value = "en") }
    .piece { it.set(a, timestamp = 2L, key = "tz", value = "UTC") }
    .piece { it.set(a, timestamp = 3L, key = "theme", value = "dark") }
var bravo = alpha

// B changes one setting and puts only that cell on the wire. The frame is the same size
// whether the map holds three keys or ten thousand, and the other keys are untouched.
val change = alpha.set(b, timestamp = 4L, key = "theme", value = "light")
alpha = alpha.piece(change)
bravo = bravo.piece(change)
check(alpha == bravo)
```

**Intent:** a shared **JSON document** — nested objects, arrays and scalars — edited concurrently by several peers and converging without a merge step.
**Primitive:** `JsonCrdt` (`us.tractat.kuilt.crdt`).

`set`/`remove` return a `Patch` carrying the one key they touched, so editing one field of a
1,000-field document sends 177 bytes rather than the 127 KB the whole document weighs. A change
*inside* a nested object is still expressed by rebuilding that object and setting it at the top —
one key, but that key's value is the whole rebuilt subtree
([#2469](https://github.com/tractat-us/kuilt/issues/2469)).

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleJsonCrdt -->
```kotlin
// Two peers have converged on a document with a title and a long body.
var alpha = JsonCrdt.empty(a)
    .piece { it.set("title", text(a, "Draft")) }
    .piece { it.set("body", text(a, "a very long document body")) }
var bravo = alpha.withReplica(b)

// B retitles the document and puts only that key on the wire. The body does not travel —
// that is the whole saving, and it holds however large the rest of the document gets.
val retitle = bravo.set("title", text(b, "Final"))
check(retitle.delta.keys == setOf("title"))
check(retitle.delta["body"] == null)
```

**Intent:** replicating a CRDT live over a `Seam` by hand — collecting inbound deltas, merging them, broadcasting outbound deltas, and exposing the converged value as a `StateFlow`.
**Primitive:** `Quilter` (`us.tractat.kuilt.quilter`). Don't drive `Seam.incoming` and delta merge/broadcast yourself.

<!-- verbatim from kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt#sampleQuilterSetup -->
```kotlin
internal fun sampleQuilterSetup() = runTest(
    StandardTestDispatcher(),
    timeout = TEST_WEDGE_BACKSTOP,
) {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("my-session"))

    val cfg = QuilterConfig(expectVirtualTime = true)
    val replicator = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = QuiltMessage.serializer(GCounter.serializer()),
        scope = backgroundScope,
        config = cfg,
    )

    // Apply a mutation — the delta is broadcast to all current peers automatically.
    replicator.apply(replicator.state.value.inc(replicator.replica, 1L))

    // state is a StateFlow — always the current converged value.
    assertEquals(1L, replicator.state.value.value)
}
```

See [`crdt-quilter.md`](../../Writerside/topics/crdt-quilter.md) for `Quilter`'s wire protocol,
late-joiner full-state sync, and [Scaling to many peers](#scaling-to-many-peers) below for the
`GossipSeam` pairing.

**Intent:** read the shared state, decide, and *maybe* write — "claim the seat only if it's free", "publish this only if nobody already did", "apply the op if the state still allows it". The read and the decision have to be atomic with the write, and a refusal must be silent.
**Primitive:** `Quilter.mutateOrSkip { … }`. Return `null` from the transform to decline; it returns whether anything was published. Don't return an identity patch to mean "no change" — it leaves the state alone but still burns a sequence number and broadcasts an empty delta to every peer. Don't test the condition *before* the call either: that decides against a state nothing is holding still, so another writer can make the answer wrong before you publish.

<!-- verbatim from kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt#sampleQuilterMutateOrSkip -->
```kotlin
    // Claim a seat only if it is still free. Read and write are one atomic step, so no other
    // writer can take the seat between the check and the publish.
    fun claim(seat: String, player: String, at: Long): Boolean =
        seats.mutateOrSkip { board ->
            if (board[seat] != null) null else board.set(seats.replica, at, seat, player)
        }

    assertEquals(true, claim("north", "alice", 1L), "the seat was free — published")
    assertEquals(false, claim("north", "bob", 2L), "already taken — nothing published, no frame sent")
```

Use plain `mutate { … }` when the transform always writes.

**Intent:** two guests in the same room never see each other's updates — a `Quilter` between two joiners never converges, one peer's messages reach the host and stop, or a co-member is in the roster but every send to it fails. Typically on Multipeer, Nearby, a WebSocket hub, or any "everybody connects to the host" wiring.
**Primitive:** `Room.channel(id)` — and then nothing. The room relays through the host for you (#1994); don't build a forwarding protocol, and don't reach past the room to the raw fabric `Seam`.

The distinction that decides it: a fabric `Seam` from `Loom.host`/`Loom.join` reports the peers it holds a **direct link** to, which on a star is just the host. A `Room`'s channel view reports the **admitted roster**, and `Room.broadcast` / `Room.sendTo` wrap anything the transport cannot address directly and send it to the host to forward. So run replicators over `room.channel(...)`, never over the fabric seam the room was built on. (`Seam.sendTo` to a co-spoke still throws `PeerNotConnected` on those fabrics, and that is correct — the link really is absent.)

Two things the relay deliberately does not do. Presence is not relayed: a co-member with no direct link is watched by the host, not by you, so read `Room.roster` / `Room.events` rather than expecting a heartbeat to answer. And relayed delivery is best-effort past the first hop — `Room.broadcast` never throws, and `Room.sendTo` reports only the hop *this* member makes, naming the **host** when that is the hop that failed.

<!-- verbatim from kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarQuilterConvergenceTest.kt#setReplicator -->
```kotlin
private fun setReplicator(room: Room, scope: CoroutineScope): Quilter<GSet<String>> = Quilter(
    replica = ReplicaId(room.selfId.value),
    seam = room.channel("star-set"),
    initial = GSet.empty(),
    messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
    scope = scope,
    config = quilterConfig,
)
```

**Intent:** learn one shared model from data spread across many devices without collecting the data — federated learning / federated analytics, "each device trains locally and publishes only the update".
**Primitive:** `FedAvg` + `TrainingUpdate` (`:kuilt-warp-ml`). The count-weighted mean across peers, as a CRDT: contributions merge in any order, duplicates are absorbed, and every replica reads the same model bit-for-bit. Don't hand-roll a `(Σweights, Σcount)` accumulator or a round barrier.

<!-- verbatim from kuilt-warp-ml/src/commonSamples/kotlin/us/tractat/kuilt/warp/FedAvgSamples.kt#sampleFedAvg -->
```kotlin
val alice = ReplicaId("alice")
val bob = ReplicaId("bob")
val carol = ReplicaId("carol")

// Each peer trains locally and contributes its results.
val fromAlice = FedAvg.contribution(alice, sampleCount = 100L, localWeights = listOf(0.5, 0.3))
val fromBob   = FedAvg.contribution(bob,   sampleCount = 200L, localWeights = listOf(0.7, 0.1))
val fromCarol = FedAvg.contribution(carol, sampleCount = 300L, localWeights = listOf(0.9, 0.5))

// Any replica merges contributions in any order — result is the same.
val merged = FedAvg.ZERO.piece(fromAlice).piece(fromBob).piece(fromCarol)

// weights[i] = Σ(n_k * w_k[i]) / Σ(n_k)
val w = merged.weights
check(w.size == 2)
// Spot-check: (100*0.5 + 200*0.7 + 300*0.9) / (100+200+300) = 460/600 ≈ 0.7667
check(w[0] in 0.766..0.768)

// Idempotent: absorbing the same contribution again changes nothing.
check(merged.piece(fromAlice) == merged)
check(merged.piece(fromBob) == merged)

// Rides the coordination-free path — no Seam or Raft required.
val free = CoordinationFree(fromAlice).embroider(CoordinationFree(fromBob))
check(free.state == FedAvg.ZERO.piece(fromAlice).piece(fromBob))
```

The training step can travel too: `FedAvgKernelCodec` marshals the same step as a
content-addressed WebAssembly kernel shipped through [Code mobility](warp.md#code-mobility), and
`ReferenceTrainer` is the Kotlin oracle it is held bit-for-bit equal to. See
[`kuilt-warp-ml/module.md`](../../kuilt-warp-ml/module.md) — including the honest seam on lane
costing if you gate the workload with `HeddleAdmissionControl`.

The primitive lives in the warp family; see [warp](warp.md#code-mobility) for where a task may run and how code travels.

**Intent:** check that two peers hold the same state when you *can't* compare the objects — a cross-process or real-socket test where shipping a whole state back to assert on is impractical, or a divergence alarm between live peers in a harness.
**Primitive:** `canonicalDigest(serializer, value)` (`:kuilt-conformance`, `us.tractat.kuilt.conformance`). A 64-bit FNV-1a hash over the value's canonical CBOR encoding — converged replicas share a digest, diverged ones almost certainly don't, and one `Long` crosses the boundary instead of a whole state. Test- and harness-side only: `:kuilt-conformance` `api`-exposes `kotlin-test`, so it does not belong on a production classpath.

**In-process, don't use it.** `assertEquals(a, b)` on the states themselves is strictly better:
exact, no collision risk, and a far better failure message. `LatticeLawHarness` deliberately
compares raw bytes rather than digests for that reason. Reach for `canonicalDigest` only where the
comparison has to cross a process, a socket, or a live-peer boundary. And it is **not
cryptographic** — a 64-bit non-keyed hash is fine against accidental divergence and no defence at
all against a peer that forges a matching digest.

It also inherits the [canonical-encoding invariant](../../kuilt-crdt/module.md): a digest over a state
that encodes non-canonically reports *permanent* false divergence between replicas that agree. The
zoo holds that invariant; a bespoke state of your own has to earn it.

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CanonicalDigestTest.kt#convergedReplicasShareADigest -->
```kotlin
val ser = GSet.serializer(String.serializer())
val forward = GSet.of("alpha").piece(GSet.of("beta")).piece(GSet.of("gamma"))
val reverse = GSet.of("gamma").piece(GSet.of("beta")).piece(GSet.of("alpha"))
assertAll(
    { assertEquals(forward, reverse, "sanity: same logical state") },
    {
        assertEquals(
            canonicalDigest(ser, forward),
            canonicalDigest(ser, reverse),
            "converged replicas must share a digest",
        )
    },
)
```

**Intent:** keep only the newest N entries of a shared, replicated list or log — a chat backlog, an
audit trail, an on-device telemetry buffer — and actually *get the memory back*. Removing an entry
from an `Rga` only hides it: the insert, body and all, stays in the op-log forever, so a cap on how
much is *visible* is not a cap on how much is *held*. And once you do delete those ops, a peer that
never heard about the removal will happily re-add them on the next merge.
**Primitive:** `Rga.dropWindow(self, dropped)` (`us.tractat.kuilt.crdt`). It drops the ops **and**
leaves a suppression record behind, so the entries stay gone across a merge. Pair it with
`Rga.sequence`/`Rga.tombstones` to work out which ids fall outside the window you want to keep.

**Reading only the head, without paying for the whole log.** `toList()` and `entries()` each build
two lists the size of the log, so working out "which ids fall outside the window" by materialising
everything and then taking a handful throws nearly all of that work away. Walk `Rga.sequence`
lazily instead, filter `Rga.tombstones`, `take` what you need, and resolve just those with
`Rga.valueAt(id)` — an O(1) read of one element by its id:

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleRgaHeadWindow -->
```kotlin
    // Remove the first entry. It is TOMBSTONED, not gone — still in `sequence`, and its value
    // is still readable — which is exactly why the walk has to filter `tombstones` itself.
    log = checkNotNull(log.removeAt(0)).first
    check(ids.first() in log.tombstones)
    check(log.valueAt(ids.first()) == "entry-1")

    // The two oldest VISIBLE entries, resolving only those two.
    val head = log.sequence.asSequence()
        .filter { id -> id !in log.tombstones }
        .take(2)
        .map { id -> log.valueAt(id) }
        .toList()
    check(head == listOf("entry-2", "entry-3"))
```

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleRgaDropWindow -->
```kotlin
val a = ReplicaId("A")

var log = Rga.empty<String>()
var after = RgaId.HEAD
val ids = (1..5).map { i ->
    val (next, op) = log.insertAfter(replica = a, after = after, value = "entry-$i")
    log = next
    after = op.id
    op.id
}
val peer = log // a peer that still holds all five inserts

// Retain the newest two; drop the rest.
val (windowed, delta) = checkNotNull(log.dropWindow(self = a, dropped = ids.take(3).toSet()))
check(windowed.toList() == listOf("entry-4", "entry-5"))

// This replica's OWN dots fold into a floor — one entry per author, not one per element.
check(windowed.causalFloor()[a] == 3L)
check(windowed.compactOpCount == 0)

// The drop is permanent suppression, not deletion: merging the peer's log back in
// re-purges the dropped entries instead of resurrecting them.
check(windowed.piece(peer).toList() == listOf("entry-4", "entry-5"))

// Any peer performs the same drop by absorbing the returned delta.
check(peer.piece(delta.delta).toList() == listOf("entry-4", "entry-5"))
```

**How cheap the drop is depends on who wrote the entry, and only one of the two arms is a bound.**
Your own entries fold into a per-author *floor* — one number per author, however many entries you
drop — which is why a log you alone append to settles back to O(window). An entry **another** peer
wrote cannot: raising someone else's floor would annihilate entries they have not written yet, so
`dropWindow` records those individually, and nothing prunes those records. So the honest shape is
**bounded on the local-append path, still growing on the gossip path** — a strict improvement on
retaining the whole entry, but not a bound. Say so wherever you quote a bound.

Two more things to know before you reach for it. The window is *positional*: an entry whose
predecessor you dropped re-anchors to the front of the list rather than to that predecessor, so
relative order with older entries is not preserved (see `Rga.compactedBelow`). And if you replicate
through `Quilter`, read `causalFloor()` alongside `causalDots()` when you fold a delivered
frontier — the floored dots leave `causalDots()` entirely, and a walk that counts only dots reports
a frontier of zero for that author and stalls every downstream collection.

**Primitive:** `VersionVector.contiguous(dots, floor)` (`us.tractat.kuilt.crdt`) — the same function
`Quilter` folds its own cut with, so there is nothing left to hand-roll. It stops at the first gap
on purpose: a replica holding `1, 2, 4` has *not* delivered `4`, because `3` is still in flight and
something it has not seen may depend on it.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleVersionVectorContiguous -->
```kotlin
val a = ReplicaId("A")
val b = ReplicaId("B")

// A holds 1, 2, 4 — seq 3 is still in flight, so A has delivered 2, not 4.
val dots = setOf(Dot(a, 1L), Dot(a, 2L), Dot(a, 4L), Dot(b, 1L))
val frontier = VersionVector.contiguous(dots, floor = VersionVector.EMPTY)
check(frontier[a] == 2L)
check(frontier[b] == 1L)

// After compaction swallows A's 1..2 without keeping their ids, the floor asserts they were
// delivered and the walk starts above them — so 4 is still gapped, but 3 would now count.
val floor = VersionVector.of(mapOf(a to 2L))
check(VersionVector.contiguous(setOf(Dot(a, 4L)), floor)[a] == 2L)
check(VersionVector.contiguous(setOf(Dot(a, 3L), Dot(a, 4L)), floor)[a] == 4L)
```

`floor` is deliberately not defaulted. Passing `VersionVector.EMPTY` where a real floor exists
collapses that author's high-water to `0`, and a gossiped regression there pins every downstream
compaction below the gap forever.

**I want the edit history, not the current value — and I want it to outlive what the replica
forgets.** A replicated list is really a log of small edits, and `dropWindow`/`compact` above throw
old ones away. If you want a record that *survives* that — an audit trail, an archive, a server
holding a year of history beside a phone holding an hour — you cannot get it by merging: merging
makes forgetting **contagious**, so a compacted peer propagates its compaction to everyone it syncs
with. You have to consume the **operations** instead, as they arrive.

**Primitive:** `OpLogCrdt` (`us.tractat.kuilt.crdt`), implemented by both `Rga` and `Fugue`.
`operations()` is the live log, `classify(op)` splits it three ways, and `dotOf(id)` projects an
id to its causal dot. The split is the safety-critical part: `LogOp.Insert` and `LogOp.Remove` are
*content*, while `LogOp.Compact` is a record of *forgetting*. Keep the first two and discard the
third and your history outlives its source's — which is the whole trick.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleOpLogCrdt -->
```kotlin
// The same three-way split for any op-log CRDT — Fugue implements the identical contract.
val content = log.operations().filterNot { log.classify(it) is LogOp.Compact }
check(content.count() == 2)

// Inserts mint dots; a remove reuses its target's id, so a dot cursor is defined over
// inserts only.
val dots = log.operations()
    .mapNotNull { (log.classify(it) as? LogOp.Insert)?.id }
    .map { id -> log.dotOf(id) }
    .toSet()
check(dots == setOf(Dot(a, 1L), Dot(a, 2L)))
```

Two traps. **`operations()` is the *live* log, never a complete history** — a replica that has
already compacted no longer holds what it dropped, and for `Rga` an op below the `compactedBelow`
floor leaves with **no** `Compact` naming it. So feed an archive as ops arrive; you cannot
reconstruct one from a replica afterwards. And **encode ops with `opSerializer`**, never a
compiler-generated serializer: the generated one writes a different wire format and cannot encode a
polymorphic element type under CBOR, putting your bytes outside the golden vectors that pin the
format across versions. It is on the interface, and on the `Rga`/`Fugue` companions for a decoder
that has bytes but no replica.

## Scaling to many peers

Everyone-talks-to-everyone is fine for a card game and stops working for a room of a hundred:
each device ends up holding a hundred links, every change goes out a hundred times, and the
bookkeeping each peer keeps about the others grows with the size of the room rather than with
how much of it that peer actually talks to.

**Intent:** make a large session practical without changing anything above the fabric.
**Primitive:** `GossipSeam` (`:kuilt-gossip`) wrapped around the seam you already have, paired with
`deltaTargets = { gossip.activePeers.value }` on your `Quilter`. A `GossipSeam` **is** a `Seam`, so
`Room`, `Quilter` and everything downstream are untouched: `broadcast` floods to a handful of
neighbours who re-flood to *their* handful, duplicates are recognised and dropped, and `Quilter`'s
anti-entropy reconcile is the backstop that makes "usually connected" good enough.

<!-- verbatim from kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/GossipQuilterConvergenceTest.kt#quilterOverGossipSeamConverges -->
```kotlin
val gossips = mesh.seams.mapIndexed { i, base ->
    GossipSeam(base = base, random = Random(1 + i), clock = clock, config = noHeartbeat, jitter = ZERO..ZERO)
}
gossips.forEach { it.start(backgroundScope) }
flush()

// The overlay holds a k-regular active view, strictly smaller than full membership —
// the ack-set every replicator GCs against.
val k = recommendedActiveViewSize(n)
gossips.forEach { g ->
    assertEquals(k, g.activePeers.value.size, "each node holds k=$k active neighbours")
}
assertTrue(k < n - 1, "k=$k must be a strict subset of the N-1=${n - 1} full membership")

// One Quilter per node, GCing only against that node's active neighbours.
val quilters = gossips.mapIndexed { i, gossip ->
    Quilter(
        seam = gossip,
        initial = GCounter.ZERO,
        valueSerializer = GCounter.serializer(),
        scope = backgroundScope,
        config = quilterCfg,
        deltaTargets = { gossip.activePeers.value },
        random = Random(100 + i),
    )
}
```

(`mesh.seams` there is just the seams you already have; the zeroed jitter and hour-scale heartbeats
are what make that test deterministic — production takes the defaults.)

**Two views, answering different questions.** `activePeers` is the ~k neighbours this peer exchanges
updates with, and is what `deltaTargets` should point at so the GC watermark tracks *those* acks
rather than the whole room's — that is the scaling win, and it is the line people leave out. `peers`
is full membership, delegated from the base seam, and is the pool anti-entropy samples from.
`recommendedActiveViewSize(n)` is the k the default policy draws (`max(4, ⌈ln N⌉ + 2)`, so ~4–7 for
tens to low hundreds), which is what keeps the union of everyone's independent choices a single
connected graph even though nobody is connected to everybody. **Seed `random` per peer** — a shared
seed makes every peer pick the same neighbours and collapses that graph. (`spares` is the short
standby list a lost neighbour is replaced from; read it to inspect failover, don't drive it.)

`start(scope)` once, on a scope you own. `sendTo` is deliberately **not** shaped by the overlay: it
passes straight through to the base seam, so point-to-point still reaches any peer the transport can
address. `incoming` keeps the single-collection contract, and `GossipSeam` is itself the sole
collector of the base seam's — ping/pong frames are consumed by the per-neighbour detectors and never
surface to you.

Reach for it by size, not by reflex: at a handful of peers a partial mesh buys nothing and costs a
hop, and adding it later is a one-line change where the seam is built. The other shipped
`TopologyPolicy` is `FullFanout`, where one hub re-floods every broadcast to all its spokes;
`CoroutineScope.hostedOverlay(selfId, source, dispatcher, …)` is that hub already assembled over a
`ConnectionSource`.

## Dealing cards nobody can peek at

You want to deal a hand where nobody — not even whoever is running the app — can see a card
they were not dealt, and nobody can arrange which card they get. There is no trusted dealer
and no server holding the deck. The obvious thing, shuffling an array on one device and
sending each player their slice, asks every player to trust that device completely.

**Intent:** a fair deal with no dealer.
**Primitive:** `DealSession` (`:kuilt-deal`) over any `Seam`. Every player encrypts the whole deck
with their own key; because the cipher **commutes**, those layers peel off in any order, so a card is
readable by exactly the players who still hold an unstripped layer of it. `assignQuorums` says who
those players are, per card; `strip()` removes the layers that are not protecting anybody's secrecy;
`decrypt(index)` reads a card once it is `CardPhase.REVEALED`.

<!-- verbatim from kuilt-deal-test/src/commonTest/kotlin/us/tractat/kuilt/deal/test/DealSessionTest.kt#twoPlayerPokerDeal_aliceSeesHerCard_bobCannotRead -->
```kotlin
val alice = PeerId("alice")
val bob = PeerId("bob")
val (aliceSession, bobSession) =
    fakeDealSessionPair(alice, bob, seededXorSchemes(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

val originalCard = "ACE_OF_SPADES".encodeToByteArray()
val deck = listOf(originalCard)

// Shuffle: both players encrypt the deck (alice first, then bob builds on it)
aliceSession.shuffle(deck)
bobSession.shuffle(deck)

// Deal: alice's hand — only alice can see card 0
val quorumAlice = mapOf(0 to setOf(alice))
aliceSession.assignQuorums(quorumAlice)
bobSession.assignQuorums(quorumAlice)

// Reveal: non-quorum players (bob) strip their layers
bobSession.strip()

// Alice decrypts her own layer
val revealed = aliceSession.decrypt(0)
assertEquals(originalCard.toList(), revealed.toList())

// Secrecy: bob is not in the quorum — he cannot recover the plaintext.
val bobAttempt = runCatchingCancellable { bobSession.decrypt(0) }.getOrNull()
assertNotEquals(originalCard.toList(), bobAttempt?.toList())
```

That test drives the session with a fast XOR stand-in for the cipher; in production the
`CommutativeScheme` is `SraScheme()`. Pass a **factory**, not one instance — every player mints their
own key in their own process, and a harness that hands one object to both sides is testing the single
arrangement that cannot expose a cross-instance disagreement. For your own tests,
`fakeDealSessionPair` / `fakeDealSessionGroup` (`:kuilt-deal-test`) wire a group of sessions over fake
seams, and `CommutativeSchemeConformanceSuite` is how a scheme of your own earns the round-trip,
commutativity and strip-order-independence properties the whole deal rests on.

Three things to know before building on it. **Assign every quorum on every peer before any peer
strips** — quorum membership is what decides whether a strip is accepted, so one that arrives before
the local assignment is dropped and never retried. **A partial quorum takes more than one pass**:
where three or more players share sight of one card, members strip each other's reveal tracks in a
canonical order, so call `strip()` again as remote ops arrive until the card reads `REVEALED`. And a
card *index* is not a secret — the deal hides values; which slot went to whom is your protocol's
business.

### Nobody chose that number

**Intent:** a shared random value — a seed, a first player, a die roll — that every peer agrees on and
no peer could steer. You are about to have one peer pick it and broadcast it.
**Primitive:** `FairRandom(seam, peers).roll()` (`:kuilt-deal`). Two phases: everyone publishes a hash
of their secret, then the secret itself, and the seed is derived from all of them — so one honest
contributor is enough to make the result unpredictable to everybody, including that contributor.

<!-- verbatim from kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt#twoPeers_agreeOnIdenticalSeed -->
```kotlin
val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
val peers = setOf(alice, bob)
val (aliceSeam, bobSeam) = fakeSeamPair(alice, bob)

val aliceDef = scope.async { FairRandom(aliceSeam, peers).roll() }
val bobDef = scope.async { FairRandom(bobSeam, peers).roll() }

val aliceSeed = aliceDef.await()
val bobSeed = bobDef.await()

assertEquals(aliceSeed, bobSeed, "Both peers must derive the same seed")
```

`roll()` is one round — build a fresh `FairRandom` per roll. It **throws rather than hangs** when the
seam tears, or when a required participant leaves the live peer set mid-round: the missing
commit or reveal is never coming, so it raises `SeamCollapsedException` within a bound and you need no
outer timeout of your own. The one thing it cannot defend against is stated plainly in its own KDoc.
A last mover sees every other reveal before deciding whether to send its own, so **withholding is a
game-layer concern** — forfeit the peer that goes quiet; commit-reveal cannot.

## Dedup

**Intent:** skip a message/id you've already handled ("seenIds", "skip-if-exists").
**Primitive:** `GSet` (`:kuilt-crdt`) for a converging grow-only set, or kuilt's dedup key where you're inside a replicated log. Don't keep an ad-hoc `mutableSetOf<Long>()`.

<!-- verbatim from kuilt-crdt/src/commonSamples/kotlin/us/tractat/kuilt/crdt/CrdtSamples.kt#sampleGSet -->
```kotlin
var set = GSet.empty<String>()
set = set.piece(set.add("alice"))
set = set.piece(set.add("bob"))
check(set.elements == setOf("alice", "bob"))
```

## Archiving what the live replica forgets

**Intent:** keep a longer history than the live replica does — "a year on the server beside an hour on the phone", "the records a peer gossiped to us disappear once that peer forgets them", an archive or audit trail of a replicated log. Don't tee what a replica applied by hand, and don't try it with a second replica of a bigger size: forgetting is contagious through a merge, so the big one shrinks to the small one on first contact.
**Primitive:** `BoltDecorator` (`:kuilt-bolt`) fed by `WarpLogRecordExporter`'s `appliedOps` (`AppliedOpSink`, `:kuilt-otel`) — or by any other `Rga`/`Fugue` owner, since neither side knows what the other is for.

**Wire the merge path or the whole thing is pointless.** `WarpLogRecordExporter` already publishes on both, and the merge one is why: a merge is a state join with no operations to tee, and gossip is how another device's records arrive. An archive fed only by local exports holds this replica's own telemetry and nobody else's.

Re-merging the same peer every anti-entropy round does **not** re-archive its log — `BoltDecorator` suppresses what it has already kept, and for *inserts* it does so at one entry per author rather than one per operation: an insert mints a unique causal dot, so a frontier of archived dots answers "kept already?" with no working set to exceed. That frontier is bounded too, but in **runs** — `frontierWindow`, one per author plus one for each unfilled hole in that author's sequence, so ordinary traffic never approaches it; past it the shortest run is evicted and the inserts it covered are archived a second time. Holes come from a peer that compacted before you first met it, so an archive attached to an *established* mesh is the one to size against. A **remove** mints no dot (it reuses its target insert's id), so those are remembered one at a time in a bounded LRU window — size `removalWindow` to the aggregate live *tombstones* you expect to be offered, not to whole logs. Past it the window thrashes and suppression collapses: the archive then grows by roughly the *whole* offered set of removes per round, not by the excess. A miss costs a duplicate operation in the archive, never a lost one.

**Completeness is bounded by how often you merge, not by how much the archive holds.** A peer can only hand over what it *still has*, and a peer running its own buffer cap windows its oldest records away with no marker saying so. Merge with it more slowly than its buffer turns over and the archive is exactly as complete as your gossip schedule allowed — quietly, because a replay's truncation verdict reports damage to the *archive*, not a gap at the *source*.

A `clear()` empties the replica and leaves the archive alone; that asymmetry is the entire point. A refused append is reported on `BoltDecorator.health` with the **dots** of the records it could not keep, because the live replica windows those away next — so they are lost from both sides, and a count would leave nothing to act on. That surface is bounded and conflating, so a consumer that must not lose an identity calls `BoltDecorator.publish` itself and reads its `AppendResult` rather than routing through a `Unit`-returning sink.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleArchivingExporter -->
```kotlin
val format = BoltArchiveFormat.rga(LogRecord.serializer())
val bolt = InMemoryBolt(format, Clock.System)
val archive = BoltDecorator(bolt, format)

// The exporter publishes the operations it applied; the decorator archives them. Neither
// knows the other's job, so the same decorator serves any Rga/Fugue owner.
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("server-uuid-abc123"),
    store = InMemoryDurableStore(),
    appliedOps = { ops -> archive.publish(ops) },
)

// Records that arrived by GOSSIP are archived too: a merge publishes the remote log, which
// is the only reason a server's archive ever holds a phone's records. Re-merging the same
// peer costs nothing — the decorator suppresses what it has already kept. Merge OFTEN
// ENOUGH, though: this only ever carries what the peer has not yet windowed away.
exporter.merge(peersLog)

// And the archive keeps them after the live replica has forgotten them.
exporter.clear()
val kept = bolt.replay(ReplayScope.All).frames().toList().flatMap { it.ops }
check(kept.isNotEmpty()) { "a clear empties the replica, never the archive" }
```

**Intent:** read that archive back — "replay what the phone compacted away", "give me everything this machine wrote last Tuesday", resuming where the last pass stopped. Don't hand-roll an "is my archive intact" check, and don't decide the history is complete because the replay finished.
**Primitive:** `Bolt.replay(scope)` (`:kuilt-bolt`), returning a cold flow of `Archived` frames terminated by exactly one verdict — `CleanTail` or `Truncated`.

**The verdict is the product; collect to completion or you don't get one.** A replay that stopped at damage and one that read everything both just *end*, so a stream without a verdict hands back an incomplete history indistinguishable from a complete one — and "I still hold what the live replica forgot" is the only thing an archive sells. `take(n)`/`first()` get no verdict, honestly: they stopped reading before the archive said how it ended. `.frames()` discards it deliberately — fine for a diagnostic dump, not for anything acting on the archive being whole.

`TruncationReason` splits on the **remedy**, not the layer. `SegmentHeader` and `Frame` mean "not readable *yet*" — a writer mid-append, a device still locked — so a later resume from `atOffset` can work. `MissingRegion` means the bytes are **gone**, and `atOffset` is the honest end of the readable history rather than a cursor. Retrying it will never produce those records.

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltReplayVerdict -->
```kotlin
var records = 0
var complete = false

// Collect to COMPLETION. The terminal verdict is what a replay sells — a history that
// stopped at damage, and one that did not, are otherwise indistinguishable. A consumer
// that cuts the flow short (take, first, an early return) gets no verdict, honestly.
bolt.replay(ReplayScope.All).collect { event ->
    when (event) {
        is Archived -> records += event.ops.size
        CleanTail -> complete = true
        is Truncated -> when (event.reason) {
            // Not readable YET — a writer mid-append, a device still locked. Resuming
            // from atOffset later can work.
            TruncationReason.SegmentHeader, TruncationReason.Frame -> retryFrom(event.atOffset)
            // GONE. atOffset is the honest end of the readable history and is NOT a
            // resume cursor: nothing will ever produce the records behind it.
            TruncationReason.MissingRegion -> reportPermanentGap(event.atOffset)
        }
    }
}

if (!complete) reportPartialHistory(records)
```

**Two of the four scopes are cursors and two are queries; resume only from a cursor.** `All` and `FromOffset` are total over the frames they have not yet seen. `Arrived` filters by **arrival** time — when the archive was *told*, arbitrarily later than when it happened for anything that came by merge — and `InsertsAbove` filters by causal coverage over **inserts only**, because a `Remove` mints no dot of its own. So a frame of pure removes is selected by no dot scope at all, however recent: resuming from a dot frontier would skip it and replay a removed record as live.

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltResumeCursor -->
```kotlin
// Consume what the archive holds now, remembering where each frame ended. `.frames()`
// deliberately drops the terminal verdict — fine for a cursor walk, not for anything
// that acts on the history being complete.
var cursor = 0L
bolt.replay(ReplayScope.All).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}

// Later — after more appends — pick up exactly there. An offset that falls inside a frame
// yields that frame from its start, so a cursor can never point at half a record.
bolt.replay(ReplayScope.FromOffset(cursor)).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}
```

**A replay may be READ. It must never be AUTHORED FROM.** Folding one into a fresh replica produces a structurally valid state, so nothing stops you — the damage lands one step later and is permanent. A replica seeded from a replay missing frames at its tail re-mints an already-used `(replica, seq)` dot carrying different content, breaking the dense per-author delivery counter every causal-stability version vector depends on, mesh-wide, with nothing to purge it.

Before trimming the live replica's own window on the strength of "the archive has it", ask `Bolt.durability()` (or read it off `BoltDecorator.health`). A flush covers a **range**, so a failed one puts every frame since the last good flush in doubt — not the append that triggered it, whose result is already in your past. It is sticky and widening precisely so it can be polled.
