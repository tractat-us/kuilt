package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import us.tractat.kuilt.test.assertAll

class RgaCompactedFloorTest {

    private val me = ReplicaId("a")
    private val peer = ReplicaId("b")

    /** Append [n] records as [author], returning the state and the ops in mint order. */
    private fun chain(
        n: Int,
        author: ReplicaId = me,
        prefix: String = "r",
    ): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var rga = Rga.empty<String>()
        var tail = RgaId.HEAD
        val ops = mutableListOf<RgaOp.Insert<String>>()
        repeat(n) { i ->
            val (next, op) = rga.insertAfter(author, tail, "$prefix$i")
            rga = next
            tail = op.id
            ops += op
        }
        return rga to ops
    }

    @Test
    fun anEmptyRgaHasAnEmptyFloor() {
        assertEquals(VersionVector.EMPTY, Rga.empty<String>().compactedBelow)
    }

    @Test
    fun raisingTheFloorPurgesTheOpsBeneathItAndHidesTheirRecords() {
        val (rga, _) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        assertEquals(listOf("r3", "r4"), floored.toList(), "the first three seqs are gone")
        assertTrue(floored.ops.none { it is RgaOp.Insert && it.id.seq <= 3L }, "their ops are purged")
    }

    @Test
    fun anInsertBeneathTheFloorIsNotReAdmitted() {
        val (rga, ops) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        val reapplied = floored.apply(ops[0])

        assertEquals(listOf("r3", "r4"), reapplied.toList(), "a late raw apply must not resurrect")
    }

    /**
     * The `Remove` arm of the same guard. It cannot be pinned through [Rga.toList] — the record
     * is already invisible either way — so this asserts on the **state**: admitting the `Remove`
     * would put an op at-or-below the floor back into [Rga.ops], and the very next [Rga.piece]
     * would purge it again, so `a.piece(a) != a` and idempotence is gone.
     */
    @Test
    fun aRemoveBeneathTheFloorIsNotReAdmitted() {
        val (rga, ops) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        val reapplied = floored.apply(RgaOp.Remove<String>(ops[0].id))

        assertAll(
            { assertEquals(floored, reapplied, "a late raw Remove must not re-enter the op-log") },
            { assertEquals(floored.ops, reapplied.ops, "and specifically must not grow ops") },
            { assertEquals(reapplied, reapplied.piece(reapplied), "so piece stays idempotent") },
        )
    }

    @Test
    fun aMergeWithAPeerHoldingTheRawInsertsDoesNotResurrectThem() {
        val (rga, _) = chain(5)
        val peerHoldingEverything = rga
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 3L)))

        assertEquals(listOf("r3", "r4"), floored.piece(peerHoldingEverything).toList())
        assertEquals(listOf("r3", "r4"), peerHoldingEverything.piece(floored).toList(), "and commutatively")
    }

    @Test
    fun floorsMergeByElementwiseMax() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))
        val b = rga.withCompactedBelow(VersionVector.of(mapOf(me to 4L, peer to 7L)))

        assertEquals(VersionVector.of(mapOf(me to 4L, peer to 7L)), a.piece(b).compactedBelow)
        assertEquals(a.piece(b).compactedBelow, b.piece(a).compactedBelow, "commutative")
    }

    /**
     * The floor is now part of [Rga.equals], so the lattice laws have to be re-proved over
     * states that carry one — `QuiltedLawsTest` and the conformance bindings only reach the
     * unfloored constructors. Tasks 3–7 rest on the whole product being a join-semilattice,
     * not just on the floor component merging by max.
     */
    @Test
    fun theLatticeLawsHoldOverFlooredStates() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))
        val b = rga.withCompactedBelow(VersionVector.of(mapOf(me to 4L, peer to 7L)))
        val c = rga.withCompactedBelow(VersionVector.of(mapOf(peer to 3L)))

        assertAll(
            { assertEquals(a, a.piece(a), "idempotent") },
            { assertEquals(a.piece(b), b.piece(a), "commutative") },
            { assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)), "associative") },
            { assertEquals(a.piece(b).hashCode(), b.piece(a).hashCode(), "hashCode agrees with equals") },
        )
    }

    /**
     * Pins the **accepted** cost documented on [Rga.compactedBelow]: a floor records no
     * positions, so `computeSequence`'s #293 reroot has nothing to walk and a survivor whose
     * predecessor was floored away lands on [RgaId.HEAD]. HEAD's child list is sorted by id
     * descending, so the high-lamport survivor `b1` overtakes `b0` — a record it used to trail.
     *
     * This is a reordering, not a divergence: the order is still a function of `(ops, floor)`.
     * The test exists so that if anyone later *fixes* the reroot, they see the cost they paid
     * (a per-element positions map is exactly what the floor removes) rather than a silent
     * behaviour change.
     */
    @Test
    fun aSurvivorWhosePredecessorWasFlooredRerootsToHeadAndCanOvertake() {
        val (s1, a1) = Rga.empty<String>().insertAfter(me, RgaId.HEAD, "a1")
        val (s2, _) = s1.insertAfter(peer, RgaId.HEAD, "b0")
        val (s3, a2) = s2.insertAfter(me, a1.id, "a2")
        val (rga, _) = s3.insertAfter(peer, a2.id, "b1")

        assertEquals(listOf("b0", "a1", "a2", "b1"), rga.toList(), "b1 trails b0 while its ancestor lives")

        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 2L)))

        assertEquals(
            listOf("b1", "b0"),
            floored.toList(),
            "with a1/a2 floored, b1 re-roots to HEAD and outranks the older b0",
        )
    }

    @Test
    fun twoStatesDifferingOnlyInTheirFloorAreNotEqual() {
        val (rga, _) = chain(5)
        val a = rga.withCompactedBelow(VersionVector.of(mapOf(peer to 9L)))

        assertTrue(a != rga, "the floor is part of the value, not a cache")
    }

    /**
     * A floor raised past every op this replica holds is the **only** surviving evidence that
     * the seqs it covers were ever minted — the ops that carried them are gone and, unlike
     * [RgaOp.Compact], a floor has no id-set to re-emit dots from. `cacheAfterFloor` must
     * therefore fold it into the seq high-water, or the next mint reuses a swallowed seq and
     * two distinct records share a dot (the #639 class).
     */
    @Test
    fun aFloorRaisedPastTheHeldOpsStillHoldsTheSeqHighWaterUp() {
        val (rga, _) = chain(2) // me has minted seqs 1..2 only
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 9L)))

        val (_, op) = floored.insertAfter(me, RgaId.HEAD, "fresh")

        assertEquals(10L, op.id.seq, "the floor is the only record that seqs 3..9 were minted")
    }

    /**
     * [Rga.fromOps] purges on construction, so a state whose op-set contradicts its own floor
     * cannot exist. This is the path a wire decode takes: without the purge the decoded value
     * would hold ops at-or-below its floor, the first [Rga.piece] would drop them, and
     * `a.piece(a) != a`. Asserts the op-set as well as the sequence — the ops are the state,
     * and a decoded blob could carry a *tombstoned* floored id that [Rga.toList] never shows.
     */
    @Test
    fun fromOpsPurgesAnOpSetThatContradictsItsOwnFloor() {
        val (rga, _) = chain(5)
        val floor = VersionVector.of(mapOf(me to 3L))

        val decoded = Rga.fromOps(rga.ops, rga.lamport, floor)

        assertAll(
            { assertEquals(listOf("r3", "r4"), decoded.toList(), "the floored records stay hidden") },
            {
                assertTrue(
                    decoded.ops.none { it is RgaOp.Insert && it.id.seq <= 3L },
                    "and their ops never enter the log",
                )
            },
            { assertEquals(decoded, decoded.piece(decoded), "so piece is idempotent on a decoded value") },
            { assertEquals(rga.withCompactedBelow(floor), decoded, "same value as the locally-floored state") },
        )
    }

    /**
     * The same evidence has to survive [Rga.piece] — a floor absorbed from a peer counts too.
     *
     * The mechanism is **not** a fold inside [Rga.piece], which has none and needs none: the peer
     * is cacheless, so `computeMaxSeqByReplica` folds the floor into *its* high-water at
     * construction, and `piece` then carries that across with `mergeMax(other.maxSeqByReplica)`.
     * Folding the merged floor a second time in `piece` could never change the result — every
     * construction site maintains `maxSeqByReplica[r] >= compactedBelow[r]` — and a guard that
     * did so was deleted as dead code once the cacheless path was fixed at the source (#2127).
     */
    @Test
    fun mergingInAFloorRaisesTheSeqHighWaterToo() {
        val (rga, _) = chain(2)
        // fromOps is the cacheless path: this state's seq high-water comes from the floor alone.
        val flooredPeer = Rga.fromOps<String>(emptySet(), 0L, VersionVector.of(mapOf(me to 9L)))

        val (_, op) = rga.piece(flooredPeer).insertAfter(me, RgaId.HEAD, "fresh")

        assertEquals(10L, op.id.seq, "the peer's floor-derived high-water must carry across piece")
    }

    @Test
    fun aFloorCoveringEverythingLeavesAnEmptyButUsableSequence() {
        val (rga, _) = chain(5)
        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 5L)))

        assertEquals(emptyList(), floored.toList())
        val (grown, op) = floored.insertAfter(me, RgaId.HEAD, "fresh")
        assertEquals(listOf("fresh"), grown.toList())
        assertTrue(op.id.seq > 5L, "the next seq must not reuse a swallowed one")
    }

    /**
     * The companion to [aSurvivorWhosePredecessorWasFlooredRerootsToHeadAndCanOvertake], and the
     * reason [Rga.compactedBelow]'s KDoc no longer claims the reorder is cross-author only.
     * `insertAt(replica, 0, v)` anchors after HEAD, so one author routinely holds two
     * HEAD-children; flooring the elder's subtree root lets its successor re-root to HEAD and
     * overtake the younger sibling. Written as an executed counterexample rather than prose so
     * the claim cannot quietly become false again.
     */
    @Test
    fun aSingleAuthorLogReordersTooWhenAFlooredPredecessorForcesARerootToHead() {
        val (s1, a) = Rga.empty<String>().insertAfter(me, RgaId.HEAD, "A")
        val (s2, _) = s1.insertAfter(me, RgaId.HEAD, "B")
        val (rga, _) = s2.insertAfter(me, a.id, "C")

        assertEquals(listOf("B", "A", "C"), rga.toList(), "HEAD's two children sort descending")

        val floored = rga.withCompactedBelow(VersionVector.of(mapOf(me to 1L)))

        assertEquals(
            listOf("C", "B"),
            floored.toList(),
            "with A floored, C re-roots to HEAD and overtakes B — inside a single author",
        )
    }

    // ── dropWindow — the sound mint path ────────────────────────────────────

    @Test
    fun dropWindowFoldsAContiguousOwnPrefixIntoTheFloorAndLeavesNoResidue() {
        val (rga, ops) = chain(5)
        val dropped = ops.take(3).map { it.id }.toSet()

        val (state, patch) = rga.dropWindow(me, dropped)!!

        assertAll(
            { assertEquals(VersionVector.of(mapOf(me to 3L)), state.compactedBelow) },
            { assertEquals(listOf("r3", "r4"), state.toList()) },
            {
                assertTrue(
                    state.ops.none { it is RgaOp.Compact },
                    "a fully contiguous own prefix needs no explicit Compact — that is the whole bound",
                )
            },
            {
                assertEquals(
                    VersionVector.of(mapOf(me to 3L)),
                    patch.delta.compactedBelow,
                    "the delta carries the floor",
                )
            },
        )
    }

    @Test
    fun dropWindowStopsTheFloorAtTheFirstOwnGapAndRecordsTheRestExplicitly() {
        val (rga, ops) = chain(5)
        // Drop seqs 1, 2 and 4 — 3 is retained, so the floor may only reach 2.
        val dropped = setOf(ops[0].id, ops[1].id, ops[3].id)

        val (state, _) = rga.dropWindow(me, dropped)!!

        val explicit = state.ops.filterIsInstance<RgaOp.Compact>().flatMap { it.positions.keys }.toSet()
        assertAll(
            {
                assertEquals(
                    VersionVector.of(mapOf(me to 2L)),
                    state.compactedBelow,
                    "the floor stops below the gap",
                )
            },
            { assertEquals(setOf(ops[3].id), explicit, "the above-gap drop is recorded explicitly") },
            { assertEquals(listOf("r2", "r4"), state.toList(), "and the retained record in the gap survives") },
        )
    }

    /**
     * The floor entry raised is keyed to [me], so it is not enough that a peer's dot goes to the
     * residue — a peer's **seq** must not advance the walk either. The two authors are given
     * *disjoint* dropped seqs (mine 1, theirs 2 and 3) precisely so the two failures separate:
     * a walk that forgets to filter by author reaches 3 and floors my own retained seqs 2 and 3
     * out of existence. Asserting only the peer's floor entry cannot see that; `toList()` can.
     */
    @Test
    fun dropWindowNeverRaisesAForeignAuthorsFloor() {
        val (mine, myOps) = chain(3)
        val (theirs, theirOps) = chain(3, author = peer, prefix = "p")
        val merged = mine.piece(theirs)

        val (state, _) = merged.dropWindow(me, setOf(myOps[0].id, theirOps[1].id, theirOps[2].id))!!

        val explicit = state.ops.filterIsInstance<RgaOp.Compact>().flatMap { it.positions.keys }.toSet()
        assertAll(
            { assertEquals(0L, state.compactedBelow[peer], "a peer's dots are never floored locally") },
            { assertEquals(1L, state.compactedBelow[me], "and a peer's seq never advances my floor") },
            {
                assertEquals(
                    setOf(theirOps[1].id, theirOps[2].id),
                    explicit,
                    "the peer's dots are dropped explicitly instead",
                )
            },
            {
                assertEquals(
                    listOf("r1", "r2", "p0"),
                    state.toList(),
                    "my own retained seqs 2 and 3 must survive a peer's seq-3 drop",
                )
            },
        )
    }

    @Test
    fun dropWindowReturnsNullWhenNothingIsDropped() {
        val (rga, _) = chain(3)
        assertNull(rga.dropWindow(me, emptySet()))
    }

    /**
     * The residue filter's `it in insertsById` arm. Task 9 windows by id set, and an id an
     * earlier pass already took explicitly is gone from `insertsById` while still failing the
     * floor test (a peer's dot is never floored), so it reaches [Rga.positionsFor] — which is
     * `getValue` and throws. Re-dropping must be a no-op, not a crash.
     */
    @Test
    fun dropWindowToleratesAnIdAnEarlierDropAlreadyTook() {
        val (s1, myFirst) = Rga.empty<String>().insertAfter(me, RgaId.HEAD, "mine0")
        val (s2, mySecond) = s1.insertAfter(me, myFirst.id, "mine1")
        val (s3, theirFirst) = s2.insertAfter(peer, mySecond.id, "theirs0")
        val (merged, _) = s3.insertAfter(peer, theirFirst.id, "theirs1")

        val (once, _) = merged.dropWindow(me, setOf(theirFirst.id))!!
        val (twice, _) = once.dropWindow(me, setOf(theirFirst.id, myFirst.id))!!

        assertAll(
            { assertEquals(listOf("mine0", "mine1", "theirs1"), once.toList()) },
            { assertEquals(1L, twice.compactedBelow[me], "the own dot still folds into the floor") },
            { assertEquals(0L, twice.compactedBelow[peer], "and the peer's floor is still untouched") },
            { assertEquals(listOf("mine1", "theirs1"), twice.toList(), "the re-passed id is simply skipped") },
        )
    }

    /**
     * A **genuine** second replica — built through the receive path ([Rga.apply]) rather than by
     * aliasing the local state — absorbs a floor-only delta. This covers `deltaOf`'s `emptySet()`
     * arm; the residue arm is covered by the sibling below.
     */
    @Test
    fun theDeltaFromDropWindowCarriesTheSameDropToAPeer() {
        val (rga, ops) = chain(5)
        val remote = ops.fold(Rga.empty<String>()) { acc, op -> acc.apply(op) }
        assertEquals(rga.toList(), remote.toList(), "the two replicas start converged")

        val (state, patch) = rga.dropWindow(me, ops.take(3).map { it.id }.toSet())!!

        assertAll(
            { assertEquals(listOf("r3", "r4"), remote.piece(patch).toList(), "a peer merging the delta drops too") },
            { assertEquals(state, remote.piece(patch), "and lands on exactly the local state") },
        )
    }

    /**
     * The delta's **residue** arm — `deltaOf`'s `setOf(compactOp)`.
     *
     * Every other delta assertion here uses a fully contiguous own drop, where `compactOp` is
     * `null`, so dropping that arm entirely leaves them all green while a peer silently never
     * learns of any foreign-author drop and keeps the record forever. That is this issue's
     * unbounded-log disease relocated to the peer, so the drop has to be **mixed**: an own
     * contiguous prefix that folds into the floor *plus* a foreign dot that cannot, and the
     * peer must lose both.
     */
    @Test
    fun theDeltaCarriesTheResidueToAPeerAsWellAsTheFloor() {
        val (s1, m1) = Rga.empty<String>().insertAfter(me, RgaId.HEAD, "m1")
        val (s2, m2) = s1.insertAfter(me, m1.id, "m2")
        val (s3, p1) = s2.insertAfter(peer, m2.id, "p1")
        val (s4, m3) = s3.insertAfter(me, p1.id, "m3")
        val (local, m4) = s4.insertAfter(me, m3.id, "m4")
        val remote = listOf(m1, m2, p1, m3, m4).fold(Rga.empty<String>()) { acc, op -> acc.apply(op) }
        assertEquals(local.toList(), remote.toList(), "the two replicas start converged")

        // My seqs 1..2 fold into the floor; the peer's dot cannot, so it must ride the delta
        // as an explicit Compact. My seq 3 is retained, which is what stops the floor at 2.
        val (state, patch) = local.dropWindow(me, setOf(m1.id, m2.id, p1.id))!!

        assertAll(
            { assertEquals(VersionVector.of(mapOf(me to 2L)), patch.delta.compactedBelow, "the delta's floor arm") },
            {
                assertEquals(
                    setOf(p1.id),
                    patch.delta.ops.filterIsInstance<RgaOp.Compact>().flatMap { it.positions.keys }.toSet(),
                    "and its residue arm — without this the peer never hears about p1",
                )
            },
            { assertEquals(listOf("m3", "m4"), state.toList(), "locally all three go") },
            { assertEquals(listOf("m3", "m4"), remote.piece(patch).toList(), "and all three go on the peer too") },
            { assertEquals(state, remote.piece(patch), "the peer lands on exactly the local state") },
        )
    }

    /**
     * The bound, measured rather than argued. If `ops.size` tracks the record count instead of
     * the window, `dropWindow` is minting explicit `Compact` entries it should have folded into
     * the floor — the Θ(elements ever) cost #2127 exists to remove.
     */
    @Test
    fun repeatedWindowingKeepsTheOpLogFlatRatherThanGrowingWithEveryDrop() {
        var rga = Rga.empty<String>()
        var tail = RgaId.HEAD
        val window = ArrayDeque<RgaId>()
        repeat(200) { i ->
            val (next, op) = rga.insertAfter(me, tail, "r$i")
            rga = next
            tail = op.id
            window.addLast(op.id)
            if (window.size > 5) {
                val drop = buildSet { repeat(window.size - 5) { add(window.removeFirst()) } }
                rga = rga.dropWindow(me, drop)!!.first
            }
        }
        assertAll(
            { assertEquals(5, rga.size, "the window holds") },
            { assertEquals(5, rga.ops.size, "and the op-log holds too — no per-drop residue accumulates") },
            { assertEquals(VersionVector.of(mapOf(me to 195L)), rga.compactedBelow) },
        )
    }

    /**
     * The wedge. An own dot dropped *explicitly* on an earlier pass is absent from
     * `insertsById`, so it never reappears in a later `dropped` set. A contiguity walk that
     * consults only the current drop set therefore freezes the floor below that dot forever.
     *
     * Not adversarial: `WarpLogRecordExporterSegmentTest`'s legacy-blob fixture carries a
     * `Compact` of the replica's **own** dot, so the upgrade population — long-lived devices,
     * precisely the ones that need the bound — is exactly the wedged population.
     */
    @Test
    fun anOwnDotAlreadyCompactedExplicitlyDoesNotWedgeTheFloorForever() {
        val (rga, ops) = chain(6)
        // Simulate an inherited/merged explicit Compact of our OWN seq 1 — the legacy-blob shape.
        val inherited = rga.apply(RgaOp.Compact(rga.positionsFor(setOf(ops[0].id))))

        // Now window away seqs 2..4. Seq 1 is gone already and will never be in `dropped` again.
        val (state, _) = inherited.dropWindow(me, ops.subList(1, 4).map { it.id }.toSet())!!

        assertAll(
            {
                assertEquals(
                    VersionVector.of(mapOf(me to 4L)),
                    state.compactedBelow,
                    "the walk must step OVER the already-compacted seq 1, not stop below it",
                )
            },
            { assertEquals(listOf("r4", "r5"), state.toList()) },
        )
    }

    /** The wedge's cost, measured: a single inherited `Compact` must not un-bound the log. */
    @Test
    fun anInheritedCompactDoesNotMakeEveryLaterDropExplicitResidue() {
        var rga = Rga.empty<String>()
        var tail = RgaId.HEAD
        val ids = mutableListOf<RgaId>()
        repeat(3) { i ->
            val (next, op) = rga.insertAfter(me, tail, "seed$i")
            rga = next
            tail = op.id
            ids += op.id
        }
        rga = rga.apply(RgaOp.Compact(rga.positionsFor(setOf(ids[0])))) // the inherited Compact

        val window = ArrayDeque(ids.drop(1))
        repeat(200) { i ->
            val (next, op) = rga.insertAfter(me, tail, "r$i")
            rga = next
            tail = op.id
            window.addLast(op.id)
            if (window.size > 5) {
                val drop = buildSet { repeat(window.size - 5) { add(window.removeFirst()) } }
                rga = rga.dropWindow(me, drop)!!.first
            }
        }
        // 5 live inserts + the one inherited Compact. NOT 200-ish singleton Compacts. Exact, not a
        // bound: the count is deterministic, and slack here would hide residue accumulating.
        assertEquals(6, rga.ops.size, "an inherited Compact must not make the bound fake")
    }

    // ── seq survival across a floor — the #639 regression class ─────────────

    /**
     * The invariant [dropWindow] must never break: a floor rises, and the per-author seq
     * high-water does **not** fall back with the ops it purged. The floor is itself the
     * evidence those seqs were minted, so both the cached path (`cacheAfterFloor`) and the
     * cacheless recompute (`computeMaxSeqByReplica`, which the wire decode reaches through
     * [Rga.fromOps]) have to fold it in. If either regresses, the next mint re-issues a seq
     * that already exists and two distinct records share a dot — #639, relocated to the floor.
     *
     * The window is drained **completely**, so no surviving [RgaOp.Insert] can hold the
     * high-water up on the recomputed side; that is what makes the recomputed assertion
     * sensitive to the floor rather than to the op-log.
     *
     * The cached arm is a **contrast**, not an independent pin, and deliberately so: a floor
     * [dropWindow] raised can never exceed the high-water this replica had already minted, so
     * `cacheAfterFloor`'s own fold is a no-op on every state reachable through this entry point.
     * Its pin is [aFloorRaisedPastTheHeldOpsStillHoldsTheSeqHighWaterUp], which reaches past the
     * held ops through [Rga.withCompactedBelow]. What the arm buys here is that the two paths are
     * asserted to agree in one place — the divergence, not either value alone, is the bug.
     */
    @Test
    fun aFlooredReplicaNeverReusesASeqItAlreadyMinted() {
        val (rga, ops) = chain(5)
        val floored = rga.dropWindow(me, ops.map { it.id }.toSet())!!.first

        // Everything is gone; the floor is what remembers. Force the cacheless path too.
        val reloaded = Rga.fromOps(floored.ops, floored.lamport, floored.compactedBelow)

        val (_, freshFromCached) = floored.insertAfter(me, RgaId.HEAD, "next")
        val (_, freshFromReloaded) = reloaded.insertAfter(me, RgaId.HEAD, "next")

        assertAll(
            { assertTrue(floored.ops.isEmpty(), "the window drained — no op survives to carry the seq") },
            { assertEquals(6L, freshFromCached.id.seq, "the cached path does not regress the high-water") },
            { assertEquals(6L, freshFromReloaded.id.seq, "and the recomputed path must agree (#639)") },
        )
    }

    /**
     * The same evidence has to survive the **wire**, which is the cacheless path in production:
     * `RgaSerializer` decodes through [Rga.fromOps], with no cache to inherit.
     *
     * Deliberately **mixed-author**. My whole prefix folds into the floor and leaves no op
     * behind, while the peer's records keep the op-set non-empty — so the decoded log holds no
     * dot of mine at all, and only the decoded floor can hold my high-water up. A same-author
     * partial drop cannot pin this: the highest retained `Insert` carries the high-water on its
     * own, and the assertion then passes whether or not the recompute consults the floor.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aFlooredReplicaSurvivesAWireRoundTripWithoutRegressingItsSeq() {
        val (mine, myOps) = chain(5)
        val (theirs, _) = chain(3, author = peer, prefix = "p")
        val floored = mine.piece(theirs).dropWindow(me, myOps.map { it.id }.toSet())!!.first
        val cbor = Cbor { alwaysUseByteString = true }
        val ser = Rga.wireSerializer(serializer<String>())

        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, floored))
        val (_, fresh) = decoded.insertAfter(me, RgaId.HEAD, "next")

        assertAll(
            { assertTrue(decoded.ops.isNotEmpty(), "the peer's records keep the decoded op-log non-empty") },
            {
                assertTrue(
                    decoded.ops.none { it is RgaOp.Insert && it.id.replicaId == me },
                    "yet no dot of mine survives it",
                )
            },
            {
                assertTrue(
                    decoded.ops.none { it is RgaOp.Compact && it.positions.keys.any { k -> k.replicaId == me } },
                    "nor does a surviving Compact carry my dots — otherwise it, not the floor, would hold my high-water up",
                )
            },
            { assertEquals(6L, fresh.id.seq, "so the decoded floor is the only thing holding my high-water up") },
        )
    }

    // ── Quilted.causalFloor() — the capability that exports the floor ────────

    @Test
    fun theDefaultCausalFloorIsEmptySoNonOpLogCrdtsAreUnaffected() {
        assertEquals(VersionVector.EMPTY, GSet.of("x").causalFloor())
    }

    /**
     * In this construction — one `dropWindow` call, no prior `Compact` — the dots the floor
     * swallowed are **gone** from [Rga.causalDots] and [Rga.causalFloor] is the only thing
     * that still says they were delivered. A consumer folding a delivered frontier reads both
     * halves as a union — `causalDots() ∪ {dots at-or-below causalFloor()}` — which is what
     * PR 3 teaches `Quilter` to do. That union is the contract; it is not always a partition —
     * see `anOwnDotAlreadyCompactedExplicitlyDoesNotWedgeTheFloorForever`, where a later
     * `dropWindow` raises the floor past a dot a still-retained `Compact` already re-emits.
     *
     * The third arm binds through the [Quilted] supertype on purpose, and is **not** a
     * restatement of the first. Had this shipped as the `Quilted<S>.causalFloor()`
     * **extension** it was first specced as — plus a more specific `Rga<V>` one — the first
     * arm resolves to the `Rga` extension and passes, and
     * [theDefaultCausalFloorIsEmptySoNonOpLogCrdtsAreUnaffected] resolves to the generic one
     * and passes too. Both are green while a `Quilter` holding a `Quilted<S>` has nothing to
     * call at all. This arm is the only thing that fails in that world, so the receiver the
     * one consumer that matters actually uses is pinned rather than assumed.
     */
    @Test
    fun rgaReportsItsFloorAndStopsReEmittingTheDotsBeneathIt() {
        val (rga, ops) = chain(5)
        val floored = rga.dropWindow(me, ops.take(3).map { it.id }.toSet())!!.first
        val asQuilted: Quilted<Rga<String>> = floored

        assertAll(
            { assertEquals(VersionVector.of(mapOf(me to 3L)), floored.causalFloor()) },
            {
                assertEquals(
                    setOf(Dot(me, 4L), Dot(me, 5L)),
                    floored.causalDots(),
                    "the floor carries the dropped dots now — re-emitting would make the capability pointless",
                )
            },
            {
                assertEquals(
                    VersionVector.of(mapOf(me to 3L)),
                    asQuilted.causalFloor(),
                    "and it must dispatch through Quilted — that is the only receiver a Quilter has",
                )
            },
        )
    }

    /**
     * Every existing floor assertion above runs on a single-author [chain], where `dropWindow`
     * folds the whole drop into the floor and returns `compactOp == null`. That never builds the
     * state the "delivered = [Rga.causalDots] **or** at-or-below [Rga.causalFloor]" contract is
     * actually written for: one holding **both** a raised floor and a retained `Compact`, which is
     * what a drop spanning two authors produces.
     *
     * The two halves are asserted jointly and in both directions — their union is exactly the set
     * delivered before the drop (nothing lost, nothing over-claimed). For *this* single call they
     * also do not overlap, so the floored dots genuinely left [Rga.causalDots] rather than being
     * reported twice — but that is a property of this one call, not a general guarantee: a later
     * `dropWindow` can raise the floor past a dot a still-retained `Compact` already recorded, and
     * the two halves overlap then (see `anOwnDotAlreadyCompactedExplicitlyDoesNotWedgeTheFloorForever`).
     * The union is what every consumer actually relies on; the disjointness here is incidental.
     */
    @Test
    fun aFloorAndARetainedCompactTogetherCoverEveryDeliveredDot() {
        val (mine, myOps) = chain(3)
        val (theirs, theirOps) = chain(3, author = peer, prefix = "p")
        val merged = mine.piece(theirs)
        val deliveredBefore = merged.causalDots()

        // My seqs 1-2 are a contiguous own prefix and fold into the floor; the peer's seq 1
        // can never be floored locally, so it is recorded as an explicit Compact instead.
        val (state, _) = merged.dropWindow(me, setOf(myOps[0].id, myOps[1].id, theirOps[0].id))!!

        val floor = state.causalFloor()
        val beneathFloor = floor.entries.flatMapTo(mutableSetOf()) { (author, high) ->
            (1L..high).map { Dot(author, it) }
        }
        assertAll(
            { assertTrue(state.ops.any { it is RgaOp.Compact }, "the state holds a retained Compact") },
            { assertEquals(VersionVector.of(mapOf(me to 2L)), floor, "and a non-empty floor — both at once") },
            {
                assertEquals(
                    deliveredBefore,
                    state.causalDots() + beneathFloor,
                    "read together the halves cover exactly what was delivered — no dot lost, none invented",
                )
            },
            {
                assertEquals(
                    emptySet<Dot>(),
                    state.causalDots() intersect beneathFloor,
                    "and they do not overlap — the floored dots really did leave causalDots",
                )
            },
        )
    }

    // ── Composite aggregation — a floor beneath a composite must reach its surface ───

    private fun leaf(text: String): JsonNode =
        JsonNode.Leaf(MVRegister.empty<JsonValue>().set(me, JsonValue.Str(text)))

    /**
     * A [JsonNode.Array] authored by [author] whose first element has been folded into the floor,
     * leaving [survivor] as its one live element and `{author: 1}` as its floor.
     */
    private fun flooredArray(author: ReplicaId, survivor: JsonNode): JsonNode.Array {
        val (one, first) = Rga.empty<JsonNode>().insertAfter(author, RgaId.HEAD, leaf("dropped"))
        val (two, _) = one.insertAfter(author, first.id, survivor)
        return JsonNode.Array(two.dropWindow(author, setOf(first.id))!!.first)
    }

    /**
     * [LatticeProduct] unions its components' [Quilted.causalDots]; its floor has to rise to both
     * the same way, or a product wrapping a floored [Rga] reports a frontier missing exactly the
     * dots the floor swallowed. The two components are floored on **different** authors so that
     * taking either one alone — not just dropping the override — fails.
     */
    @Test
    fun aLatticeProductRaisesItsFloorToBothComponents() {
        val (mineRaw, myOps) = chain(3)
        val (theirsRaw, theirOps) = chain(2, author = peer, prefix = "p")
        val mineFloored = mineRaw.dropWindow(me, setOf(myOps[0].id, myOps[1].id))!!.first
        val theirsFloored = theirsRaw.dropWindow(peer, setOf(theirOps[0].id))!!.first

        val product = LatticeProduct.of(mineFloored, theirsFloored)

        assertEquals(
            VersionVector.of(mapOf(me to 2L, peer to 1L)),
            product.causalFloor(),
            "a product's floor is the elementwise max of both components' — either alone under-reports",
        )
    }

    /** The nested array is floored on a different author from the outer one, so neither half alone passes. */
    @Test
    fun aJsonArrayRaisesItsFloorToTheArraysNestedInsideIt() {
        val outer = flooredArray(me, flooredArray(peer, leaf("kept")))

        assertEquals(
            VersionVector.of(mapOf(me to 1L, peer to 1L)),
            outer.causalFloor(),
            "an array's floor is its own RGA's raised by every element's — not one or the other",
        )
    }

    @Test
    fun aJsonObjectRaisesItsFloorToTheArraysBeneathIt() {
        val map = ORMap.empty<String, JsonNode>()
            .piece { it.put(me, "mine", flooredArray(me, leaf("x"))) }
            .piece { it.put(me, "theirs", flooredArray(peer, leaf("y"))) }

        assertEquals(
            VersionVector.of(mapOf(me to 1L, peer to 1L)),
            JsonNode.Object(map).causalFloor(),
            "every value's floor reaches the object's surface, not just one of them",
        )
    }

    @Test
    fun aJsonDocumentRaisesItsFloorToTheArraysBeneathIt() {
        val doc = JsonCrdt.empty(me)
            .set("mine", flooredArray(me, leaf("x")))
            .set("theirs", flooredArray(peer, leaf("y")))

        assertEquals(
            VersionVector.of(mapOf(me to 1L, peer to 1L)),
            doc.causalFloor(),
            "the document root aggregates too — it is the receiver a Quilter over a JsonCrdt holds",
        )
    }
}
