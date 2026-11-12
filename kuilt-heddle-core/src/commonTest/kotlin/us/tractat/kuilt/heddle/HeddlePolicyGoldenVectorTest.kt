package us.tractat.kuilt.heddle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform determinism pin (design §7.3 "bit-identical decisions across
 * JVM/Native/wasmJs", following the SRA wire byte-parity precedent). A fixed seed
 * drives a randomized sequence of demand toggles, spends, and returns; the policy's
 * chosen picks are recorded as a canonical `id:amount` string list and asserted
 * byte-for-byte against a checked-in golden vector.
 *
 * Both the input generation ([canonicalPicks], via the platform-independent
 * `kotlin.random.Random` XorWow generator) and the policy's exact-rational selection
 * are deterministic, so **every** target — JVM, Android, iOS, macOS, wasmJs — must
 * reproduce [GOLDEN] exactly. Any accidental floating-point or platform-dependent
 * arithmetic in the selection path would diverge here. That is the whole point of
 * running this in `commonTest` rather than `jvmTest`.
 */
class HeddlePolicyGoldenVectorTest {

    @Test
    fun canonicalSequenceMatchesGoldenVectorOnEveryTarget() {
        assertEquals(GOLDEN, canonicalPicks())
    }

    private companion object {
        /**
         * Generated once from seed [SEED] (see the scratch generator in the PR history)
         * and pinned here. Regenerate only on a deliberate policy change, and expect the
         * whole vector to move.
         */
        val GOLDEN: List<String> = listOf(
            "c:7",
            "d:7",
            "d:7",
            "a:7",
            "c:7",
            "a:7",
            "b:7",
            "c:7",
            "b:7",
            "b:7",
            "b:7",
            "none",
            "d:7",
            "c:7",
            "d:7",
            "c:7",
            "c:7",
            "d:7",
            "d:7",
            "c:7",
            "a:7",
            "a:7",
            "d:7",
            "c:7",
            "c:7",
            "d:7",
            "c:7",
            "d:7",
            "c:7",
            "c:7",
            "d:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "c:7",
            "d:7",
            "c:7",
            "d:7",
            "a:7",
            "a:7",
            "c:7",
            "a:7",
            "b:7",
            "c:7",
            "d:7",
            "c:7",
            "c:7",
            "none",
            "a:7",
            "d:7",
            "b:7",
            "b:7",
            "a:7",
            "c:7",
            "c:7",
            "b:7",
            "d:7",
            "b:7",
            "b:7",
            "c:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "c:7",
            "a:7",
            "a:7",
            "d:7",
            "d:7",
            "d:7",
            "b:7",
            "d:7",
            "a:7",
            "a:7",
            "d:7",
            "a:7",
            "a:7",
            "c:7",
            "c:7",
            "c:7",
            "b:7",
            "d:7",
            "b:7",
            "d:7",
            "d:7",
            "b:7",
            "d:7",
            "b:7",
            "c:7",
            "c:7",
            "c:7",
            "c:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "a:7",
            "d:7",
            "a:7",
            "b:7",
            "b:7",
            "a:7",
            "d:7",
            "b:7",
            "d:7",
            "none",
            "b:7",
            "b:7",
            "b:7",
            "b:7",
            "none",
            "none",
            "none",
            "none",
            "d:7",
            "d:7",
            "none",
        )
    }
}

private const val SEED: Long = 0x4845_44_4C45 // "HEDLE"

/**
 * The canonical, fully-deterministic scheduling scenario. Kept top-level (not in the
 * test class) so the scratch generator can call it too. Four children with fixed
 * weights and virtual-time seats; each round perturbs demand/spends/returns from the
 * seeded RNG, picks, and applies the grant. Returns the ordered `id:amount` (or `none`)
 * decisions.
 */
internal fun canonicalPicks(): List<String> {
    val rng = Random(SEED)

    class C(
        val id: String,
        val weight: Weight,
        val ivt: Long,
        var issued: Long = 0L,
        var returned: Long = 0L,
        var spent: Long = 0L,
        var demanding: Boolean = true,
    ) {
        val outstanding get() = issued - returned - spent
    }

    val children = List(4) { i ->
        C(
            id = ('a' + i).toString(),
            weight = Weight.of((rng.nextInt(4) + 1).toLong()),
            ivt = rng.nextInt(4).toLong(),
        )
    }
    val config = PolicyConfig(quantum = 7L, perChildOutstandingCap = 40L)
    val out = ArrayList<String>()

    repeat(120) {
        // Perturb each child deterministically (fixed iteration order).
        for (c in children) {
            if (rng.nextInt(6) == 0) c.demanding = !c.demanding
            // Spend up to what's outstanding, keeping the child hungry.
            if (c.outstanding > 0L) c.spent += rng.nextInt((c.outstanding + 1L).toInt().coerceAtLeast(1)).toLong()
            // Occasionally return a small amount of committed-but-unspent entitlement.
            val committedUnspent = c.issued - c.returned - c.spent
            if (committedUnspent > 0L && rng.nextInt(8) == 0) {
                c.returned += rng.nextInt((committedUnspent + 1L).toInt().coerceAtLeast(1)).toLong()
            }
        }

        val edges = children.map { c ->
            PolicyEdge(
                record = AttachmentRecord(AttachmentId(c.id), GroupId("root"), GroupId(c.id), c.weight),
                summary = EdgeSummary(AttachmentId(c.id), c.issued, c.returned, c.spent),
                demand = if (c.demanding) Demand(targetOutstanding = 25L, maximumUsefulGrant = 12L) else Demand.NONE,
                // The seat that used to be `AttachmentRecord.initialVirtualTime = ivt` (issue #1752).
                // `Gauge(ivt, folded = 0)` with the base issuance as the fold axis reads
                // `ivt + (issued − 0)/w − returned/w`, which is the retired field's formula term for
                // term — which is why [GOLDEN] did not move when the read path was rewired.
                gauge = Gauge(Rational.of(c.ivt), folded = 0L),
                baseIssued = c.issued,
            )
        }
        val holdings = 100L
        val grant = HeddlePolicy.pick(edges, config, holdings)
        if (grant == null) {
            out.add("none")
        } else {
            out.add("${grant.attachment.value}:${grant.amount}")
            children.first { it.id == grant.attachment.value }.issued += grant.amount
        }
    }
    return out
}
