package us.tractat.kuilt.nw

import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback harness — a real Network.framework socket needs a real IO dispatcher; no virtual-time option exists here
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression cover for the PSK-identity NUL defect (#1577).
 *
 * [NwPsk.derive] returns a raw 32-byte HMAC-SHA256 output as the TLS-PSK **identity**. Apple's
 * `sec_protocol_options_add_pre_shared_key` is the TLS-1.2-era *external* PSK path, where the
 * identity is a C string end-to-end — so an embedded `0x00` truncates it in transit and the acceptor
 * answers `unknown_psk_identity` (`errSSLUnknownPSKIdentity`, OSStatus -9864). RFC 4279 §5.1 is
 * explicit that the identity "MUST be first converted to a character string, and then encoded to
 * octets using UTF-8" — raw HMAC bytes are non-conformant regardless of where the NUL lands.
 *
 * A uniformly random 32-byte identity contains at least one `0x00` with probability
 * `1 - (255/256)^32 ≈ 11.8%`, so roughly **one room in eight could never connect** — the link just
 * timed out with "no peer reached" and no surfaced error. Found on two physical iPhones; every
 * `(roomKey, serviceType)` pair committed to this module happened to derive a NUL-free identity,
 * which is exactly why CI stayed green.
 *
 * [NUL_ROOM_KEY] is chosen so the derived identity for [SERVICE_TYPE] contains a `0x00` (at byte 15).
 * That is the whole point: this suite must exercise the ~12% case on purpose rather than by luck.
 */
class NwPskNulIdentityTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"

        /** Derives an identity containing `0x00` at byte 15 under [SERVICE_TYPE] — the failing ~12% case. */
        const val NUL_ROOM_KEY = "loopback-secret-3"

        /** The suite's ordinary key: derives a NUL-free identity, i.e. the lucky 88% CI always hit. */
        const val NUL_FREE_ROOM_KEY = "loopback-secret"
    }

    private val apis = mutableListOf<RealNwApi>()

    @AfterTest
    fun tearDown() = runBlocking {
        apis.forEach { api ->
            api.stopListening()
            api.stopBrowsing()
            api.cancelPathMonitor()
        }
        apis.clear()
    }

    /**
     * The structural guarantee, independent of any transport: the identity must be printable ASCII, so
     * it is valid UTF-8 (RFC 4279 §5.1) and survives every C-string-based PSK stack in the ecosystem
     * (Apple's TLS-1.2 path, OpenSSL's pre-1.3 PSK callback, wolfSSL — all take `char*`).
     *
     * A property, not a golden vector: it must hold for adversarial inputs, so the ~12% case cannot
     * silently return.
     */
    @Test
    fun derivedIdentityIsPrintableAsciiForEveryInput() {
        val offenders = buildList {
            for (n in 0..200) {
                val id = NwPsk.derive("probe-$n", SERVICE_TYPE).identity
                if (id.any { it < 0x20 || it > 0x7E }) add("probe-$n" to id.firstOrNull { it < 0x20 || it > 0x7E })
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "PSK identity must be printable ASCII (RFC 4279 §5.1 UTF-8; C-string-safe). " +
                "${offenders.size}/201 derived identities contain non-printable bytes, e.g. ${offenders.take(3)}",
        )
    }

    /** A NUL byte specifically is the fatal case — it truncates the identity in Apple's TLS-1.2 PSK path. */
    @Test
    fun derivedIdentityNeverContainsNul() {
        val withNul = (0..200).map { "probe-$it" }.filter { 0.toByte() in NwPsk.derive(it, SERVICE_TYPE).identity }
        assertEquals(
            emptyList(), withNul,
            "PSK identity must never contain 0x00 — Apple's external-PSK path truncates it, " +
                "yielding errSSLUnknownPSKIdentity (-9864) and a room that can never connect (#1577)",
        )
    }

    /**
     * The behavioural proof over a REAL TLS-PSK loopback link: a room whose derived identity contains a
     * NUL must still weave. Before the fix this times out — the same failure two iPhones showed on
     * `_ksuite4a._tcp`, reproduced with no hardware.
     */
    @Test
    fun weavesOverRealTlsWhenDerivedIdentityContainsNul() = runBlocking {
        assertWeaves(NUL_ROOM_KEY)
    }

    /** Control: the key CI has always used. Proves the harness itself is sound. */
    @Test
    fun weavesOverRealTlsWithNulFreeIdentity() = runBlocking {
        assertWeaves(NUL_FREE_ROOM_KEY)
    }

    private suspend fun assertWeaves(roomKey: String) {
        val psk = NwPsk.derive(roomKey, SERVICE_TYPE)
        val rendezvous = NwLoopbackRendezvous()
        val hostApi = RealNwApi(psk, NwLoopbackConfig(dial = false, rendezvous = rendezvous))
        val joinerApi = RealNwApi(psk, NwLoopbackConfig(dial = true, rendezvous = rendezvous))
        apis += hostApi
        apis += joinerApi
        val host = NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(0))
        val joiner = NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(1))

        val pattern = Pattern(sessionName = "host", roomKey = roomKey)
        val seams = withTimeoutOrNull(20.seconds) {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val h = async { host.weave(Rendezvous.New(pattern)) }
                    val j = async { joiner.weave(Rendezvous.New(pattern)) }
                    h.await() to j.await()
                }
            }
        }
        assertTrue(
            seams != null,
            "weave timed out for roomKey=$roomKey — derived identity " +
                "${if (0.toByte() in psk.identity) "CONTAINS a NUL byte" else "is NUL-free"}. " +
                "A NUL-bearing identity is truncated by Apple's TLS-1.2 external-PSK path → " +
                "errSSLUnknownPSKIdentity (-9864) → the room can never connect (#1577).",
        )
        val (h, j) = seams
        assertEquals(2, h.peers.value.size, "host should see 2 peers")
        h.close(CloseReason.Normal)
        j.close(CloseReason.Normal)
    }
}
