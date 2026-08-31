package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The outcome #1811 is actually about: a **coroutine census can tell a composite's per-ply pumps apart**.
 *
 * ### Why the sibling unit test is not enough
 * `PumpInTest.theNameReachesTheLaunchedCoroutinesOwnContext` proves the mechanism — `pumpIn` puts a
 * [CoroutineName] on the launch. It says nothing about whether `CompositeSeam` then uses *distinct*
 * names, and a composite naming all six of a ply's pumps `"composite-ply"` would satisfy it while
 * leaving the census exactly as blind as before. So this reads the same observable the census reads —
 * `DebugProbes.dumpCoroutinesInfo()` → `CoroutineInfo.context[CoroutineName]` — off a **live** composite.
 *
 * `context[CoroutineName]` rather than the rendered `DebugProbes.dumpCoroutines` text on purpose: the
 * rendered form carries a name only when `kotlinx.coroutines.debug` is enabled (it needs a `CoroutineId`
 * in context too), so a test keyed to the text would be asserting a JVM flag as much as this change.
 * The context element is there either way, and it is what `ConcurrencyStressHarness`'s census reads.
 *
 * ### Assertions are on the name SET, never on instance counts — measured, not assumed
 * A pump's name appears on **more coroutines than the one launch that carries it**: a coroutine started
 * from inside a pump body inherits that body's context, name included. Measured here on a two-ply
 * composite — the two pumps collecting `seam.peers` show three tracked coroutines each while the other
 * four show one — so `12 pumps` reads back as `20 named coroutines`. That attribution is what you want in
 * a wedge hunt (work a pump spawned is parked *under that pump*, not in a fresh anonymous group), but it
 * makes any instance count a moving target, so nothing here counts instances.
 *
 * Ply ids are unique per test for the neighbouring reason: `DebugProbes` is JVM-global and a composite's
 * pumps live on a scope of their own, so a sibling test's pump whose cancellation has not yet been
 * dispatched is still in the dump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositePumpCensusTest {

    @Test
    fun everyPerPlyPumpIsNamedForItsKindAndItsPly() = runTest {
        val plies = listOf(PlyId("named-fast"), PlyId("named-slow"))
        DebugProbes.install()
        try {
            val composite = compositeOver(plies, UnconfinedTestDispatcher(testScheduler))
            runCurrent()

            val mine = pumpNamesFor(plies)

            assertAll(
                {
                    assertEquals(
                        plies.flatMap { ply -> PLY_PUMP_KINDS.map { "$it[${ply.value}]" } }.toSet(),
                        mine,
                        "each of the six per-ply pumps must carry its own kind, qualified by its ply — a " +
                            "shared name here is the blob a census cannot see into",
                    )
                },
                // The two halves the census actually leans on, stated as themselves rather than left to be
                // inferred from the set above: it groups by KIND and names the INSTANCE from the same string.
                {
                    assertEquals(
                        PLY_PUMP_KINDS.toSet(),
                        mine.map { it.substringBefore('[') }.toSet(),
                        "grouping by kind must yield one group per pump kind, not one blob",
                    )
                },
                {
                    assertEquals(
                        plies.map { it.value }.toSet(),
                        mine.map { it.substringAfter('[').removeSuffix("]") }.toSet(),
                        "…and the ply must still be recoverable from the individual name",
                    )
                },
                {
                    assertTrue(
                        allPumpNames().contains(RECONCILE),
                        "the composite-wide reconcile pump must be named too — it is not per-ply, and a " +
                            "census blind to it cannot tell a wedged reconcile from a quiet one",
                    )
                },
            )

            composite.close(CloseReason.Normal)
        } finally {
            DebugProbes.uninstall()
        }
    }

    /**
     * The second code path, which the sibling test does not reach. `CompositeSeam`'s constructor attaches
     * the initial plies directly; a ply that appears in the **desired** set later arrives through
     * `reconcile` → `attachDesiredPly` → `attachPly`. Both end at the same launches today, so this is a
     * regression fence rather than a discovery: a composite whose *dynamically* attached plies came back
     * anonymous would leave a census blind to exactly the plies that churn, which are the interesting ones.
     */
    @Test
    fun aPlyAttachedThroughTheDesiredSetIsNamedToo() = runTest {
        val initial = PlyId("dynamic-initial")
        val added = PlyId("dynamic-added")
        DebugProbes.install()
        try {
            val desired = MutableStateFlow(listOf(initial to InMemoryLoom() as Loom))
            val composite = CompositeLoom(
                plies = desired,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            ).host(Pattern("host"))
            runCurrent()
            val beforeAttach = pumpNamesFor(listOf(added))

            desired.value = desired.value + (added to InMemoryLoom() as Loom)
            runCurrent()

            assertAll(
                { assertEquals(emptySet(), beforeAttach, "precondition: the ply was not attached yet") },
                {
                    assertEquals(
                        PLY_PUMP_KINDS.map { "$it[${added.value}]" }.toSet(),
                        pumpNamesFor(listOf(added)),
                        "a ply attached through the desired set must be named exactly as an initial one is",
                    )
                },
                {
                    assertEquals(
                        PLY_PUMP_KINDS.map { "$it[${initial.value}]" }.toSet(),
                        pumpNamesFor(listOf(initial)),
                        "…and the ply that was there first keeps its own names",
                    )
                },
            )

            composite.close(CloseReason.Normal)
        } finally {
            DebugProbes.uninstall()
        }
    }

    private suspend fun compositeOver(plies: List<PlyId>, dispatcher: CoroutineContext) =
        CompositeLoom(
            plies = plies.map { it to InMemoryLoom() as Loom },
            dispatcher = dispatcher,
        ).host(Pattern("host"))

    private fun allPumpNames(): List<String> =
        DebugProbes.dumpCoroutinesInfo().mapNotNull { it.context[CoroutineName]?.name }

    /**
     * The distinct per-ply pump names belonging to [plies] only. A **set**, and scoped to these plies:
     * see the class KDoc for why neither is incidental.
     */
    private fun pumpNamesFor(plies: List<PlyId>): Set<String> {
        val suffixes = plies.map { "[${it.value}]" }
        return allPumpNames().filter { name -> suffixes.any { name.endsWith(it) } }.toSet()
    }

    private companion object {
        const val RECONCILE = "composite-desired"

        /** The six pumps `CompositeSeam.attachPly` launches per ply, by name prefix. */
        val PLY_PUMP_KINDS = listOf(
            "composite-ply-state",
            "composite-ply-capability",
            "composite-ply-announce-woven",
            "composite-ply-inbound",
            "composite-ply-peers",
            "composite-ply-announce-peers",
        )
    }
}
