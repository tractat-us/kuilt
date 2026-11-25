@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session.test

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmissionRequest
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * [admissionRequest] builds the request a real room builds (#3220): for the same joiner, the room's
 * request and the factory's agree field by field. Otherwise a policy's unit test passes on a request
 * production never sends — the case that motivated it is `deviceId`, where the proof signs a missing
 * id as `""`, so `""` and none must reach a policy as one value.
 *
 * Each joiner is a real room on its own device, admitted by a real host whose policy records what it
 * was asked; the factory is then given what the app gave its join.
 */
@OptIn(AdmissionTestApi::class)
class RoomRequestParityTest {

    private class Joining(val memberName: String?, val deviceId: String?)

    @Test
    fun theRoomAsksWithTheRequestTheFactoryBuilds() = runTest {
        val asked = mutableListOf<AdmissionRequest>()
        val open = AdmissionPolicy.open()
        val admission = HostAdmission(AdmissionPolicy { request -> asked += request; open.decide(request) }, RevocationList.inMemory())
        val loom = InMemoryLoom()
        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val host = SeamRoomFactory(loom, backgroundScope, clock, fakeKeyStore(0))
            .host(Pattern("room"), memberName = "Host", admission = admission)

        val joinings = listOf(
            Joining(memberName = "Ada", deviceId = "phone-1"),
            Joining(memberName = null, deviceId = null), // unnamed: shown under its peer id
            Joining(memberName = "Cy", deviceId = ""), // signs the same bytes as no device id
        )
        val expected = joinings.mapIndexed { i, joining ->
            val store = fakeKeyStore(i + 1)
            val room = SeamRoomFactory(loom, backgroundScope, clock, store)
                .join(InMemoryTag("room"), memberName = joining.memberName, deviceId = joining.deviceId)
            host.roster.first { roster -> roster.any { it.id == room.selfId } }
            admissionRequest(
                key = store.deviceKey().id,
                displayName = joining.memberName ?: room.selfId.value,
                deviceId = joining.deviceId,
            )
        }

        assertEquals(joinings.size, asked.size, "rig: the host asked its policy once per joiner")
        assertAll(
            *expected.zip(asked).flatMap { (factory, room) ->
                listOf(
                    { assertEquals(factory.key, room.key, "key") },
                    { assertEquals(factory.displayName, room.displayName, "displayName") },
                    { assertEquals(factory.deviceId, room.deviceId, "deviceId") },
                )
            }.toTypedArray(),
            { assertEquals(null, asked[2].deviceId, "an empty device id reaches the policy as none") },
        )
        host.leave()
    }
}
