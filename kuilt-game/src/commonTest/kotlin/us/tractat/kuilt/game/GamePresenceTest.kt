@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.seconds

class GamePresenceTest {

    /**
     * Two [GamePresence] replicas over connected seams converge: a host declaration
     * on one is visible on the other after Quilter delta delivery.
     */
    @Test
    fun hostDeclarationConverges() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val (s1, s2) = seats(loom, 2)

        val p1 = GamePresence(s1, backgroundScope)
        val p2 = GamePresence(s2, backgroundScope)

        p1.declareHost()

        testScheduler.advanceUntilIdle()

        assertContains(p2.declaredHosts(), ReplicaId(s1.selfId.value))
    }
}
