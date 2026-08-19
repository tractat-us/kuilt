@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * A fabric seam surfaces the frame ceiling of the link(s) under it as
 * [us.tractat.kuilt.core.Seam.maxPayloadBytes] (#2047).
 *
 * Neither seam adds a per-frame header — both hand the caller's `payload` to [Connection.send] byte
 * for byte — so the payload budget *is* the frame ceiling here. Subtraction happens further up, in
 * the layers that wrap a payload before it gets this far.
 */
class SeamPayloadBudgetTest {

    /** A generous wedge backstop, not an assertion (#1739, #1891). */
    private val backstop = 30.seconds

    @Test
    fun aTwoPeerSeamReportsItsLinksFrameCeiling() = runTest(timeout = backstop) {
        val (mine, _) = connectionPair(maxFrameBytes = 4096)

        val seam = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))

        assertEquals(4096, seam.maxPayloadBytes, "a 2-peer seam writes the payload verbatim")
        seam.close()
    }

    @Test
    fun aTwoPeerSeamOverALinkThatNamesNoCeilingReportsNone() = runTest(timeout = backstop) {
        val (mine, _) = connectionPair()

        val seam = identified(mine, PeerId("self"), PeerId("remote"), StandardTestDispatcher(testScheduler))

        assertNull(seam.maxPayloadBytes, "unknown, not unbounded — the seam invents nothing")
        seam.close()
    }

    /**
     * A mesh sends one payload to every link verbatim, so its budget is the **tightest** ceiling
     * across them — and a link that names none does not erase what the others do know.
     */
    @Test
    fun aMeshReportsTheTightestCeilingAcrossItsLinks() = runTest(timeout = backstop) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (tightMine, tightTheirs) = connectionPair(maxFrameBytes = 1024)
        val (looseMine, looseTheirs) = connectionPair(maxFrameBytes = 8192)
        val (unknownMine, unknownTheirs) = connectionPair()

        val meshDeferred = async {
            hubMesh(PeerId("self"), listOf(tightMine, looseMine, unknownMine), dispatcher, random = Random(0))
        }
        val handshakes = listOf(
            PeerId("tight") to tightTheirs,
            PeerId("loose") to looseTheirs,
            PeerId("unknown") to unknownTheirs,
        ).mapIndexed { index, (id, conn) ->
            async {
                conn.send(MeshWire.encodeHello(id, meshNonce(index.toByte())))
                meshHelloOf(conn.incoming.first())
            }
        }
        val mesh = meshDeferred.await()
        handshakes.forEach { it.await() }

        // The tightest link ceiling LESS the mesh's own frame-type byte (#2474): what the seam
        // publishes is what a caller may hand it, and the type byte is spent out of that budget
        // rather than added to the wire.
        assertEquals(
            1024 - MeshWire.TYPE_BYTES,
            mesh.maxPayloadBytes,
            "one payload must fit every link, so the tightest wins — less the frame-type byte",
        )
        mesh.close()
    }

    @Test
    fun aMeshWhoseLinksNameNoCeilingReportsNone() = runTest(timeout = backstop) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (mine, theirs) = connectionPair()

        val meshDeferred = async { hubMesh(PeerId("self"), listOf(mine), dispatcher, random = Random(0)) }
        val handshake = async {
            theirs.send(MeshWire.encodeHello(PeerId("other"), meshNonce(0)))
            meshHelloOf(theirs.incoming.first())
        }
        val mesh = meshDeferred.await()
        handshake.await()

        assertNull(mesh.maxPayloadBytes, "no link names a ceiling, so neither does the mesh")
        mesh.close()
    }
}
