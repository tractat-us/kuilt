package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Tag

/**
 * Samples for the discovery primitives used by `@sample` KDoc tags.
 *
 * Compiled as part of commonTest, so an API change that breaks a sample breaks
 * the build instead of silently producing stale documentation.
 */

/** Merge two discovery feeds into one live roster; a departure drops a peer. */
@Suppress("unused")
internal fun sampleDiscoveryRoster() = runTest {
    // Two transports, each a feed of "appeared" / "left" events.
    val mdnsPeers = MutableSharedFlow<Tag>(extraBufferCapacity = 8)
    val mdnsGone = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val mdns = object : PeerDiscoverySource {
        override val kind = DiscoveryKind.Mdns
        override fun discoveries(): Flow<Tag> = mdnsPeers
        override fun departures(): Flow<String> = mdnsGone
    }
    val multipeer = object : PeerDiscoverySource {
        override val kind = DiscoveryKind.Multipeer
        override fun discoveries(): Flow<Tag> = emptyFlow() // idle in this sample
    }

    // One StateFlow the lobby UI renders directly — no hand-rolled merge.
    val roster = discoveryRoster(listOf(mdns, multipeer), backgroundScope)
    runCurrent()

    mdnsPeers.emit(InMemoryTag("alice"))
    mdnsPeers.emit(InMemoryTag("bob"))
    runCurrent()
    check(roster.value.map { it.peerKey }.toSet() == setOf("alice", "bob"))

    // A departure removes the peer, keyed on Tag.peerKey.
    mdnsGone.emit("alice")
    runCurrent()
    check(roster.value.map { it.peerKey }.toSet() == setOf("bob"))
}
