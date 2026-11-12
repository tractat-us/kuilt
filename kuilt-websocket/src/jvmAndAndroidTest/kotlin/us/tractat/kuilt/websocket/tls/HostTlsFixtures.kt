package us.tractat.kuilt.websocket.tls

import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The instant every fixture is minted at. It is the real time the test class loads — not a fixed
 * date — so that a verifier which ignored its injected clock and read the wall clock instead would
 * see a different "now" from the one the test passes it only when the test moves its clock, which is
 * exactly what the expiry tests do.
 */
internal val mintedAt: Instant = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

internal fun clockAt(instant: Instant): Clock = object : Clock {
    override fun now(): Instant = instant
}

internal fun clockAfter(elapsed: Duration): Clock = clockAt(mintedAt + elapsed)

internal val mintClock: Clock = clockAt(mintedAt)

internal fun lanAuthority(commonName: String = "test host CA"): HostCertificateAuthority =
    HostCertificateAuthority.mint(commonName, PermittedNames.LocalNetwork, lifetime = 3650.days, clock = mintClock)

internal val hostNames: HostNames = HostNames(
    dnsNames = listOf("localhost", "alice.local"),
    ipAddresses = listOf("127.0.0.1", "::1", "192.168.1.20").map(InetAddress::getByName),
)

internal fun lanIdentity(authority: HostCertificateAuthority = lanAuthority()): HostTlsIdentity =
    authority.issue(hostNames, LeafLifetime.Maximum, mintClock)
