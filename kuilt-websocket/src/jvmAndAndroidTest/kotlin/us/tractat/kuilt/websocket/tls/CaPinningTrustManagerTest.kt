package us.tractat.kuilt.websocket.tls

import us.tractat.kuilt.test.assertAll
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Each refusal is paired with the same fixture accepted under the honest condition, so a red here
 * means the check under test refused, not that the fixture was broken some other way.
 */
class CaPinningTrustManagerTest {

    private fun verify(pin: CaPin, chain: List<X509Certificate>, clock: kotlin.time.Clock = mintClock) =
        caPinningTrustManager(pin, clock).checkServerTrusted(chain.toTypedArray(), "ECDHE_ECDSA")

    @Test
    fun acceptsLeafSignedByThePinnedCa() {
        val identity = lanIdentity()
        verify(identity.caPin, identity.certificateChain)
    }

    @Test
    fun acceptsAPinParsedFromItsPrintedForm() {
        val identity = lanIdentity()
        verify(CaPin.parse(identity.caPin.encoded), identity.certificateChain)
    }

    @Test
    fun rejectsALeafNotSignedByThePinnedCa() {
        // Two CAs with the same name, so the leaf's issuer name chains to the pinned CA and only the
        // signature can tell them apart.
        val pinned = lanAuthority("shared name")
        val impostor = lanAuthority("shared name")
        val impostorLeaf = lanIdentity(impostor).leafCertificate
        assertAll(
            { assertEquals(pinned.certificate.subjectX500Principal, impostorLeaf.issuerX500Principal) },
            { assertNotEquals(pinned.pin, impostor.pin) },
            // The impostor's leaf is otherwise sound: its own CA's pin accepts it.
            { verify(impostor.pin, listOf(impostorLeaf, impostor.certificate)) },
            {
                assertFailsWith<CertificateException> {
                    verify(pinned.pin, listOf(impostorLeaf, pinned.certificate))
                }
            },
        )
    }

    @Test
    fun rejectsAChainWhoseCaIsNotThePinnedOne() {
        val pinned = lanIdentity()
        val other = lanIdentity()
        assertAll(
            { verify(other.caPin, other.certificateChain) },
            { assertFailsWith<CertificateException> { verify(pinned.caPin, other.certificateChain) } },
        )
    }

    @Test
    fun rejectsAChainThatOmitsTheCa() {
        val identity = lanIdentity()
        assertFailsWith<CertificateException> { verify(identity.caPin, listOf(identity.leafCertificate)) }
    }

    @Test
    fun rejectsAnExpiredLeaf() {
        val identity = lanAuthority().issue(hostNames, LeafLifetime(1), mintClock)
        assertAll(
            // Within its day it is accepted; the CA itself is good for years on both sides of the line.
            { verify(identity.caPin, identity.certificateChain, clockAfter(12.hours)) },
            { verify(identity.caPin, lanIdentity(identity.authority).certificateChain, clockAfter(2.days)) },
            {
                assertFailsWith<CertificateException> {
                    verify(identity.caPin, identity.certificateChain, clockAfter(2.days))
                }
            },
        )
    }

    @Test
    fun rejectsALeafOutsideTheCaNameConstraints() {
        val authority = lanAuthority()
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fun chainFor(names: HostNames) =
            listOf(authority.signLeaf(key.public, names, LeafLifetime(30), mintedAt), authority.certificate)
        assertAll(
            // The same unchecked signing path, inside the constraints, is accepted.
            { verify(authority.pin, chainFor(HostNames(listOf("alice.local"), emptyList()))) },
            {
                assertFailsWith<CertificateException> {
                    verify(authority.pin, chainFor(HostNames(listOf("bank.example.com"), emptyList())))
                }
            },
            {
                assertFailsWith<CertificateException> {
                    verify(authority.pin, chainFor(HostNames(emptyList(), listOf(InetAddress.getByName("8.8.8.8")))))
                }
            },
        )
    }

    @Test
    fun rejectsTheCaCertificatePresentedAsTheServersOwn() {
        val authority = lanAuthority()
        assertFailsWith<CertificateException> {
            verify(authority.pin, listOf(authority.certificate, authority.certificate))
        }
    }

    @Test
    fun refusesClientAuthentication() {
        val identity = lanIdentity()
        assertFailsWith<CertificateException> {
            caPinningTrustManager(identity.caPin, mintClock)
                .checkClientTrusted(identity.certificateChain.toTypedArray(), "ECDHE_ECDSA")
        }
    }
}
