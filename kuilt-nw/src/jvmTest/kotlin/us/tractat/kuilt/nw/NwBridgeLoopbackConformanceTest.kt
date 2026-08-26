package us.tractat.kuilt.nw

import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-network loopback conformance harness — a real Network.framework socket driven through the dylib needs a real IO dispatcher; there is no virtual-time option here
import org.junit.AssumptionViolatedException
import us.tractat.kuilt.conformance.CapabilityGaps
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag
import kotlin.test.AfterTest

/**
 * Verifies that [NwLoom] over the **JVM bridge** ([BridgeNwApi] → `libkuilt.dylib` → `RealNwApi`)
 * satisfies every invariant in [SeamConformanceSuite] on a macOS runner — a real `127.0.0.1` TLS-PSK
 * link, real GCD sockets, real bytes over the loopback interface, driven **through the cdecl bridge
 * from the JVM**. This is the JVM↔JVM analogue of the appleTest [NwLoopbackConformanceTest] (which
 * runs the same suite against `RealNwApi` directly on the K/N side). Together they close the last
 * *automated* test gap below real-hardware: the fabric's whole runtime stack — JNA marshalling,
 * `StableRef` runtime lifecycle, the callback→flow bridge, and the TLS-PSK handshake — is proven
 * end-to-end without a second device.
 *
 * ## Loopback mode over the bridge (no Bonjour, no AWDL)
 * Each side's native runtime is built with [NwNativeLib.nw_runtime_create_loopback] over a shared
 * [NwNativeLib.nw_loopback_rendezvous_create] handle: the HOST (`dial = 0`) binds an ephemeral
 * `127.0.0.1` listener and publishes its real bound port into the rendezvous; the JOINER (`dial = 1`)
 * awaits that port and dials it. No multicast, no network permissions, no second device.
 *
 * ## macOS + dylib gate (skips cleanly elsewhere)
 * [SeamConformanceSuite]'s test methods are inherited, so there is no place to put a per-method
 * `assumeTrue`. Instead [newLoomPair] throws [AssumptionViolatedException] off macOS or when the
 * dylib is absent — JUnit treats a thrown assumption violation as **skipped**, not failed. On a
 * macOS-arm64 host with the bundled dylib it genuinely RUNS.
 *
 * ## Real dispatcher, and a real-clock deadline ([loopbackLoomPair])
 * [NwLoom.weave] derives its seam scope from `currentCoroutineContext()`, so under the suite's
 * `runTest` virtual clock its `withTimeout` would fast-forward past the real socket connect.
 * [loopbackLoomPair] therefore dispatches each `weave` onto [Dispatchers.Default].
 *
 * Because the weave really does run on the wall clock, the deadline it fails at is a *test gate*, and
 * the same factory injects [LOOPBACK_CONFORMANCE_WEAVE_BACKSTOP] in place of production's shipped 30 s
 * so a contended runner is not indistinguishable from a broken fabric (#2386). It also arms [FAIL_FAST],
 * which is what keeps a deadline that generous affordable when the fabric really is broken. All three
 * live in that one factory precisely so this suite cannot forget any of them.
 */
class NwBridgeLoopbackConformanceTest : SeamConformanceSuite() {

    private companion object {
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val ROOM_KEY = "loopback-secret"

        /**
         * Suite-scoped, so it must live here: `kotlin.test` builds a fresh test-class instance per test
         * method, and an instance field would reset between the 30 tests and latch nothing.
         */
        val FAIL_FAST = LoopbackWeaveFailFast("NwBridgeLoopbackConformanceTest")
    }

    // Tracked for teardown. Bridges are close()d (which disposes the native runtime via the
    // exactly-once Cleaner gate — the double-destroy-safe path); rendezvous handles are disposed
    // directly AFTER the runtimes. If the test skipped (off macOS), all stay empty.
    private var lib: NwNativeLib? = null
    private val bridges = mutableListOf<BridgeNwApi>()
    private val rendezvousHandles = mutableListOf<Pointer>()

    @AfterTest
    fun tearDown() {
        // Runtimes before the rendezvous. close() cancels the drain scope AND disposes the native
        // runtime exactly once (its Cleaner and this close() share a CAS gate), so this never
        // double-destroys — even if a bridge later becomes GC-unreachable.
        // ALLOW-runCatching: non-suspend @AfterTest teardown over JNA handles — no coroutine context, and one failing close must not skip the rest.
        bridges.forEach { bridge -> runCatching { bridge.close() } }
        // ALLOW-runCatching: as above — non-suspend native handle disposal, best-effort per handle.
        rendezvousHandles.forEach { rv -> runCatching { lib?.nw_loopback_rendezvous_destroy(rv) } }
        bridges.clear()
        rendezvousHandles.clear()
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val loaded = NwNativeLib.load()
            ?: throw AssumptionViolatedException("libkuilt.dylib is macOS-only; this suite no-ops elsewhere.")
        lib = loaded
        check(loaded.kuilt_protocol_version() == NwNativeLib.EXPECTED_PROTOCOL_VERSION) {
            "Bridge ABI mismatch: dylib protocol version != expected ${NwNativeLib.EXPECTED_PROTOCOL_VERSION}"
        }

        val psk = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        // One shared rendezvous per pair: the host publishes its real bound port, the joiner awaits it.
        val rv = requireNotNull(loaded.nw_loopback_rendezvous_create()) {
            "nw_loopback_rendezvous_create returned null on macOS — stale or wrong-arch dylib"
        }
        rendezvousHandles += rv

        val hostHandle = requireNotNull(
            loaded.nw_runtime_create_loopback(psk.psk, psk.psk.size, psk.identity, psk.identity.size, rv, 0),
        ) { "nw_runtime_create_loopback(host) returned null on macOS — stale or wrong-arch dylib" }
        val joinerHandle = requireNotNull(
            loaded.nw_runtime_create_loopback(psk.psk, psk.psk.size, psk.identity, psk.identity.size, rv, 1),
        ) { "nw_runtime_create_loopback(joiner) returned null on macOS — stale or wrong-arch dylib" }

        val hostBridge = BridgeNwApi(loaded, hostHandle)
        val joinerBridge = BridgeNwApi(loaded, joinerHandle)
        bridges += hostBridge
        bridges += joinerBridge

        return loopbackLoomPair(
            failFast = FAIL_FAST,
            serviceType = SERVICE_TYPE,
            hostApi = hostBridge,
            joinerApi = joinerBridge,
            weaveDispatcher = Dispatchers.Default,
        )
    }

    override fun joinTag(): Tag = InMemoryTag(sessionName = "host", peerKey = "nw-bridge-loopback-joiner")

    /**
     * The loopback link is real TLS-PSK through the dylib, so `securesTransport = true` — this test IS
     * the JVM-bridge proof of that capability for kuilt-nw.
     *
     * `reportsLiveCapability = false`, though, because the flag tracks the **binding under test**, not
     * the abstract fabric — the same rule that makes `securesTransport` `true` here and `false` on
     * `NwConformanceTest`'s plaintext fake. [BridgeNwApi] does not override [NwApi.pathState], so it
     * inherits the shared never-updated `null` ("no monitor wired") and [NwSeam] therefore reports
     * availability `Unknown` forever on this binding — which is exactly what the `false` branch asserts.
     * The static loom report supplies only the ROLES. The observer is real on `RealNwApi` and proven by
     * `NwLoopbackConformanceTest` / `NwSeamCapabilityTest`; it is simply absent here (#1712).
     */
    override fun capabilities(): SeamCapabilities =
        SeamCapabilities.FULL.copy(securesTransport = true, reportsLiveCapability = false)

    override fun capabilityGaps(): Map<String, String> =
        mapOf("reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY)

    /**
     * No gap: [NwSeam] publishes its 16 MiB frame ceiling as of #2134, so this harness is held to the
     * number by [payloadOfExactlyTheBudgetIsCarried] and [overBudgetAddressedSendIsRefusedNotLeaked]
     * instead of declaring it away (#2069). That first case is the one that found #2134 — a payload of
     * exactly the budget arrives as 256+ chunks, which is what the lossy receive path could not survive.
     */
    override fun payloadBudgetGap(): String? = null

}
