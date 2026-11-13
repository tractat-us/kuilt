package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Admit wire v3 carries each member's device key (#3210): the joiner names its key in its Hello,
 * and the host passes keys on in all three Welcome shapes, so every roster entry carries one.
 *
 * - the **direct** Welcome and its **roster copy** carry the joiner's key, from its Hello;
 * - each **bootstrap** Welcome carries an existing member's key, as the host recorded it;
 * - the **host intro** carries the host's own key, from its [DeviceKeyStore].
 *
 * Nothing here proves a key is held: in this version every key is the sender's claim. Each logical
 * device gets its own [InMemoryDeviceKeyStore], so every key in a test is distinct and an assertion
 * that one key arrived cannot be satisfied by another's.
 */
class AdmitKeysTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope, store: DeviceKeyStore) =
        SeamRoomFactory(loom, scope, clock = { Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = store)

    @Test
    fun `the host records the key the joiner's hello named`() = runTest {
        val loom = InMemoryLoom()
        val hostStore = InMemoryDeviceKeyStore()
        val bobStore = InMemoryDeviceKeyStore()
        val host = factory(loom, backgroundScope, hostStore).host(Pattern("HostA"), memberName = "HostA")
        val bob = factory(loom, backgroundScope, bobStore).join(InMemoryTag("Bob"), memberName = "Bob")

        val bobOnHost = host.roster.first { it.size == 1 }.single()

        assertAll(
            { assertEquals(bobStore.deviceKey().id, bobOnHost.key, "the host's entry for Bob carries Bob's key") },
            { assertNotEquals(hostStore.deviceKey().id, bobOnHost.key, "rig: the two devices hold different keys") },
        )
        bob.leave()
        host.leave()
    }

    @Test
    fun `the joiner learns the host's key from the host intro`() = runTest {
        val loom = InMemoryLoom()
        val hostStore = InMemoryDeviceKeyStore()
        val host = factory(loom, backgroundScope, hostStore).host(Pattern("HostA"), memberName = "HostA")
        val bob = factory(loom, backgroundScope, InMemoryDeviceKeyStore()).join(InMemoryTag("Bob"), memberName = "Bob")

        val hostOnBob = bob.roster.first { it.isNotEmpty() }.single()

        assertAll(
            { assertEquals(host.selfId, hostOnBob.id, "rig: the entry is the host's") },
            { assertEquals(hostStore.deviceKey().id, hostOnBob.key, "the host intro carries the host's key") },
        )
        bob.leave()
        host.leave()
    }

    /**
     * Two joiners, Bob first. Carol learns Bob's key from the **bootstrap** Welcome the host sends
     * her, and Bob learns Carol's from the **roster copy** the host fans out to him.
     */
    @Test
    fun `members learn each other's keys from the bootstrap and the roster copy`() = runTest {
        val loom = InMemoryLoom()
        val bobStore = InMemoryDeviceKeyStore()
        val carolStore = InMemoryDeviceKeyStore()
        val host = factory(loom, backgroundScope, InMemoryDeviceKeyStore()).host(Pattern("HostA"), memberName = "HostA")
        val bob = factory(loom, backgroundScope, bobStore).join(InMemoryTag("Bob"), memberName = "Bob")
        host.roster.first { it.size == 1 }
        val carol = factory(loom, backgroundScope, carolStore).join(InMemoryTag("Carol"), memberName = "Carol")

        val carolOnBob = bob.roster.first { roster -> roster.any { it.id == carol.selfId } }.single { it.id == carol.selfId }
        val bobOnCarol = carol.roster.first { roster -> roster.any { it.id == bob.selfId } }.single { it.id == bob.selfId }

        assertAll(
            { assertEquals(carolStore.deviceKey().id, carolOnBob.key, "the roster copy carries Carol's key") },
            { assertEquals(bobStore.deviceKey().id, bobOnCarol.key, "the bootstrap Welcome carries Bob's key") },
        )
        carol.leave()
        bob.leave()
        host.leave()
    }

    /**
     * Every Hello names this device's key and draws a fresh joiner nonce of the declared width. Two
     * joins from one device are read off the wire by a raw seam standing in for the host.
     */
    @Test
    fun `every hello names the device key and a fresh nonce`() = runTest {
        val loom = InMemoryLoom()
        val store = InMemoryDeviceKeyStore()
        val listener = loom.host(Pattern("Listener"))
        // One collection of the single-collection `incoming`: the first Hello from each of two senders.
        val firstHelloPerSender = async {
            withTimeout(5.seconds) {
                val bySender = mutableMapOf<PeerId?, AdmitMessage.Hello>()
                listener.incoming
                    .mapNotNull { swatch -> (AdmitMessage.decode(swatch.toByteArray()) as? AdmitMessage.Hello)?.let { swatch.sender to it } }
                    .first { (sender, hello) -> bySender.getOrPut(sender) { hello }; bySender.size == 2 }
                bySender.values.toList()
            }
        }
        runCurrent()

        repeat(2) { attempt ->
            factory(loom, backgroundScope, store).join(InMemoryTag("Bob-$attempt"), memberName = "Bob")
        }
        val hellos = firstHelloPerSender.await()
        val (first, second) = hellos

        assertAll(
            { assertEquals(2, hellos.size, "rig: both Hellos reached the listener") },
            { assertEquals(ProtocolVersion.CURRENT, first.protocolVersion) },
            { assertEquals(store.deviceKey().id, first.key, "the Hello names this device's key") },
            { assertEquals(first.key, second.key, "one device, one key") },
            { assertEquals(AdmitMessage.Hello.JOINER_NONCE_SIZE, first.joinerNonce?.size) },
            { assertEquals(AdmitMessage.Hello.JOINER_NONCE_SIZE, second.joinerNonce?.size) },
            { assertNotEquals(first.joinerNonce?.toList(), second.joinerNonce?.toList(), "each Hello draws a fresh nonce") },
        )
        listener.close()
    }
}
