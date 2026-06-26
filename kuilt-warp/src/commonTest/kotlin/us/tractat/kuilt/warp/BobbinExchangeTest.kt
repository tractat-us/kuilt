/**
 * C5 go/no-go: **lazy-bobbin exchange** via [BobbinExchange].
 *
 * Proves the defining property: a [BobbinHash] is gossiped eagerly to all peers via
 * a `GSet<BobbinHash>` manifest, but the kernel bytes travel only on demand — a peer
 * that lacks the bytes sends a [FetchMessage]-style request and awaits a response from
 * any neighbour that holds them. Re-hashing verifies integrity before caching.
 *
 * Coroutine discipline mirrors [SymbolicDispatchTest]: [UnconfinedTestDispatcher] with
 * virtual time driven by bounded [advanceTimeBy] steps — never [advanceUntilIdle]
 * (Quilter anti-entropy timers re-arm forever under virtual time).
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val BOBBIN_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/**
 * Bounded virtual-time advance: step through anti-entropy intervals to let the GSet
 * manifest converge across peers. Never [advanceUntilIdle] — anti-entropy re-arms forever.
 */
private fun TestScope.settle() {
    repeat(8) { advanceTimeBy(BOBBIN_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }
}

class BobbinExchangeTest {

    /**
     * Happy path: A puts bytes → manifest gossips to B → B lacks the bytes (lazy) →
     * B.fetch returns the original bytes AND the creel is populated.
     */
    @Test
    fun manifestConvergesAndFetchDeliversBytes() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("bobbin-exchange"))
            val seamB = loom.join(InMemoryTag("b"))
            val creelA = Creel()
            val creelB = Creel()

            val exchangeA = BobbinExchange(seamA, creelA, backgroundScope, BOBBIN_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, creelB, backgroundScope, BOBBIN_QUILTER_CONFIG)

            val original = byteArrayOf(10, 20, 30, 40, 50)
            val hash = exchangeA.put(original)

            settle()

            assertAll(
                { assertTrue(exchangeB.manifest.value.any { it.hash == hash }, "B's manifest must contain the hash after settling") },
                { assertFalse(creelB.contains(hash), "B must not have fetched the bytes yet (lazy)") },
            )

            val fetched = exchangeB.fetch(hash)
            runCurrent()

            assertAll(
                { assertContentEquals(original, fetched, "fetch must return the original bytes") },
                { assertTrue(creelB.contains(hash), "creel must be populated after fetch") },
            )
        }

    /**
     * Cache hit: if the creel already holds the bytes, fetch returns immediately without
     * sending any network request.
     */
    @Test
    fun fetchReturnsCachedBytesImmediately() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("bobbin-cache-hit"))
            val creel = Creel()
            val exchange = BobbinExchange(seam, creel, backgroundScope, BOBBIN_QUILTER_CONFIG)

            val bytes = byteArrayOf(1, 2, 3)
            val hash = exchange.put(bytes)

            // No settle needed — creel already holds the bytes via put()
            val fetched = exchange.fetch(hash)

            assertContentEquals(bytes, fetched, "cache hit must return byte-identical content")
        }

    /**
     * Manifest grows as more bobbins are added. All hashes are visible on the advertising
     * peer immediately (no settle needed for the local creel).
     */
    @Test
    fun manifestReflectsAllPutHashes() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("bobbin-manifest-local"))
            val creel = Creel()
            val exchange = BobbinExchange(seam, creel, backgroundScope, BOBBIN_QUILTER_CONFIG)

            val h1 = exchange.put(byteArrayOf(1))
            val h2 = exchange.put(byteArrayOf(2))
            val h3 = exchange.put(byteArrayOf(3))
            runCurrent()

            assertAll(
                { assertTrue(exchange.manifest.value.any { it.hash == h1 }) },
                { assertTrue(exchange.manifest.value.any { it.hash == h2 }) },
                { assertTrue(exchange.manifest.value.any { it.hash == h3 }) },
            )
        }

    /**
     * A variant published via [BobbinExchange.putVariant] appears in the local manifest as a
     * [BobbinMeta] carrying its [VariantKey] provenance, distinct from a raw bobbin (null variantOf).
     */
    @Test
    fun putVariantPublishesMetaWithProvenance() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("variant-meta-local"))
            val exchange = BobbinExchange(seam, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)

            val rawHash = exchange.put(byteArrayOf(1, 2, 3))
            val variantHash = exchange.putVariant(
                byteArrayOf(1, 2, 3, 9),
                VariantKey(rawHash, Target.Jvm, OptLevel.O2),
            )
            settle()

            assertAll(
                { assertTrue(exchange.manifest.value.any { it.hash == rawHash && it.variantOf == null }, "raw bobbin has null variantOf") },
                {
                    assertTrue(
                        exchange.manifest.value.any {
                            it.hash == variantHash && it.variantOf == VariantKey(rawHash, Target.Jvm, OptLevel.O2)
                        },
                        "variant bobbin carries its VariantKey",
                    )
                },
            )
        }

    /**
     * Tamper rejection: [Creel.putVerified] throws [IllegalArgumentException] when
     * the supplied bytes do not match the expected hash. This is the integrity invariant
     * that [BobbinExchange.fetch] relies on to detect corrupt or tampered responses.
     */
    @Test
    fun tamperRejectedByPutVerified() {
        val creel = Creel()
        val bytes = byteArrayOf(10, 20, 30)
        val hash = creel.put(bytes)
        val tamperedBytes = byteArrayOf(99, 88, 77)

        assertFailsWith<IllegalArgumentException>(
            message = "putVerified must reject bytes whose hash does not match the expected hash",
        ) {
            creel.putVerified(hash, tamperedBytes)
        }
    }

    /**
     * Late holder: A starts fetch(hash) before any peer holds the bytes.
     * After peer B acquires the bytes and one anti-entropy interval elapses,
     * A's periodic re-request reaches B and A's pending fetch completes.
     *
     * Regression for defect (b): the single-shot Request design means a late holder
     * is never reached — the periodic re-request fixes this.
     */
    @Test
    fun lateHolderCompletesViaPeriodicReRequest() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("bobbin-late-holder"))
            val seamB = loom.join(InMemoryTag("b"))
            val exchangeA = BobbinExchange(seamA, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)

            val bytes = byteArrayOf(10, 20, 30, 40, 50)
            // Compute the hash without injecting bytes into either exchange yet.
            val hash = Creel().put(bytes)

            // A starts fetching while no peer holds the bytes: Request goes out, no Response.
            var fetched: ByteArray? = null
            val fetchJob = backgroundScope.launch { fetched = exchangeA.fetch(hash) }
            runCurrent() // A broadcasts Request; B has no bytes → no response → A suspends

            // B now acquires the bytes.
            exchangeB.put(bytes)
            runCurrent()

            // Elapse one anti-entropy interval to fire A's periodic re-request loop.
            advanceTimeBy(BOBBIN_QUILTER_CONFIG.antiEntropyInterval)
            runCurrent() // A re-broadcasts Request; B responds; A's deferred completes

            assertAll(
                { assertTrue(fetchJob.isCompleted, "fetch job must complete after re-request") },
                { assertContentEquals(bytes, fetched, "fetch must return the original bytes") },
            )
        }

    /**
     * Cancelled fetch leaves no stale in-flight entry.
     *
     * A fetch that is cancelled while suspended on deferred.await() must clean up its
     * inFlight entry so that a subsequent fetch for the same hash issues a fresh Request
     * and completes normally when served.
     *
     * Regression for defect (a): without the try/finally cleanup, the stale entry causes
     * the subsequent fetch to join a dead deferred and hang forever.
     */
    @Test
    fun cancelledFetchDoesNotOrphanInFlightEntry() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("bobbin-cancel-orphan"))
            val seamB = loom.join(InMemoryTag("b"))
            val exchangeA = BobbinExchange(seamA, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)

            val bytes = byteArrayOf(1, 2, 3)
            val hash = Creel().put(bytes)

            // A fetches while nobody holds the bytes → suspends on deferred.await()
            val cancelledJob = backgroundScope.launch { exchangeA.fetch(hash) }
            runCurrent() // A sends Request; B has no bytes → no response

            // Cancel mid-await.
            cancelledJob.cancel()
            runCurrent() // CancellationException propagates; finally-block removes inFlight[hash]

            // B now holds the bytes.
            exchangeB.put(bytes)
            runCurrent()

            // A fresh fetch must send a new Request (inFlight was cleared) and complete.
            var fetched: ByteArray? = null
            val newJob = backgroundScope.launch { fetched = exchangeA.fetch(hash) }
            runCurrent()

            assertAll(
                { assertTrue(newJob.isCompleted, "second fetch must complete after cancel-cleanup") },
                { assertContentEquals(bytes, fetched, "second fetch must return the original bytes") },
            )
        }

    /**
     * Concurrent waiters survive a sibling's cancellation.
     *
     * Two callers fetch the same hash and share one in-flight entry (one deferred, one Request).
     * Cancelling one must NOT clear the shared entry: when a holder later serves the bytes, the
     * surviving waiter still completes.
     *
     * Regression for the waiter-orphan defect: a naive per-waiter `finally` removed the shared
     * in-flight entry as soon as the *first* caller was cancelled, so the response found no entry
     * to complete and the survivor — plus the periodic re-request, which no longer saw the hash —
     * hung forever. Reference-counting waiters fixes it: only the last departing waiter clears it.
     */
    @Test
    fun cancellingOneConcurrentWaiterDoesNotOrphanTheOther() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("bobbin-multi-waiter-cancel"))
            val seamB = loom.join(InMemoryTag("b"))
            val exchangeA = BobbinExchange(seamA, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)
            val exchangeB = BobbinExchange(seamB, Creel(), backgroundScope, BOBBIN_QUILTER_CONFIG)

            val bytes = byteArrayOf(7, 8, 9, 10)
            val hash = Creel().put(bytes)

            // Two concurrent fetches for the same hash share one in-flight entry; nobody holds bytes yet.
            val cancelledJob = backgroundScope.launch { exchangeA.fetch(hash) }
            var survivorBytes: ByteArray? = null
            val survivorJob = backgroundScope.launch { survivorBytes = exchangeA.fetch(hash) }
            runCurrent() // both suspend on the shared deferred; A broadcast one Request, B had no bytes

            // Cancel one of the two waiters; its finally must decrement the count, NOT clear the entry.
            cancelledJob.cancel()
            runCurrent()

            // B acquires the bytes; A's periodic re-request (one interval later) reaches B.
            exchangeB.put(bytes)
            runCurrent()
            advanceTimeBy(BOBBIN_QUILTER_CONFIG.antiEntropyInterval)
            runCurrent()

            assertAll(
                { assertTrue(cancelledJob.isCancelled, "the cancelled waiter must be cancelled") },
                { assertTrue(survivorJob.isCompleted, "the surviving waiter must still complete") },
                { assertContentEquals(bytes, survivorBytes, "survivor must receive the original bytes") },
            )
        }
}
