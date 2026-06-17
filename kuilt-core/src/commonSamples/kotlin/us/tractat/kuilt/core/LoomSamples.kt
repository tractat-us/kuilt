package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Samples for [Loom] and [Seam] used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

/** Host a session and join it; exchange one frame. */
@Suppress("unused")
internal fun sampleHostAndJoin() = runTest {
    val loom = InMemoryLoom()

    val host = loom.host(Pattern("Alice"))
    val joiner = loom.join(InMemoryTag("Bob"))

    // collect one frame from the host's incoming flow
    val receivedByHost = async {
        host.incoming.first()
    }

    joiner.broadcast("hello".encodeToByteArray())
    val frame = receivedByHost.await()

    check(frame.payload.decodeToString() == "hello")
    check(frame.sender == joiner.selfId)

    host.close()
    joiner.close()
}

/** Collect the single-collection rule — wrap with shareIn for fan-out. */
@Suppress("unused")
internal fun sampleIncomingFanout() = runTest {
    val loom = InMemoryLoom()
    val host = loom.host(Pattern("Alice"))
    val joiner = loom.join(InMemoryTag("Bob"))

    // Collect three frames then stop; proves the flow is cold and single-shot.
    val collected = launch { host.incoming.take(3).toList() }

    repeat(3) { joiner.broadcast("frame-$it".encodeToByteArray()) }
    collected.join()

    host.close()
    joiner.close()
}

/** Check FabricAvailability before weaving on a conditional fabric. */
@Suppress("unused")
internal fun sampleFabricAvailability() {
    val loom = InMemoryLoom()
    when (val avail = loom.availability()) {
        is FabricAvailability.Available -> { /* ready to weave */ }
        is FabricAvailability.Unavailable -> error("Fabric not usable: ${avail.reason}")
    }
}

// ── MuxSeam ───────────────────────────────────────────────────────────────────

/**
 * Split one [Seam] into N independent logical channels via [MuxSeam].
 *
 * [Seam.incoming] is single-collection per the kuilt contract. [MuxSeam] takes
 * sole ownership of that collection and fans the stream out to per-channel views,
 * each prefixed with a 1-byte tag. Use this whenever two independent consumers
 * (e.g. a [us.tractat.kuilt.quilter.Quilter] and a Raft transport) must share
 * one underlying seam.
 */
@Suppress("unused")
internal fun sampleMuxSeamChannels() = runTest {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("mux-demo"))

    val mux = MuxSeam(seam, this)

    // Each channel gets a typed Seam view that strips the tag on reads and
    // prepends it on writes — the rest of your code sees a plain Seam.
    val replicatorSeam: Seam = mux.channel(0x00.toByte())
    val coordinatorSeam: Seam = mux.channel(0x01.toByte())

    // channel() is idempotent — calling it again with the same tag returns the same Seam.
    check(mux.channel(0x00.toByte()) === replicatorSeam)
    check(replicatorSeam !== coordinatorSeam)
}
