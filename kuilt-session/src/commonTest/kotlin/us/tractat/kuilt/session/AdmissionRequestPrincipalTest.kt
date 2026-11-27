@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class, SessionInternalApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.admit.AdmissionDecision
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmissionRequest
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The request a host's [AdmissionPolicy] is asked about carries the principal the joiner's link was
 * attested as (#3750): the same value its admitted [Member.principal] then carries, on the relay path
 * (`Seam.withPrincipal`) and the hub path (a `PrincipalRoster` keyed by peer), and `null` on a link
 * nothing attested. Each test reads the principal off the request the policy actually received, so a
 * room that built the request without it reds here while the member still carries it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdmissionRequestPrincipalTest {
    private val zeroClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

    /** A policy that admits everyone and keeps every request it is asked about. */
    private class Recording {
        val asked = mutableListOf<AdmissionRequest>()
        val admission = HostAdmission(
            AdmissionPolicy { request ->
                asked += request
                AdmissionDecision.Admit(GrantTerms(1.hours))
            },
            RevocationList.inMemory(),
        )
    }

    /** A loom whose hosted connections are attested as [principal], as a server loom's are. */
    private fun attesting(mesh: InMemoryLoom, principal: Principal): Loom = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = mesh.weave(rendezvous)
            return if (rendezvous is Rendezvous.New) seam.withPrincipal(principal) else seam
        }
    }

    @Test
    fun theRequestCarriesTheRelayLinksPrincipal() = runTest {
        val mesh = InMemoryLoom()
        val principal = Principal("account-123")
        val recording = Recording()
        val host = LoomRoomHost(attesting(mesh, principal), Pattern("server"), fakeKeyStore(0), clock = zeroClock, admission = recording.admission)
        val hostRoom = CompletableDeferred<Room>()
        val hostJob = backgroundScope.launch { host.start { room -> hostRoom.complete(room) } }
        SeamRoomFactory(mesh, backgroundScope, zeroClock, fakeKeyStore(1)).join(InMemoryTag("server"))

        val member = hostRoom.await().roster.first { it.size == 1 }.single()

        assertAll(
            { assertEquals(1, recording.asked.size, "rig: the policy was asked about the joiner") },
            { assertEquals(principal, member.principal, "rig: the link was attested") },
            { assertEquals(principal, recording.asked.single().principal, "the request carries the link's principal") },
        )
        hostJob.cancel()
    }

    @Test
    fun theRequestCarriesEachHubLinksOwnPrincipal(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) { "no dispatcher in the test's context" }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(42L))
            val recording = Recording()
            val hostRoom = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(0))
                .host(Pattern("room"), admission = recording.admission)
            val ada = Principal("account-ada")
            val bea = Principal("account-bea")
            for ((index, principal) in listOf(ada, bea).withIndex()) {
                val loom = fabric.clientLoom(PeerId("joiner-$index"), Random(index + 1L), principal = principal)
                SeamRoomFactory(loom, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(index + 1)).join(InMemoryTag("room"))
            }

            val roster = hostRoom.roster.first { it.size == 2 }
            val askedByKey = recording.asked.associate { it.key to it.principal }

            assertAll(
                { assertEquals(2, recording.asked.size, "rig: the policy was asked about both joiners") },
                { assertEquals(setOf(ada, bea), roster.map { it.principal }.toSet(), "rig: each link was attested as its own principal") },
                { assertEquals(roster.associate { it.key to it.principal }, askedByKey, "each request carries its own link's principal, as its member does") },
                { assertEquals(ada, askedByKey[fakeDeviceKey(1).id], "Ada's request names Ada's link") },
            )
        }

    /** What [relinkWhileThePolicyDecides] saw once the exchange had ended. */
    private class Relinked(
        val asked: List<AdmissionRequest>,
        val reRegistrations: Int,
        val roster: List<Member>,
        val heardByTheNewLink: List<AdmitMessage>,
        val ended: List<String>,
    )

    /**
     * The policy decides about one link, and another takes its id before it answers (#3773's review).
     * Ada's link says Hello and proves her key. While the policy is asked about her, a second
     * connection, authenticated as Mallory, announces her peer id, which is no secret, and sends one
     * frame and no Hello, so the challenge the policy was asked about stays current. The hub replaces
     * one attested principal with another on purpose (`rosterUpdatesPrincipalOnReconnect`), so the
     * room reads Mallory for that id from then on, and every frame it sends the id reaches Mallory's
     * link. The policy is then released with [firstAnswer], and any later ask is admitted.
     *
     * The rig counts the hub's roster turning to Mallory, read off the hub seam the room was built
     * on, and the policy is released only once it has, so a run in which the link never changed waits
     * out the wedge backstop rather than passing. [check] runs once a pass of the exchange has settled
     * as an outcome that ends it ([ENDING_OUTCOMES]), and whatever that outcome sent has reached
     * Mallory's link.
     */
    private fun relinkWhileThePolicyDecides(firstAnswer: AdmissionDecision, check: (Relinked) -> Unit): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) { "no dispatcher in the test's context" }
            val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(42L))
            val hub = CompletableDeferred<Seam>()
            val capturing = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam =
                    fabric.serverLoom.weave(rendezvous).also { hub.complete(it) }
            }
            val firstAsked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val asked = mutableListOf<AdmissionRequest>()
            val admission = HostAdmission(
                AdmissionPolicy { request ->
                    asked += request
                    if (asked.size > 1) return@AdmissionPolicy AdmissionDecision.Admit(GrantTerms(1.hours))
                    firstAsked.complete(Unit)
                    release.await()
                    firstAnswer
                },
                RevocationList.inMemory(),
            )
            val hostRoom = SeamRoomFactory(capturing, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(0))
                .host(Pattern("room"), admission = admission)
            val settled = MutableStateFlow(emptyList<String>())
            assertNotNull(hostRoom as? SeamRoom, "rig: the host is a SeamRoom").seatSettledForTest = { outcome ->
                settled.update { it + outcome }
            }
            val joiner = PeerId("joiner-0")
            val roster = hub.await() as PrincipalRoster
            val reRegistrations = MutableStateFlow(0)
            backgroundScope.launch {
                roster.attestedPrincipals.map { it[joiner] }.distinctUntilChanged().collect { principal ->
                    if (principal == MALLORY) reRegistrations.update { it + 1 }
                }
            }
            val adaLoom = fabric.clientLoom(joiner, Random(1L), principal = ADA)
            backgroundScope.launch {
                SeamRoomFactory(adaLoom, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(1)).join(InMemoryTag("room"))
            }

            firstAsked.await()
            val malloryChannel = NamedMux(fabric.clientSeam(joiner, Random(2L), principal = MALLORY), backgroundScope).channel("room")
            val heard = mutableListOf<AdmitMessage>()
            backgroundScope.launch {
                malloryChannel.incoming.collect { frame -> AdmitMessage.decode(frame.toByteArray())?.let { heard += it } }
            }
            malloryChannel.broadcast(byteArrayOf())
            reRegistrations.first { it > 0 }
            release.complete(Unit)
            val ended = settled.first { outcomes -> outcomes.any { it in ENDING_OUTCOMES } }.filter { it in ENDING_OUTCOMES }
            testScheduler.runCurrent()

            check(Relinked(asked.toList(), reRegistrations.value, hostRoom.roster.value, heard.toList(), ended))
        }

    /**
     * An admit about Ada's link is not acted on once the id's link is Mallory's: the exchange ends,
     * and nobody is seated under it. Asking again instead would build a request holding Ada's proven
     * key and Mallory's principal, and a policy that gates on the account could then seat Mallory's
     * link under Ada's key. So the policy is asked once, about Ada, and the room seats nobody.
     */
    @Test
    fun anAdmitForALinkAnotherTookOverSeatsNobody(): TestResult =
        relinkWhileThePolicyDecides(AdmissionDecision.Admit(GrantTerms(1.hours))) { seen ->
            assertAll(
                { assertEquals(1, seen.reRegistrations, "rig: the hub re-registered the joiner's id as Mallory while the policy decided") },
                { assertEquals(listOf(ADA), seen.asked.map { it.principal }, "the policy was asked once, about Ada's link") },
                { assertEquals(emptyList(), seen.roster, "nobody is seated under that exchange") },
                { assertEquals(emptyList(), seen.heardByTheNewLink.filterIsInstance<AdmitMessage.Welcome>(), "the link that took the id over is sent no Welcome") },
            )
        }

    /**
     * A refusal about Ada's link is not acted on once the id's link is Mallory's either: the exchange
     * ends with nothing sent, as when a sender leaves mid-exchange, since the only link the id reaches
     * now is the one that never said Hello. Ada gets back in by saying Hello again. Acting on the
     * refusal would send it to Mallory's link, and asking again would ask about Mallory under Ada's
     * key, which is the fail-open this pins against.
     */
    @Test
    fun aRefusalForALinkAnotherTookOverEndsTheExchangeAndSendsNothing(): TestResult =
        relinkWhileThePolicyDecides(AdmissionDecision.Reject(RejectCode.Unknown("not-invited"))) { seen ->
            assertAll(
                { assertEquals(1, seen.reRegistrations, "rig: the hub re-registered the joiner's id as Mallory while the policy decided") },
                { assertEquals(listOf(ADA), seen.asked.map { it.principal }, "the policy was asked once, about Ada's link") },
                { assertEquals(emptyList(), seen.roster, "nobody is seated under that exchange") },
                { assertEquals(emptyList(), seen.heardByTheNewLink.filterIsInstance<AdmitMessage.Reject>(), "the link that took the id over is sent no refusal") },
            )
        }

    /** A seam that reports [attestedPrincipals] beside [inner]'s frames, as a hub does. */
    private class RosteredSeam(inner: Seam, override val attestedPrincipals: StateFlow<Map<PeerId, Principal>>) : Seam by inner, PrincipalRoster

    /**
     * The release arm (Decided 18): Eve claims Bob's id under her own key while Bob's seat is held
     * partitioned, so the policy is asked about her before the seat is released for her. The link at
     * Bob's id is attested anew, as Mallory, while the policy decides, and the policy admits. The admit
     * was about the link Ada's principal named, so it releases nothing: Bob's seat stays held, and
     * nobody is seated in it.
     *
     * The policy flips the roster itself, inside `decide`, so the change lands after the request was
     * built and before the answer is acted on. The flip is counted.
     */
    @Test
    fun anAdmitForALinkAttestedAnewReleasesNoPartitionedSeat() = runTest {
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val bobKey = fakeDeviceKey(1)
        val eveKey = fakeDeviceKey(3)
        val roster = MutableStateFlow(mapOf(bob to ADA))
        val asked = mutableListOf<AdmissionRequest>()
        var flips = 0
        val admission = HostAdmission(
            AdmissionPolicy { request ->
                asked += request
                if (request.key == eveKey.id) {
                    roster.update { it + (bob to MALLORY) }
                    flips++
                }
                AdmissionDecision.Admit(GrantTerms(1.hours))
            },
            RevocationList.inMemory(),
        )
        val host = rawHost(joiners = setOf(bob, carol), admission = admission, adopted = { RosteredSeam(it, roster) })
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, fakeDeviceKey(2), nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, eveKey, nonce = 3) // Eve, under Bob's id
        val seat = host.member(bob)

        assertAll(
            { assertEquals(1, flips, "rig: the link at Bob's id was attested anew while the policy decided about Eve") },
            { assertEquals(listOf(ADA), asked.filter { it.key == eveKey.id }.map { it.principal }, "the policy was asked about Eve once, as the link Ada's principal named") },
            { assertEquals(emptyList(), host.events.edgesSince(mark).map { it.describe() }, "Bob's seat is not released, and nobody is seated") },
            { assertIs<Liveness.Partitioned>(seat?.liveness, "Bob's seat is still held partitioned") },
            { assertEquals(bobKey.id, seat?.key, "under Bob's key") },
        )
    }

    @Test
    fun anUnattestedLinksRequestCarriesNoPrincipal() = runTest {
        val mesh = InMemoryLoom()
        val recording = Recording()
        val host = LoomRoomHost(mesh, Pattern("server"), fakeKeyStore(0), clock = zeroClock, admission = recording.admission)
        val hostRoom = CompletableDeferred<Room>()
        val hostJob = backgroundScope.launch { host.start { room -> hostRoom.complete(room) } }
        SeamRoomFactory(mesh, backgroundScope, zeroClock, fakeKeyStore(1)).join(InMemoryTag("server"))

        hostRoom.await().roster.first { it.size == 1 }

        assertAll(
            { assertEquals(1, recording.asked.size, "rig: the policy was asked about the joiner") },
            { assertNull(recording.asked.single().principal, "nothing attested the link") },
        )
        hostJob.cancel()
    }

    @Test
    fun toStringSaysWhetherALinkWasAttestedButNotAsWhom() {
        val key = fakeDeviceKey(1).id
        val attested = AdmissionRequest(key, "Ada", deviceId = null, principal = Principal("account-secret-42")).toString()
        val unattested = AdmissionRequest(key, "Ada", deviceId = null).toString()

        assertAll(
            { assertFalse("account-secret-42" in attested, "the principal's value is not printed: $attested") },
            { assertTrue("principal=attested" in attested, "an attested link says so: $attested") },
            { assertTrue("principal=none" in unattested, "an unattested one says so: $unattested") },
        )
    }

    private companion object {
        val ADA = Principal("account-ada")
        val MALLORY = Principal("account-mallory")

        /** The outcomes after which an exchange's loop returns. */
        val ENDING_OUTCOMES = setOf("Stale", "Refused", "Admitted", "Restored", "Superseded")
    }
}
