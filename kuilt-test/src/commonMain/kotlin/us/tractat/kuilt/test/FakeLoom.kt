package us.tractat.kuilt.test

import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * A test double for [Loom] that returns [FakeSeam] instances.
 *
 * [weave] with [Rendezvous.New] seeds the seam's [FakeSeam.selfId] from the
 * pattern's session name. For wired two-peer scenarios, prefer [fakeSeamPair].
 *
 * ```kotlin
 * val loom = FakeLoom()
 * val seam = loom.host(Pattern("alice"))
 * // seam.selfId == PeerId("alice")
 * ```
 */
public class FakeLoom : Loom {
    override suspend fun weave(rendezvous: Rendezvous): FakeSeam =
        when (rendezvous) {
            is Rendezvous.New -> seam(rendezvous.pattern.sessionName)
            is Rendezvous.Existing -> seam(rendezvous.tag.sessionName)
        }

    private fun seam(displayName: String): FakeSeam =
        FakeSeam(
            selfId = PeerId(displayName),
            initialPeers = setOf(PeerId(displayName)),
            initialState = SeamState.Woven,
        )
}

/**
 * Build a wired pair of [FakeSeam]s whose [FakeSeam.broadcast] calls cross-deliver
 * into the other seam's [Seam.incoming], matching the send semantics of a real
 * two-peer fabric.
 *
 * - Each side's peers set contains both [hostId] and [joinerId].
 * - A [FakeSeam.broadcast] on one side stamps the sender's [FakeSeam.selfId] and a
 *   monotonically increasing sequence at the receiver.
 * - The broadcast is also recorded in the sender's [FakeSeam.broadcasts] list.
 * - Delivery is synchronous — no coroutine substrate required.
 *
 * ```kotlin
 * val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
 * host.broadcast(byteArrayOf(1, 2, 3))
 * val frame = joiner.incoming.first()   // Swatch(payload=[1,2,3], sender=PeerId("host"), sequence=1)
 * ```
 */
public fun fakeSeamPair(
    hostId: PeerId,
    joinerId: PeerId,
): Pair<FakeSeam, FakeSeam> {
    val bothPeers = setOf(hostId, joinerId)
    val host = FakeSeam(selfId = hostId, initialPeers = bothPeers)
    val joiner = FakeSeam(selfId = joinerId, initialPeers = bothPeers)
    wireDelivery(sender = host, receiver = joiner)
    wireDelivery(sender = joiner, receiver = host)
    return host to joiner
}

private fun wireDelivery(sender: FakeSeam, receiver: FakeSeam) {
    sender.onBroadcast = { payload ->
        receiver.deliver(
            Swatch(
                payload = payload,
                sender = sender.selfId,
                sequence = receiver.nextSequence(),
            ),
        )
    }
}

/**
 * Build a wired group of [FakeSeam]s, one per id in [peerIds], whose
 * [FakeSeam.broadcast] calls cross-deliver into every *other* seam's
 * [Seam.incoming] — the N-peer generalisation of [fakeSeamPair].
 *
 * - Each seam's peers set contains all of [peerIds].
 * - A broadcast on one seam stamps the sender's [FakeSeam.selfId] and a
 *   monotonically increasing per-receiver sequence at each receiver.
 * - Delivery is synchronous — no coroutine substrate required.
 */
public fun fakeSeamGroup(peerIds: List<PeerId>): List<FakeSeam> {
    require(peerIds.size >= 2) { "A seam group needs at least 2 peers, got ${peerIds.size}" }
    require(peerIds.toSet().size == peerIds.size) { "Duplicate peer ids in $peerIds" }
    val allPeers = peerIds.toSet()
    val seams = peerIds.map { FakeSeam(selfId = it, initialPeers = allPeers) }
    for (sender in seams) {
        val receivers = seams.filter { it !== sender }
        sender.onBroadcast = { payload ->
            for (receiver in receivers) {
                receiver.deliver(
                    Swatch(
                        payload = payload,
                        sender = sender.selfId,
                        sequence = receiver.nextSequence(),
                    ),
                )
            }
        }
    }
    return seams
}
