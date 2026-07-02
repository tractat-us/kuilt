package us.tractat.kuilt.websocket.tls

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.sslConnector
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * A freshly generated, self-signed TLS identity for a dev-LAN log-tap host — everything a
 * device needs to serve `wss://` without a real certificate authority.
 *
 * The problem this solves: on a shared or untrusted LAN a plaintext `ws://` tap can be read
 * off the wire by anyone sniffing packets. Encrypting it needs a certificate, but a dev tool
 * cannot ask you to run a certificate authority. So the host mints its own throwaway
 * certificate and shows a short [fingerprintSha256] alongside the join code. The pulling side
 * pins that exact fingerprint (see [pinnedTlsHttpClient]); nothing else is trusted. It is the
 * same trust model as an SSH host key — you confirm the fingerprint once, out of band, and the
 * connection is then authenticated end to end.
 *
 * Create one with [generateDevTlsIdentity], hand it to [tapTlsConnector] on the server engine,
 * and print [fingerprintSha256] next to the join code. The certificate lives only in memory;
 * generating a new identity each run is the intended, low-ceremony flow.
 *
 * @property keyStore the in-memory keystore holding the generated key and self-signed cert.
 * @property keyAlias the alias under which the key/cert live in [keyStore].
 * @property fingerprintSha256 the SHA-256 of the certificate (DER-encoded), lowercase hex with
 *   colon separators — the value the host shows and the joiner pins.
 */
public class DevTlsIdentity internal constructor(
    public val keyStore: KeyStore,
    public val keyAlias: String,
    private val password: CharArray,
) {
    /** The self-signed leaf certificate this identity presents on the `wss://` handshake. */
    public val certificate: X509Certificate =
        keyStore.getCertificate(keyAlias) as X509Certificate

    public val fingerprintSha256: String = sha256Fingerprint(certificate)

    /** The keystore password, as the `() -> CharArray` [sslConnector] expects. A defensive copy. */
    public fun keyStorePassword(): CharArray = password.copyOf()

    /** The private-key password, as the `() -> CharArray` [sslConnector] expects. A defensive copy. */
    public fun privateKeyPassword(): CharArray = password.copyOf()
}

/**
 * Generate a self-signed TLS identity for a dev-LAN tap host.
 *
 * RSA-2048 / SHA-256, valid for [daysValid] days. The certificate carries the loopback names
 * as subject-alternative names, but hostname verification is deliberately not what secures the
 * connection — the [DevTlsIdentity.fingerprintSha256] pin is (see [pinnedTlsHttpClient]) — so a
 * host reached by its LAN IP still works without knowing that IP at generation time.
 *
 * @param subjectCommonName the certificate subject CN; cosmetic for a pinned dev cert.
 * @param daysValid certificate validity window; a short life suits an ephemeral dev tap.
 * @param ipAddresses subject-alternative IP names; defaults to loopback.
 * @param domains subject-alternative DNS names; defaults to `localhost`.
 */
public fun generateDevTlsIdentity(
    subjectCommonName: String = "kuilt-tap",
    daysValid: Long = 30,
    ipAddresses: List<InetAddress> = listOf(Inet4Address.getByName("127.0.0.1")),
    domains: List<String> = listOf("localhost"),
): DevTlsIdentity {
    val alias = "kuilt-tap"
    val password = "kuilt-tap".toCharArray()
    val keyStore = buildKeyStore {
        certificate(alias) {
            hash = io.ktor.network.tls.extensions.HashAlgorithm.SHA256
            sign = io.ktor.network.tls.extensions.SignatureAlgorithm.RSA
            keySizeInBits = 2048
            this.password = String(password)
            this.daysValid = daysValid
            this.subject = X500Principal("CN=$subjectCommonName")
            this.domains = domains
            this.ipAddresses = ipAddresses
        }
    }
    return DevTlsIdentity(keyStore, alias, password)
}

/**
 * Add an HTTPS ([wss][DevTlsIdentity]) connector serving [identity] to a Ktor server engine.
 *
 * Call inside `embeddedServer(Netty, configure = { tapTlsConnector(identity, port) }) { … }`.
 * The mounted [us.tractat.kuilt.websocket.KtorServerLoom] then serves over TLS; the joiner
 * connects with `wss://…` and a [pinnedTlsHttpClient]. Plain `ws://` remains available — add a
 * normal `connector { }` too, or skip this and serve only `ws://` on a trusted link.
 */
public fun ApplicationEngine.Configuration.tapTlsConnector(
    identity: DevTlsIdentity,
    port: Int,
    host: String = "0.0.0.0",
) {
    sslConnector(
        keyStore = identity.keyStore,
        keyAlias = identity.keyAlias,
        keyStorePassword = { identity.keyStorePassword() },
        privateKeyPassword = { identity.privateKeyPassword() },
    ) {
        this.port = port
        this.host = host
    }
}

/**
 * SHA-256 of a certificate's DER encoding, lowercase hex with colon separators — the canonical
 * "certificate fingerprint" form browsers and `openssl x509 -fingerprint -sha256` print.
 */
internal fun sha256Fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02x".format(byte) }
