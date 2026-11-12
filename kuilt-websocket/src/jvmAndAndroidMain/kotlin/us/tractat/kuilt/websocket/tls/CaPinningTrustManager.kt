package us.tractat.kuilt.websocket.tls

import java.security.GeneralSecurityException
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager
import kotlin.time.Clock

/**
 * A trust manager that accepts a TLS server only when its certificate chains to the certificate
 * authority whose key [pin] names — the joining half of [HostCertificateAuthority].
 *
 * A device given a host's pin out of band trusts that host, and nobody else, with no certificate
 * store involved. The server must send its CA certificate after its leaf, as
 * [HostTlsIdentity.certificateChain] is ordered. The check then refuses the handshake unless all of
 * these hold:
 *
 * - some certificate after the leaf carries the pinned key;
 * - that CA certificate is self-signed by the pinned key, is a CA allowed to sign certificates, and is
 *   valid now;
 * - the leaf is signed by it, is valid now, is for server authentication, and
 *   names only what the CA's NameConstraints permit.
 *
 * The chain is checked by the platform's own PKIX validator, with the pinned key as its only trust
 * anchor and the CA certificate as the one intermediate, so the CA's extensions are enforced exactly
 * as RFC 5280 reads them. Revocation is not checked: a host CA publishes no revocation list, and
 * a peer forgets a compromised host by dropping its pin.
 *
 * It does not check that the leaf names the address dialled; pair it with the client engine's
 * hostname check if that matters, which the leaf's names are written to pass. It accepts no client
 * certificates.
 *
 * It is a plain [X509TrustManager], so it fits any client engine that takes one.
 *
 * @param clock what "now" is when checking validity dates.
 */
public fun caPinningTrustManager(pin: CaPin, clock: Clock): X509TrustManager = CaPinningTrustManager(pin, clock)

private class CaPinningTrustManager(private val pin: CaPin, private val clock: Clock) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("a CA pin authenticates servers, not clients")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server presented no certificate")
        val authority = chain.drop(1).firstOrNull { CaPin.of(it.publicKey) == pin }
            ?: throw CertificateException("no certificate in the server's chain carries the pinned CA key $pin")
        verifyHostChain(leaf, authority, Date(clock.now().toEpochMilliseconds()))
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Check [leaf] against [authority] at [at], as [caPinningTrustManager] describes. The caller has
 * already matched [authority]'s key to the pin; the anchor is that key, so the CA certificate is
 * validated as an intermediate and its own extensions — BasicConstraints, KeyUsage and the
 * NameConstraints that bind the leaf — are applied rather than taken on trust.
 *
 * @throws CertificateException when the chain does not hold.
 */
internal fun verifyHostChain(leaf: X509Certificate, authority: X509Certificate, at: Date) {
    // A host CA carries no extended key usage, so this one check also refuses the CA certificate
    // replayed as a server's own.
    if (SERVER_AUTH !in leaf.extendedKeyUsage.orEmpty()) {
        throw CertificateException("the server's certificate is not for server authentication")
    }
    val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf, authority))
    val anchor = TrustAnchor(authority.subjectX500Principal, authority.publicKey, null)
    val parameters = PKIXParameters(setOf(anchor)).apply {
        isRevocationEnabled = false
        date = at
    }
    try {
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
    } catch (e: GeneralSecurityException) {
        throw CertificateException("the server's chain does not hold under the pinned CA: ${e.message}", e)
    }
}
