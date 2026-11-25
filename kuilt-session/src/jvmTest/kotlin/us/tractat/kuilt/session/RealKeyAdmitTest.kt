@file:OptIn(us.tractat.kuilt.identity.IdentityInternalApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.identity.KeyScheme
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A host and a joiner on **real** P-256 device keys, end to end through admission: the one room test
 * that does not use `:kuilt-identity-test`'s fake scheme, so nothing in a room pins only the fake.
 *
 * Every other room test runs on fake keys so wasmJs stays on virtual time (#3293). The JVM's provider
 * answers without a promise, so a real key costs a room test nothing here. `AdmitKeysTest` makes the
 * same assertions on fake keys, on every target.
 *
 * Since v4 (#3211) admission runs the signed challenge and proof, so the first test also proves both
 * keys with ECDSA. The proof arms below drive a host by hand with real keys: an honest proof admits,
 * and a proof signed by another real key is refused, so the host's check is ECDSA's and not the fake's.
 */
class RealKeyAdmitTest {
    private fun factory(loom: InMemoryLoom, scope: kotlinx.coroutines.CoroutineScope, store: InMemoryDeviceKeyStore) =
        SeamRoomFactory(loom, scope, clock = { Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = store)

    @Test
    fun `host and joiner exchange real P-256 keys through admission`() = runTest {
        val loom = InMemoryLoom()
        val hostStore = InMemoryDeviceKeyStore()
        val bobStore = InMemoryDeviceKeyStore()
        val host = factory(loom, backgroundScope, hostStore).host(Pattern("HostA"), memberName = "HostA", admission = HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()))
        val bob = factory(loom, backgroundScope, bobStore).join(InMemoryTag("Bob"), memberName = "Bob")

        val bobOnHost = host.roster.first { it.size == 1 }.single()
        val hostOnBob = bob.roster.first { it.isNotEmpty() }.single()
        val (hostKey, bobKey) = hostStore.deviceKey().id to bobStore.deviceKey().id
        val reparsed = DidKey.parse(bobOnHost.key.value)

        assertAll(
            { assertSame(KeyScheme.P256, hostStore.keyScheme, "rig: the host is on the real scheme") },
            { assertSame(KeyScheme.P256, bobStore.keyScheme, "rig: Bob is on the real scheme") },
            { assertNotEquals(hostKey, bobKey, "rig: the two devices hold different keys") },
            { assertEquals(bobKey, bobOnHost.key, "the host's entry for Bob carries Bob's key") },
            { assertEquals(hostKey, hostOnBob.key, "the host intro carries the host's key") },
            // A fake key need not be on the curve; a key that came through a real room parses as P-256.
            { assertEquals(bobKey, reparsed, "the key the host holds is a real P-256 key") },
        )
        bob.leave()
        host.leave()
    }

    private val bob = PeerId("bob")

    /** A real-P-256 host over a [FakeSeam] that sees Bob, and Bob's Hello naming [bobKey]. */
    private suspend fun TestScope.challengeBob(bobKey: DeviceKey): Pair<FakeSeam, Room> {
        val hostId = PeerId("host")
        val seam = FakeSeam(selfId = hostId, initialPeers = setOf(hostId, bob))
        val room = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock = { Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = InMemoryDeviceKeyStore())
            .adopt(seam, AdoptRole.Host(HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory()), roomId = RoomId("room-1")), memberName = "Host")
        runCurrent()
        val hello = AdmitMessage.Hello(
            displayName = "Bob",
            sessionId = "session-bob",
            protocolVersion = ProtocolVersion.CURRENT,
            key = bobKey.id,
            joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE) { 1 },
        )
        seam.deliver(bob, AdmitMessage.encode(hello))
        runCurrent()
        return seam to room
    }

    private fun FakeSeam.sentToBob(): List<AdmitMessage> = directed.filter { it.first == bob }.mapNotNull { AdmitMessage.decode(it.second) }

    @Test
    fun `an honest real-key proof admits and one signed by another real key is refused`() = runTest {
        val bobKey = DeviceKey.generate()
        val forger = DeviceKey.generate()

        val (honestSeam, honestHost) = challengeBob(bobKey)
        val honestChallenge = honestSeam.sentToBob().filterIsInstance<AdmitMessage.Challenge>().single()
        val challengeIsRealP256 = assertNotNull(Challenge.decode(honestChallenge.statement)).isSignedByHost(KeyScheme.P256)
        honestSeam.deliver(bob, AdmitMessage.encode(handProof(honestChallenge, PeerId("host"), bobKey)))
        runCurrent()

        val (forgedSeam, forgedHost) = challengeBob(bobKey)
        val forgedChallenge = forgedSeam.sentToBob().filterIsInstance<AdmitMessage.Challenge>().single()
        forgedSeam.deliver(bob, AdmitMessage.encode(handProof(forgedChallenge, PeerId("host"), forger)))
        runCurrent()

        assertAll(
            { assertTrue(challengeIsRealP256, "rig: the host signed its challenge with ECDSA") },
            { assertEquals(listOf(bobKey.id), honestHost.roster.value.map { it.key }, "an honest real-key proof admits") },
            { assertEquals(1, forgedSeam.sentToBob().count { it is AdmitMessage.Challenge }, "rig: the forger was challenged") },
            {
                assertEquals(
                    listOf(RejectCode.BadProof),
                    forgedSeam.sentToBob().filterIsInstance<AdmitMessage.Reject>().map { it.code },
                    "a proof signed by another real key is refused",
                )
            },
            { assertTrue(forgedHost.roster.value.isEmpty()) },
        )
        honestHost.leave()
        forgedHost.leave()
    }
}
