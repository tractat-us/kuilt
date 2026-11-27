@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class, SessionInternalApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import us.tractat.kuilt.session.admit.GrantTerms
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The policy decides about one principal and the link changes under it before it answers (#3773's
     * review): a second connection, authenticated as someone else, announces the joiner's peer id, which
     * is no secret, and sends one frame and no Hello, so the challenge it is asked about stays current.
     * The hub replaces an attested principal with an attested one on purpose
     * (`rosterUpdatesPrincipalOnReconnect`), so the room reads Mallory for the joiner from then on. An
     * admission must never carry a principal the policy did not approve: whoever is seated, its
     * principal is the one on the last request the policy admitted.
     *
     * The rig counts the hub's roster turning to Mallory, read off the hub seam the room was built on,
     * and the policy is released only after it has, so a run in which the link never changed reds on
     * the rig rather than passing.
     */
    @Test
    fun aPrincipalThatChangesWhileThePolicyDecidesIsNotAdmittedUnderTheOldDecision(): TestResult =
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
            val approved = mutableListOf<AdmissionRequest>()
            val admission = HostAdmission(
                AdmissionPolicy { request ->
                    asked += request
                    if (asked.size == 1) {
                        firstAsked.complete(Unit)
                        release.await()
                    }
                    approved += request
                    AdmissionDecision.Admit(GrantTerms(1.hours))
                },
                RevocationList.inMemory(),
            )
            val hostRoom = SeamRoomFactory(capturing, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(0))
                .host(Pattern("room"), admission = admission)
            val joiner = PeerId("joiner-0")
            val ada = Principal("account-ada")
            val mallory = Principal("account-mallory")
            val roster = hub.await() as PrincipalRoster
            val reRegistrations = MutableStateFlow(0)
            backgroundScope.launch {
                roster.attestedPrincipals.map { it[joiner] }.distinctUntilChanged().collect { principal ->
                    if (principal == mallory) reRegistrations.update { it + 1 }
                }
            }
            val adaLoom = fabric.clientLoom(joiner, Random(1L), principal = ada)
            backgroundScope.launch {
                SeamRoomFactory(adaLoom, backgroundScope, clock = zeroClock, deviceKeyStore = fakeKeyStore(1)).join(InMemoryTag("room"))
            }

            firstAsked.await()
            val malloryLink = fabric.clientSeam(joiner, Random(2L), principal = mallory)
            NamedMux(malloryLink, backgroundScope).channel("room").broadcast(byteArrayOf())
            reRegistrations.first { it > 0 }
            release.complete(Unit)
            val member = hostRoom.roster.first { it.isNotEmpty() }.single()

            assertAll(
                { assertEquals(ada, asked.first().principal, "rig: the policy was first asked about Ada's link") },
                { assertEquals(1, reRegistrations.value, "rig: the hub re-registered the joiner's id as Mallory while the policy decided") },
                { assertEquals(approved.last().principal, member.principal, "the member carries the principal the policy last approved") },
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
}
