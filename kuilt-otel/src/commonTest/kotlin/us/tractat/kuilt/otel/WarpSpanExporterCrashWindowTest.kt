package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Crash-window regression for #1053: the causal clock must be persisted **before** a
 * span's durable write so a crash in the two-write window can never leave a persisted
 * span at a dot the recovered clock re-mints.
 */
class WarpSpanExporterCrashWindowTest {

    private val replicaA = ReplicaId("A")
    private fun traceId(id: Byte) = ByteString(ByteArray(16) { id })
    private fun spanId(id: Byte) = ByteString(ByteArray(8) { id })
    private fun span(id: Byte) = SpanRecord(
        traceId = traceId(id),
        spanId = spanId(id),
        parentSpanId = null,
        name = "op",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L,
        endEpochNanos = 2_000L,
        causalStamp = null,
    )

    // The clock's own store key (private in WarpCausalClock); reconstructed here so the
    // fake store can crash precisely the clock write, modelling a crash in the export's
    // two-write window.
    private val clockKey = StoreKey("otel.causal.clock")

    /**
     * Persists every write except those to [failKey] while [crashed] — modelling a crash
     * that lands one of the two export writes but not the other. Flip [crashed] to false
     * to "reboot" and let recovery read whatever was durably committed.
     */
    private class CrashStore(private val failKey: StoreKey) : DurableStore {
        private val backing = InMemoryDurableStore()
        var crashed: Boolean = true
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (crashed && key == failKey) throw IllegalStateException("simulated crash writing $failKey")
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)
    }

    @Test
    fun crashInWriteWindowDoesNotRemintDotOnRecover() = runTest {
        val store = CrashStore(failKey = clockKey)

        // Export a span while the clock's durable write crashes. Whatever ends up
        // durable, the recovered clock must not re-mint a dot a durable span already used.
        val clock1 = WarpCausalClock(replicaA)
        val exp1 = WarpSpanExporter(replicaA, store, causalClock = clock1)
        exp1.export(span(1)) // may fail — we only care about what is durable afterwards.

        // Reboot: the clock write succeeds again; recovery reads the committed state.
        store.crashed = false
        val clock2 = WarpCausalClock(replicaA)
        clock2.recover(store)
        val exp2 = WarpSpanExporter(replicaA, store, causalClock = clock2)
        exp2.recover()

        val maxDurableSpanSeq = exp2.snapshot().elements
            .mapNotNull { it.causalStamp?.dot?.seq }
            .maxOrNull() ?: 0L
        val nextDot = clock2.tick().dot

        assertTrue(
            nextDot.seq > maxDurableSpanSeq,
            "re-minted dot: next tick seq=${nextDot.seq} is not strictly greater than " +
                "the max durable span dot seq=$maxDurableSpanSeq",
        )
    }

    @Test
    fun clockPersistFailureThenRetryDoesNotDoubleCopySpan() = runTest {
        // First attempt: the clock's durable write fails, so export returns Failure. A
        // caller that retries the same span must not accumulate a second stamped copy.
        val store = CrashStore(failKey = clockKey)
        val clock = WarpCausalClock(replicaA)
        val exp = WarpSpanExporter(replicaA, store, causalClock = clock)

        val first = exp.export(span(1))
        assertIs<ExportResult.Failure>(first)

        store.crashed = false // clock write recovers
        val second = exp.export(span(1)) // caller retries the same span
        assertIs<ExportResult.Success>(second)

        val copies = exp.snapshot().elements.filter { it.spanId == spanId(1) }
        assertEquals(1, copies.size, "retry produced a duplicate stamped copy: $copies")
    }
}
