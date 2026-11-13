package us.tractat.kuilt.identity

import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RevocationList] on real threads. `revoke` never suspends, so no test dispatcher can interleave two
 * calls: every common test runs them one after another, and a list with no lock at all would pass
 * them. Only threads that really run at once can lose an update, so this is the one test that sees
 * the lock.
 *
 * [THREADS] threads revoke the same [SUBJECTS] subjects, each with its own cutoffs, released together
 * by a barrier. A lost read-modify-write leaves some subject below its largest cutoff; a corrupted
 * map loses a subject outright. Either shows as a subject whose cutoff is not the maximum.
 */
class RevocationListThreadSafetyTest {
    @Test
    fun concurrentRevokesKeepEveryMaximum() {
        val subjects = List(SUBJECTS) { syntheticKey(it) }
        val shortfalls = (1..ROUNDS).map { round ->
            val list = RevocationList.inMemory()
            val barrier = CyclicBarrier(THREADS)
            val threads = (0 until THREADS).map { t ->
                Thread {
                    barrier.await()
                    runBlocking {
                        // Alternate directions so threads meet on the same subject from both ends.
                        val order = if (t % 2 == 0) subjects.indices else subjects.indices.reversed()
                        for (i in order) list.revoke(subjects[i], notBefore = cutoff(t, i))
                    }
                }.apply { start() }
            }
            threads.forEach { it.join() }
            round to subjects.indices.count { i -> list.notBefore(subjects[i]) != cutoff(THREADS - 1, i) }
        }
        assertAll(
            { assertEquals(ROUNDS, shortfalls.size, "every round ran") },
            { assertEquals(emptyList(), shortfalls.filter { it.second > 0 }, "(round, subjects short of their maximum)") },
        )
    }

    private fun cutoff(thread: Int, subject: Int): Long = thread * 1_000_000L + subject

    /** A syntactically valid name for no particular key: the list compares names and never parses them. */
    private fun syntheticKey(n: Int): DidKey =
        DidKey.ofCompressedPoint(ByteArray(33) { if (it == 0) 0x02 else if (it < 5) (n ushr (8 * (it - 1))).toByte() else 0x11 })

    private companion object {
        const val THREADS = 8
        const val SUBJECTS = 500
        const val ROUNDS = 40
    }
}
