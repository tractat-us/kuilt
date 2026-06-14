package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConnPairTest {
    @Test
    fun framesCrossToTheOtherEnd() = runTest {
        val (a, b) = connPair()
        a.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), b.incoming.first())
    }
}
