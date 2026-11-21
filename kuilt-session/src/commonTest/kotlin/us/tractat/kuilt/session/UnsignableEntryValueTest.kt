@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.election.SeamElectionLobby
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A room id or device id the key-proving exchange could never sign (#3211) is refused where the
 * caller passes it, with an `IllegalArgumentException`, at every entry point. Left to the exchange, a
 * host's unsignable room id fails every challenge as a retryable refusal its joiners can never get
 * past, and a joiner's unsignable device id fails its proof, so the join times out.
 *
 * Two values per field, one for each half of the rule the signed statements apply: an unpaired
 * surrogate, which has no UTF-8, and a string whose UTF-8 is one byte longer than a layout field
 * carries. Each refusal's message names the field, which is what shows the entry check refused it
 * rather than something further along. The control is the longest value that does fit.
 */
class UnsignableEntryValueTest {
    private fun TestScope.factory(loom: InMemoryLoom = InMemoryLoom(), store: Int = 0) =
        SeamRoomFactory(loom, backgroundScope, { Instant.fromEpochMilliseconds(0) }, fakeKeyStore(store))

    private suspend fun refusal(field: String, block: suspend () -> Any?) {
        val e = assertFailsWith<IllegalArgumentException> { block() }
        assertTrue(e.message.orEmpty().startsWith("$field cannot be signed"), "refused at the entry check: ${e.message}")
    }

    @Test
    fun hostRefusesAnUnsignableRoomId() = runTest {
        for (bad in UNSIGNABLE) refusal("roomId") { factory().host(Pattern("room"), roomId = RoomId(bad)) }
    }

    @Test
    fun hostAdoptRefusesAnUnsignableRoomId() = runTest {
        for (bad in UNSIGNABLE) {
            refusal("roomId") { factory().adopt(FakeSeam(PeerId("h")), SessionRole.Host, roomId = RoomId(bad)) }
        }
    }

    @Test
    fun joinRefusesAnUnsignableDeviceId() = runTest {
        for (bad in UNSIGNABLE) refusal("deviceId") { factory().join(InMemoryTag("room"), deviceId = bad) }
    }

    @Test
    fun joinerAdoptRefusesAnUnsignableDeviceId() = runTest {
        for (bad in UNSIGNABLE) {
            refusal("deviceId") { factory().adopt(FakeSeam(PeerId("j")), SessionRole.Joiner, deviceId = bad) }
        }
    }

    /**
     * Refused before the election runs. The lobby has a second peer that never acknowledges, so a
     * `start` that reached the freeze round would retry forever and this test would time out.
     */
    @Test
    fun lobbyStartRefusesAnUnsignableDeviceIdBeforeTheElection() = runTest {
        val loom = InMemoryLoom()
        // A flat InMemoryLoom refuses a second `New`, so the second peer weaves `Existing`.
        fun lobby(seam: Seam, store: Int) = SeamElectionLobby(
            seam = seam,
            factory = factory(loom, store),
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(0) },
            roomKey = null,
        )
        val lobbies = listOf(
            lobby(loom.weave(Rendezvous.New(Pattern("game"))), store = 0),
            lobby(loom.weave(Rendezvous.Existing(InMemoryTag("game"))), store = 1),
        )
        val elected = lobbies[0].host.first { lobbies[0].peers.value.size == 2 }
        val host = lobbies.single { it.selfId == elected }
        for (bad in UNSIGNABLE) refusal("deviceId") { host.start(deviceId = bad) }
        lobbies.forEach { it.leave() }
    }

    @Test
    fun lobbyAwaitRoomRefusesAnUnsignableDeviceId() = runTest {
        val lobby = factory().electLobby(Pattern("game"))
        for (bad in UNSIGNABLE) refusal("deviceId") { lobby.awaitRoom(deviceId = bad) }
        lobby.leave()
    }

    /** The longest values that do fit are accepted: the rule refuses past the field, not at it. */
    @Test
    fun theLongestSignableValuesAreAccepted() = runTest {
        val host = factory().adopt(FakeSeam(PeerId("h")), SessionRole.Host, roomId = RoomId(LONGEST))
        val joiner = factory(store = 1).adopt(FakeSeam(PeerId("j")), SessionRole.Joiner, deviceId = LONGEST)
        assertAll(
            { assertEquals(FIELD_LIMIT, LONGEST.encodeToByteArray().size, "rig: the value fills the field exactly") },
            { assertEquals(RoomId(LONGEST), host.roomId.value) },
        )
        joiner.leave()
        host.leave()
    }

    /** A host built without a room id has nothing to sign into its challenges, so it is not built. */
    @Test
    fun aHostRoomCannotBeBuiltWithoutARoomId() = runTest {
        val e = assertFailsWith<IllegalArgumentException> {
            SeamRoom(
                seam = FakeSeam(PeerId("h")),
                role = SessionRole.Host,
                scope = backgroundScope,
                clock = { Instant.fromEpochMilliseconds(0) },
                device = RoomDevice.of(fakeKeyStore(0)),
                heartbeatConfig = HeartbeatConfig(),
            )
        }
        assertTrue(e.message.orEmpty().contains("roomId"), "refused for its missing room id: ${e.message}")
    }

    private companion object {
        /** The most bytes a layout field carries. */
        const val FIELD_LIMIT = 0xFFFF
        val LONGEST = "x".repeat(FIELD_LIMIT)
        val UNSIGNABLE = listOf("bad-\uD800-id", "x".repeat(FIELD_LIMIT + 1))
    }
}
