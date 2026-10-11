@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.VersionVector
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A GC coordinator whose `applyCompaction` leaves `state` where it was must fail, not re-run the
 * same compaction forever (#2816). Each fixture holds exactly one compactable item under a cut
 * that covers it, so the first pass compacts and only a stalled callback can reach a second.
 */
class GcCoordinatorBoundsTest {
    private val author = ReplicaId("author")
    private val vv = VersionVector.of(mapOf(author to 100L))

    @Test
    fun rgaRejectsStalledApplication() = stalled { scope, applied ->
        val inserted = Rga.empty<String>().insertAfter(author, RgaId.HEAD, "a").first
        val state = checkNotNull(inserted.removeAt(0)).first
        RgaGcCoordinator(
            MutableStateFlow(state), MutableStateFlow(CutFrontier(vv, vv)),
            MutableStateFlow(vv), { applied() }, scope = scope,
        )
    }

    @Test
    fun fugueRejectsStalledApplication() = stalled { scope, applied ->
        val inserted = Fugue.empty<String>().insertAt(author, 0, "a").first
        val state = checkNotNull(inserted.removeAt(0)).first
        FugueGcCoordinator(
            MutableStateFlow(state), MutableStateFlow(CutFrontier(vv, vv)),
            MutableStateFlow(vv), { applied() }, scope = scope,
        )
    }

    @Test
    fun movableTreeRejectsStalledApplication() = stalled { scope, applied ->
        val (a, id) = MovableTree.empty<String>().addNode(author, 1L, MovableTree.ROOT_ID, "a")
        val b = a.move(author, 2L, id, MovableTree.ROOT_ID).first
        val state = b.move(author, 3L, id, MovableTree.ROOT_ID).first
        MovableTreeGcCoordinator(
            MutableStateFlow(state), MutableStateFlow(CutFrontier(vv, vv)),
            MutableStateFlow(vv), { applied() }, scope = scope,
        )
    }

    private fun stalled(create: (CoroutineScope, () -> Unit) -> AutoCloseable) = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + CoroutineExceptionHandler { _, error -> failures += error },
        )
        var calls = 0
        val coordinator = create(scope) {
            calls++
            // Fuse: without the check the loop calls back forever; this turns that into an
            // assertion on the second call instead of a wedged test.
            assertTrue(calls <= 1, "stalled compaction callback repeated")
        }
        try {
            runCurrent()
            assertAll(
                { assertEquals(1, calls, "the stalled callback must be exercised exactly once") },
                {
                    val failure = assertIs<IllegalStateException>(failures.singleOrNull())
                    assertEquals(STALLED_CALLBACK, failure.message)
                },
            )
        } finally {
            coordinator.close()
        }
    }
}
