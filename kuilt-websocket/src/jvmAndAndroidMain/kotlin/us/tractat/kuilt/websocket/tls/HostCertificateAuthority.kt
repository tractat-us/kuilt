package us.tractat.kuilt.websocket.tls

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A host's own small certificate authority: the long-lived key a peer pins once, so the host can
 * re-sign its short-lived certificate whenever its addresses change without anyone trusting it again.
 *
 * A host on a LAN has no domain name a public authority would certify, and its addresses move: a
 * laptop gets a new one on every network. A self-signed certificate would change with them, and every
 * device would have to trust it afresh each time. So the host mints a CA once, keeps it, and hands
 * peers its [pin]; the certificate it actually serves is a leaf the CA [issue]s and later
 * [re-signs][HostTlsIdentity.reissue].
 *
 * Every CA minted here is ECDSA P-256 and carries, all critical: BasicConstraints (a CA, path length
 * 0, so it signs leaves and never another CA), KeyUsage `keyCertSign` and `cRLSign`, and
 * NameConstraints from [PermittedNames], so a device that installs it trusts it for those names alone.
 * It also carries Subject and Authority Key Identifiers.
 *
 * Mint one with [mint]; keep it with the identity it belongs to ([HostTlsIdentity.encode]).
 *
 * @property certificate the CA's self-signed certificate — what a browser or OS store installs.
 */
public class HostCertificateAuthority private constructor(
    public val certificate: X509Certificate,
    private val privateKey: PrivateKey,
) {
    /** The pin a peer is handed: see [CaPin]. */
    public val pin: CaPin = CaPin.of(certificate.publicKey)

    /**
     * Issue a leaf for [names] under a fresh ECDSA P-256 key.
     *
     * The leaf is checked against this CA's own chain verification before it is returned, so a leaf
     * this returns is one a [caPinningTrustManager] holding [pin] accepts at [clock]'s now.
     *
     * @throws IllegalArgumentException when a name falls outside this CA's [PermittedNames], or the
     *   CA itself is not valid now — a leaf no peer would accept is refused rather than minted.
     */
    public fun issue(names: HostNames, lifetime: LeafLifetime, clock: Clock): HostTlsIdentity {
        val keyPair = newP256KeyPair()
        return HostTlsIdentity(this, issueChecked(keyPair.public, names, lifetime, clock), keyPair.private)
    }

    /** This CA's certificate as PEM: the file a browser or an OS trust store imports. */
    public fun certificatePem(): String = Pem.encode(Pem.CERTIFICATE, certificate.encoded)

    internal fun issueChecked(publicKey: PublicKey, names: HostNames, lifetime: LeafLifetime, clock: Clock): X509Certificate {
        val now = clock.now()
        val leaf = signLeaf(publicKey, names, lifetime, now)
        try {
            verifyHostChain(leaf, certificate, Date(now.toEpochMilliseconds()))
        } catch (e: CertificateException) {
            throw IllegalArgumentException("this CA would not vouch for a leaf for $names: ${e.message}", e)
        }
        return leaf
    }

    /** Sign a leaf with no check that a peer would accept it — tests build the leaves peers must refuse. */
    internal fun signLeaf(publicKey: PublicKey, names: HostNames, lifetime: LeafLifetime, now: Instant): X509Certificate {
        val notBefore = (now - CLOCK_SKEW_ALLOWANCE).truncatedToSeconds()
        val tbs = Certificates.tbs(
            issuer = certificate.subjectX500Principal.encoded,
            subject = Certificates.name(LEAF_COMMON_NAME),
            notBefore = notBefore,
            notAfter = notBefore + lifetime.days.days,
            publicKey = publicKey,
            extensions = listOf(
                Certificates.extension(BASIC_CONSTRAINTS, critical = true, Der.sequence()),
                Certificates.extension(KEY_USAGE, critical = true, Der.namedBits(DIGITAL_SIGNATURE)),
                Certificates.extension(EXTENDED_KEY_USAGE, critical = false, Der.sequence(Der.oid(SERVER_AUTH))),
                Certificates.extension(SUBJECT_ALT_NAME, critical = false, Certificates.generalNames(names)),
                Certificates.extension(SUBJECT_KEY_ID, critical = false, Der.octetString(keyIdentifier(publicKey))),
                Certificates.extension(
                    AUTHORITY_KEY_ID,
                    critical = false,
                    Der.sequence(Der.implicitPrimitive(0, keyIdentifier(certificate.publicKey))),
                ),
            ),
        )
        return Certificates.sign(tbs, privateKey)
    }

    internal fun privateKeyEncoded(): ByteArray = privateKey.encoded

    public companion object {
        /**
         * Mint a new CA.
         *
         * @param commonName the name a person sees when a browser or a phone asks them to trust it.
         * @param permittedNames the only names the CA may vouch for; [PermittedNames.LocalNetwork]
         *   is what a LAN host needs.
         * @param lifetime how long the CA lives, at least a day. It outlives many leaves: when it
         *   expires every peer has to pin a new one.
         */
        public fun mint(
            commonName: String,
            permittedNames: PermittedNames,
            lifetime: Duration,
            clock: Clock,
        ): HostCertificateAuthority {
            require(lifetime >= 1.days) { "a certificate authority lives at least a day, got $lifetime" }
            val keyPair = newP256KeyPair()
            val subject = Certificates.name(commonName)
            val keyId = keyIdentifier(keyPair.public)
            val notBefore = (clock.now() - CLOCK_SKEW_ALLOWANCE).truncatedToSeconds()
            val tbs = Certificates.tbs(
                issuer = subject,
                subject = subject,
                notBefore = notBefore,
                notAfter = (notBefore + lifetime).truncatedToSeconds(),
                publicKey = keyPair.public,
                extensions = listOf(
                    Certificates.extension(BASIC_CONSTRAINTS, critical = true, Der.sequence(Der.boolean(true), Der.integer(0))),
                    Certificates.extension(KEY_USAGE, critical = true, Der.namedBits(KEY_CERT_SIGN, CRL_SIGN)),
                    Certificates.extension(NAME_CONSTRAINTS, critical = true, Certificates.nameConstraints(permittedNames)),
                    Certificates.extension(SUBJECT_KEY_ID, critical = false, Der.octetString(keyId)),
                    Certificates.extension(AUTHORITY_KEY_ID, critical = false, Der.sequence(Der.implicitPrimitive(0, keyId))),
                ),
            )
            return HostCertificateAuthority(Certificates.sign(tbs, keyPair.private), keyPair.private)
        }

        /** Rebuild a kept CA, refusing a certificate that is not a CA or a key that is not its own. */
        internal fun restore(certificate: X509Certificate, privateKey: PrivateKey): HostCertificateAuthority {
            require(certificate.basicConstraints >= 0) { "the kept CA certificate is not a certificate authority" }
            require(signedBy(certificate, certificate.publicKey)) { "the kept CA certificate is not self-signed" }
            require(keysMatch(privateKey, certificate.publicKey)) { "the kept CA key does not match its certificate" }
            return HostCertificateAuthority(certificate, privateKey)
        }

        /** Peers' clocks drift; a leaf minted this second should not be "not yet valid" on a phone a minute behind. */
        private val CLOCK_SKEW_ALLOWANCE = 1.hours

        /** Fixed and dotless, so no verifier mistakes it for a host name to check against the constraints. */
        private const val LEAF_COMMON_NAME = "kuilt host"
    }
}

/**
 * A host's TLS identity: its [authority], and the leaf certificate and key it serves.
 *
 * This is what a host keeps across restarts. [encode] it to bytes and write them somewhere durable —
 * a `DurableStore` from `kuilt-store`, or a file only the host's user can read, since it holds both
 * private keys — and [decode] it on the next start. When the host's addresses change, [reissue] the
 * leaf: same key, same CA, so every peer that pinned [caPin] still trusts it.
 *
 * @property authority the CA that signed the leaf.
 * @property leafCertificate the certificate the host presents.
 * @property leafPrivateKey the leaf's private key, for the server's TLS engine.
 */
public class HostTlsIdentity internal constructor(
    public val authority: HostCertificateAuthority,
    public val leafCertificate: X509Certificate,
    public val leafPrivateKey: PrivateKey,
) {
    /** The pin peers hold: the [authority]'s. */
    public val caPin: CaPin get() = authority.pin

    /** What a server sends on the handshake: the leaf, then the CA a pinning peer checks it against. */
    public val certificateChain: List<X509Certificate> = listOf(leafCertificate, authority.certificate)

    /** The names the leaf answers to — compare against the host's current ones to decide whether to [reissue]. */
    public val names: HostNames = Certificates.namesOf(leafCertificate)

    /**
     * Re-sign the leaf for [names], keeping its key and its CA.
     *
     * @throws IllegalArgumentException as [HostCertificateAuthority.issue] does.
     */
    public fun reissue(names: HostNames, lifetime: LeafLifetime, clock: Clock): HostTlsIdentity =
        HostTlsIdentity(authority, authority.issueChecked(leafCertificate.publicKey, names, lifetime, clock), leafPrivateKey)

    /**
     * Encode the whole identity — both certificates and both private keys — as PEM text in UTF-8:
     * the leaf certificate, the CA certificate, the leaf key and the CA key, in that order, keys as
     * unencrypted PKCS#8. `openssl` reads every block.
     */
    public fun encode(): ByteArray = listOf(
        Pem.encode(Pem.CERTIFICATE, leafCertificate.encoded),
        Pem.encode(Pem.CERTIFICATE, authority.certificate.encoded),
        Pem.encode(Pem.PRIVATE_KEY, leafPrivateKey.encoded),
        Pem.encode(Pem.PRIVATE_KEY, authority.privateKeyEncoded()),
    ).joinToString("").encodeToByteArray()

    public companion object {
        /**
         * Decode what [encode] wrote.
         *
         * It checks that the pieces belong together — the CA is a self-signed CA, the leaf is
         * signed by it, each key is its certificate's — so a mixed-up or corrupted file is refused
         * here rather than at a peer's handshake. It does not check dates: an expired leaf decodes,
         * and the host [reissue]s it.
         *
         * @throws IllegalArgumentException when [bytes] are not a whole, consistent identity.
         */
        public fun decode(bytes: ByteArray): HostTlsIdentity {
            val blocks = Pem.decode(bytes.decodeToString())
            require(blocks.map { it.first } == listOf(Pem.CERTIFICATE, Pem.CERTIFICATE, Pem.PRIVATE_KEY, Pem.PRIVATE_KEY)) {
                "a host TLS identity is a leaf certificate, a CA certificate, a leaf key and a CA key, got ${blocks.map { it.first }}"
            }
            val (leaf, ca) = blocks.take(2).map { Certificates.parse(it.second) }
            val (leafKey, caKey) = blocks.drop(2).map { privateKeyOf(it.second) }
            val authority = HostCertificateAuthority.restore(ca, caKey)
            require(leaf.basicConstraints == -1) { "the kept leaf certificate is a certificate authority" }
            require(signedBy(leaf, ca.publicKey)) { "the kept leaf certificate was not signed by the kept CA" }
            require(keysMatch(leafKey, leaf.publicKey)) { "the kept leaf key does not match its certificate" }
            return HostTlsIdentity(authority, leaf, leafKey)
        }

        private fun privateKeyOf(pkcs8: ByteArray): PrivateKey = try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("a kept key is not an EC PKCS#8 private key", e)
        }
    }
}

/** Building, signing and reading back X.509 certificates; the DER itself is [Der]'s. */
internal object Certificates {
    private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val COMMON_NAME = "2.5.4.3"
    private const val SERIAL_BYTES = 16
    private val random = SecureRandom()

    fun name(commonName: String): ByteArray {
        require(commonName.isNotBlank()) { "a certificate needs a common name" }
        return Der.sequence(Der.set(Der.sequence(Der.oid(COMMON_NAME), Der.utf8String(commonName))))
    }

    fun tbs(
        issuer: ByteArray,
        subject: ByteArray,
        notBefore: Instant,
        notAfter: Instant,
        publicKey: PublicKey,
        extensions: List<ByteArray>,
    ): ByteArray = Der.sequence(
        Der.explicit(0, Der.integer(2)),
        Der.integer(serial()),
        Der.sequence(Der.oid(ECDSA_WITH_SHA256)),
        issuer,
        Der.sequence(Der.time(Date(notBefore.toEpochMilliseconds())), Der.time(Date(notAfter.toEpochMilliseconds()))),
        subject,
        publicKey.encoded,
        Der.explicit(3, Der.sequence(extensions)),
    )

    fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        } else {
            Der.sequence(Der.oid(oid), Der.octetString(value))
        }

    fun sign(tbs: ByteArray, key: PrivateKey): X509Certificate {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key)
            update(tbs)
        }.sign()
        return parse(Der.sequence(tbs, Der.sequence(Der.oid(ECDSA_WITH_SHA256)), Der.bitString(signature)))
    }

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    fun generalNames(names: HostNames): ByteArray = Der.sequence(
        names.dnsNames.map { Der.implicitPrimitive(DNS_NAME, it.encodeToByteArray()) } +
            names.ipAddresses.map { Der.implicitPrimitive(IP_ADDRESS, it.address) },
    )

    fun nameConstraints(permitted: PermittedNames): ByteArray {
        val subtrees = permitted.dnsNames.map { Der.sequence(Der.implicitPrimitive(DNS_NAME, it.encodeToByteArray())) } +
            permitted.ipRanges.map { Der.sequence(Der.implicitPrimitive(IP_ADDRESS, it.constraintBytes())) }
        return Der.sequence(Der.implicitConstructed(0, subtrees))
    }

    fun namesOf(certificate: X509Certificate): HostNames {
        val entries = certificate.subjectAlternativeNames.orEmpty()
        return HostNames(
            dnsNames = entries.filter { it[0] == DNS_NAME }.map { it[1] as String },
            ipAddresses = entries.filter { it[0] == IP_ADDRESS }.map { InetAddress.getByName(it[1] as String) },
        )
    }

    /** Positive and never zero; 128 random bits, well inside RFC 5280's 20-octet ceiling. */
    private fun serial(): BigInteger = BigInteger(1, ByteArray(SERIAL_BYTES).also(random::nextBytes)).setBit(SERIAL_BYTES * 8 - 1)

    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7
}

/** PEM armour: base64 at 64 characters a line between BEGIN and END labels. */
internal object Pem {
    const val CERTIFICATE = "CERTIFICATE"
    const val PRIVATE_KEY = "PRIVATE KEY"
    private const val LINE = 64
    private val block = Regex("-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]*?)-----END \\1-----")

    fun encode(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.Default.encode(der).chunked(LINE).joinToString("\n") + "\n-----END $label-----\n"

    fun decode(text: String): List<Pair<String, ByteArray>> = block.findAll(text).map { match ->
        val body = match.groupValues[2].filterNot(Char::isWhitespace)
        val der = try {
            Base64.Default.decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("a ${match.groupValues[1]} block is not base64", e)
        }
        match.groupValues[1] to der
    }.toList()
}

internal const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
private const val BASIC_CONSTRAINTS = "2.5.29.19"
private const val KEY_USAGE = "2.5.29.15"
private const val EXTENDED_KEY_USAGE = "2.5.29.37"
private const val SUBJECT_ALT_NAME = "2.5.29.17"
private const val NAME_CONSTRAINTS = "2.5.29.30"
private const val SUBJECT_KEY_ID = "2.5.29.14"
private const val AUTHORITY_KEY_ID = "2.5.29.35"
private const val DIGITAL_SIGNATURE = 0
private const val KEY_CERT_SIGN = 5
private const val CRL_SIGN = 6
private const val KEY_ID_BYTES = 20

/** A key identifier: the leftmost 160 bits of SHA-256 over the SubjectPublicKeyInfo (RFC 7093 §2). */
private fun keyIdentifier(publicKey: PublicKey): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).copyOf(KEY_ID_BYTES)

private fun newP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
}.generateKeyPair()

private fun Instant.truncatedToSeconds(): Instant = Instant.fromEpochSeconds(epochSeconds)

private fun signedBy(certificate: X509Certificate, key: PublicKey): Boolean = try {
    certificate.verify(key)
    true
} catch (_: java.security.GeneralSecurityException) {
    false
}

private fun keysMatch(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
    val signature = try {
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(challenge)
        }.sign()
    } catch (_: java.security.GeneralSecurityException) {
        return false
    }
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey)
        update(challenge)
        verify(signature)
    }
}
