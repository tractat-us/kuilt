@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.test.fakeDeviceKey
import us.tractat.kuilt.session.Member
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.ReturningMark
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The pass refusals a real joiner cannot reach (#3223; the design's *Testing*: a grant for another
 * room, another subject, an untrusted issuer). A device's room presents only its host's passes for
 * this room or for every room of that host's, and keeps only passes naming its own key, so none of
 * these passes ever leaves a real device. The host's checks are what stands behind that, and they
 * would go untested if the joiner's filtering were the only thing anyone ran.
 *
 * So these rows run on the reference rig alone, whose [PassPlanting] puts a pass into the Proof a
 * fresh device sends: a device that never held a pass, with no seat, presenting one that is good in
 * every respect but the one each row changes. The host must ignore it and decide the device as a
 * newcomer, so its admission policy is asked exactly once, and the membership is not marked as a
 * return.
 *
 * Each row asserts its rig fired: the device's one Proof carried exactly the planted pass, and the
 * pass is what the row says it is. [aPlantedGoodPassLetsTheDeviceInUnvetted] is the control arm: the
 * same path carrying a pass with nothing wrong lets the device in with no call to the policy, so the
 * one call in every other row is the host refusing that row's pass, not a pass that never arrived.
 *
 * Not in [RoomConformanceSuite], deliberately: the hook is a forging tool, and a published suite
 * cannot ask a harness over a real fabric for one.
 */
class PlantedPassRefusalTest {

    /** What one fresh device's first admission came to, with [planted] passes in its Proof. */
    private class Outcome(
        val hostKey: DidKey,
        val deviceKey: DidKey,
        val roomId: String,
        val plantedPerProof: List<List<Grant>>,
        val roomsOwnPerProof: List<List<ByteArray>>,
        val vetted: List<DidKey>,
        val joins: List<MembershipEvent.Joined>,
        val seat: Member?,
    )

    /**
     * Host a room, launch one fresh device whose Proof carries what [plant] returns, and wait for it
     * to be seated: every row's admission goes through, since the policy admits everyone.
     */
    private suspend fun TestScope.admitWithPlanted(plant: suspend (proof: AdmissionProof, sender: DidKey, now: Long) -> List<Grant>): Outcome {
        val planting = PassPlanting { proof, sender -> plant(proof, sender, testScheduler.currentTime) }
        val h = referenceReturnHarness(planting)
        val vetting = VettingCount()
        val host = h.hostFactory.host(h.hostPattern, admission = vetting.admission)
        val hostTape = EventTape(host, backgroundScope)
        val joiner = h.newDevice().launch().join(h.joinTag)
        try {
            val seated = awaitRoster(host, "the device holds a seat") { roster -> roster.any { it.id == joiner.selfId } }
            awaitRoster(joiner, "the device sees the host") { it.isNotEmpty() }
            runCurrent()
            val hostKey = joiner.roster.value.single { it.id == host.selfId }.key
            return Outcome(
                hostKey = hostKey,
                deviceKey = seated.single { it.id == joiner.selfId }.key,
                roomId = checkNotNull(host.roomId.value) { "a host room knows its id" }.value,
                plantedPerProof = planting.plantedPerProof(),
                roomsOwnPerProof = planting.roomsOwnPerProof(),
                vetted = vetting.keys(),
                joins = hostTape.events().filterIsInstance<MembershipEvent.Joined>().filter { it.member.id == joiner.selfId },
                seat = host.roster.value.singleOrNull { it.id == joiner.selfId },
            )
        } finally {
            joiner.leave()
            host.leave()
        }
    }

    /**
     * The one pass a row planted, after asserting the device sent exactly one Proof, its room put no
     * pass of its own in it, and the planting added exactly one: so the host saw that pass and no other.
     */
    private fun Outcome.plantedPass(): Grant {
        assertEquals(1, plantedPerProof.size, "rig: the device sent one Proof, and the planting rewrote it: $plantedPerProof")
        assertEquals(listOf(0), roomsOwnPerProof.map { it.size }, "rig: a fresh device's room presented no pass of its own")
        assertEquals(1, plantedPerProof.single().size, "rig: the planting added exactly one pass: ${plantedPerProof.single()}")
        return plantedPerProof.single().single()
    }

    /** The host ignored the planted pass: one call to the policy, about the device's own key, and no mark. */
    private fun Outcome.assertVettedAsANewcomer(why: String) = assertAll(
        { assertEquals(listOf(deviceKey), vetted, "$why: the policy is asked once, about the device's key") },
        { assertNull(joins.singleOrNull()?.returning, "an ordinary admission, not marked as a return: $joins") },
        { assertEquals(1, joins.size, "one Joined for the device: $joins") },
        { assertEquals(deviceKey, seat?.key, "and the device is seated under its own key") },
    )

    @Test
    fun aPlantedGoodPassLetsTheDeviceInUnvetted() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val outcome = admitWithPlanted { proof, sender, now -> listOf(passFrom(HOST, sender, GrantScope.Room(proof.roomId), now)) }
        val pass = outcome.plantedPass()
        assertAll(
            { assertEquals(outcome.hostKey, pass.issuer, "rig: the planted pass is the host's") },
            { assertEquals(outcome.deviceKey, pass.subject, "rig: for the device presenting it") },
            { assertEquals(GrantScope.Room(outcome.roomId), pass.scope, "rig: for this room") },
            { assertEquals(emptyList(), outcome.vetted, "a good pass on the planted path lets the device in without asking the policy") },
            { assertEquals(listOf(ReturningMark(previousId = null)), outcome.joins.map { it.returning }, "marked returning, with no id the host remembers") },
        )
    }

    @Test
    fun grantForAnotherRoomIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val outcome = admitWithPlanted { _, sender, now -> listOf(passFrom(HOST, sender, GrantScope.Room(ANOTHER_ROOM), now)) }
        val pass = outcome.plantedPass()
        assertAll(
            { assertEquals(outcome.hostKey, pass.issuer, "rig: the planted pass is the host's") },
            { assertEquals(outcome.deviceKey, pass.subject, "rig: for the device presenting it") },
            { assertTrue(outcome.roomId != ANOTHER_ROOM, "rig: for a room that is not this one: ${outcome.roomId}") },
            { outcome.assertVettedAsANewcomer("a pass for another room is ignored") },
        )
    }

    @Test
    fun grantForAnotherSubjectIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val outcome = admitWithPlanted { proof, _, now -> listOf(passFrom(HOST, fakeDeviceKey(ANOTHER_DEVICE).id, GrantScope.Room(proof.roomId), now)) }
        val pass = outcome.plantedPass()
        assertAll(
            { assertEquals(outcome.hostKey, pass.issuer, "rig: the planted pass is the host's") },
            { assertEquals(GrantScope.Room(outcome.roomId), pass.scope, "rig: for this room") },
            { assertTrue(pass.subject != outcome.deviceKey, "rig: naming another device's key") },
            { outcome.assertVettedAsANewcomer("a pass for another device is ignored") },
        )
    }

    @Test
    fun untrustedIssuerIsIgnored() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val outcome = admitWithPlanted { proof, sender, now -> listOf(passFrom(ANOTHER_HOST, sender, GrantScope.Room(proof.roomId), now)) }
        val pass = outcome.plantedPass()
        assertAll(
            { assertTrue(pass.issuer != outcome.hostKey, "rig: the planted pass is another host's") },
            { assertEquals(outcome.deviceKey, pass.subject, "rig: for the device presenting it") },
            { assertEquals(GrantScope.Room(outcome.roomId), pass.scope, "rig: for this room") },
            { outcome.assertVettedAsANewcomer("a pass from a host this one does not trust is ignored") },
        )
    }

    private suspend fun passFrom(issuer: Int, subject: DidKey, scope: GrantScope, now: Long): Grant =
        Grant.issue(fakeDeviceKey(issuer), subject, scope, issuedAt = now, notAfter = now + 1.hours.inWholeMilliseconds)

    /** A bounded wait on [room]'s roster, on virtual time; fails naming what it waited for. */
    private suspend fun awaitRoster(room: Room, expected: String, predicate: (Set<Member>) -> Boolean): Set<Member> =
        withTimeoutOrNull(AWAIT_BUDGET) { room.roster.first(predicate) }
            ?: throw AssertionError("roster never satisfied: $expected; it was ${room.roster.value.map { it.id.value to it.liveness }}")

    private companion object {
        /** The reference rig's host runs on `fakeKeyStore(0)`, so its key is this one. */
        const val HOST = 0
        const val ANOTHER_HOST = 7_001
        const val ANOTHER_DEVICE = 7_002
        const val ANOTHER_ROOM = "another-room"
        val AWAIT_BUDGET = 5.seconds
    }
}
