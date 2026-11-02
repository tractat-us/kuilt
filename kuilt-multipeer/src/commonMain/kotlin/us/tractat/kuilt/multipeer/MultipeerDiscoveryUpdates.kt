package us.tractat.kuilt.multipeer

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import us.tractat.kuilt.core.Tag

/**
 * A snapshot and its triggering arrival travel together, so a new subscriber cannot read
 * a snapshot newer than an already-queued arrival and then overwrite it with that old arrival.
 * Factories publish under the same lock that updates their visible-peer set, including losses
 * and teardown. Teardown replaces the generation: a subscriber that queued replay before
 * acquiring its session must discard updates from the stopped generation. Only the first
 * accepted update seeds a collector; subsequent updates preserve individual
 * arrival callbacks. The bounded buffer drops oldest updates under overload, keeping the replayed
 * snapshot current even when an existing collector is slow.
 */
internal class MultipeerDiscoveryUpdates {
    private val updates = MutableSharedFlow<MultipeerDiscoveryUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Collection-owned teardown writes under both the lifecycle and visible-peer locks; acquisition
    // reads it under the lifecycle lock, after any preceding teardown has completed. A browse session
    // also captures it at start, and factories drop a callback whose session no longer matches,
    // checked under the visible-peer lock: on Apple, stopping a browser cannot recall a delegate
    // callback already dispatched, so without that check a stopped session's callback would publish
    // under its successor's generation. The JVM bridge's stop waits for callbacks, so there it is a
    // backstop.
    var generation: MultipeerBrowseGeneration = MultipeerBrowseGeneration()
        private set

    val events: SharedFlow<MultipeerDiscoveryUpdate> = updates.asSharedFlow()

    fun publish(current: Set<MultipeerAdvertisement>, arrival: MultipeerAdvertisement? = null) {
        updates.tryEmit(MultipeerDiscoveryUpdate(current, arrival, generation))
    }

    fun endSession() {
        generation = MultipeerBrowseGeneration()
        publish(emptySet())
    }
}

internal class MultipeerBrowseGeneration

internal data class MultipeerDiscoveryUpdate(
    val current: Set<MultipeerAdvertisement>,
    val arrival: MultipeerAdvertisement?,
    val generation: MultipeerBrowseGeneration,
)

internal fun Flow<MultipeerDiscoveryUpdate>.advertisements(
    acquiredGeneration: () -> MultipeerBrowseGeneration,
): Flow<Tag> = flow {
    var first = true
    collect { update ->
        if (update.generation !== acquiredGeneration()) return@collect
        if (first) {
            first = false
            update.current.forEach { emit(it) }
        } else {
            update.arrival?.let { emit(it) }
        }
    }
}
