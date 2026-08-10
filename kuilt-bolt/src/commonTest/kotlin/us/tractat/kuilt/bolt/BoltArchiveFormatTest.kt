package us.tractat.kuilt.bolt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.FugueOp
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The format binding — what a segment says it holds, and that the module is genuinely CRDT-agnostic.
 *
 * The `Fugue` half is not redundant with the `Rga` conformance suite. `FugueOpSerializer` is
 * `internal` to `:kuilt-crdt`, so `OpLogCrdt.opSerializer` is the **only** way to canonically encode
 * a `FugueOp` at all — a bolt that reached for a compiler-generated serializer instead would not
 * merely write different bytes here, it would fail outright under CBOR. Exercising the `Fugue` path
 * is what proves the archive is bound to the canonical route rather than to `Rga` specifically.
 */
class BoltArchiveFormatTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }

    /**
     * The segment header's self-description is the **canonical** descriptor's `serialName`. Pinned
     * against the literal rather than against `opSerializer.descriptor.serialName`, which would
     * compare the value to itself: swapping in a compiler-generated serializer would move both sides
     * together and the assertion would survive the very substitution it exists to catch.
     */
    @Test
    fun aSegmentDescribesItselfWithTheCanonicalSerialNames() {
        val rga = BoltArchiveFormat.rga(serializer<String>())
        val fugue = BoltArchiveFormat.fugue(serializer<Int>())

        assertAll(
            { assertEquals("us.tractat.kuilt.crdt.RgaOp", rga.opFormat, "the canonical Rga op descriptor") },
            { assertEquals("kotlin.String", rga.elementType, "and its element type") },
            { assertEquals("us.tractat.kuilt.crdt.FugueOp", fugue.opFormat, "the canonical Fugue op descriptor") },
            { assertEquals("kotlin.Int", fugue.elementType, "and its element type") },
        )
    }

    @Test
    fun aBoltOverFugueOpsRoundTripsAndStillDiscardsCompactionRecords() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt: Bolt<FugueOp<String>> = InMemoryBolt(BoltArchiveFormat.fugue(serializer<String>()), clock)
        val (f1, first) = Fugue.empty<String>().insertAt(alice, 0, "kept")
        val (f2, second) = f1.insertAt(bob, 1, "suppressed")
        val (f3, removal) = assertNotNull(f2.removeAt(1), "removeAt(1) must find the trailing element")
        val cut = VersionVector.of(mapOf(alice to first.id.seq, bob to second.id.seq))
        val (compacted, compactOp) = assertNotNull(
            f3.compact(stableCut = cut, frontierMax = cut, delivered = cut),
            "the tombstoned, causally-stable, unanchored TRAILING insert must be GC-eligible",
        )

        val content = bolt.append(listOf(first, second, removal))
        val compaction = bolt.append(listOf(compactOp))
        val archived = bolt.replay(ReplayScope.All).toList().flatMap { it.ops }

        assertAll(
            { assertIs<AppendResult.Written>(content, "content ops are archived") },
            { assertIs<AppendResult.Skipped>(compaction, "a Fugue compaction record is discarded too") },
            { assertEquals(listOf(first, second, removal), archived, "every content op round-trips, in order") },
            {
                assertTrue(
                    compacted.operations().none { it == second || it == removal },
                    "while the live Fugue really did forget them",
                )
            },
        )
    }

    /**
     * The insert-only dot rule, asserted against the interface rather than against `Rga` internals:
     * a `Remove` contributes nothing to the frame's dot set even though it carries an id.
     */
    @Test
    fun onlyInsertsContributeDotsToAFrame() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val bolt = InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock)
        val (r1, insert) = Rga.empty<String>().insertAt(alice, 0, "only")
        val (_, removal) = assertNotNull(r1.removeAt(0), "removeAt(0) must find the element")

        val mixed = assertIs<AppendResult.Written>(bolt.append(listOf(insert, removal)))

        assertAll(
            { assertEquals(setOf(insert.id.dot), mixed.insertDots, "the insert's dot, and only it") },
            {
                assertTrue(
                    removal.id == insert.id,
                    "the remove reuses the insert's id — which is exactly why it cannot mint a dot of its own",
                )
            },
            { assertEquals(2, mixed.opCount, "both ops are archived; only their dot contribution differs") },
        )
    }
}
