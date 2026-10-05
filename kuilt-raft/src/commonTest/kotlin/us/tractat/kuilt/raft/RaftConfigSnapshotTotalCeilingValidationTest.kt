package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.SnapshotReceiver
import us.tractat.kuilt.raft.internal.SnapshotReceiver.ChunkOutcome
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Regression for #2909: [RaftConfig] refuses a [RaftConfig.snapshotTotalCeiling] below 1.
 *
 * The ceiling bounds the bytes a follower accumulates reassembling one §7 snapshot. Below 1 it admits
 * no snapshot carrying any state at all — at `0` only an empty one fits, below `0` not even that — so a
 * follower that falls behind the leader's compaction floor never catches up, and the only signal is a
 * rejection metric that does not name the setting.
 *
 * The floor is `1`, not [RaftConfig.snapshotChunkCeiling]: the receiver checks only the accumulated
 * sum, so a positive total below one chunk still installs every snapshot that small.
 * [aTotalBelowOneChunkStillInstallsASnapshotThatSmall] pins that, so the floor stays a defect check
 * rather than a size policy.
 */
internal class RaftConfigSnapshotTotalCeilingValidationTest {

    /** The shipped default, as a literal so a silent change to it reddens here. */
    private val shippedDefault = 64 * 1024 * 1024

    @Test
    fun aCeilingBelowOneIsRefused() = assertAll(
        { assertRefused(0) },
        { assertRefused(-1) },
        { assertRefused(Int.MIN_VALUE) },
    )

    @Test
    fun oneAndTheShippedDefaultAreAdmitted() = assertAll(
        { assertEquals(1, RaftConfig(snapshotTotalCeiling = 1).snapshotTotalCeiling, "the floor itself must be usable") },
        { assertEquals(shippedDefault, RaftConfig().snapshotTotalCeiling, "the no-argument default must be admitted") },
        { assertEquals(Int.MAX_VALUE, RaftConfig(snapshotTotalCeiling = Int.MAX_VALUE).snapshotTotalCeiling) },
    )

    /**
     * Why the floor is not `snapshotChunkCeiling`: a total of 1 under the default 16 KiB chunk ceiling
     * still installs a one-byte snapshot, so the smaller total is a working configuration, not a defect.
     */
    @Test
    fun aTotalBelowOneChunkStillInstallsASnapshotThatSmall() {
        val config = RaftConfig(snapshotTotalCeiling = 1)
        val meta = SnapshotMeta(lastIncludedIndex = 7L, lastIncludedTerm = 3L)

        val outcome = SnapshotReceiver(config.snapshotTotalCeiling)
            .onChunk(meta, offset = 0L, data = byteArrayOf(9), done = true)

        assertAll(
            { assertEquals(true, config.snapshotTotalCeiling < config.snapshotChunkCeiling, "precondition: total below one chunk") },
            { assertIs<ChunkOutcome.Complete>(outcome, "a snapshot no larger than the total must install") },
        )
    }

    /**
     * The offending value appears in the failure. A consumer sees this exception with no other context;
     * a bare "requirement failed" would send them to the source.
     */
    @Test
    fun theRefusalNamesTheOffendingValue() {
        val message = assertFailsWith<IllegalArgumentException> {
            RaftConfig(snapshotTotalCeiling = -7)
        }.message.orEmpty()

        assertAll(
            { assertContains(message, "snapshotTotalCeiling", message = "names the field: $message") },
            { assertContains(message, "-7", message = "names the offending value: $message") },
        )
    }

    /** `copy` runs the same `init`; pinned so the check can never move to a factory `copy` bypasses. */
    @Test
    fun copyIsValidatedToo() {
        assertFailsWith<IllegalArgumentException> { RaftConfig().copy(snapshotTotalCeiling = 0) }
    }

    private fun assertRefused(snapshotTotalCeiling: Int) {
        assertFailsWith<IllegalArgumentException>(
            "snapshotTotalCeiling=$snapshotTotalCeiling is below 1 and must be refused at construction",
        ) {
            RaftConfig(snapshotTotalCeiling = snapshotTotalCeiling)
        }
    }
}
