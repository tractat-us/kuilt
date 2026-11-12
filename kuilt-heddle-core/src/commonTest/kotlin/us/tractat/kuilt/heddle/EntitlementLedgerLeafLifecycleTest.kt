package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A leaf is a group with no **ACTIVE** child edge (design §5.2, #3191).
 *
 * A child edge record never goes away, so a leaf predicate that only asks "does any child edge
 * exist" makes a group permanently unchargeable the moment it has ever had a child. A reload that
 * turns a group into a lane retires its children but keeps their records. A reverted add leaves a
 * PREPARED child nobody will ever activate. Either way `spend` threw and the node's `reserve`
 * answered `NoHoldings` forever, while the allocator, which descends only through ACTIVE edges,
 * treated the group as a leaf and stopped handing anything below it. Nobody could use what the
 * group held.
 *
 * The expected values are literals worked out by hand, so they cannot move with the fixture.
 */
class EntitlementLedgerLeafLifecycleTest {

    private val root = GroupId("root")
    private val g = GroupId("g")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val alice = ReplicaId("alice")

    private val f = AttachmentId("f") // root → g
    private val ca = AttachmentId("ca") // g → a
    private val cb = AttachmentId("cb") // g → b

    private fun EntitlementLedger.applying(patch: Patch<EntitlementLedger>?): EntitlementLedger =
        piece(assertNotNull(patch, "the fixture's own write must apply"))

    private fun EntitlementLedger.attached(id: AttachmentId, parent: GroupId, child: GroupId): EntitlementLedger {
        val prepared = applying(prepare(AttachmentRecord(id, parent, child, Weight.ONE)))
        return prepared.applying(prepared.activate(id))
    }

    /** 100 minted to alice at the root, `root → g` active, and [down] of it delegated to `g`. */
    private fun lane(down: Long): EntitlementLedger {
        val l = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "genesis").attached(f, root, g)
        return l.applying(l.delegate(alice, f, down))
    }

    /** `minted = Σ holdings + Σ effLeafSpent`, over alice — the only replica these fixtures write. */
    private fun conserved(l: EntitlementLedger): Long =
        listOf(root, g, a, b).sumOf { l.holdings(it, alice) } +
            l.allEdges().sumOf { e ->
                l.storedSlot(CounterFamily.LEAF_SPENT, e, alice) + l.storedSlot(CounterFamily.LEAF_RELOC_IN, e, alice) -
                    l.storedSlot(CounterFamily.LEAF_RELOC_OUT, e, alice)
            }

    @Test
    fun aGroupWhoseChildrenRetiredIsAChargeableLane() {
        var l = lane(down = 30L).attached(ca, g, a).attached(cb, g, b)
        l = l.applying(l.delegate(alice, ca, 10L))
        l = l.applying(l.delegate(alice, cb, 5L))
        l = l.applying(l.spend(alice, a, 4L))
        val interior = l
        assertFalse(interior.isLeaf(g), "precondition: g is a group while its children are active")
        assertFailsWith<IllegalArgumentException> { interior.spend(alice, g, 1L) }

        // The reload: both children close, drain back up, and retire. g is now a lane.
        l = l.applying(l.close(ca))
        l = l.applying(l.close(cb))
        l = l.applying(l.release(alice, ca, 6L))
        l = l.applying(l.release(alice, cb, 5L))
        l = l.applying(l.retire(ca))
        l = l.applying(l.retire(cb))
        val retired = l

        assertAll(
            { assertEquals(Lifecycle.RETIRED, retired.lifecycle(ca), "rig: ca retired") },
            { assertEquals(Lifecycle.RETIRED, retired.lifecycle(cb), "rig: cb retired") },
            { assertTrue(retired.activeChildren(g).isEmpty(), "rig: the allocator sees nothing below g") },
            // 30 in, minus a's 4 of spend that left through the retired ca.
            { assertEquals(26L, retired.holdings(g, alice), "g holds what its children gave back") },
            { assertTrue(retired.isLeaf(g), "a group whose children all retired is a leaf") },
        )

        val charged = retired.applying(retired.spend(alice, g, 7L))
        assertAll(
            { assertEquals(19L, charged.holdings(g, alice), "the lane was charged") },
            { assertEquals(7L, charged.edge(f)?.spent, "the charge landed on g's inbound edge") },
            { assertEquals(emptyList(), charged.validate(), "validate stays clean") },
            // root 70 + g 19 + leafSpent(ca) 4 + leafSpent(f) 7.
            { assertEquals(100L, conserved(charged), "conservation") },
        )
    }

    @Test
    fun aLaneWithAStrayPreparedChildIsStillChargeable() {
        // A reverted add: g → a is prepared, and nothing will ever activate it.
        val l = lane(down = 20L).let { it.applying(it.prepare(AttachmentRecord(ca, g, a, Weight.ONE))) }
        assertAll(
            { assertEquals(Lifecycle.PREPARED, l.lifecycle(ca), "rig: the stray child is prepared") },
            { assertTrue(l.activeChildren(g).isEmpty(), "rig: the allocator sees nothing below g") },
            { assertTrue(l.isLeaf(g), "a prepared child does not make g a group") },
        )

        val charged = l.applying(l.spend(alice, g, 5L))
        assertAll(
            { assertEquals(15L, charged.holdings(g, alice), "the lane was charged") },
            { assertEquals(emptyList(), charged.validate(), "validate stays clean") },
            // root 80 + g 15 + leafSpent(f) 5.
            { assertEquals(100L, conserved(charged), "conservation") },
        )
    }

    /**
     * Why the predicate is "no ACTIVE child" and not "no live (ACTIVE or CLOSING) child". Nothing can
     * be delegated across a CLOSING edge, so with the live predicate a group whose children are all
     * closing could neither hand its holdings down nor spend them. A crashed peer strands its
     * earmarks (§8.1), so a closing child may never drain, and the group would be stuck for good.
     *
     * Charging the group while its child drains is sound because `holdings` subtracts every child's
     * net inflow whatever its lifecycle: the group can spend only what it holds, never what is
     * still out at the child, and each unit the child gives back becomes the group's to spend.
     */
    @Test
    fun aClosingChildLeavesItsParentALaneThatSpendsOnlyWhatItHolds() {
        var l = lane(down = 30L).attached(ca, g, a)
        l = l.applying(l.delegate(alice, ca, 10L))
        l = l.applying(l.close(ca))
        val closing = l
        assertAll(
            { assertEquals(Lifecycle.CLOSING, closing.lifecycle(ca), "rig: ca is closing") },
            { assertEquals(10L, closing.edge(ca)?.outstanding, "rig: ca has not drained") },
            { assertTrue(closing.isLeaf(g), "a group whose only child is closing is a lane") },
            { assertEquals(20L, closing.holdings(g, alice), "g's holdings exclude what is still out at a") },
            { assertNull(closing.spend(alice, g, 21L), "g cannot spend what a still holds") },
        )

        l = l.applying(l.spend(alice, g, 20L))
        // The closing child still spends and releases, and what it gives back is g's to spend.
        l = l.applying(l.spend(alice, a, 3L))
        l = l.applying(l.release(alice, ca, 7L))
        val drained = l
        assertAll(
            { assertEquals(7L, drained.holdings(g, alice), "a's release came back to g") },
            { assertEquals(emptyList(), drained.validate(), "validate is clean mid-drain") },
        )
        l = l.applying(l.spend(alice, g, 7L))
        l = l.applying(l.retire(ca))
        val done = l
        assertAll(
            { assertEquals(Lifecycle.RETIRED, done.lifecycle(ca), "ca drained and retired") },
            { assertEquals(0L, done.holdings(g, alice), "g spent everything it held") },
            { assertEquals(emptyList(), done.validate(), "validate stays clean") },
            // root 70 + leafSpent(f) 27 + leafSpent(ca) 3.
            { assertEquals(100L, conserved(done), "conservation") },
        )
    }

    /** The protection `spend`'s `require` exists for: a group's holdings are the allocator's to hand down. */
    @Test
    fun anActiveChildKeepsItsParentAGroup() {
        val l = lane(down = 30L).attached(ca, g, a).let { it.applying(it.prepare(AttachmentRecord(cb, g, b, Weight.ONE))) }
        assertAll(
            { assertFalse(l.isLeaf(g), "one active child makes g a group, whatever its siblings are") },
            { assertFailsWith<IllegalArgumentException> { l.spend(alice, g, 1L) } },
        )
    }

    /**
     * Every pair of child lifecycles under `g`, including no child at all: `g`'s holdings must be
     * usable in exactly one way. Either `g` is a leaf and `spend` charges it, or `g` has an ACTIVE
     * child and the allocator hands the holdings down. Both at once would let a group spend around
     * the policy. Neither is the starvation in #3191.
     */
    @Test
    fun aGroupsHoldingsAreUsableInExactlyOneWayUnderEveryChildLifecycle() {
        val states: List<Lifecycle?> = listOf(null) + Lifecycle.entries
        val checks = ArrayList<() -> Unit>()
        var childrenButNoActiveOne = 0
        for (sa in states) for (sb in states) {
            val l = lane(down = 50L).withChild(ca, a, sa).withChild(cb, b, sb)
            val label = "ca=$sa cb=$sb"
            val leaf = l.isLeaf(g)
            val spends = leaf && l.spend(alice, g, 1L) != null
            val cell = Cell(l)
            val grants = FairShareAllocator(alice, PolicyConfig(quantum = 10L)).schedule(g, cell, earmarked = 0L) { hungry }
            if ((sa != null || sb != null) && l.activeChildren(g).isEmpty()) childrenButNoActiveOne++
            checks += { assertEquals(l.activeChildren(g).isEmpty(), leaf, "$label: isLeaf is the allocator's view") }
            checks += { assertTrue(spends != (grants > 0), "$label: spends=$spends grants=$grants, exactly one") }
            checks += { assertEquals(emptyList(), l.validate(), "$label: validate") }
            if (!leaf) checks += { assertFailsWith<IllegalArgumentException>("$label") { l.spend(alice, g, 1L) } }
        }
        // 4 x 4 pairs of none/PREPARED/CLOSING/RETIRED, minus none/none.
        assertEquals(15, childrenButNoActiveOne, "rig: the pairs that have child edges but no active one")
        assertAll(*checks.toTypedArray())
    }

    private val hungry = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

    /** `g → child` driven to [state] through the real mutators; `null` is no edge at all. */
    private fun EntitlementLedger.withChild(id: AttachmentId, child: GroupId, state: Lifecycle?): EntitlementLedger {
        if (state == null) return this
        var l = applying(prepare(AttachmentRecord(id, g, child, Weight.ONE)))
        if (state == Lifecycle.PREPARED) return l
        l = l.applying(l.activate(id))
        if (state == Lifecycle.ACTIVE) return l
        l = l.applying(l.delegate(alice, id, 5L))
        l = l.applying(l.close(id))
        if (state == Lifecycle.CLOSING) return l
        l = l.applying(l.release(alice, id, 5L))
        return l.applying(l.retire(id))
    }

    private class Cell(override var current: EntitlementLedger) : LedgerCell {
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch)
            return true
        }
    }
}
