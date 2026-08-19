@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [Connection] implementation's obligation to the mesh's **graceful displacement drain** (#2474).
 *
 * ## The gap this exists to close
 * A full mesh double-dials, so two connections to one peer are routine and one of them loses. Before
 * #2474 the loser was closed on the spot, and whatever the remote had written into the
 * publish-then-swap window went with it **unless that connection's close happened to be an orderly
 * flush**. Delivery was therefore a property of the transport rather than of the seam, and nothing
 * anywhere reddened when a transport stopped flushing — the definition of an unpinned guarantee.
 *
 * Since #2474 the seam holds the losing link open, reads it to the remote's in-band goodbye, and only
 * then closes it, so the guarantee no longer rests on close semantics at all. **This suite is what
 * says so**, by holding it against a connection that flushes nothing.
 *
 * ## The fixture hook is NOT nullable, deliberately
 * [newAbruptClosingConnectionPair] is a plain abstract member with no opt-out. A backend that "cannot
 * reach an abrupt close" would be declaring unreachable the very state this property exists for,
 * which moves the vacuity one level up where it is harder to see rather than removing it. Every
 * backend can supply one, because abruptness is a property of the **fixture**, not of the production
 * transport: wrap the real connection, or hand back an in-memory pair whose close discards.
 *
 * And because a fixture that quietly *did* flush would make [aDisplacedLinksTailSurvivesAnAbruptClose]
 * pass for the wrong reason, that property is preceded by [theFixtureReallyDiscardsOnClose], which
 * measures the fixture itself and fails by name rather than quietly.
 *
 * ## Why it lives here rather than in `:kuilt-conformance`
 * Driving the window deterministically needs a **hand-driven** far end: a second real mesh stops
 * writing to the loser the moment it dedups, so the only frames it could put in the window are ones
 * already delivered before the swap — a fixture configured at exactly the value where the property
 * cannot fail. A hand-driven far end has to speak `MeshWire`, which is `internal`. Publishing a wire
 * to make one TCK portable is the worse trade, so the suite sits in the module that can see it.
 *
 * The cost is stated rather than hidden: only same-module subclasses can be held to this, so an
 * out-of-tree `Connection` gets the guarantee but no obligation. What the guarantee *rests* on — the
 * seam closing nothing until the goodbye — is transport-independent by construction, which is why
 * that trade is affordable.
 *
 * ## Mutation receipt
 * JVM, source-changed so every row executed. **real** = a defect that could ship; **rig** = a
 * mutation of this suite's own fixture, checking each part is load-bearing.
 *
 * | # | Mutation | Kind | verdict |
 * |---|----------|------|---------|
 * | 1 | `addLink`'s replace arm closes the loser on the spot instead of draining it | real | **RED — [aDisplacedLinksTailSurvivesAnAbruptClose]**, delivering `[[11,1]]`: the whole tail gone |
 * | 2 | [newAbruptClosingConnectionPair] returns the well-behaved `connectionPair()` | rig | **RED — [theFixtureReallyDiscardsOnClose]** |
 * | 3 | rows 1 **and** 2 together | rig | **both RED** — the drain property still reds, same `[[11,1]]` |
 *
 * **Row 3 is the honest reading, and it corrects what row 2 looks like it is saying.** A flushing
 * fixture does NOT make the drain property vacuous today, because [singleCollection] — which every
 * mesh link is wrapped in, whatever the delegate — cancels its republishing pump before closing that
 * delegate, so the tail dies at the wrapper no matter how carefully the transport underneath flushes.
 *
 * What [theFixtureReallyDiscardsOnClose] is for, then, is the *future*: making `singleCollection`
 * flush on close is a plausible and locally sensible change, and it would silently re-satisfy the
 * property above from the transport instead of from the seam — which is precisely how this guarantee
 * came to be unpinned in the first place. The precondition keeps the fixture's abruptness a checked
 * fact rather than an inherited accident.
 */
internal abstract class MeshDisplacementDrainConformanceSuite {

    /**
     * Two connected [Connection]s whose `close` is **abrupt**: closing either end must complete that
     * end's [Connection.incoming] at once, discarding anything the peer has already sent and this end
     * has not yet collected.
     *
     * Called once per link; a property needing two links calls it twice. Both ends must be fresh and
     * unread — the mesh wraps its end before reading it.
     *
     * Non-nullable on purpose; see the class KDoc.
     */
    abstract fun newAbruptClosingConnectionPair(): Pair<Connection, Connection>

    /**
     * The precondition of [aDisplacedLinksTailSurvivesAnAbruptClose], asserted rather than assumed.
     *
     * A backend handing back a well-behaved, flushing pair would make that property pass without
     * testing anything: the tail would arrive because the *connection* delivered it, not because the
     * seam drained it. This measures the fixture directly — send, close, then collect — so such a
     * backend fails here instead of passing quietly there.
     */
    @Test
    fun theFixtureReallyDiscardsOnClose(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (mine, theirs) = newAbruptClosingConnectionPair()

        theirs.send(byteArrayOf(1))
        theirs.send(byteArrayOf(2))
        mine.close()

        val survived = mutableListOf<ByteArray>()
        backgroundScope.launch { mine.incoming.collect { survived += it } }
        runCurrent()

        assertTrue(
            survived.isEmpty(),
            "harness contract: newAbruptClosingConnectionPair must return a pair whose close DISCARDS " +
                "what the peer already sent. This one delivered ${survived.size} frame(s) after close, so " +
                "the drain property below would be satisfied by the connection rather than by the seam",
        )
    }

    /**
     * **The property.** A mesh displaces a link, the remote keeps writing to it for a moment longer,
     * and every one of those frames is still delivered — in send order, ahead of the surviving link's
     * — even though closing that link would have thrown them away.
     *
     * The interleave is load-bearing: `TAIL_1` and `AFTER_SWAP` are handed to their links in that
     * order with a scheduler step between `AFTER_SWAP` and `TAIL_2`, so a seam that drains but does
     * not hold ordering delivers `AFTER_SWAP` in the middle, and a seam that does neither delivers no
     * tail at all.
     */
    @Test
    fun aDisplacedLinksTailSurvivesAnAbruptClose(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val mesh = hubMesh(SELF, emptyList(), StandardTestDispatcher(testScheduler), Random(0))

        val received = mutableListOf<Swatch>()
        backgroundScope.launch { mesh.incoming.collect { received += it } }
        runCurrent()

        // The peer is published on `first`, then `second` wins the canonical-nonce tiebreak (all-zero
        // beats all-0xFF) and displaces it. `first` is now the drained loser.
        val first = admit(mesh, HIGH_NONCE)
        val second = admit(mesh, LOW_NONCE)

        assertTrue(
            PEER in mesh.peers.value,
            "precondition: the peer must be in the roster over BOTH links, or nothing was ever displaced",
        )

        first.send(MeshWire.encodeData(TAIL_1))
        second.send(MeshWire.encodeData(AFTER_SWAP))
        runCurrent()
        first.send(MeshWire.encodeData(TAIL_2))
        first.send(MeshWire.encodeGoodbye())
        runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf(TAIL_1.toList(), TAIL_2.toList(), AFTER_SWAP.toList()),
                    received.map { it.toByteArray().toList() },
                    "a displaced link must be DRAINED to its goodbye, not closed: a missing tail frame is " +
                        "the abrupt close showing through, and AFTER_SWAP in the middle is a drain with no " +
                        "receiver ordering hold behind it",
                )
            },
            {
                assertTrue(
                    received.all { it.sender == PEER },
                    "a drained link's frames stay attributed to their peer — draining is not anonymising",
                )
            },
        )
        mesh.close()
    }

    /**
     * Admit one more link to [PEER], driving its far end through the mesh handshake with [nonce], and
     * return the far end so the caller can write to it.
     *
     * The reply is emitted from a collector started UNDISPATCHED **before** `addLink`, since a
     * handshake is a crossing pair of preambles: a reply registered afterwards can miss the mesh's own.
     */
    private fun TestScope.admit(mesh: Mesh, nonce: ByteArray): Connection {
        val (meshEnd, farEnd) = newAbruptClosingConnectionPair()
        val replied = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            farEnd.incoming.first() // the mesh's own preamble
            farEnd.send(MeshWire.encodeHello(PEER, nonce))
        }
        backgroundScope.launch { mesh.addLink(meshEnd) }
        testScheduler.runCurrent()
        check(replied.isCompleted) { "harness: the mesh's handshake preamble never reached the far end" }
        return farEnd
    }

    private companion object {
        val SELF = PeerId("conformance-self")
        val PEER = PeerId("conformance-peer")

        val LOW_NONCE = ByteArray(MESH_NONCE_BYTES) { 0x00 }
        val HIGH_NONCE = ByteArray(MESH_NONCE_BYTES) { 0xFF.toByte() }

        val TAIL_1 = byteArrayOf(0x0a, 1)
        val TAIL_2 = byteArrayOf(0x0a, 2)
        val AFTER_SWAP = byteArrayOf(0x0b, 1)
    }
}
