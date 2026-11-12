package us.tractat.kuilt.websocket.tls

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

/**
 * The whole lifecycle in one place, as the cookbook quotes it: a host mints its CA and leaf once,
 * keeps them, re-signs the leaf when its address changes, and a peer holding only the printed pin
 * trusts every leaf along the way.
 */
class HostTlsStoryTest {

    @Test
    fun aHostMintsKeepsAndReissuesWhileAPeerPinsTheCa() {
        val clock = mintClock

        // First start: mint the CA and a leaf for today's names, and keep both.
        val authority = HostCertificateAuthority.mint("Alice's laptop", PermittedNames.LocalNetwork, 3650.days, clock)
        val names = HostNames(listOf("localhost", "alice.local"), listOf(InetAddress.getByName("192.168.1.20")))
        val kept: ByteArray = authority.issue(names, LeafLifetime.Maximum, clock).encode()

        // The pin travels out of band, beside the join code: 43 characters, URL-safe.
        val pin: String = authority.pin.encoded

        // Next start, on a new network: load, re-sign for the new address, keep again.
        val loaded = HostTlsIdentity.decode(kept)
        val moved = HostNames(listOf("localhost", "alice.local"), listOf(InetAddress.getByName("10.0.0.7")))
        val identity = if (loaded.names == moved) loaded else loaded.reissue(moved, LeafLifetime.Maximum, clock)

        // A peer holding only the pin trusts the re-signed leaf: same CA, same pin.
        val trust = caPinningTrustManager(CaPin.parse(pin), clock)
        trust.checkServerTrusted(identity.certificateChain.toTypedArray(), "ECDHE_ECDSA")
        assertEquals(loaded.leafCertificate.publicKey, identity.leafCertificate.publicKey)
    }
}
