@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PayloadTooLarge
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A fabric seam that **publishes** a payload budget ([Seam.maxPayloadBytes]) also **enforces** it:
 * an over-budget payload is refused by the seam, before the frame reaches the wire (#2069).
 *
 * The sibling `SeamPayloadBudgetTest` covers the other half — that the number a seam publishes is
 * derived correctly from the link(s) under it (#2047). Publishing without enforcing is what this
 * file exists for, and the gap it closes was worse than a leaked fabric error:
 *
 *  - `LinkSeam.sendTo` enqueued and returned **success**; its write loop then met the transport's
 *    own oversize error, read it as a remote drop, and tore the **whole seam** down asynchronously.
 *  - `MeshSeam.sendTo` routed that same error into `removePeer`, **evicting a healthy recipient** as
 *    though its link had died — and swallowed the throwable, so nothing said "oversize".
 *
 * Both connection pairs here are built with `enforcesFrameCeiling = true`, so the fake refuses an
 * oversize frame the way a length-prefixed transport does. Without that these tests would pass
 * against a seam with no pre-check at all: the fake would carry the frame, nothing would fail, and
 * the assertion would prove nothing about the layer that refused.
 */
class FabricSeamPayloadRefusalTest {

    /** A generous wedge backstop, not an assertion (#1739, #1891). */
    private val backstop = 30.seconds

    private val ceiling = 1024

    // ---------------------------------------------------------------- LinkSeam (2-peer)

    @Test
    fun aTwoPeerSeamRefusesAnOverBudgetAddressedSendWithoutTearingDown() = runTest(timeout = backstop) {
        val (mine, _) = connectionPair(maxFrameBytes = ceiling, enforcesFrameCeiling = true)
        val seam = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))

        val refusal = assertFailsWith<PayloadTooLarge> { seam.sendTo(PeerId("remote"), ByteArray(ceiling + 1)) }
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(ceiling + 1, refusal.payloadBytes) },
            { assertEquals(ceiling, refusal.budgetBytes, "the link writes the payload verbatim — nothing is reserved") },
            { assertEquals(0, refusal.reservedBytes) },
            {
                assertIs<SeamState.Woven>(
                    seam.state.value,
                    "refusing one payload must not tear the session — the wire was never written",
                )
            },
        )
        seam.close()
    }

    @Test
    fun aTwoPeerSeamDropsAnOverBudgetBroadcastWithoutTearingDown() = runTest(timeout = backstop) {
        val (mine, theirs) = connectionPair(maxFrameBytes = ceiling, enforcesFrameCeiling = true)
        val seam = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))

        // Best-effort by contract: broadcast drops an over-budget payload rather than reporting it.
        seam.broadcast(ByteArray(ceiling + 1))
        testScheduler.runCurrent()

        assertIs<SeamState.Woven>(seam.state.value, "a dropped broadcast must not tear the session")

        // ...and the seam is still usable afterwards: the next in-budget frame reaches the wire.
        val inBudget = ByteArray(ceiling) { 7 }
        seam.broadcast(inBudget)
        assertContentEquals(inBudget, theirs.incoming.first(), "the dropped frame must not have been queued ahead")
        seam.close()
    }

    @Test
    fun aTwoPeerSeamCarriesAPayloadOfExactlyItsBudget() = runTest(timeout = backstop) {
        val (mine, theirs) = connectionPair(maxFrameBytes = ceiling, enforcesFrameCeiling = true)
        val seam = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))

        val atBudget = ByteArray(ceiling) { 3 }
        seam.sendTo(PeerId("remote"), atBudget)

        assertContentEquals(atBudget, theirs.incoming.first(), "the published number is a promise, not a hint")
        seam.close()
    }

    // ---------------------------------------------------------------- MeshSeam (N-peer)

    @Test
    fun aMeshRefusesAnOverBudgetAddressedSendWithoutEvictingTheRecipient() = runTest(timeout = backstop) {
        val mesh = tightAndLooseMesh()

        val refusal = assertFailsWith<PayloadTooLarge> { mesh.seam.sendTo(TIGHT, ByteArray(ceiling + 1)) }
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(ceiling, refusal.budgetBytes, "the budget is THAT link's ceiling, not the mesh minimum") },
            { assertContains(mesh.seam.peers.value, TIGHT, "a payload the caller got wrong is not a dead link") },
            { assertIs<SeamState.Woven>(mesh.seam.state.value) },
        )
        mesh.seam.close()
    }

    /**
     * A mesh broadcast is best-effort **per link**: a payload that fits the loose link but not the
     * tight one still reaches the loose peer, and the tight peer is skipped — not evicted, and not
     * escalated into an error the timer-driven callers of `broadcast` could not survive.
     */
    @Test
    fun aMeshBroadcastSkipsOnlyTheLinksTooTightForIt() = runTest(timeout = backstop) {
        val mesh = tightAndLooseMesh()

        val overTight = ByteArray(ceiling + 1) { 5 }
        mesh.seam.broadcast(overTight)
        val onTheLooseWire = mesh.loose.incoming.first()

        assertAll(
            { assertContentEquals(overTight, onTheLooseWire, "the loose link can carry it") },
            { assertContains(mesh.seam.peers.value, TIGHT, "the tight peer was skipped, not evicted") },
            { assertIs<SeamState.Woven>(mesh.seam.state.value) },
        )
        mesh.seam.close()
    }

    // ---------------------------------------------------------------- harness

    private class TightAndLoose(val seam: Mesh, val loose: Connection)

    /**
     * A 2-link hub mesh: one link with a [ceiling]-byte enforced ceiling, one with eight times that.
     * The loose link's *remote* end comes back too, so a test can read what actually crossed it.
     */
    private suspend fun TestScope.tightAndLooseMesh(): TightAndLoose {
        val dispatcher: CoroutineContext = StandardTestDispatcher(testScheduler)
        val (tightMine, tightTheirs) = connectionPair(maxFrameBytes = ceiling, enforcesFrameCeiling = true)
        val (looseMine, looseTheirs) = connectionPair(maxFrameBytes = ceiling * 8, enforcesFrameCeiling = true)

        val meshDeferred = async {
            hubMesh(PeerId("self"), listOf(tightMine, looseMine), dispatcher, random = Random(0))
        }
        val handshakes = listOf(TIGHT to tightTheirs, LOOSE to looseTheirs).mapIndexed { index, (id, conn) ->
            async {
                conn.send(MeshHello.encode(id, meshNonce(index.toByte())))
                MeshHello.decode(conn.incoming.first())
            }
        }
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }
        return TightAndLoose(mesh, looseTheirs)
    }

    private companion object {
        val TIGHT = PeerId("tight")
        val LOOSE = PeerId("loose")
    }
}
