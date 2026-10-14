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
 * and teardown. Only the first update seeds a collector; subsequent updates preserve individual
 * arrival callbacks. The bounded buffer drops oldest updates under overload, keeping the replayed
 * snapshot current even when an existing collector is slow.
 */
internal class MultipeerDiscoveryUpdates {
    private val updates = MutableSharedFlow<MultipeerDiscoveryUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<MultipeerDiscoveryUpdate> = updates.asSharedFlow()

    fun publish(current: Set<MultipeerAdvertisement>, arrival: MultipeerAdvertisement? = null) {
        updates.tryEmit(MultipeerDiscoveryUpdate(current, arrival))
    }
}

internal data class MultipeerDiscoveryUpdate(
    val current: Set<MultipeerAdvertisement>,
    val arrival: MultipeerAdvertisement?,
)

internal fun Flow<MultipeerDiscoveryUpdate>.advertisements(): Flow<Tag> = flow {
    var first = true
    collect { update ->
        if (first) {
            first = false
            update.current.forEach { emit(it) }
        } else {
            update.arrival?.let { emit(it) }
        }
    }
}
