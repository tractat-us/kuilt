package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.test.assertAll
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
 * leaving the census exactly as blind as before. So this test reads the same observable the census
 * reads — `DebugProbes.dumpCoroutinesInfo()` → `CoroutineInfo.context[CoroutineName]` — off a **live**
 * composite, and asserts the six kinds are separable and the ply is recoverable.
 *
 * `context[CoroutineName]` rather than the rendered `DebugProbes.dumpCoroutines` text on purpose: the
 * rendered form only carries a name when `kotlinx.coroutines.debug` is enabled (it needs a `CoroutineId`
 * in context too), so a test keyed to the text would be asserting a JVM flag as much as this change.
 * The context element is there either way, and it is what `ConcurrencyStressHarness`'s census reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositePumpCensusTest {

    @Test
    fun everyPerPlyPumpIsNamedForItsKindAndItsPly() = runTest {
        DebugProbes.install()
        try {
            val composite = CompositeLoom(
                plies = listOf(
                    FAST to InMemoryLoom() as Loom,
                    SLOW to InMemoryLoom() as Loom,
                ),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            ).host(Pattern("host"))
            runCurrent()

            val named = DebugProbes.dumpCoroutinesInfo()
                .mapNotNull { it.context[CoroutineName]?.name }
                .filter { it.startsWith("composite-") }
                .toSet()

            assertAll(
                {
                    assertEquals(
                        PLY_PUMP_KINDS.flatMap { kind -> PLIES.map { "$kind[${it.value}]" } }.toSet() +
                            "composite-desired",
                        named,
                        "the six per-ply pumps and the reconcile pump must each carry their own name",
                    )
                },
                // The property the census leans on, stated as itself rather than inferred from the set
                // above: a census keyed on the name can group by KIND and still name the INSTANCE.
                {
                    assertEquals(
                        PLY_PUMP_KINDS.size + 1,
                        named.map { it.substringBefore('[') }.toSet().size,
                        "grouping by kind must yield one group per pump kind, not one blob",
                    )
                },
                {
                    assertEquals(
                        PLIES.map { it.value }.toSet(),
                        named.filter { '[' in it }.map { it.substringAfter('[').removeSuffix("]") }.toSet(),
                        "…and the ply must still be recoverable from the individual name",
                    )
                },
            )

            composite.close(CloseReason.Normal)
        } finally {
            DebugProbes.uninstall()
        }
    }

    /**
     * The direction that catches a name built from a constant instead of the ply. Two plies of the same
     * kind must not collide — that collapse is the #1811 blind spot arriving one level down.
     */
    @Test
    fun twoPliesOfTheSameKindDoNotShareAPumpName() = runTest {
        DebugProbes.install()
        try {
            val composite = CompositeLoom(
                plies = listOf(
                    FAST to InMemoryLoom() as Loom,
                    SLOW to InMemoryLoom() as Loom,
                ),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            ).host(Pattern("host"))
            runCurrent()

            val perPly = DebugProbes.dumpCoroutinesInfo()
                .mapNotNull { it.context[CoroutineName]?.name }
                .filter { it.startsWith("composite-ply-") }

            assertAll(
                { assertEquals(PLY_PUMP_KINDS.size * PLIES.size, perPly.size, "one pump per kind per ply") },
                {
                    assertEquals(
                        perPly.size,
                        perPly.toSet().size,
                        "no two per-ply pumps may share a name — that is the blob this issue is about",
                    )
                },
                { assertTrue(perPly.all { it.endsWith("]") }, "every per-ply name must be ply-qualified") },
            )

            composite.close(CloseReason.Normal)
        } finally {
            DebugProbes.uninstall()
        }
    }

    private companion object {
        val FAST = PlyId("fast")
        val SLOW = PlyId("slow")
        val PLIES = listOf(FAST, SLOW)

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
