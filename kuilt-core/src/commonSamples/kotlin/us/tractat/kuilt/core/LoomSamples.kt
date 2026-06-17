package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals

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

// ── Doc-alias samples (camelCase mirrors of backtick-named InMemoryLoomTest fns) ──

/**
 * The receiving [Seam] stamps `sender` from the sending peer's [PeerId].
 *
 * Alias for the `Swatch sender field on received broadcast equals sender PeerId`
 * test — the backtick name can't be an `include-symbol` target.
 */
@Suppress("unused")
internal fun sampleSwatchSenderField() = runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val deferred = async { b.incoming.first() }
    a.broadcast(byteArrayOf(0))
    val frame = deferred.await()

    assertEquals(a.selfId, frame.sender)
}

/**
 * [Seam.close] is idempotent — a second call must not throw.
 *
 * Alias for the `close is idempotent — calling twice does not throw` test.
 */
@Suppress("unused")
internal fun sampleCloseIsIdempotent() = runTest {
    val factory = InMemoryLoom()
    val link = factory.host(Pattern("Alice"))

    link.close()
    link.close() // must not throw
}

/**
 * Closing a peer removes it from every other peer's [Seam.peers] set.
 *
 * Alias for the `close removes the closing peer from every other peer's peers set` test.
 */
@Suppress("unused")
internal fun sampleCloseRemovesPeer() = runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))
    val c = factory.join(InMemoryTag("Charlie"))

    b.close()

    val expected = setOf(a.selfId, c.selfId)
    assertEquals(expected, a.peers.value)
    assertEquals(expected, c.peers.value)
}

/**
 * The receiving [Seam] assigns sequence numbers starting at 1, increasing by 1.
 *
 * Alias for the `sequence on received frames is monotonically increasing starting from 1` test.
 */
@Suppress("unused")
internal fun sampleSequenceMonotonicallyIncreasing() = runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val frames = async { b.incoming.take(3).toList() }

    a.broadcast(byteArrayOf(1))
    a.broadcast(byteArrayOf(2))
    a.broadcast(byteArrayOf(3))

    val received = frames.await()
    assertEquals(1L, received[0].sequence)
    assertEquals(2L, received[1].sequence)
    assertEquals(3L, received[2].sequence)
}

/**
 * A broadcast from one peer is received by all other peers.
 *
 * Alias for the `broadcast from A causes B to receive the frame` test.
 */
@Suppress("unused")
internal fun sampleBroadcastReceived() = runTest {
    val factory = InMemoryLoom()
    val a = factory.host(Pattern("Alice"))
    val b = factory.join(InMemoryTag("Bob"))

    val receivedByB = async { b.incoming.first() }

    a.broadcast(byteArrayOf(1, 2, 3))

    val frame = receivedByB.await()
    assertEquals(Swatch(byteArrayOf(1, 2, 3), sender = a.selfId, sequence = 1L), frame)
}

/**
 * After host and join, both peers appear in each other's [Seam.peers] set.
 *
 * Alias for the `join after open causes both peers to appear in each other's peer set` test.
 */
@Suppress("unused")
internal fun sampleJoinPeerSet() = runTest {
    val factory = InMemoryLoom()
    val host = factory.host(Pattern("Alice"))
    val joiner = factory.join(InMemoryTag("Bob"))

    assertEquals(setOf(host.selfId, joiner.selfId), host.peers.value)
    assertEquals(setOf(host.selfId, joiner.selfId), joiner.peers.value)
}
