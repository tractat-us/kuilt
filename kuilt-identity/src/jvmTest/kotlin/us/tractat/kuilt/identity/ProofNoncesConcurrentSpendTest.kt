package us.tractat.kuilt.identity

import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two spends of one good nonce at once, on a two-thread dispatcher: exactly one wins. `spend` never
 * suspends, so no test dispatcher can interleave two calls, and an instance with no lock at all
 * would pass every common test. A barrier releases both threads together each round, so the two
 * spends really race at the insert the lock guards.
 */
class ProofNoncesConcurrentSpendTest {
    @Test
    fun twoConcurrentSpendsOfOneNonceHaveOneWinner() {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val dispatcher = pool.asCoroutineDispatcher()
            val rounds = runBlocking {
                val nonces = ProofNonces.create(Random(0), ProofNonces.DEFAULT_TTL, ProofNonces.DEFAULT_CAPACITY)
                (1..ROUNDS).map { round ->
                    val now = T + round
                    val fresh = nonces.check(nonces.issue(now), now) as NonceCheck.Fresh
                    val barrier = CyclicBarrier(THREADS)
                    val outcomes = withContext(dispatcher) {
                        List(THREADS) {
                            async {
                                barrier.await()
                                nonces.spend(fresh, now)
                            }
                        }.awaitAll()
                    }
                    round to outcomes
                }
            }
            val wrong = rounds.filter { (_, outcomes) -> outcomes.sortedBy { it.toString() } != listOf(NonceSpend.Replayed, NonceSpend.Spent) }
            assertAll(
                { assertEquals(ROUNDS, rounds.size, "every round ran") },
                { assertEquals(ROUNDS * THREADS, rounds.sumOf { it.second.size }, "every spend ran") },
                { assertEquals(emptyList(), wrong, "(round, outcomes) without exactly one Spent and one Replayed") },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 2
        const val ROUNDS = 2_000
        const val T = 1_700_000_000_000L
    }
}
