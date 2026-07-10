@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The cross-core routed unicast (slice 5C): a per-recipient message reaches
 * *exactly* the one player it addresses, crossing the fully-meshed server core when
 * the player sits behind a different server — and is **never** fanned to a second
 * recipient (the leak-boundary invariant this slice exists to protect).
 *
 * Like the [AttachmentDirectoryTest], this drives real [Seam]s ([InMemoryLoom]) under
 * `UnconfinedTestDispatcher` — no Raft cluster, so no `MultiNodeRaftSim`; the tight
 * timeout keeps a non-converging run fast to fail. Players (`bob`, `carol`) carry
 * explicit *logical* ids — the directory/envelope key — while servers are their
 * core-seam ids; delivery down a player's own two-peer link doesn't depend on the
 * transport-assigned id of that link.
 */
class RoutedUnicastRouterTest {

    private val bob = PeerId("bob")
    private val carol = PeerId("carol")

    @Test
    fun routedUnicastCrossesCoreAndLandsOnAddressee() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // Two-server core; player Bob is behind S2. A message S1 holds for Bob must
        // travel S1 → core → S2 → Bob.
        val coreLoom = InMemoryLoom()
        val s1Core = coreLoom.host(Pattern("core"))
        val s2Core = coreLoom.join(InMemoryTag("core"))
        val s1 = s1Core.selfId
        val s2 = s2Core.selfId

        val bLoom = InMemoryLoom()
        val s2ToBob = bLoom.host(Pattern("s2-bob"))
        val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

        val lookup: (PeerId) -> PeerId? = { if (it == bob) s2 else null }

        val r1 = routedUnicastRouter(self = s1, coreSeam = s1Core, lookup = lookup, scope = backgroundScope)
        val r2 = routedUnicastRouter(self = s2, coreSeam = s2Core, lookup = lookup, scope = backgroundScope)
        r2.registerLocalSpoke(bob, s2ToBob)

        val bobReceived = collectInto(bobSeam)

        r1.route(bob, "for-bob".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(1, bobReceived.size, "Bob must receive exactly the one routed frame")
        assertEquals("for-bob", bobReceived.single().decodeToString())
        // It crossed the core: Bob is behind S2, S1 held no local Bob, so the frame
        // reached him only via S2 — proven by the delivering sender being S2's link.
        assertEquals(s2ToBob.selfId, bobReceived.single().sender, "frame must be delivered by S2, i.e. it crossed the core")
    }

    @Test
    fun fannedUnicastIsImpossible_leakBoundaryGuard() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // Three-server core; Bob behind S2, Carol behind S3. S1 routes a unicast for
        // Bob. The guard: it lands on Bob and NOWHERE else — not on Carol (another
        // server's player), not on the non-destination server S3.
        val coreLoom = InMemoryLoom()
        val s1Core = coreLoom.host(Pattern("core"))
        val s2Core = coreLoom.join(InMemoryTag("core"))
        val s3Core = coreLoom.join(InMemoryTag("core"))
        val s1 = s1Core.selfId
        val s2 = s2Core.selfId
        val s3 = s3Core.selfId

        val bLoom = InMemoryLoom()
        val s2ToBob = bLoom.host(Pattern("s2-bob"))
        val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

        val cLoom = InMemoryLoom()
        val s3ToCarol = cLoom.host(Pattern("s3-carol"))
        val carolSeam = cLoom.join(InMemoryTag("s3-carol"))

        val lookup: (PeerId) -> PeerId? = { mapOf(bob to s2, carol to s3)[it] }

        // Wrap S1's core seam so we can HARD-assert the origin never fanned: it must
        // have addressed exactly one server and NEVER broadcast.
        val s1Recording = RecordingSeam(s1Core)
        val r1 = routedUnicastRouter(self = s1, coreSeam = s1Recording, lookup = lookup, scope = backgroundScope)
        val r2 = routedUnicastRouter(self = s2, coreSeam = s2Core, lookup = lookup, scope = backgroundScope)
        val r3 = routedUnicastRouter(self = s3, coreSeam = s3Core, lookup = lookup, scope = backgroundScope)
        r2.registerLocalSpoke(bob, s2ToBob)
        r3.registerLocalSpoke(carol, s3ToCarol)

        val bobReceived = collectInto(bobSeam)
        val carolReceived = collectInto(carolSeam)

        r1.route(bob, "secret-for-bob".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        // Landed on exactly the addressee...
        assertEquals(listOf("secret-for-bob"), bobReceived.map { it.decodeToString() })
        // ...and NOWHERE else: no other player observed it.
        assertTrue(carolReceived.isEmpty(), "a different-server player must never observe a unicast for Bob")
        // Structural no-fan at the origin: the router addressed exactly ONE server and
        // NEVER broadcast to the core — a fanned unicast is impossible by construction.
        assertEquals(listOf(s2), s1Recording.sentTo, "origin must address exactly the one destination server")
        assertEquals(0, s1Recording.broadcastCount, "origin must NEVER broadcast a unicast to the core")
        // The non-destination server S3 was never addressed on the core, so it delivered
        // nothing locally — corroborated by Carol (behind S3) receiving nothing.
        assertTrue(s3 !in s1Recording.sentTo, "non-destination server must never receive the frame")
    }

    @Test
    fun unknownRecipientDroppedNotFanned() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // Directory returns null for the recipient (unattached/unknown): the frame is
        // dropped at the origin — sent nowhere, broadcast nowhere.
        val coreLoom = InMemoryLoom()
        val s1Core = coreLoom.host(Pattern("core"))
        val s2Core = coreLoom.join(InMemoryTag("core"))
        val s1 = s1Core.selfId
        val s2 = s2Core.selfId

        val bLoom = InMemoryLoom()
        val s2ToBob = bLoom.host(Pattern("s2-bob"))
        val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

        val lookup: (PeerId) -> PeerId? = { null } // nobody is attached anywhere yet

        val s1Recording = RecordingSeam(s1Core)
        val r1 = routedUnicastRouter(self = s1, coreSeam = s1Recording, lookup = lookup, scope = backgroundScope)
        val r2 = routedUnicastRouter(self = s2, coreSeam = s2Core, lookup = lookup, scope = backgroundScope)
        r2.registerLocalSpoke(bob, s2ToBob)

        val bobReceived = collectInto(bobSeam)

        r1.route(bob, "into-the-void".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertTrue(bobReceived.isEmpty(), "an unattached recipient's frame must be dropped, not delivered")
        assertTrue(s1Recording.sentTo.isEmpty(), "a dropped frame must never cross the core")
        assertEquals(0, s1Recording.broadcastCount, "a dropped frame must never be fanned")
    }

    @Test
    fun wiresRealAttachmentDirectoryLookup() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // End-to-end with slice 5B's real AttachmentDirectory as the lookup: S2 attaches
        // Bob, it replicates to S1, and S1 then routes a unicast for Bob across the core.
        val coreLoom = InMemoryLoom()
        val s1Core = coreLoom.host(Pattern("core"))
        val s2Core = coreLoom.join(InMemoryTag("core"))
        val s1 = s1Core.selfId
        val s2 = s2Core.selfId

        // The directory replicates over its OWN inter-server channel, distinct from the
        // routing channel (single-collection: each concern owns its own seam).
        val dirLoom = InMemoryLoom()
        val s1Dir = dirLoom.host(Pattern("dir"))
        val s2Dir = dirLoom.join(InMemoryTag("dir"))

        val bLoom = InMemoryLoom()
        val s2ToBob = bLoom.host(Pattern("s2-bob"))
        val bobSeam = bLoom.join(InMemoryTag("s2-bob"))

        var t = 0L
        val clock: () -> Long = { ++t }
        val cfg = QuilterConfig(expectVirtualTime = true)
        // `self` is each server's routing identity (its core-seam selfId) — the value the
        // router later addresses over the core — independent of the directory channel's ids.
        val dir1 = attachmentDirectory(self = s1, interServerSeam = s1Dir, scope = backgroundScope, clock = clock, config = cfg)
        val dir2 = attachmentDirectory(self = s2, interServerSeam = s2Dir, scope = backgroundScope, clock = clock, config = cfg)

        dir2.attach(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(s2, dir1.lookup(bob), "precondition: attachment replicated S2 -> S1")

        val r1 = routedUnicastRouter(self = s1, coreSeam = s1Core, lookup = dir1::lookup, scope = backgroundScope)
        val r2 = routedUnicastRouter(self = s2, coreSeam = s2Core, lookup = dir2::lookup, scope = backgroundScope)
        r2.registerLocalSpoke(bob, s2ToBob)

        val bobReceived = collectInto(bobSeam)

        r1.route(bob, "via-directory".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("via-directory"), bobReceived.map { it.decodeToString() })
    }

    /** Collect a seam's incoming into a growing list (single collector, on the test's background scope). */
    private fun TestScope.collectInto(seam: Seam): List<Swatch> {
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { seam.incoming.collect { received += it } }
        return received
    }
}

/**
 * A [Seam] decorator that records every [sendTo] target and counts every [broadcast],
 * delegating all behaviour to [inner]. Lets a test HARD-assert the router's no-fan
 * discipline structurally: exactly which peers it addressed, and that it never
 * broadcast. Test-only double; access is serial under `runTest`.
 */
internal class RecordingSeam(private val inner: Seam) : Seam {
    val sentTo: MutableList<PeerId> = mutableListOf()
    var broadcastCount: Int = 0
        private set

    override val selfId: PeerId get() = inner.selfId
    override val peers: StateFlow<Set<PeerId>> get() = inner.peers
    override val state: StateFlow<SeamState> get() = inner.state
    override val incoming: Flow<Swatch> get() = inner.incoming

    override suspend fun broadcast(payload: ByteArray) {
        broadcastCount++
        inner.broadcast(payload)
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        sentTo += peer
        inner.sendTo(peer, payload)
    }

    override suspend fun close(reason: CloseReason) = inner.close(reason)
}
