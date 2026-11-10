package us.tractat.kuilt.websocket.tls

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.PublicKey
import kotlin.io.encoding.Base64

/**
 * The one thing a peer has to know to trust a host: a short fingerprint of the host's
 * certificate authority key.
 *
 * A host hands this to a joining device out of band, the way it already hands over a join code —
 * printed beside the link, carried in a QR code. The device then trusts any certificate that key
 * signed, and nothing else (see [caPinningTrustManager]). Because the pin names the CA rather than
 * one certificate, the host can re-sign its certificate when its addresses change and every device
 * that pinned it keeps working.
 *
 * The pin is the SHA-256 of the CA's DER-encoded SubjectPublicKeyInfo, in unpadded base64url: 43
 * characters, safe in a URL query without escaping. That is the only encoding [parse] accepts — a
 * hex string, a padded one or a digest of anything else is refused rather than guessed at.
 */
@JvmInline
public value class CaPin private constructor(public val encoded: String) {

    override fun toString(): String = encoded

    public companion object {
        private const val ENCODED_LENGTH = 43
        private const val DIGEST_BYTES = 32
        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** The pin of [publicKey]: SHA-256 over its SubjectPublicKeyInfo encoding. */
        public fun of(publicKey: PublicKey): CaPin =
            CaPin(base64Url.encode(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)))

        /**
         * Parse a pin as [encoded] prints it.
         *
         * @throws IllegalArgumentException when [text] is not 43 characters of unpadded base64url
         *   naming a 32-byte digest.
         */
        public fun parse(text: String): CaPin {
            require(text.length == ENCODED_LENGTH) {
                "a CA pin is $ENCODED_LENGTH characters of unpadded base64url, got ${text.length}: $text"
            }
            val digest = try {
                base64Url.decode(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("a CA pin is unpadded base64url: $text", e)
            }
            require(digest.size == DIGEST_BYTES) { "a CA pin names a $DIGEST_BYTES-byte SHA-256 digest: $text" }
            return CaPin(text)
        }
    }
}

/**
 * How long a host's leaf certificate lives, in whole days: at least one, at most [MAX_DAYS].
 *
 * The ceiling is the longest validity Apple's platforms accept for any TLS server certificate, so a
 * leaf kuilt mints is one an iPhone or a Mac will take. It is checked here, in the type, so no path
 * can mint a longer one.
 */
@JvmInline
public value class LeafLifetime(public val days: Int) {
    init {
        require(days in 1..MAX_DAYS) { "a leaf certificate lives 1..$MAX_DAYS days, got $days" }
    }

    public companion object {
        /** Apple's cap on a TLS server certificate's validity period. */
        public const val MAX_DAYS: Int = 825

        /** The longest lifetime there is. */
        public val Maximum: LeafLifetime = LeafLifetime(MAX_DAYS)
    }
}

/**
 * A block of IP addresses — `192.168.0.0/16`, `fc00::/7` — that a certificate authority may vouch
 * for.
 *
 * @property network the first address of the block. Its host bits must be zero: `192.168.1.0/16`
 *   is refused rather than silently widened, since it almost certainly says something its author
 *   did not mean.
 * @property prefixLength how many leading bits every address in the block shares.
 */
public class IpRange(public val network: InetAddress, public val prefixLength: Int) {
    init {
        val bits = network.address.size * 8
        require(prefixLength in 0..bits) { "a /$prefixLength prefix does not fit a $bits-bit address" }
        require(network.address.contentEquals(masked(network.address, prefixLength))) {
            "$network/$prefixLength has host bits set; its network is ${InetAddress.getByAddress(
                masked(network.address, prefixLength),
            ).hostAddress}"
        }
    }

    /** The NameConstraints form: the address followed by its mask. */
    internal fun constraintBytes(): ByteArray = network.address + mask(network.address.size, prefixLength)

    override fun equals(other: Any?): Boolean =
        other is IpRange && other.network == network && other.prefixLength == prefixLength

    override fun hashCode(): Int = network.hashCode() * 31 + prefixLength

    override fun toString(): String = "${network.hostAddress}/$prefixLength"

    public companion object {
        /** Parse `address/prefix`, for example `10.0.0.0/8` or `fc00::/7`. */
        public fun parse(cidr: String): IpRange {
            val parts = cidr.split('/')
            require(parts.size == 2) { "expected address/prefix, got $cidr" }
            return IpRange(literalAddress(parts[0]), parts[1].toInt())
        }

        private fun mask(size: Int, prefixLength: Int): ByteArray =
            ByteArray(size) { i -> (0xff shl (8 - (prefixLength - i * 8).coerceIn(0, 8))).toByte() }

        private fun masked(address: ByteArray, prefixLength: Int): ByteArray {
            val mask = mask(address.size, prefixLength)
            return ByteArray(address.size) { i -> (address[i].toInt() and mask[i].toInt()).toByte() }
        }
    }
}

/**
 * The names a certificate authority may vouch for, and so the only names a device that trusts it
 * will believe.
 *
 * Every CA kuilt mints carries these as a critical NameConstraints extension. That is what makes
 * installing a host's CA on a phone safe: the phone then trusts it for a LAN host's names alone, so
 * someone who stole the CA's key still could not pose as a public website. [LocalNetwork] is the set
 * a LAN host needs.
 *
 * @property dnsNames permitted DNS names. Each covers itself and every name beneath it — `local`
 *   permits `alice.local` — which is how X.509 name constraints read a DNS name.
 * @property ipRanges permitted address blocks.
 */
public class PermittedNames(dnsNames: List<String>, public val ipRanges: List<IpRange>) {
    public val dnsNames: List<String> = dnsNames.map(::canonicalDnsName)

    init {
        require(this.dnsNames.isNotEmpty() || ipRanges.isNotEmpty()) {
            "a certificate authority must permit at least one name"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PermittedNames && other.dnsNames == dnsNames && other.ipRanges == ipRanges

    override fun hashCode(): Int = dnsNames.hashCode() * 31 + ipRanges.hashCode()

    override fun toString(): String = "PermittedNames(dns=$dnsNames, ip=$ipRanges)"

    public companion object {
        /**
         * What a host on a local network can be called: `localhost` and `.local` names, loopback,
         * and the private address blocks — `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` and the
         * IPv6 unique-local `fc00::/7`.
         */
        public val LocalNetwork: PermittedNames = PermittedNames(
            dnsNames = listOf("localhost", "local"),
            ipRanges = listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")
                .map(IpRange::parse),
        )
    }
}

/**
 * The names a host's leaf certificate answers to: its DNS names and its IP addresses. A device
 * dialling the host by any of them finds it in the certificate.
 */
public class HostNames(dnsNames: List<String>, public val ipAddresses: List<InetAddress>) {
    public val dnsNames: List<String> = dnsNames.map(::canonicalDnsName)

    init {
        require(this.dnsNames.isNotEmpty() || ipAddresses.isNotEmpty()) { "a host certificate needs at least one name" }
    }

    override fun equals(other: Any?): Boolean =
        other is HostNames && other.dnsNames.toSet() == dnsNames.toSet() && other.ipAddresses.toSet() == ipAddresses.toSet()

    override fun hashCode(): Int = dnsNames.toSet().hashCode() * 31 + ipAddresses.toSet().hashCode()

    override fun toString(): String = "HostNames(dns=$dnsNames, ip=${ipAddresses.map { it.hostAddress }})"
}

private val dnsLabel = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** Lower-case, and refuse anything that is not a plain dotted DNS name: no wildcard, no leading dot. */
private fun canonicalDnsName(name: String): String {
    val lower = name.lowercase()
    require(lower.length <= 253 && lower.split('.').all(dnsLabel::matches)) { "not a DNS name: $name" }
    return lower
}

/** An address from a literal only, so parsing a range can never cost a DNS lookup. */
private fun literalAddress(literal: String): InetAddress {
    val ipv4 = literal.split('.').let { octets -> octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) } }
    val ipv6 = literal.contains(':') && literal.all { it.isLetterOrDigit() || it == ':' || it == '.' }
    require(ipv4 || ipv6) { "not an IP literal: $literal" }
    val address = InetAddress.getByName(literal)
    check(address is Inet4Address || address is Inet6Address) { "not an IP literal: $literal" }
    return address
}
