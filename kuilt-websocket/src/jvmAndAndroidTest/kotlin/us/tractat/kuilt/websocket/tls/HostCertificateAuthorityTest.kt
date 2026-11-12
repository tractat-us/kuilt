package us.tractat.kuilt.websocket.tls

import us.tractat.kuilt.test.assertAll
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class HostCertificateAuthorityTest {

    @Test
    fun caIsAnEcdsaP256CaWithItsExtensions() {
        val ca = lanAuthority().certificate
        val key = ca.publicKey as ECPublicKey
        assertAll(
            { assertEquals("SHA256withECDSA", ca.sigAlgName) },
            { assertEquals(256, key.params.curve.field.fieldSize) },
            { assertEquals(0, ca.basicConstraints, "a CA with path length 0") },
            { assertTrue(ca.keyUsage[5], "keyCertSign") },
            { assertTrue(ca.keyUsage[6], "cRLSign") },
            { assertTrue(ca.criticalExtensionOIDs.containsAll(listOf("2.5.29.19", "2.5.29.15", "2.5.29.30"))) },
            { assertEquals(ca.subjectX500Principal, ca.issuerX500Principal) },
            { assertContentEquals(keyIdOf(ca.getExtensionValue("2.5.29.14")), keyIdOf(ca.getExtensionValue("2.5.29.35"))) },
        )
    }

    @Test
    fun leafIsAServerCertificateUnderTheCaKeyIdentifier() {
        val identity = lanIdentity()
        val leaf = identity.leafCertificate
        assertAll(
            { assertEquals(-1, leaf.basicConstraints, "not a CA") },
            { assertTrue(leaf.keyUsage[0], "digitalSignature") },
            { assertEquals(listOf(SERVER_AUTH), leaf.extendedKeyUsage) },
            { assertEquals(hostNames, identity.names) },
            { assertEquals(identity.authority.certificate.subjectX500Principal, leaf.issuerX500Principal) },
            {
                assertContentEquals(
                    keyIdOf(identity.authority.certificate.getExtensionValue("2.5.29.14")),
                    keyIdOf(leaf.getExtensionValue("2.5.29.35")),
                )
            },
        )
    }

    @Test
    fun leafLivesAtMost825Days() {
        val leaf = lanAuthority().issue(hostNames, LeafLifetime.Maximum, mintClock).leafCertificate
        assertAll(
            { assertEquals(825L, (leaf.notAfter.time - leaf.notBefore.time) / 1.days.inWholeMilliseconds) },
            { assertEquals(0L, (leaf.notAfter.time - leaf.notBefore.time) % 1.days.inWholeMilliseconds) },
            { assertFailsWith<IllegalArgumentException> { LeafLifetime(826) } },
            { assertFailsWith<IllegalArgumentException> { LeafLifetime(0) } },
        )
    }

    @Test
    fun reissueKeepsTheLeafKeyAndTheCa() {
        val original = lanIdentity()
        val moved = HostNames(listOf("alice.local"), listOf(InetAddress.getByName("10.0.0.7")))
        val reissued = original.reissue(moved, LeafLifetime(90), clockAfter(3.days))
        assertAll(
            { assertEquals(original.leafCertificate.publicKey, reissued.leafCertificate.publicKey) },
            { assertEquals(original.leafPrivateKey, reissued.leafPrivateKey) },
            { assertEquals(original.caPin, reissued.caPin) },
            { assertNotEquals(original.leafCertificate.serialNumber, reissued.leafCertificate.serialNumber) },
            { assertEquals(moved, reissued.names) },
            {
                caPinningTrustManager(original.caPin, clockAfter(3.days))
                    .checkServerTrusted(reissued.certificateChain.toTypedArray(), "ECDHE_ECDSA")
            },
        )
    }

    @Test
    fun issueRefusesNamesTheCaMayNotVouchFor() {
        val authority = lanAuthority()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { authority.issue(HostNames(listOf("example.com"), emptyList()), LeafLifetime(30), mintClock) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    authority.issue(HostNames(emptyList(), listOf(InetAddress.getByName("8.8.8.8"))), LeafLifetime(30), mintClock)
                }
            },
        )
    }

    @Test
    fun issueRefusesOnceTheCaHasExpired() {
        val authority = HostCertificateAuthority.mint("short CA", PermittedNames.LocalNetwork, 2.days, mintClock)
        assertFailsWith<IllegalArgumentException> { authority.issue(hostNames, LeafLifetime(1), clockAfter(5.days)) }
    }

    @Test
    fun encodeThenDecodeKeepsTheWholeIdentity() {
        val identity = lanIdentity()
        val kept = HostTlsIdentity.decode(identity.encode())
        assertAll(
            { assertContentEquals(identity.leafCertificate.encoded, kept.leafCertificate.encoded) },
            { assertContentEquals(identity.authority.certificate.encoded, kept.authority.certificate.encoded) },
            { assertContentEquals(identity.leafPrivateKey.encoded, kept.leafPrivateKey.encoded) },
            { assertEquals(identity.caPin, kept.caPin) },
            // The kept CA key still signs: a restart can re-sign the leaf under the same pin.
            {
                val reissued = kept.reissue(hostNames, LeafLifetime(30), mintClock)
                caPinningTrustManager(identity.caPin, mintClock).checkServerTrusted(reissued.certificateChain.toTypedArray(), "ECDHE_ECDSA")
            },
        )
    }

    @Test
    fun encodingIsPemOpensslReads() {
        val text = lanIdentity().encode().decodeToString()
        assertAll(
            { assertEquals(2, Regex("-----BEGIN CERTIFICATE-----").findAll(text).count()) },
            { assertEquals(2, Regex("-----BEGIN PRIVATE KEY-----").findAll(text).count()) },
            { assertTrue(text.lines().all { it.length <= 64 }) },
        )
    }

    @Test
    fun decodeRefusesPiecesThatDoNotBelongTogether() {
        val one = Pem.decode(lanIdentity().encode().decodeToString())
        val other = Pem.decode(lanIdentity().encode().decodeToString())
        fun encoded(blocks: List<Pair<String, ByteArray>>) =
            blocks.joinToString("") { (label, der) -> Pem.encode(label, der) }.encodeToByteArray()
        assertAll(
            // Control: the same re-encoding of untouched blocks decodes.
            { HostTlsIdentity.decode(encoded(one)) },
            { assertFailsWith<IllegalArgumentException> { HostTlsIdentity.decode(encoded(listOf(one[0], one[1], other[2], one[3]))) } },
            { assertFailsWith<IllegalArgumentException> { HostTlsIdentity.decode(encoded(listOf(one[0], one[1], one[2], other[3]))) } },
            { assertFailsWith<IllegalArgumentException> { HostTlsIdentity.decode(encoded(listOf(other[0], one[1], one[2], one[3]))) } },
            { assertFailsWith<IllegalArgumentException> { HostTlsIdentity.decode(encoded(listOf(one[1], one[0], one[2], one[3]))) } },
            { assertFailsWith<IllegalArgumentException> { HostTlsIdentity.decode(encoded(one.take(3))) } },
        )
    }

    @Test
    fun pinIsUnpaddedBase64UrlSha256OfTheCaKey() {
        val authority = lanAuthority()
        val digest = MessageDigest.getInstance("SHA-256").digest(authority.certificate.publicKey.encoded)
        val expected = Base64.UrlSafe.encode(digest).trimEnd('=')
        assertAll(
            { assertEquals(expected, authority.pin.encoded) },
            { assertEquals(43, authority.pin.encoded.length) },
            { assertEquals(authority.pin, CaPin.parse(authority.pin.encoded)) },
            { assertFailsWith<IllegalArgumentException> { CaPin.parse(authority.pin.encoded + "=") } },
            { assertFailsWith<IllegalArgumentException> { CaPin.parse(digest.joinToString("") { "%02x".format(it) }) } },
            { assertFailsWith<IllegalArgumentException> { CaPin.parse(authority.pin.encoded.dropLast(1) + "+") } },
        )
    }

    @Test
    fun namesRefuseWhatNoCertificateCanCarry() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { HostNames(emptyList(), emptyList()) } },
            { assertFailsWith<IllegalArgumentException> { HostNames(listOf("*.local"), emptyList()) } },
            { assertFailsWith<IllegalArgumentException> { HostNames(listOf(".local"), emptyList()) } },
            { assertFailsWith<IllegalArgumentException> { IpRange.parse("192.168.1.0/16") } },
            { assertFailsWith<IllegalArgumentException> { IpRange.parse("10.0.0.0/33") } },
            { assertFailsWith<IllegalArgumentException> { IpRange.parse("example.com/8") } },
            { assertEquals("ALICE.local".lowercase(), HostNames(listOf("ALICE.local"), emptyList()).dnsNames.single()) },
        )
    }

    @Test
    fun aCaPinnedChainRejectionIsACertificateException() {
        // The trust manager's contract for a JSSE engine: refusals are CertificateException, never a
        // runtime exception escaping the handshake.
        val identity = lanIdentity()
        assertFailsWith<CertificateException> {
            caPinningTrustManager(lanAuthority().pin, mintClock).checkServerTrusted(identity.certificateChain.toTypedArray(), "ECDHE_ECDSA")
        }
    }

    /** The last 20 bytes of an SKI or AKI extension value are the key identifier itself. */
    private fun keyIdOf(extensionValue: ByteArray): ByteArray = extensionValue.copyOfRange(extensionValue.size - 20, extensionValue.size)
}
