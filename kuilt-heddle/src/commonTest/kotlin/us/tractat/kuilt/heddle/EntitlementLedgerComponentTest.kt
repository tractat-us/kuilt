package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One lattice component of [EntitlementLedger], named exactly as the constructor parameter is,
 * paired with a ledger in which **only** that component is set to a non-default value.
 *
 * See [LEDGER_COMPONENTS] for why this exists.
 */
internal class LedgerComponent(val name: String, val ledger: EntitlementLedger)

/**
 * **The** enumeration of [EntitlementLedger]'s lattice components — the one place the set is
 * written down, and the input to every property in [EntitlementLedgerComponentTest].
 *
 * A component missing from `equals` is not a cosmetic defect. `Quilter` gates every write path on
 * ledger equality: `MutableStateFlow.update` stores nothing when `old == new`, and anti-entropy
 * both heals (`merged != current`) and pushes back (`msg.state != current`) on the same test. So a
 * delta carrying **only** an unobserved component compares equal to the state it would have
 * changed and is discarded on the author *and* on every receiver, with no path back. That is what
 * happened to `transferRelocIn`/`transferRelocOut` when they were added (#2366): the fix that
 * introduced them no-opped in its own headline case, because `relocationPatch` returns a patch of
 * exactly those two components whenever the strand's counters have already closed at zero.
 *
 * Adding a constructor parameter without adding its entry here is caught rather than trusted:
 * `EntitlementLedgerComponentCoverageTest` (jvmTest) derives the declared component set by
 * reflection and asserts it equals the names below.
 *
 * Every value is distinct, so [componentsAreDistinguishedFromEachOther] can tell a component
 * compared against the *wrong* sibling from one compared correctly.
 */
internal val LEDGER_COMPONENTS: List<LedgerComponent> = run {
    val e = AttachmentId("component-edge")
    val r = ReplicaId("component-replica")
    val path = PathKey.of(e)
    fun counter(n: Long) = mapOf(e to GCounter.of(r to n))
    fun rows(n: Long) = mapOf(path to mapOf(r to GCounter.of(ReplicaId("component-recipient") to n)))

    listOf(
        LedgerComponent(
            "records",
            EntitlementLedger.of(
                records = mapOf(
                    e to setOf(AttachmentRecord(e, GroupId("p"), GroupId("c"), Weight.of(1L, 1L))),
                ),
            ),
        ),
        LedgerComponent(
            "minted",
            EntitlementLedger.of(minted = mapOf(MintId("component-mint") to MintRecord(GroupId("root"), r, 1L))),
        ),
        LedgerComponent("issued", EntitlementLedger.of(issued = counter(2L))),
        LedgerComponent("returned", EntitlementLedger.of(returned = counter(3L))),
        LedgerComponent("leafSpent", EntitlementLedger.of(leafSpent = counter(4L))),
        LedgerComponent("rollupSpent", EntitlementLedger.of(rollupSpent = counter(5L))),
        LedgerComponent("transfers", EntitlementLedger.of(transfers = rows(6L))),
        LedgerComponent("lifecycle", EntitlementLedger.of(lifecycle = mapOf(e to Lifecycle.RETIRED))),
        LedgerComponent("issuedRelocIn", EntitlementLedger.of(issuedRelocIn = counter(7L))),
        LedgerComponent("leafRelocIn", EntitlementLedger.of(leafRelocIn = counter(8L))),
        LedgerComponent("leafRelocOut", EntitlementLedger.of(leafRelocOut = counter(9L))),
        LedgerComponent("rollupRelocIn", EntitlementLedger.of(rollupRelocIn = counter(10L))),
        LedgerComponent("rollupRelocOut", EntitlementLedger.of(rollupRelocOut = counter(11L))),
        LedgerComponent("gauges", EntitlementLedger.of(gauges = mapOf(e to Gauge(Rational.of(1L, 2L), 12L)))),
        LedgerComponent("transferRelocIn", EntitlementLedger.of(transferRelocIn = rows(13L))),
        LedgerComponent("transferRelocOut", EntitlementLedger.of(transferRelocOut = rows(14L))),
    )
}

/**
 * Every lattice component of [EntitlementLedger] must be **observable through `equals`**, because
 * observability through `equals` is what decides whether a patch touching it survives replication
 * at all (see [LEDGER_COMPONENTS] for the mechanism, and #2366 for the instance).
 *
 * Written as a walk rather than as one assertion per component on purpose: the failure mode here
 * has recurred, and it recurs *at the moment a component is added*, which is exactly when nobody
 * is looking at a hand-written `equals`. A walk over a single declared set turns "someone
 * remembered" into "the suite already covers it".
 */
class EntitlementLedgerComponentTest {

    /**
     * The headline property: a ledger differing from [EntitlementLedger.ZERO] in **one** component
     * must not compare equal to it. A component omitted from `equals` reds here, named.
     */
    @Test
    fun everyComponentIsObservableThroughEquality() {
        for (c in LEDGER_COMPONENTS) {
            assertNotEquals(
                EntitlementLedger.ZERO,
                c.ledger,
                "`${c.name}` is not compared by EntitlementLedger.equals — a patch carrying only " +
                    "this component compares equal to the state it would change, so Quilter " +
                    "discards it on the author and on every receiver (#2366)",
            )
            assertNotEquals(
                c.ledger,
                EntitlementLedger.ZERO,
                "`${c.name}`: equality must be symmetric about ZERO too",
            )
        }
    }

    /**
     * The same property for `hashCode`, which `equals` is contractually paired with — a hash that
     * ignores a component puts every value of it in one bucket, and any `Set`/`Map` of ledgers
     * degrades accordingly. Distinct values are chosen in [LEDGER_COMPONENTS], so this is a
     * deterministic assertion rather than a probabilistic one.
     */
    @Test
    fun everyComponentIsObservableThroughHashCode() {
        for (c in LEDGER_COMPONENTS) {
            assertNotEquals(
                EntitlementLedger.ZERO.hashCode(),
                c.ledger.hashCode(),
                "`${c.name}` does not contribute to EntitlementLedger.hashCode",
            )
        }
    }

    /**
     * `toString` is the diagnostic surface a failing assertion prints, so a component missing from
     * it makes exactly the bug above *invisible in the failure message* — two ledgers reported as
     * identical text while asserted unequal. Cheap to hold, and it reds on the same omission.
     */
    @Test
    fun everyComponentIsNamedByToString() {
        val rendered = LEDGER_COMPONENTS.first().ledger.toString()
        for (c in LEDGER_COMPONENTS) {
            assertTrue(
                rendered.contains("${c.name}="),
                "`${c.name}` is missing from EntitlementLedger.toString: $rendered",
            )
        }
    }

    /**
     * Non-vacuity for the walk, and the arm that catches a *mis-wired* comparison rather than an
     * omitted one: each single-component ledger must differ from every **other** single-component
     * ledger. A `leafRelocIn == other.leafRelocOut` typo leaves both ledgers `!= ZERO` — the
     * property above stays green — but collapses the pair here.
     */
    @Test
    fun componentsAreDistinguishedFromEachOther() {
        for (a in LEDGER_COMPONENTS) {
            for (b in LEDGER_COMPONENTS) {
                if (a.name == b.name) continue
                assertNotEquals(
                    a.ledger,
                    b.ledger,
                    "`${a.name}` and `${b.name}` are indistinguishable — one is compared against " +
                        "the other's field, or neither is compared at all",
                )
            }
        }
    }

    /**
     * The rig assertion for the whole file: every entry really does set exactly one component, so
     * "it differs from ZERO" is attributable to the named component and not to a fixture that
     * happened to set two. Verified through `piece`: merging a single-component ledger into ZERO
     * must reproduce it, and merging any two distinct ones must differ from both.
     */
    @Test
    fun eachEntrySetsExactlyOneComponent() {
        for (c in LEDGER_COMPONENTS) {
            assertTrue(
                EntitlementLedger.ZERO.piece(c.ledger) == c.ledger,
                "rig: `${c.name}`'s ledger is not reproduced by merging it into ZERO",
            )
        }
    }
}
