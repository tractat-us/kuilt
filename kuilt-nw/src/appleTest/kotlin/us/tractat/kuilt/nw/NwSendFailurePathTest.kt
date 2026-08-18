package us.tractat.kuilt.nw

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the **emission** half of #2455: `RealNwApi.send` must report an immediately-known failure by
 * throwing [NwSendFailedException], rather than logging the dead id at debug and returning normally.
 *
 * Before the fix this binding had no failure path at all, so `NwSeam.sendTo`/`broadcast`'s
 * `.onFailure { … removeByConn(connId) }` was unreachable against the real fabric: a frame written onto a
 * connection the remote had already destroyed was silently discarded, and "no send error in the log" was
 * evidence of nothing. This is the half no fake can prove — `NwSeamTest`'s
 * `aSendOntoALinkTheRemoteAlreadyDestroyedEvictsThePeer` proves the seam's REACTION under virtual time,
 * but only against `FakeNwApi`, which is why it could sit green while this binding could not fail.
 *
 * Both cases run with no live `nw_connection` and no socket: an unknown id needs no registry entry at all,
 * and a closed one is produced by [RealNwApi.registerInertConnectionForTest] plus the production
 * [RealNwApi.driveCloseForTest] path — so nothing arms an async GCD callback that could outlive the test.
 */
class NwSendFailurePathTest {

    private companion object {
        const val ROOM_KEY = "send-failure-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
        val FRAME = byteArrayOf(1, 2, 3, 4)
    }

    @Test
    fun sendToAnUnknownConnectionIdThrows() = runTest {
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))

        val failure = assertFailsWith<NwSendFailedException> {
            api.send(NwConnectionId("nw-never-registered"), FRAME)
        }

        assertTrue(
            "nw-never-registered" in (failure.message ?: ""),
            "the failure must name the connection the caller addressed — was '${failure.message}'",
        )
    }

    @Test
    fun sendToAConnectionThisBindingAlreadyClosedThrows() = runTest {
        // The field shape (#2425): the connection existed, then died. The registry entry is gone, so the
        // send has nowhere to go — and before #2455 that returned normally, which is how 182 bytes went
        // onto a destroyed connection with nothing recording a failure and no retry.
        val api = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE))
        val id = api.registerInertConnectionForTest(endpoint = null)
        api.markClosingForTest(id)
        api.driveCloseForTest(id, failed = false)

        // Precondition: this id really is closed, so the assertion below cannot pass for the trivial
        // reason that it was never registered — the closed case is a distinct code path from the unknown one.
        assertTrue(
            api.connectionStates.value[id] is NwConnState.Closed,
            "rig check: the connection must be latched Closed before the send, was ${api.connectionStates.value[id]}",
        )

        assertFailsWith<NwSendFailedException> { api.send(id, FRAME) }
    }
}
