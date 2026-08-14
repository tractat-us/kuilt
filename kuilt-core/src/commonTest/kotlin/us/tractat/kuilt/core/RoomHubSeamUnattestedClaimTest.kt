@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The #2357 reproducer, end to end over the real mux-hub path: an **unauthenticated** connection
 * that self-asserts an already-attested peer's [PeerId] must not be able to make the room forget an
 * attestation the host verified.
 *
 * alice joins over a link the host verified as `verified-alice`. mallory then dials a **second,
 * concurrent** link — alice's is never dropped — carrying **no** principal and announcing alice's
 * id, which is not a secret: it is the self-asserted preamble field every peer broadcasts. No
 * credential is needed, and [RoomAuthorizer.authorize] never sees a [Principal], so no deployment
 * can express a policy that refuses this.
 *
 * ## What this test pins, and what it deliberately records as still true
 *
 * The assertion under repair is the roster one: `attestedPrincipals` used to drop alice's entry
 * outright, because `RoomHubSeam.deliver`'s registration block treated a `null` principal as a
 * **remove** rather than a no-op. A consumer reading the roster saw alice as unattested, and nothing
 * anywhere reported that a takeover had occurred.
 *
 * The unicast assertion is the **rig**, and it is not a wish: it asserts that mallory really did
 * capture `sendTo(alice, …)`. That is what makes the roster assertion non-vacuous — the state under
 * test genuinely occurred — and it is simultaneously the honest record of what #2357's chosen fix
 * does **not** cover. Refusing the re-claim outright, binding the id to the attestation, or re-keying
 * the roster on the principal are all behaviour changes with consumer impact and remain open. So
 * after the fix the room can report a peer as attested while routing that peer's unicast to someone
 * else; that is a deliberate, recorded residual, and the reason it is spelled out here rather than
 * left for a reader to discover is that a test asserting only the repaired half would read as a
 * clean bill of health.
 */
class RoomHubSeamUnattestedClaimTest {

    @Test
    fun unattestedClaimCapturesUnicastButCannotEraseTheAttestation() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0))
            val room = fabric.serverLoom.host(Pattern(ROOM))
            val roster = room as PrincipalRoster
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")

            // The room's inbound stream is single-collection (ADR-034): collect it exactly once, into
            // an observable set of frame bodies, so the test can await a specific frame's arrival.
            val heard = MutableStateFlow(emptySet<String>())
            backgroundScope.launch { room.incoming.collect { f -> heard.update { it + f.decodeToString() } } }

            // alice joins over a link the host verified. Her first frame is what registers her.
            val aliceClient = fabric.clientSeam(alice, Random(1), aliceKey)
            NamedMux(aliceClient, backgroundScope).channel(ROOM).broadcast(ALICE_HELLO.encodeToByteArray())
            heard.first { ALICE_HELLO in it }
            assertEquals(
                aliceKey,
                roster.attestedPrincipals.value[alice],
                "rig: the host-verified admission is on the roster before the claim",
            )

            // mallory: a SECOND, concurrent link. alice's client is never closed.
            val malloryClient = fabric.clientSeam(alice, Random(2), principal = null)
            val malloryChannel = NamedMux(malloryClient, backgroundScope).channel(ROOM)
            malloryChannel.broadcast(MALLORY_HELLO.encodeToByteArray())
            heard.first { MALLORY_HELLO in it }

            // Rig: prove mallory captured unicast — `sendTo(alice, …)` now lands on its link. Subscribe
            // before the send: NamedMux channel views are replay-0, so a late subscriber drops the frame.
            val malloryReceived = async { malloryChannel.incoming.first() }
            runCurrent()
            room.sendTo(alice, PROBE)
            val malloryGot = malloryReceived.await()
            val rosterAfter = roster.attestedPrincipals.value

            assertAll(
                {
                    assertEquals(
                        aliceKey,
                        rosterAfter[alice],
                        "an unattested claim must not erase the attestation the host verified",
                    )
                },
                {
                    assertContentEquals(
                        PROBE,
                        malloryGot.toByteArray(),
                        "rig: the unattested link DID capture unicast — the displacement really happened, " +
                            "and #2357's fix deliberately does not close that half",
                    )
                },
            )
        }

    private companion object {
        const val ROOM = "table-7"
        const val ALICE_HELLO = "alice-hello"
        const val MALLORY_HELLO = "mallory-hello"
        val PROBE = byteArrayOf(9, 8, 7)
    }
}
