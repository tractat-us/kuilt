package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fixed-roster fake. [departures] defaults to [emptyFlow] here — a *constructor* default, chosen
 * per call site and visible in this file, not the interface default that used to let an
 * implementation opt out of removal by saying nothing at all.
 */
private class FakeSource(
    override val kind: DiscoveryKind,
    private val discoveries: Flow<Tag>,
    private val departures: Flow<String> = emptyFlow(),
) : PeerDiscoverySource {
    override fun discoveries(): Flow<Tag> = discoveries
    override fun departures(): Flow<String> = departures
}

private fun peerKeysOf(roster: Set<Tag>): Set<String> = roster.map { it.peerKey }.toSet()

class DiscoveryRosterTest {
    @Test
    fun mergesTwoSourcesKeyedOnPeerKey() = runTest {
        val mdns = MutableSharedFlow<Tag>(extraBufferCapacity = 16)
        val multipeer = MutableSharedFlow<Tag>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(
                FakeSource(DiscoveryKind.Mdns, mdns),
                FakeSource(DiscoveryKind.Multipeer, multipeer),
            ),
            backgroundScope,
        )
        runCurrent() // let the Eagerly-started fold attach its collectors

        mdns.emit(InMemoryTag("alice"))
        multipeer.emit(InMemoryTag("bob"))
        runCurrent()

        assertEquals(setOf("alice", "bob"), peerKeysOf(roster.value))
    }

    @Test
    fun departureRemovesThePeer() = runTest {
        val discoveries = MutableSharedFlow<Tag>(extraBufferCapacity = 16)
        val departures = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(FakeSource(DiscoveryKind.Mdns, discoveries, departures)),
            backgroundScope,
        )
        runCurrent()

        discoveries.emit(InMemoryTag("alice"))
        discoveries.emit(InMemoryTag("bob"))
        runCurrent()
        assertEquals(setOf("alice", "bob"), peerKeysOf(roster.value))

        departures.emit("alice")
        runCurrent()
        assertEquals(setOf("bob"), peerKeysOf(roster.value))
    }

    @Test
    fun emptyDeparturesAccumulatesGhosts() = runTest {
        // One source announces departures; the other has no leave signal at all (emptyFlow).
        val mdnsDisc = MutableSharedFlow<Tag>(extraBufferCapacity = 16)
        val mdnsDep = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val mpcDisc = MutableSharedFlow<Tag>(extraBufferCapacity = 16)
        val roster = discoveryRoster(
            listOf(
                FakeSource(DiscoveryKind.Mdns, mdnsDisc, mdnsDep),
                FakeSource(DiscoveryKind.Multipeer, mpcDisc), // no leave signal: departures() is empty
            ),
            backgroundScope,
        )
        runCurrent()

        // The same physical peer shows up on both transports under transport-scoped keys.
        mdnsDisc.emit(InMemoryTag("peer-mdns"))
        mpcDisc.emit(InMemoryTag("peer-mpc"))
        runCurrent()
        assertEquals(setOf("peer-mdns", "peer-mpc"), peerKeysOf(roster.value))

        // It leaves. Only the mDNS source can announce the departure.
        mdnsDep.emit("peer-mdns")
        runCurrent()

        // The mDNS key is gone; the Multipeer key lingers forever — the documented
        // add-only ghost: a source whose departures() is empty never removes what it added.
        assertEquals(setOf("peer-mpc"), peerKeysOf(roster.value))
    }
}
