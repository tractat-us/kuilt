package us.tractat.kuilt.nw

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Holds [NwSeam.incoming] to at most one collector that has not been cancelled (#3073).
 *
 * A spool hands each frame to one collector, so a second collector takes frames from the first and neither
 * hears about it. [NwLoom] answers a repeat of a live rendezvous with the live seam, which is how a second room
 * or lobby reaches this: it looks like a re-weave from there. So the second collector is refused here, with an
 * [IllegalStateException] thrown into the coroutine collecting.
 *
 * **A cancelled collector does not count, even before it has finished.** `SeamRoom.restartIncomingCollect`
 * cancels its collector and starts the next without joining, so on a multi-threaded dispatcher the new one can
 * arrive while the old is still unwinding. Cancellation flips [Job.isActive] at the `cancel` call, so that order
 * always reads as free. The old collector cannot read another frame by then: its next emit throws.
 *
 * A collector whose context has no [Job] cannot be cancelled by anyone here, so it holds until it finishes.
 */
internal class NwSingleCollector {
    private class Claim(val job: Job?) {
        val isLive: Boolean get() = job == null || job.isActive
    }

    private val holder = atomic<Claim?>(null)

    /** [upstream], refusing a collector that arrives while a live one holds it. */
    fun <T> guard(upstream: Flow<T>): Flow<T> = flow {
        val claim = claim(currentCoroutineContext()[Job])
        try {
            emitAll(upstream)
        } finally {
            // Only its own claim: a cancelled collector finishing late must not free the one that replaced it.
            holder.compareAndSet(claim, null)
        }
    }

    private fun claim(job: Job?): Claim {
        val mine = Claim(job)
        while (true) {
            val held = holder.value
            check(held == null || !held.isLive) {
                "Seam.incoming is single-collection: a second live collector would take frames from the first, " +
                    "with no error on either side. A second room or lobby on one NwLoom session shares this seam: " +
                    "leave the first before starting another. To fan out inside one consumer, use shareIn."
            }
            if (holder.compareAndSet(held, mine)) return mine
        }
    }
}
