package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrincipalConnectionTest {

    @Test
    fun nullPrincipalReturnsReceiverUnwrapped() {
        val (conn, _) = connectionPair()
        assertSame(conn, conn.withPrincipal(null), "an unattested connection is never wrapped")
    }

    @Test
    fun principalRidesTheConnectionAndDelegatesFrames() = runTest {
        val (mine, theirs) = connectionPair()
        val principal = Principal("user-42")
        val attested = mine.withPrincipal(principal)

        val reported = assertIs<PrincipalAttested>(attested, "wrapped connection must report attestation")

        // The wrapper is a transparent Connection: frames flow both ways unchanged.
        val outbound = byteArrayOf(1, 2, 3)
        attested.send(outbound)
        val receivedFar = theirs.incoming.first()
        val inbound = byteArrayOf(4, 5)
        theirs.send(inbound)
        val receivedNear = attested.incoming.first()

        assertAll(
            { assertEquals(principal, reported.principal) },
            { assertContentEquals(outbound, receivedFar, "send must delegate to the inner connection") },
            { assertContentEquals(inbound, receivedNear, "incoming must delegate to the inner connection") },
        )
    }

    @Test
    fun closeDelegatesToInnerConnection() = runTest {
        val (mine, theirs) = connectionPair()
        val attested: Connection = mine.withPrincipal(Principal("user-42"))
        attested.close()
        // Closing the wrapper closes the inner connection's outbound spool: the far end's
        // incoming completes without emitting.
        assertTrue(theirs.incoming.toList().isEmpty(), "far end must observe the close as completion")
    }
}
