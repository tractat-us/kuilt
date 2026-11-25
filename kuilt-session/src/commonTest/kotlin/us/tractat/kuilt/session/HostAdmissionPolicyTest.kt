@file:OptIn(
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
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
import us.tractat.kuilt.session.election.ElectionOutcome
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The host's [AdmissionPolicy] decides every newcomer, and nothing else (#3220).
 *
 * A newcomer is a proven key with no seat to restore: an ordinary admission, or an additional seat
 * for another live process of a device (plan Amendment C). A restore — the same seat back under the
 * same id or a new one — never asks. Each test counts the policy's calls, so a restore that asked, or
 * a newcomer let in unasked, reds on the count.
 *
 * The policy is the app's own code, so the room must survive whatever it does: throw, mint its own
 * cancellation, or never answer. And its answer is about one challenge: an answer that comes back
 * after a newer Hello replaced that challenge seats nobody and refuses nobody.
 */
class HostAdmissionPolicyTest {
    private val bob = PeerId("bob")
    private val bobTab = PeerId("bob-tab")
    private val carol = PeerId("carol")
    private val eve = PeerId("eve")

    private val bobKey: DeviceKey = fakeDeviceKey(1)
    private val carolKey: DeviceKey = fakeDeviceKey(2)
    private val eveKey: DeviceKey = fakeDeviceKey(3)

    private val admitAll: AdmissionDecision = AdmissionDecision.Admit(GrantTerms(readmitFor = 1.hours))

    /** A policy that records every request it is asked about, then answers with [answer]. */
    private class Recorded(private val answer: suspend (AdmissionRequest) -> AdmissionDecision) {
        val asked = mutableListOf<AdmissionRequest>()
        val revocations: RevocationList = RevocationList.inMemory()
        val admission = HostAdmission(
            policy = AdmissionPolicy { request ->
                asked += request
                answer(request)
            },
            revocations = revocations,
        )

        fun keys(): List<DidKey> = asked.map { it.key }
    }

    private fun List<MembershipEvent>.render() = map { it.describe() }

    private fun RawHost.directWelcomesTo(peer: PeerId): List<AdmitMessage.Welcome> =
        sentTo(peer).welcomesFor(peer).filter { it.membership != null }

    // ── What the policy decides ──────────────────────────────────────────────

    /**
     * A refusal reaches the joiner with the policy's own code, answering the Hello it refuses, and
     * seats nobody.
     */
    @Test
    fun policyRejectReachesJoinerWithItsCode() = runTest {
        val notInvited = RejectCode.Unknown("not-invited")
        val recorded = Recorded { AdmissionDecision.Reject(notInvited) }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)

        val rejects = host.rejectsTo(bob)
        assertAll(
            { assertEquals(listOf(bobKey.id), recorded.keys(), "rig: the policy was asked about Bob, once") },
            { assertEquals(listOf<RejectCode>(notInvited), rejects.map { it.code }, "Bob hears the policy's code") },
            { assertTrue(rejects.single().answers?.contentEquals(host.hello("bob", bobKey.id, 1).joinerNonce) == true, "the refusal names Bob's Hello") },
            { assertTrue(host.room.roster.value.none { it.id == bob }, "a refusal seats nobody") },
            { assertTrue(host.events.since(0).none { it is MembershipEvent.Joined }, "no Joined") },
        )
    }

    /** The same refusal, seen by a real joiner: it ends `AdmissionFailed(Rejected)` with the code's id. */
    @Test
    fun aRealJoinerRefusedByThePolicyEndsRejected() = runTest {
        val recorded = Recorded { AdmissionDecision.Reject(RejectCode.Unknown("not-invited")) }
        val loom = InMemoryLoom()
        val host = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(HOST_DEVICE))
            .host(Pattern("room"), memberName = "Host", admission = recorded.admission)
        val joiner = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(1)).join(InMemoryTag("room"), memberName = "Bob")

        val failed = joiner.events.filterIsInstance<MembershipEvent.AdmissionFailed>().first()

        assertAll(
            { assertEquals(1, recorded.asked.size, "rig: the host reached the policy, so the proof was verified") },
            { assertEquals<RejectCode?>(RejectCode.Unknown("not-invited"), (failed.reason as? AdmissionFailure.Rejected)?.code, "refused with the policy's code: $failed") },
            { assertTrue(host.roster.value.isEmpty(), "nobody was admitted") },
        )
        joiner.leave()
        host.leave()
    }

    /**
     * `Admit` seats the newcomer: one `Joined`, one direct `Welcome`. The pass its terms buy is
     * `GrantIssueTest`'s.
     */
    @Test
    fun policyAdmitSeatsTheNewcomer() = runTest {
        val recorded = Recorded { admitAll }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)

        assertAll(
            { assertEquals(listOf(bobKey.id), recorded.keys(), "the policy was asked about Bob, once") },
            { assertEquals(listOf("Joined(bob)"), host.events.edgesSince(0).render()) },
            { assertEquals(1, host.directWelcomesTo(bob).size, "one direct Welcome") },
        )
    }

    // ── What it never decides ────────────────────────────────────────────────

    /**
     * Every restore arm of the key → seat table, one after another on one host, and the policy asked
     * only once: when Bob was a newcomer. A healed link under the same id, a retry to a connected
     * seat, and a rebuilt process under a fresh id that takes the partitioned seat over.
     */
    @Test
    fun restoresNeverCallThePolicy() = runTest {
        val recorded = Recorded { admitAll }
        val host = rawHost(joiners = setOf(bob, bobTab, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        val vettedAtStart = recorded.keys()

        host.partition(bob, alive = setOf(carol))
        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 3) // same id, partitioned seat
        val afterPartitionedRestore = host.events.since(mark).render()
        host.admit(bob, bobKey, nonce = 4) // same id, connected seat
        host.partition(bob, alive = setOf(carol))
        val takeoverMark = host.events.mark()
        host.admit(bobTab, bobKey, nonce = 5) // fresh id, the key's partitioned seat

        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id), vettedAtStart, "rig: each newcomer was vetted once") },
            { assertTrue("Resumed(bob)" in afterPartitionedRestore, "rig: the partitioned seat was restored: $afterPartitionedRestore") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.Superseded(bobTab)})", "Joined(bob-tab)"),
                    host.events.edgesSince(takeoverMark).render(),
                    "rig: the fresh id took the seat over",
                )
            },
            { assertEquals(vettedAtStart, recorded.keys(), "no restore asked the policy") },
        )
    }

    /**
     * The lobby's host entry point is `ElectionLobby.start` (census P1): the room the elected host
     * adopts decides its members with the admission `start` was given. The member waiting in
     * `awaitRoom` supplies none.
     */
    @Test
    fun theLobbysHostDecidesWithTheAdmissionStartWasGiven() = runTest {
        val recorded = Recorded { admitAll }
        val loom = InMemoryLoom()
        val clock = virtualClock()
        val stores = listOf(fakeKeyStore(5), fakeKeyStore(6))
        // `electLobby` weaves `New` on every peer, which a flat InMemoryLoom refuses twice, so the
        // second peer weaves `Existing`.
        fun lobby(seam: Seam, device: Int) = SeamElectionLobby(
            seam = seam,
            factory = SeamRoomFactory(loom, backgroundScope, clock, stores[device]),
            scope = backgroundScope,
            clock = clock,
            roomKey = null,
        )
        val lobbies = listOf(
            lobby(loom.weave(Rendezvous.New(Pattern("lobby"))), device = 0),
            lobby(loom.weave(Rendezvous.Existing(InMemoryTag("lobby"))), device = 1),
        )
        val elected = lobbies[0].host.first { lobbies[0].peers.value.size == 2 }
        lobbies[1].host.first { lobbies[1].peers.value.size == 2 }
        val hostIndex = lobbies.indexOfFirst { it.selfId == elected }
        val member = lobbies[1 - hostIndex]

        val outcome = async { member.awaitRoom(memberName = "Bob") }
        val hostRoom = lobbies[hostIndex].start(memberName = "Alice", admission = recorded.admission)
        val memberRoom = assertIs<ElectionOutcome.Adopted>(outcome.await()).room
        hostRoom.roster.first { it.size == 1 }
        val memberKey = stores[1 - hostIndex].deviceKey().id

        assertAll(
            { assertEquals(listOf(memberKey), recorded.keys(), "the elected host asked its policy about the member, once") },
            { assertEquals(SessionRole.Host, hostRoom.role.value, "rig: start adopted the host's room") },
        )
        memberRoom.leave()
        hostRoom.leave()
    }

    // ── A policy that misbehaves ─────────────────────────────────────────────

    /**
     * A policy that throws refuses that joiner retryably, logs, and leaves the room running: the next
     * newcomer is asked and admitted. On Kotlin/Native an escaped throw would abort the process; under
     * `runTest` it fails the test as an uncaught exception, so the escape is caught on every target.
     */
    @Test
    fun throwingPolicyRejectsRetryablyAndRoomSurvives() = runTest {
        val recorded = Recorded { request ->
            if (request.key == bobKey.id) throw IllegalStateException("the app's allow-list store is down")
            admitAll
        }
        val host = rawHost(joiners = setOf(bob, eve), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)
        val bobRejects = host.rejectsTo(bob)
        host.admit(eve, eveKey, nonce = 2)

        assertAll(
            { assertEquals(listOf(bobKey.id, eveKey.id), recorded.keys(), "rig: asked about Bob, threw, then asked about Eve") },
            { assertEquals(listOf<RejectCode>(RejectCode.AdmissionPolicyFailed), bobRejects.map { it.code }, "Bob hears the policy failed") },
            { assertTrue(bobRejects.single().code.retryable, "a policy's failure is retryable") },
            { assertTrue(host.room.roster.value.none { it.id == bob }, "a throwing policy admits nobody") },
            { assertEquals(setOf(eve), host.room.roster.value.map { it.id }.toSet(), "the room keeps admitting") },
        )
    }

    /**
     * A policy that tries to refuse as `"superseded"` — the room's own code, sent only with a
     * successor — throws building its answer, so the joiner hears `AdmissionPolicyFailed` and the room
     * never builds a refusal it cannot send.
     */
    @Test
    fun aPolicyRefusingAsSupersededFailsRetryably() = runTest {
        val recorded = Recorded { AdmissionDecision.Reject(RejectCode.Unknown("superseded")) }
        val host = rawHost(joiners = setOf(bob, eve), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)

        assertAll(
            { assertEquals(listOf(bobKey.id), recorded.keys(), "rig: the policy was asked about Bob") },
            { assertEquals(listOf<RejectCode>(RejectCode.AdmissionPolicyFailed), host.rejectsTo(bob).map { it.code }) },
            { assertTrue(host.room.roster.value.isEmpty(), "nobody was seated") },
        )
    }

    /**
     * The #1834 shape: a policy whose own `withTimeout` fires throws a cancellation the room did not
     * ask for. It is a failure like any other throw, so Bob hears `AdmissionPolicyFailed`. Rethrowing
     * it by type would cancel the admit coroutine silently, and Bob would hear nothing until his own
     * deadline.
     */
    @Test
    fun policyMintedCancellationIsAFailureNotASilence() = runTest {
        val recorded = Recorded { withTimeout(1.milliseconds) { awaitCancellation() } }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertAll(
            { assertEquals(1, recorded.asked.size, "rig: the policy ran and its own timeout fired") },
            { assertEquals(listOf<RejectCode>(RejectCode.AdmissionPolicyFailed), host.rejectsTo(bob).map { it.code }) },
            { assertTrue(host.room.roster.value.isEmpty()) },
        )
    }

    /**
     * A policy that never answers is cancelled at the challenge's deadline, and Bob hears nothing: his
     * own admit deadline ends that attempt (census P7). Nothing is left wedged: Bob's next Hello is
     * asked about afresh and admitted, once, and the stalled decision released late changes nothing.
     */
    @Test
    fun slowPolicyTimesOutWithoutWedgingPending() = runTest {
        val firstRelease = CompletableDeferred<AdmissionDecision>()
        var firstCancelled = false
        val recorded = Recorded { _ ->
            if (firstRelease.isCompleted || firstCancelled) {
                admitAll
            } else {
                var answered = false
                try {
                    firstRelease.await().also { answered = true }
                } finally {
                    if (!answered) firstCancelled = true
                }
            }
        }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)

        host.admit(bob, bobKey, nonce = 1)
        val askedWhileStalled = recorded.asked.size
        advanceTimeBy(KEY_SEAT_ADMIT_TIMEOUT + 1.milliseconds)
        runCurrent()
        val sentWhileStalled = host.sentTo(bob).filter { it is AdmitMessage.Welcome || it is AdmitMessage.Reject }

        host.admit(bob, bobKey, nonce = 2)
        firstRelease.complete(admitAll)
        runCurrent()

        assertAll(
            { assertEquals(1, askedWhileStalled, "rig: the host was waiting on the policy") },
            { assertTrue(firstCancelled, "the host stopped waiting on the stalled decision at its deadline") },
            { assertEquals(emptyList(), sentWhileStalled, "a stalled policy admits and refuses nobody") },
            { assertEquals(2, recorded.asked.size, "the fresh Hello was asked about afresh") },
            { assertEquals(listOf("Joined(bob)"), host.events.edgesSince(0).render(), "exactly one Joined for Bob") },
            { assertTrue(host.rejectsTo(bob).isEmpty(), "nothing refused Bob") },
        )
    }

    /** The joiner's half: a host whose policy never answers leaves a real joiner `AdmissionFailed(TimedOut)`. */
    @Test
    fun slowPolicyLeavesTheJoinerTimedOut() = runTest {
        val recorded = Recorded { awaitCancellation() }
        val loom = InMemoryLoom()
        val host = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(HOST_DEVICE), admitTimeout = KEY_SEAT_ADMIT_TIMEOUT)
            .host(Pattern("room"), memberName = "Host", admission = recorded.admission)
        val joiner = SeamRoomFactory(loom, backgroundScope, virtualClock(), fakeKeyStore(1), admitTimeout = KEY_SEAT_ADMIT_TIMEOUT)
            .join(InMemoryTag("room"), memberName = "Bob")

        val failed = joiner.events.filterIsInstance<MembershipEvent.AdmissionFailed>().first()

        assertAll(
            { assertEquals(1, recorded.asked.size, "rig: the host reached the policy, so the proof was verified") },
            { assertEquals<AdmissionFailure>(AdmissionFailure.TimedOut, failed.reason) },
            { assertTrue(host.roster.value.isEmpty(), "nobody was admitted") },
        )
        joiner.leave()
        host.leave()
    }

    /**
     * Review Focus 3: Bob's retry lands while his first decision is still out. The first decision then
     * completes against a challenge that is no longer current and is dropped, never refused (census P6):
     * a `Reject` would end the very joiner the second decision admits.
     */
    @Test
    fun retriedHelloDuringDecisionAdmitsOnce() = runTest {
        val gates = listOf(CompletableDeferred<AdmissionDecision>(), CompletableDeferred<AdmissionDecision>())
        var asked = 0
        val recorded = Recorded { gates[asked++].await() }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)

        val first = host.admit(bob, bobKey, nonce = 1)
        val askedAfterFirst = asked
        val second = host.admit(bob, bobKey, nonce = 2)

        gates[0].complete(admitAll)
        runCurrent()
        val admittedByTheStaleDecision = host.room.roster.value.any { it.id == bob }
        gates[1].complete(admitAll)
        runCurrent()

        assertAll(
            { assertEquals(1, askedAfterFirst, "rig: the first decision was in flight when Bob retried") },
            { assertFalse(first.hostNonce.contentEquals(second.hostNonce), "rig: the retry drew a new challenge") },
            { assertEquals(2, asked, "rig: both proofs reached the policy") },
            { assertFalse(admittedByTheStaleDecision, "the overwritten challenge's decision admits nobody") },
            { assertEquals(listOf("Joined(bob)"), host.events.edgesSince(0).render(), "exactly one Joined") },
            { assertEquals(1, host.directWelcomesTo(bob).size, "one direct Welcome") },
            { assertTrue(host.rejectsTo(bob).isEmpty(), "the stale decision is dropped, never refused") },
        )
    }

    /**
     * The policy suspends, so the seats can change under it. Bob's second process arrives while his
     * first is connected: an additional seat, which the policy vets. Before it answers, the first
     * process goes quiet and its seat is held partitioned. The room decides again once the policy has
     * answered, under the lock, and takes the partitioned seat over rather than giving the key a second
     * seat (Amendment A § 1.5/2.4).
     */
    @Test
    fun aSeatTheKeyLostDuringVettingIsTakenOverNotDoubled() = runTest {
        val gate = CompletableDeferred<AdmissionDecision>()
        var holdBobsKey = false
        val recorded = Recorded { request -> if (holdBobsKey && request.key == bobKey.id) gate.await() else admitAll }
        val host = rawHost(joiners = setOf(bob, bobTab, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        holdBobsKey = true

        host.admit(bobTab, bobKey, nonce = 3)
        val askedWhileConnected = recorded.asked.size
        val mark = host.events.mark()
        host.partition(bob, alive = setOf(carol))
        gate.complete(admitAll)
        runCurrent()

        val underBobsKey = host.room.roster.value.filter { it.key == bobKey.id }.map { it.id }
        assertAll(
            { assertEquals(3, askedWhileConnected, "rig: the second process was being vetted, as an additional seat") },
            { assertEquals(listOf(bobTab), underBobsKey, "the key holds one seat, the taken-over one: ${host.room.roster.value.map { it.id to it.liveness }}") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.Superseded(bobTab)})", "Joined(bob-tab)"),
                    host.events.edgesSince(mark).render(),
                    "the second process inherits the partitioned seat",
                )
            },
        )
    }

    // ── A newcomer under a partitioned member's id (Decided 18) ──────────────

    /**
     * Eve claims Bob's id under her own key while Bob's seat is held partitioned. Rule 3 would release
     * Bob's seat for her, but she is a newcomer, so the policy is asked first and the seat is released
     * only for an `Admit`. The policy refuses her here: Bob's seat stays held, and his own return
     * restores it without asking the policy.
     */
    @Test
    fun aRefusedNewcomerUnderAPartitionedIdLeavesTheSeatHeld() = runTest {
        val notInvited = RejectCode.Unknown("not-invited")
        val recorded = Recorded { request -> if (request.key == eveKey.id) AdmissionDecision.Reject(notInvited) else admitAll }
        val host = rawHost(joiners = setOf(bob, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, eveKey, nonce = 3) // Eve, under Bob's id
        val afterEve = host.member(bob)
        val edgesAfterEve = host.events.edgesSince(mark).render()
        val askedAfterEve = recorded.keys()
        host.admit(bob, bobKey, nonce = 4) // Bob's own return

        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id, eveKey.id), askedAfterEve, "rig: the policy was asked about Eve") },
            { assertEquals(listOf<RejectCode>(notInvited), host.rejectsTo(bob).map { it.code }, "Eve hears the policy's code") },
            { assertEquals(emptyList(), edgesAfterEve, "Bob's seat is not released for a newcomer the policy refused") },
            { assertIs<Liveness.Partitioned>(afterEve?.liveness, "Bob's seat is still held partitioned") },
            { assertEquals(bobKey.id, afterEve?.key, "under Bob's key") },
            { assertEquals(listOf("Resumed(bob)"), host.events.edgesSince(mark).render(), "Bob's return restores the seat") },
            { assertEquals(askedAfterEve, recorded.keys(), "the restore asked nobody") },
        )
    }

    /** The same, when the policy throws on the newcomer: a failure releases nothing either. */
    @Test
    fun aFailedVetUnderAPartitionedIdLeavesTheSeatHeld() = runTest {
        val recorded = Recorded { request -> if (request.key == eveKey.id) throw IllegalStateException("the allow-list is down") else admitAll }
        val host = rawHost(joiners = setOf(bob, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, eveKey, nonce = 3)
        val afterEve = host.member(bob)
        val edgesAfterEve = host.events.edgesSince(mark).render()
        host.admit(bob, bobKey, nonce = 4)

        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id, eveKey.id), recorded.keys(), "rig: the policy was asked about Eve, and only her") },
            { assertEquals(listOf<RejectCode>(RejectCode.AdmissionPolicyFailed), host.rejectsTo(bob).map { it.code }) },
            { assertEquals(emptyList(), edgesAfterEve, "a failed vet releases nothing") },
            { assertIs<Liveness.Partitioned>(afterEve?.liveness, "Bob's seat is still held partitioned") },
            { assertEquals(listOf("Resumed(bob)"), host.events.edgesSince(mark).render(), "Bob's return restores the seat") },
        )
    }

    /** And when the policy never answers: the challenge runs out, and the seat is still Bob's. */
    @Test
    fun aStalledVetUnderAPartitionedIdLeavesTheSeatHeld() = runTest {
        val recorded = Recorded { request -> if (request.key == eveKey.id) awaitCancellation() else admitAll }
        val host = rawHost(joiners = setOf(bob, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))

        val mark = host.events.mark()
        host.admit(bob, eveKey, nonce = 3)
        host.advanceKeeping(alive = setOf(carol), duration = KEY_SEAT_ADMIT_TIMEOUT + KEY_SEAT_HEARTBEAT.interval)
        val afterEve = host.member(bob)
        val edgesAfterEve = host.events.edgesSince(mark).render()
        host.admit(bob, bobKey, nonce = 4)

        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id, eveKey.id), recorded.keys(), "rig: the policy was asked about Eve") },
            { assertTrue(host.rejectsTo(bob).isEmpty(), "a stalled vet refuses nobody") },
            { assertEquals(emptyList(), edgesAfterEve, "a stalled vet releases nothing") },
            { assertIs<Liveness.Partitioned>(afterEve?.liveness, "Bob's seat is still held partitioned") },
            { assertEquals(listOf("Resumed(bob)"), host.events.edgesSince(mark).render(), "Bob's return restores the seat") },
        )
    }

    // ── Revocation (Decided 14) ──────────────────────────────────────────────

    /**
     * Bob's device is revoked after he was admitted. His next Hello does not get the seat back: it is
     * released and his key decided again, so the policy is asked about him as a newcomer.
     */
    @Test
    fun aRevokedDevicesHeldSeatGoesToThePolicy() = runTest {
        val recorded = Recorded { admitAll }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        val firstMembership = host.directWelcomesTo(bob).single().membership
        host.advanceKeeping(alive = setOf(bob), duration = 100.milliseconds)
        recorded.revocations.revoke(bobKey.id, notBefore = testScheduler.currentTime)

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 2)
        val welcomes = host.directWelcomesTo(bob)

        assertAll(
            { assertEquals(listOf(bobKey.id, bobKey.id), recorded.keys(), "the revoked device is vetted again") },
            {
                assertEquals(
                    listOf("Left(bob, ${LeaveReason.PartitionExpired})", "Joined(bob)"),
                    host.events.edgesSince(mark).render(),
                    "the seat is released, and Bob joins as a new member",
                )
            },
            { assertEquals(2, welcomes.size, "rig: two direct Welcomes") },
            { assertTrue(welcomes.last().membership != firstMembership, "a new membership, not the old one restored") },
            { assertIs<Liveness.Connected>(host.member(bob)?.liveness) },
        )
    }

    /**
     * The control: a cutoff no later than Bob's admission does not touch his seat, which comes back
     * unasked. Without it the row above could pass on a room that vets every return.
     */
    @Test
    fun aRevocationThatPredatesTheAdmissionLeavesTheSeatRestorable() = runTest {
        val recorded = Recorded { admitAll }
        val host = rawHost(joiners = setOf(bob), admission = recorded.admission)
        val admittedAt = testScheduler.currentTime
        host.admit(bob, bobKey, nonce = 1)
        host.advanceKeeping(alive = setOf(bob), duration = 100.milliseconds)
        recorded.revocations.revoke(bobKey.id, notBefore = admittedAt)

        val mark = host.events.mark()
        host.admit(bob, bobKey, nonce = 2)

        assertAll(
            { assertEquals(listOf(bobKey.id), recorded.keys(), "the restore asked nobody") },
            { assertEquals(emptyList(), host.events.edgesSince(mark).render(), "the connected seat came back silently") },
            { assertEquals(2, host.directWelcomesTo(bob).size, "rig: the restore answered Bob's Hello") },
        )
    }

    /**
     * Revocation is checked per key, before any restore verdict is trusted: a revoked device's
     * partitioned seat is not taken over under a new id either. The new id is a newcomer, and the old
     * seat is left to its window.
     */
    @Test
    fun aRevokedDevicesPartitionedSeatIsNotTakenOverUnderANewId() = runTest {
        val recorded = Recorded { admitAll }
        val host = rawHost(joiners = setOf(bob, bobTab, carol), admission = recorded.admission)
        host.admit(bob, bobKey, nonce = 1)
        host.admit(carol, carolKey, nonce = 2)
        host.partition(bob, alive = setOf(carol))
        recorded.revocations.revoke(bobKey.id, notBefore = testScheduler.currentTime)
        val asksBefore = host.hold.calls.size

        val mark = host.events.mark()
        host.admit(bobTab, bobKey, nonce = 3)

        assertAll(
            { assertEquals(listOf(bobKey.id, carolKey.id, bobKey.id), recorded.keys(), "the new id is vetted") },
            { assertEquals(listOf("Joined(bob-tab)"), host.events.edgesSince(mark).render(), "a new member; nothing superseded") },
            { assertEquals(asksBefore, host.hold.calls.size, "the revoked seat's window was never asked to restore it") },
            { assertIs<Liveness.Partitioned>(host.member(bob)?.liveness, "the old seat is left to its window") },
        )
    }

}
