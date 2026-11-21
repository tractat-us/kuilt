@file:OptIn(us.tractat.kuilt.identity.IdentityInternalApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.identity.KeyScheme
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * A host and a joiner on **real** P-256 device keys, end to end through admission: the one room test
 * that does not use `:kuilt-identity-test`'s fake scheme, so nothing in a room pins only the fake.
 *
 * Every other room test runs on fake keys so wasmJs stays on virtual time (#3293). The JVM's provider
 * answers without a promise, so a real key costs a room test nothing here. `AdmitKeysTest` makes the
 * same assertions on fake keys, on every target.
 */
class RealKeyAdmitTest {
    private fun factory(loom: InMemoryLoom, scope: kotlinx.coroutines.CoroutineScope, store: InMemoryDeviceKeyStore) =
        SeamRoomFactory(loom, scope, clock = { Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = store)

    @Test
    fun `host and joiner exchange real P-256 keys through admission`() = runTest {
        val loom = InMemoryLoom()
        val hostStore = InMemoryDeviceKeyStore()
        val bobStore = InMemoryDeviceKeyStore()
        val host = factory(loom, backgroundScope, hostStore).host(Pattern("HostA"), memberName = "HostA")
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
}
