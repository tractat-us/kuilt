package us.tractat.kuilt.raft

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression for #2839: [RaftConfig] refuses a [RaftConfig.snapshotChunkCeiling] below 1.
 *
 * The ceiling is the most raw state bytes one §7 InstallSnapshot chunk carries. On a transport that
 * publishes no payload budget the engine uses it as given, so a value below one broke a snapshot
 * transfer two different ways:
 *
 * - **`0`** — every chunk carries zero bytes, the offset never advances, and the leader re-sends the
 *   same empty non-final chunk forever. The follower never catches up and nothing throws.
 * - **Negative** — the slice's `copyOfRange` throws on the engine's actor loop.
 *
 * The engine's `maxOf(1, …)` floor never reached either case: it sits on the budgeted path only.
 *
 * Both edges are asserted from both sides — `1` must still construct — because a floor tested on one
 * side only is satisfied by a floor in the wrong place.
 */
internal class RaftConfigSnapshotChunkCeilingValidationTest {

    /** The shipped default, as a literal so a silent change to it reddens here. */
    private val shippedDefault = 16 * 1024

    @Test
    fun aCeilingBelowOneIsRefused() = assertAll(
        { assertRefused(0) },
        { assertRefused(-1) },
        { assertRefused(Int.MIN_VALUE) },
    )

    @Test
    fun oneAndTheShippedDefaultAreAdmitted() = assertAll(
        { assertEquals(1, RaftConfig(snapshotChunkCeiling = 1).snapshotChunkCeiling, "the floor itself must be usable") },
        { assertEquals(shippedDefault, RaftConfig().snapshotChunkCeiling, "the no-argument default must be admitted") },
        { assertEquals(Int.MAX_VALUE, RaftConfig(snapshotChunkCeiling = Int.MAX_VALUE).snapshotChunkCeiling) },
    )

    /**
     * The offending value appears in the failure. A consumer sees this exception with no other context;
     * a bare "requirement failed" would send them to the source.
     */
    @Test
    fun theRefusalNamesTheOffendingValue() {
        val message = assertFailsWith<IllegalArgumentException> {
            RaftConfig(snapshotChunkCeiling = -7)
        }.message.orEmpty()

        assertAll(
            { assertContains(message, "snapshotChunkCeiling", message = "names the field: $message") },
            { assertContains(message, "-7", message = "names the offending value: $message") },
        )
    }

    /** `copy` runs the same `init`; pinned so the check can never move to a factory `copy` bypasses. */
    @Test
    fun copyIsValidatedToo() {
        assertFailsWith<IllegalArgumentException> { RaftConfig().copy(snapshotChunkCeiling = 0) }
    }

    private fun assertRefused(snapshotChunkCeiling: Int) {
        assertFailsWith<IllegalArgumentException>(
            "snapshotChunkCeiling=$snapshotChunkCeiling is below 1 and must be refused at construction",
        ) {
            RaftConfig(snapshotChunkCeiling = snapshotChunkCeiling)
        }
    }
}
