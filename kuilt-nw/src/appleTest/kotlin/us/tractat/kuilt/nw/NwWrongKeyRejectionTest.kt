package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback harness — a real Network.framework socket needs a real IO dispatcher; there is no virtual-time option here
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **authentication half** of the TLS-PSK proof: a peer whose PSK is derived from a *different*
 * `roomKey` cannot connect. [NwLoopbackConformanceTest] proves a *matched* PSK drives a real
 * encrypted link; this proves a *mismatched* PSK does not — together they show the `roomKey` (the
 * out-of-band shared secret) is what gates membership, not merely reachability.
 *
 * ## Why this is the honest proof, not a re-statement of TLS reasoning
 * `secureParams` installs **only** a PSK — no certificate identity — so there is no non-PSK
 * completion path a mismatched peer could fall back to: the TLS-PSK handshake is the sole way a
 * connection reaches `ready`. This test locks that in. A future regression that added a fallback
 * identity (or otherwise weakened `secureParams`) would let the wrong-key joiner connect and this
 * test would go red — the guard the [NwLoopbackConformanceTest] happy path cannot give, since it
 * only ever exercises matched keys.
 *
 * ## Harness (mirrors [NwLoopbackConformanceTest], no Bonjour/AWDL)
 * A host [RealNwApi] (PSK from [ROOM_KEY]) binds an ephemeral `127.0.0.1` listener and publishes its
 * real bound port into a shared [NwLoopbackRendezvous]; a joiner [RealNwApi] (PSK from
 * [WRONG_ROOM_KEY]) awaits that port and dials it. The loopback interface always connects at the TCP
 * layer, so the *only* thing that can stop the link forming is the TLS-PSK mismatch — the joiner's
 * handshake fails, no `connectionOpened` fires, and its `weave` times out into
 * [NwUnreachableException] within the injected [WEAVE_TIMEOUT].
 */
class NwWrongKeyRejectionTest {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"
        const val WRONG_ROOM_KEY = "loopback-WRONG-secret"

        /** Small so a mismatched joiner fails fast; the real handshake still runs and rejects within it. */
        val WEAVE_TIMEOUT = 5.seconds

        /** Outer guard so a bug can never hang the runner — comfortably above [WEAVE_TIMEOUT]. */
        val HARNESS_TIMEOUT = 30.seconds
    }

    private val apis = mutableListOf<RealNwApi>()

    @AfterTest
    fun tearDown() = runBlocking {
        apis.forEach { api ->
            api.stopListening()
            api.stopBrowsing()
        }
        apis.clear()
    }

    @Test
    fun joinerWithDifferentRoomKeyCannotConnectAndWeaveThrowsUnreachable() = runBlocking(Dispatchers.Default) {
        withTimeout(HARNESS_TIMEOUT) {
            val rendezvous = NwLoopbackRendezvous()
            val hostApi = RealNwApi(NwPsk.derive(ROOM_KEY, SERVICE_TYPE), NwLoopbackConfig(dial = false, rendezvous = rendezvous))
            val joinerApi = RealNwApi(NwPsk.derive(WRONG_ROOM_KEY, SERVICE_TYPE), NwLoopbackConfig(dial = true, rendezvous = rendezvous))
            apis += hostApi
            apis += joinerApi

            val hostLoom = NwLoom(hostApi, serviceType = SERVICE_TYPE, random = Random(0), weaveTimeout = WEAVE_TIMEOUT)
            val joinerLoom = NwLoom(joinerApi, serviceType = SERVICE_TYPE, random = Random(1), weaveTimeout = WEAVE_TIMEOUT)

            // Drive the host in the background so it binds the listener and publishes the port the joiner
            // dials. It will never resolve a peer (the joiner's handshake is rejected), so it too times out
            // — swallow that; the joiner's outcome is the assertion. Cancelled in `finally` so no loop leaks.
            val hostScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            hostScope.launch {
                runCatchingCancellable { hostLoom.weave(Rendezvous.New(Pattern(sessionName = "host", roomKey = ROOM_KEY))) }
            }

            try {
                val failure = assertFailsWith<NwUnreachableException> {
                    joinerLoom.weave(Rendezvous.New(Pattern(sessionName = "host", roomKey = WRONG_ROOM_KEY)))
                }
                assertTrue(
                    failure.message?.contains("timed out") == true,
                    "wrong-key joiner must fail as an unreachable weave (no peer resolved), got: ${failure.message}",
                )
            } finally {
                hostScope.cancel()
            }
        }
    }
}
