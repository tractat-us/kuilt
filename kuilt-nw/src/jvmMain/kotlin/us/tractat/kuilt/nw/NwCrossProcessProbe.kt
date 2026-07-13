package us.tractat.kuilt.nw

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * CLI-style probes for cross-process kuilt-nw bisection over the macOS JVM bridge.
 *
 * Two JVM processes (or a Mac JVM and an iPhone build) drive the **host** and
 * **joiner** roles independently, so a stalled real-radio handshake can be
 * attributed to the transport layer rather than anything downstream. This mirrors
 * `MultipeerCrossProcessProbe`: pure transport-layer observation — no consensus,
 * no room — that logs every peer-set transition and every incoming frame.
 *
 * The kuilt-nw fabric is a **symmetric full mesh scoped by `roomKey`**: both roles
 * advertise + browse + auto-dial on `weave`, and only peers holding the same
 * `roomKey` under the same `serviceType` complete the TLS-PSK handshake. There is
 * therefore no per-host targeting step (unlike Multipeer's browse-then-invite) —
 * the joiner needs only the shared `roomKey` and `serviceType`, the same pair the
 * host used.
 *
 * **macOS-only.** Both entry points short-circuit with a clean failed [Result]
 * (never a hang or a raw dylib-load error) when the native bridge is unavailable —
 * a non-macOS JVM, or a Mac without `libkuilt.dylib` on the path — so the probe is
 * safe to invoke anywhere and honest about what it did. Each probe writes lines
 * tagged `[host]` / `[joiner]` so two simultaneous logs interleave readably; pair
 * with `log show --predicate 'process == "java"' --info` for the Apple-framework
 * view alongside this Kotlin trace.
 */
public object NwCrossProcessProbe {
    public data class Result(
        val passed: Boolean,
        val message: String,
    )

    /**
     * Host role. Opens a session over the real radios and observes it until
     * [timeoutMs], logging every peer-set transition and incoming frame. Does not
     * drive any consensus — a stuck connection is thus attributable to the nw
     * transport alone.
     *
     * @param displayName this peer's human-readable label (also the session name).
     * @param serviceType the Bonjour service type to advertise and browse.
     * @param roomKey     the shared bearer secret the TLS-PSK is derived from.
     */
    public fun runHost(
        displayName: String,
        serviceType: String,
        roomKey: String,
        timeoutMs: Long = 5L * 60L * 1000L,
        nowMs: () -> Long = System::currentTimeMillis,
        log: (String) -> Unit = ::println,
    ): Result {
        unavailableReason()?.let { reason ->
            log("[host] nw bridge unavailable: $reason")
            return Result(passed = false, message = "nw bridge unavailable: $reason")
        }
        log("[host] hosting displayName=$displayName serviceType=$serviceType")
        val t0 = nowMs()
        return runBlocking {
            val seam =
                runCatchingCancellable { nwHost(Pattern(sessionName = displayName, roomKey = roomKey), serviceType) }
                    .getOrElse { e ->
                        log("[host] nwHost failed: ${e::class.simpleName}: ${e.message}")
                        return@runBlocking Result(passed = false, message = "host failed: ${e.message}")
                    }
            log("[host] wove in ${nowMs() - t0}ms; selfId=${seam.selfId.value}; observing…")
            val peersJob = launch {
                seam.peers.collect { current ->
                    log("[host] peers=${current.map { it.value }} (elapsed ${nowMs() - t0}ms)")
                }
            }
            val incomingJob = launch {
                seam.incoming.collect { frame ->
                    log("[host] frame from=${frame.sender?.value} bytes=${frame.payloadSize} (elapsed ${nowMs() - t0}ms)")
                }
            }
            try {
                withTimeout(timeoutMs) { awaitCancellation() }
            } catch (_: TimeoutCancellationException) {
                log("[host] timeout after ${timeoutMs}ms — exiting cleanly")
            }
            peersJob.cancel()
            incomingJob.cancel()
            runCatchingCancellable { seam.close() }
            Result(passed = true, message = "host ran cleanly for ${nowMs() - t0}ms")
        }
    }

    /**
     * Joiner role. Enters the session scoped by [roomKey] + [serviceType] (the mesh
     * auto-dials the host on discovery — no explicit invite), then observes peers
     * and incoming frames until [runForMs].
     *
     * @param displayName this peer's human-readable label and stable identity.
     * @param serviceType the Bonjour service type to browse — must match the host's.
     * @param roomKey     the shared bearer secret — must match the host's.
     */
    public fun runJoin(
        displayName: String,
        serviceType: String,
        roomKey: String,
        runForMs: Long = 60L * 1000L,
        nowMs: () -> Long = System::currentTimeMillis,
        log: (String) -> Unit = ::println,
    ): Result {
        unavailableReason()?.let { reason ->
            log("[joiner] nw bridge unavailable: $reason")
            return Result(passed = false, message = "nw bridge unavailable: $reason")
        }
        log("[joiner] joining displayName=$displayName serviceType=$serviceType")
        val t0 = nowMs()
        return runBlocking {
            val tag = NwTag(sessionName = displayName, peerKey = displayName, roomKey = roomKey)
            val seam =
                runCatchingCancellable { nwJoin(tag, serviceType) }
                    .getOrElse { e ->
                        log("[joiner] nwJoin failed: ${e::class.simpleName}: ${e.message}")
                        return@runBlocking Result(passed = false, message = "join failed: ${e.message}")
                    }
            log("[joiner] wove in ${nowMs() - t0}ms; selfId=${seam.selfId.value}; observing…")
            val peersJob = launch {
                seam.peers.collect { current ->
                    log("[joiner] peers=${current.map { it.value }} (elapsed ${nowMs() - t0}ms)")
                }
            }
            val incomingJob = launch {
                seam.incoming.collect { frame ->
                    log("[joiner] frame from=${frame.sender?.value} bytes=${frame.payloadSize} (elapsed ${nowMs() - t0}ms)")
                }
            }
            try {
                withTimeout(runForMs) { awaitCancellation() }
            } catch (_: TimeoutCancellationException) {
                log("[joiner] runForMs=${runForMs}ms elapsed — exiting cleanly")
            }
            peersJob.cancel()
            incomingJob.cancel()
            runCatchingCancellable { seam.close() }
            Result(passed = true, message = "joiner ran cleanly for ${nowMs() - t0}ms")
        }
    }

    /** The bridge's unavailability reason, or `null` when the native fabric is usable on this JVM. */
    private fun unavailableReason(): String? =
        when (val a = NwNativeLib.jvmAvailability()) {
            is FabricAvailability.Unavailable -> a.reason
            FabricAvailability.Available -> null
        }
}
