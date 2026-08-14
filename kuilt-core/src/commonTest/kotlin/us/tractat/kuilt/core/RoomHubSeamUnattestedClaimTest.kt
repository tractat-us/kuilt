@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2357, end to end over the real mux-hub path: **a live, host-verified link cannot be dispossessed
 * of its peer identity by a second link the host verified as nothing.**
 *
 * alice joins over a link the host verified as `verified-alice`. mallory then dials a **second,
 * concurrent** link — alice's is never dropped — carrying **no** principal and announcing alice's
 * id, which is not a secret: it is the self-asserted preamble field every peer broadcasts. No
 * credential is needed, and [RoomAuthorizer.authorize] never sees a [Principal], so no deployment
 * can write the policy that would refuse this. It has to be refused in code.
 *
 * ## Why the room refuses and the mesh does not
 *
 * A mux hub holds **two live links for one peer id at once** — mallory registers alongside alice,
 * and alice's connection stays open throughout. So the hub has to choose, and choosing the
 * unattested newcomer means a verified peer is silently shadowed: `attestedPrincipals` used to drop
 * alice's entry outright, while `registered[alice]` became mallory's sender, so `sendTo(alice, …)`
 * delivered to the impostor. Fixing only the roster half would have been worse than either: the room
 * would confidently report `alice → verified-alice` while the bytes went to mallory, turning an
 * accidental fail-**closed** into a fail-**open** for the consumer that gates a send on the roster.
 * Both halves are the same act — dispossession — so both are refused together.
 *
 * A mesh cannot do this. Duplicate links to one id are collapsed by a canonical-nonce tiebreak whose
 * whole point is that both ends derive the same survivor with no coordination, and the loser is
 * **closed** — so exactly one link ever exists for an id there, and a local attestation veto would
 * have each end keep a different link and close the one its peer kept. Its defence is deployment
 * policy, which it *can* express because `LinkAdmission` receives the principal
 * (`MeshAdmissionTest.bindingMismatchIsRejectedBeforeDedupLottery`). Hence no shared conformance
 * property; see `PrincipalAttestationConformanceSuite`'s "What is deliberately NOT here".
 *
 * ## The refusal is silent, deliberately
 *
 * It is structural and identical to an authorizer denial — not registered, not in [Seam.peers],
 * frame dropped — and nothing is logged, because `kuilt-core` is logger-free by contract and the one
 * caller of `deliver` is a per-connection read loop that a throw would tear down (and with it, on a
 * non-supervised scope, more than the offending connection). The cost is real and worth naming: a
 * deployment cannot tell it is under attack. The nearest signal it does get is that its
 * [RoomAuthorizer] is invoked for every refused frame — which is exactly what [authorizeCalls]
 * counts below, and is what makes the negative assertions here non-vacuous.
 */
class RoomHubSeamUnattestedClaimTest {

    @Test
    fun unattestedClaimCannotDispossessAnAttestedLink() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
            val authorizeCalls = atomic(0)
            val fabric = InMemoryRoomFabric(
                backgroundScope,
                dispatcher,
                authorizer = RoomAuthorizer { _, _ -> authorizeCalls.incrementAndGet(); true },
                random = Random(0),
            )
            val room = fabric.serverLoom.host(Pattern(ROOM))
            val roster = room as PrincipalRoster
            val alice = PeerId("alice")
            val aliceKey = Principal("verified-alice")

            // The room's inbound stream is single-collection (ADR-034): collect it exactly once, into
            // an observable set of frame bodies, so the test can await — and later refute — arrivals.
            val heard = MutableStateFlow(emptySet<String>())
            backgroundScope.launch { room.incoming.collect { f -> heard.update { it + f.decodeToString() } } }

            // alice joins over a link the host verified. Her first frame is what registers her.
            val aliceClient = fabric.clientSeam(alice, Random(1), aliceKey)
            val aliceChannel = NamedMux(aliceClient, backgroundScope).channel(ROOM)
            aliceChannel.broadcast(ALICE_HELLO.encodeToByteArray())
            heard.first { ALICE_HELLO in it }
            assertEquals(
                aliceKey,
                roster.attestedPrincipals.value[alice],
                "rig: the host-verified admission is on the roster before the claim",
            )
            val callsAfterAlice = authorizeCalls.value

            // mallory: a SECOND, concurrent link claiming alice's id. alice's client is never closed.
            val malloryClient = fabric.clientSeam(alice, Random(2), principal = null)
            val malloryChannel = NamedMux(malloryClient, backgroundScope).channel(ROOM)
            malloryChannel.broadcast(MALLORY_HELLO.encodeToByteArray())
            testScheduler.runCurrent()

            // Routing must still reach ALICE. Both ends are COLLECTED rather than awaited: `await()`
            // on the end that should receive turns the unfixed behaviour into a 30 s wedge instead of
            // a legible red — measured, not guessed. Collecting both makes every arm a set membership
            // check that cannot hang. Subscribe before the send: NamedMux views are replay-0.
            val aliceGot = backgroundScope.bodies(aliceChannel)
            val malloryGot = backgroundScope.bodies(malloryChannel)
            runCurrent()
            room.sendTo(alice, PROBE.encodeToByteArray())
            testScheduler.runCurrent()
            val rosterAfter = roster.attestedPrincipals.value

            assertAll(
                // The rig, and the reason the three negatives below are not vacuous: mallory's frame
                // really did reach the hub's admission path. Without this, a test in which mallory
                // never connected at all would pass every other assertion here.
                {
                    assertTrue(
                        authorizeCalls.value > callsAfterAlice,
                        "rig: the claimant's frame reached the room's admission gate and was refused there",
                    )
                },
                {
                    assertEquals(
                        aliceKey,
                        rosterAfter[alice],
                        "the attested link keeps its attestation — an unattested claim cannot erase it",
                    )
                },
                {
                    assertTrue(
                        PROBE in aliceGot.value,
                        "the attested link keeps its ROUTING — sendTo must reach alice, not the claimant",
                    )
                },
                {
                    assertFalse(
                        PROBE in malloryGot.value,
                        "the claimant must not capture alice's unicast",
                    )
                },
                {
                    assertFalse(
                        MALLORY_HELLO in heard.value,
                        "a refused claimant's frame is dropped, exactly as an authorizer denial's is",
                    )
                },
                { assertEquals(setOf(PeerId("server"), alice), room.peers.value, "the claimant never joins the roster") },
            )
        }

    /**
     * The converse, and the guard's blast radius: a deployment that attests **nothing** is completely
     * unaffected. Nothing is ever put in the room's principal map, so the refusal can never fire, and
     * a second link claiming a live peer's id takes over exactly as it did before #2357.
     *
     * This is not an endorsement of that takeover — it is the statement that #2357's fix is scoped to
     * *protecting an attestation* and changes no behaviour where there is none to protect. Asserting
     * it is what stops a future tightening of the guard from silently breaking every open deployment.
     */
    @Test
    fun anUnattestedDeploymentIsUnaffected() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0))
            val room = fabric.serverLoom.host(Pattern(ROOM))
            val alice = PeerId("alice")

            val heard = MutableStateFlow(emptySet<String>())
            backgroundScope.launch { room.incoming.collect { f -> heard.update { it + f.decodeToString() } } }

            val first = fabric.clientSeam(alice, Random(1), principal = null)
            NamedMux(first, backgroundScope).channel(ROOM).broadcast(ALICE_HELLO.encodeToByteArray())
            heard.first { ALICE_HELLO in it }

            val second = fabric.clientSeam(alice, Random(2), principal = null)
            val secondChannel = NamedMux(second, backgroundScope).channel(ROOM)
            secondChannel.broadcast(MALLORY_HELLO.encodeToByteArray())
            heard.first { MALLORY_HELLO in it }

            val secondGot = backgroundScope.bodies(secondChannel)
            runCurrent()
            room.sendTo(alice, PROBE.encodeToByteArray())
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        PROBE in secondGot.value,
                        "with nothing attested there is nothing to protect: the later link still takes the id",
                    )
                },
                {
                    assertTrue(
                        (room as PrincipalRoster).attestedPrincipals.value.isEmpty(),
                        "rig: this deployment really did attest nothing",
                    )
                },
            )
        }

    /**
     * The invariant the dispossession guard rests on, checked after **every** step of a
     * representative lifecycle: `principals.keys ⊆ registered.keys`.
     *
     * The guard reads `connPeerId in principals` and treats a hit as "a live attested link exists for
     * this id". That is only sound while the containment holds. Break it and the guard fails in both
     * directions silently: a **stale** entry refuses a legitimate rejoin (see
     * [theGuardDoesNotOutliveTheConnectionItProtects], which pins that half behaviourally), and a
     * **missing** one lets a dispossession through. Neither shows up as a roster or membership
     * failure — the maps simply drift apart — so it needs its own assertion.
     *
     * It is checked over a *sequence* rather than at one point, because the failure mode is a future
     * edit adding a `principals` write on a path that does not register (or dropping a removal on a
     * path that deregisters). A single-point assertion would only cover the path it happened to run.
     *
     * **No internals are exposed to test this.** `_peers` is mutated in lockstep with `registered` at
     * all three sites — added together in the registration block, removed together in `deregister`,
     * cleared together in `close` — so `peers - selfId` *is* `registered.keys`, and the containment
     * is observable through the public [PrincipalRoster] and [Seam.peers] surfaces alone. That the
     * check is public-surface-only is also its one limitation: drift confined to the private map and
     * never published (a `principals.clear()` dropped from `close`, say) is invisible here.
     */
    @Test
    fun theRosterNeverNamesAPeerThatIsNotRegistered() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val room = RoomHubSeam(ROOM, PeerId("server"), RoomAuthorizer.AllowAll)
            // Drain the inbound spool: it is bounded, and a sequence of delivers with no consumer
            // would backpressure into a wedge rather than a failure.
            backgroundScope.launch { room.incoming.collect { } }

            val alice = PeerId("alice")
            val bob = PeerId("bob")
            val aliceLink = OutboundSender { }
            val aliceRelink = OutboundSender { }
            val aliceRejoin = OutboundSender { }
            val bobLink = OutboundSender { }
            val claimantLink = OutboundSender { }
            var everAttested = false

            fun invariant(step: String) {
                val roster = room.attestedPrincipals.value.keys
                val registered = room.peers.value - room.selfId
                if (roster.isNotEmpty()) everAttested = true
                assertTrue(
                    registered.containsAll(roster),
                    "$step: the roster names ${roster - registered}, which is not registered — " +
                        "an entry in `principals` must mean a LIVE attested link, because that is " +
                        "exactly what the dispossession guard reads (#2357)",
                )
            }

            invariant("fresh hub")
            room.deliver(alice, frame(), aliceLink, Principal("verified-alice"))
            invariant("attested peer joins")
            room.deliver(bob, frame(), bobLink, null)
            invariant("unattested peer joins")
            room.deliver(alice, frame(), claimantLink, null)
            invariant("unattested claim on the attested id is refused")
            room.deliver(alice, frame(), aliceRelink, Principal("verified-alice-2"))
            invariant("attested peer re-registers, attested")
            room.deregister(bob, bobLink)
            invariant("unattested peer leaves")
            room.deregister(alice, aliceRelink)
            invariant("attested peer leaves")
            room.deliver(alice, frame(), aliceRejoin, Principal("verified-alice-3"))
            invariant("attested peer rejoins")
            room.close()
            invariant("hub closed")

            assertTrue(everAttested, "rig: the sequence really did put someone on the roster")
        }

    /**
     * The guard protects a **live** attested link, never an id in perpetuity. Once alice's link
     * tears, her id is free again — including to an unattested link, which is simply her returning
     * over a connection the host did not verify.
     *
     * This is the behavioural half of the containment invariant, and the failure it guards against is
     * the one that hurts a *legitimate* user rather than an attacker: a `principals` entry that
     * outlives its registration turns the guard into a permanent lockout, and the peer's only symptom
     * is that rejoining silently stops working. Adding a refusal to a hot path is exactly the change
     * that risks this, so it is asserted rather than reasoned about.
     */
    @Test
    fun theGuardDoesNotOutliveTheConnectionItProtects() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val room = RoomHubSeam(ROOM, PeerId("server"), RoomAuthorizer.AllowAll)
            backgroundScope.launch { room.incoming.collect { } }
            val alice = PeerId("alice")
            val attestedLink = OutboundSender { }
            val plainLink = OutboundSender { }

            room.deliver(alice, frame(), attestedLink, Principal("verified-alice"))
            assertEquals(
                Principal("verified-alice"),
                room.attestedPrincipals.value[alice],
                "rig: alice is attested, so the guard is armed for her id",
            )

            room.deregister(alice, attestedLink) // her link tears
            room.deliver(alice, frame(), plainLink, null) // she comes back, unattested this time

            assertAll(
                {
                    assertTrue(
                        alice in room.peers.value,
                        "a peer whose link dropped must be able to rejoin unattested — the guard " +
                            "defends a live attested link, not the id forever",
                    )
                },
                {
                    assertTrue(
                        room.attestedPrincipals.value.isEmpty(),
                        "…and rejoining unattested leaves her unattested, with nothing carried over",
                    )
                },
            )
        }

    private fun frame() = Swatch(byteArrayOf(1))

    /**
     * Collect [seam]'s inbound frame bodies into a growing, observable set.
     *
     * Deliberately not `async { seam.incoming.first() }`: an await on the end that is *supposed* to
     * receive turns "the wrong end got it" into a [TEST_WEDGE_BACKSTOP] wedge rather than a legible
     * assertion failure — which is exactly what the unfixed behaviour produced here before this was
     * restructured. A set that only grows makes both the positive and the negative arm a membership
     * check, and neither can hang.
     */
    private fun CoroutineScope.bodies(seam: Seam): StateFlow<Set<String>> {
        val seen = MutableStateFlow(emptySet<String>())
        launch { seam.incoming.collect { frame -> seen.update { it + frame.decodeToString() } } }
        return seen.asStateFlow()
    }

    private companion object {
        const val ROOM = "table-7"
        const val ALICE_HELLO = "alice-hello"
        const val MALLORY_HELLO = "mallory-hello"
        const val PROBE = "probe"
    }
}
