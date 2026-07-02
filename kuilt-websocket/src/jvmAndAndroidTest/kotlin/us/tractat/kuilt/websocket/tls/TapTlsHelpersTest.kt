package us.tractat.kuilt.websocket.tls

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the `wss://` tap dev-cert helpers on **both** the JVM and Android unit-test
 * variants — the whole point of hosting them in `jvmAndAndroidMain`. These exercise the
 * server-engine-free half of the story: minting a [DevTlsIdentity], the fingerprint it shows,
 * and the joiner's fingerprint pin (accept the matching cert, reject a different one). The
 * full Netty `wss://` round-trip stays JVM-only in `TapTlsPinningTest`.
 */
class TapTlsHelpersTest {

    @Test
    fun fingerprintIdentifiesCert() {
        val identity = generateDevTlsIdentity()
        val cert = requireNotNull(identity.keyStore.getCertificate(identity.keyAlias)) as X509Certificate
        val recomputed = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02x".format(it) }
        assertEquals(recomputed, identity.fingerprintSha256)
    }

    @Test
    fun matchingFingerprintTrustsCert() {
        val identity = generateDevTlsIdentity()
        val cert = requireNotNull(identity.keyStore.getCertificate(identity.keyAlias)) as X509Certificate
        // A fingerprint given with colons and in upper case still pins the same cert.
        fingerprintPinningTrustManager(identity.fingerprintSha256.uppercase())
            .checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun differentFingerprintRejectsCert() {
        val identity = generateDevTlsIdentity()
        val cert = requireNotNull(identity.keyStore.getCertificate(identity.keyAlias)) as X509Certificate
        val wrong = generateDevTlsIdentity().fingerprintSha256
        assertTrue(wrong != identity.fingerprintSha256)
        assertFailsWith<CertificateException> {
            fingerprintPinningTrustManager(wrong).checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun pinnedClientBuildsWithOkHttpEngine() {
        // Constructing the pinned client proves the OkHttp client engine resolves on this
        // variant (Android included) — no network is opened here.
        val client = pinnedTlsHttpClient(generateDevTlsIdentity().fingerprintSha256)
        client.close()
    }
}
