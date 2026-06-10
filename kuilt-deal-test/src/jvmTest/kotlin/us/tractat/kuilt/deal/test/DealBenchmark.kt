package us.tractat.kuilt.deal.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.deal.SraScheme
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Tier 2 full-cycle benchmark: shuffle → assignQuorums → strip → decrypt over the
 * in-process [fakeDealSessionPair] (zero network cost — measures pure crypto).
 *
 * **Opt-in:** skipped unless `-Pkuilt.benchmark.tests=true`. Run with:
 * ```
 * ./gradlew :kuilt-deal-test:jvmTest --tests "*DealBenchmark" -Pkuilt.benchmark.tests=true
 * ```
 *
 * These thresholds are LOOSE regression-catchers, not tight SLAs. The backing
 * [SraScheme] is ionspin (pure-Kotlin BigInteger), so a full 52-card 2-player
 * cycle takes several seconds — that cost is exactly what motivates the deferred
 * JVM/Android fast path (issue #299). The benchmark documents the end-to-end
 * deal time and guards against a catastrophic (multiplicative) regression.
 */
class DealBenchmark {

    @Before
    fun requireBenchmarkFlag() {
        Assume.assumeTrue(
            "Skipped: set -Pkuilt.benchmark.tests=true to run timing benchmarks",
            System.getProperty("kuilt.benchmark.tests") == "true",
        )
    }

    @Test
    fun fullCyclePoker52CardsTwoPlayers() = runBlocking {
        val ids = listOf(PeerId("alice"), PeerId("bob"))
        val scheme = SraScheme()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (alice, bob) = fakeDealSessionPair(ids[0], ids[1], scheme, scope)

        val deck = (1..52).map { "card:$it".encodeToByteArray() }

        val elapsed = measureTime {
            alice.shuffle(deck)
            bob.shuffle(deck)
            val quorums = (0..51).associate { it to setOf(ids[0]) }  // poker: alice sees all
            alice.assignQuorums(quorums)
            bob.assignQuorums(quorums)
            bob.strip()
            check(alice.decrypt(0).isNotEmpty())  // sanity: card 0 recovers
        }

        println("Tier 2 poker (2-player, 52 cards, SRA-2048 ionspin): ${elapsed.inWholeMilliseconds}ms")
        assertTrue(
            elapsed.inWholeSeconds < 30,
            "Full poker cycle took ${elapsed.inWholeMilliseconds}ms — exceeds the 30s catastrophic-regression bound",
        )
    }

    @Test
    fun fullCycleHanabi50CardsTwoPlayers() = runBlocking {
        val ids = listOf(PeerId("alice"), PeerId("bob"))
        val scheme = SraScheme()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val (alice, bob) = fakeDealSessionPair(ids[0], ids[1], scheme, scope)

        val deck = (1..50).map { "card:$it".encodeToByteArray() }

        val elapsed = measureTime {
            alice.shuffle(deck)
            bob.shuffle(deck)
            // Hanabi: alice cannot see her own cards (quorum = {bob})
            val quorums = (0..49).associate { it to setOf(ids[1]) }
            alice.assignQuorums(quorums)
            bob.assignQuorums(quorums)
            alice.strip()
            check(bob.decrypt(0).isNotEmpty())  // sanity: card 0 recovers
        }

        println("Tier 2 Hanabi (2-player, 50 cards, SRA-2048 ionspin): ${elapsed.inWholeMilliseconds}ms")
        assertTrue(
            elapsed.inWholeSeconds < 30,
            "Full Hanabi cycle took ${elapsed.inWholeMilliseconds}ms — exceeds the 30s catastrophic-regression bound",
        )
    }
}
