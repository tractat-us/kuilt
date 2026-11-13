package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val (hostKey, bobKey) = hostStore.deviceKey().id to bobStore.deviceKey().id

        assertAll(
            { assertEquals(bobKey, bobOnHost.key, "the host's entry for Bob carries Bob's key") },
            { assertNotEquals(hostKey, bobOnHost.key, "rig: the two devices hold different keys") },
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
        val hostKey = hostStore.deviceKey().id

        assertAll(
            { assertEquals(host.selfId, hostOnBob.id, "rig: the entry is the host's") },
            { assertEquals(hostKey, hostOnBob.key, "the host intro carries the host's key") },
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
        val (bobKey, carolKey) = bobStore.deviceKey().id to carolStore.deviceKey().id

        assertAll(
            { assertEquals(carolKey, carolOnBob.key, "the roster copy carries Carol's key") },
            { assertEquals(bobKey, bobOnCarol.key, "the bootstrap Welcome carries Bob's key") },
        )
        carol.leave()
        bob.leave()
        host.leave()
    }

    /**
     * A room reads its device's key once, before it exists, and never again (#3210). A platform store
     * can fail on a later read — a Keychain item is unavailable while the phone is locked — and a room
     * that read the store on its admit path would then fail mid-admission: on the host, inside a
     * launched coroutine with nothing to catch it and no `Reject` for the joiner; on the joiner,
     * before its admit deadline is armed. One store at a time fails, since a joiner that fails first
     * never sends the Hello that would reach the host's read.
     */
    @Test
    fun `a host store that fails after its first read does not affect the running host`() =
        admitsDespiteLaterReadFailure(hostStore = KeyStore(TEST_KEY_A, answers = 1), bobStore = KeyStore(TEST_KEY_B))

    /** The joiner half of the test above: the joiner's Hello must not read the store again. */
    @Test
    fun `a joiner store that fails after its first read does not affect the running joiner`() =
        admitsDespiteLaterReadFailure(hostStore = KeyStore(TEST_KEY_A), bobStore = KeyStore(TEST_KEY_B, answers = 1))

    private fun admitsDespiteLaterReadFailure(hostStore: KeyStore, bobStore: KeyStore) = runTest {
        val loom = InMemoryLoom()
        val host = factory(loom, backgroundScope, hostStore).host(Pattern("HostA"), memberName = "HostA")
        val bob = factory(loom, backgroundScope, bobStore).join(InMemoryTag("Bob"), memberName = "Bob")

        // Bounded in virtual time, so a room that read its store again fails here with the read
        // counts below rather than hanging until the wall-clock backstop.
        val bobOnHost = withTimeoutOrNull(10.seconds) { host.roster.first { it.size == 1 }.single() }
        val hostOnBob = withTimeoutOrNull(10.seconds) { bob.roster.first { it.isNotEmpty() }.single() }

        assertAll(
            { assertEquals(1, hostStore.reads, "the host room read its store once, before it existed") },
            { assertEquals(1, bobStore.reads, "the joiner room read its store once, before it existed") },
            { assertEquals(bobStore.key.id, bobOnHost?.key, "the host's entry for Bob carries Bob's key") },
            { assertEquals(hostStore.key.id, hostOnBob?.key, "the host intro carries the host's key") },
        )
        // The rig: the store whose answers are spent really does fail a later read, so a room that
        // made one could not have admitted.
        val spent = if (hostStore.answers == 1) hostStore else bobStore
        assertFailsWith<IllegalStateException> { spent.deviceKey() }
        bob.leave()
        host.leave()
    }

    /**
     * A store that answers its first [answers] [deviceKey] reads and throws on every later one. Its key
     * is a fixed name that signs nothing, since nothing signs in this protocol version, so the test
     * never waits on a platform provider.
     */
    private class KeyStore(name: DidKey, val answers: Int = Int.MAX_VALUE) : DeviceKeyStore {
        val key: DeviceKey = object : DeviceKey {
            override val id: DidKey = name

            override suspend fun sign(message: ByteArray): ByteArray = error("nothing signs before the key-proving exchange")
        }
        var reads = 0
            private set

        override suspend fun deviceKey(): DeviceKey {
            reads++
            check(reads <= answers) { "this store answers $answers time(s); read $reads" }
            return key
        }

        override suspend fun grants(): List<Grant> = emptyList()

        override suspend fun keepGrant(grant: Grant): Unit = Unit
    }

    /**
     * Every Hello names this device's key and draws a fresh joiner nonce of the declared width. Two
     * joins from one device are read off the wire by a raw seam standing in for the host.
     */
    @Test
    fun `every hello names the device key and a fresh nonce`() = runTest {
        val loom = InMemoryLoom()
        val store = InMemoryDeviceKeyStore()
        // Made before the virtual-time deadline below is armed: on wasmJs making a key is a
        // WebCrypto promise, and virtual time runs on while the test body waits for one.
        val deviceKey = store.deviceKey().id
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
            { assertEquals(deviceKey, first.key, "the Hello names this device's key") },
            { assertEquals(first.key, second.key, "one device, one key") },
            { assertEquals(AdmitMessage.Hello.JOINER_NONCE_SIZE, first.joinerNonce?.size) },
            { assertEquals(AdmitMessage.Hello.JOINER_NONCE_SIZE, second.joinerNonce?.size) },
            { assertNotEquals(first.joinerNonce?.toList(), second.joinerNonce?.toList(), "each Hello draws a fresh nonce") },
        )
        listener.close()
    }
}
