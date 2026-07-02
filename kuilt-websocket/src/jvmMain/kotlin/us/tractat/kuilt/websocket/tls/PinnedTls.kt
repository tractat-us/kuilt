package us.tractat.kuilt.websocket.tls

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * A trust manager that accepts a `wss://` server **only** if its certificate's SHA-256
 * fingerprint matches [expectedFingerprintSha256] — nothing else.
 *
 * This is the joiner's half of the dev-LAN trust story (see [DevTlsIdentity]). The pulling
 * side is told the fingerprint out of band — shown on the device next to the join code, or
 * carried in the discovery record — and pins it here. Because the pin authenticates the exact
 * certificate, no certificate authority and no hostname match are needed: a host reached by its
 * LAN IP is trusted iff it presents the pinned cert, and an on-path attacker with a different
 * cert is rejected at the handshake.
 *
 * The fingerprint may be given with or without colon separators and in any case; both are
 * normalised before comparison.
 */
public fun fingerprintPinningTrustManager(expectedFingerprintSha256: String): X509TrustManager {
    val expected = normalizeFingerprint(expectedFingerprintSha256)
    require(expected.length == 64) {
        "expected a SHA-256 fingerprint (64 hex chars, colons optional), got: $expectedFingerprintSha256"
    }
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client authentication is not supported by the tap fingerprint pin")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("no server certificate presented")
            val actual = normalizeFingerprint(sha256Fingerprint(leaf))
            if (actual != expected) {
                throw CertificateException(
                    "pinned tap certificate mismatch: expected $expected but server presented $actual",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * An [SSLContext] that trusts a `wss://` tap host only if it presents the certificate whose
 * SHA-256 fingerprint is [expectedFingerprintSha256]. Thin wrapper over
 * [fingerprintPinningTrustManager] for callers wiring their own client engine.
 */
public fun pinnedSslContext(expectedFingerprintSha256: String): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(fingerprintPinningTrustManager(expectedFingerprintSha256)), null)
    }

/**
 * A Ktor [HttpClient] that connects to a `wss://` tap host over TLS, trusting **only** the
 * certificate whose SHA-256 fingerprint is [expectedFingerprintSha256].
 *
 * This is the one call a puller needs to join an encrypted tap: pass the fingerprint the device
 * showed alongside the join code, then use the returned client with
 * [us.tractat.kuilt.websocket.KtorClientLoom] and a `wss://…` [WebSocketAdvertisement][us.tractat.kuilt.websocket.WebSocketAdvertisement].
 * The WebSocket plugin is installed for you. Hostname verification is disabled on purpose — the
 * fingerprint pin, not the hostname, is what authenticates the peer, and a dev host is reached
 * by an IP not present in the self-signed cert.
 *
 * The caller owns the returned client's lifecycle and must [close][HttpClient.close] it.
 *
 * @param configure additional client configuration, applied after the WebSocket plugin.
 */
public fun pinnedTlsHttpClient(
    expectedFingerprintSha256: String,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient {
    val trustManager = fingerprintPinningTrustManager(expectedFingerprintSha256)
    val sslSocketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
    }.socketFactory
    val okHttp = OkHttpClient.Builder()
        .sslSocketFactory(sslSocketFactory, trustManager)
        // The fingerprint pin fully authenticates the peer; the self-signed dev cert has no
        // hostname the LAN IP would match, so hostname verification is intentionally a no-op.
        .hostnameVerifier { _, _ -> true }
        .build()
    return HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(WebSockets)
        configure()
    }
}

/** Strip colons and whitespace, lowercase — so `AB:CD…` and `abcd…` compare equal. */
internal fun normalizeFingerprint(fingerprint: String): String =
    fingerprint.filterNot { it == ':' || it.isWhitespace() }.lowercase()
