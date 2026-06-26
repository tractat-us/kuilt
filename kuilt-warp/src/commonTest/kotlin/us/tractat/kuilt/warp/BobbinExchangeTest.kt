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
                { assertTrue(exchangeB.manifest.value.contains(hash), "B's manifest must contain the hash after settling") },
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
                { assertTrue(exchange.manifest.value.contains(h1)) },
                { assertTrue(exchange.manifest.value.contains(h2)) },
                { assertTrue(exchange.manifest.value.contains(h3)) },
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
}
