package us.tractat.kuilt.multipeer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Pattern

/**
 * CLI-style probes for cross-process Multipeer bisection. Two bundled
 * `.app` instances on one Mac drive the host (advertise + accept) and
 * joiner (browse + invite) roles independently — fills the gap left by
 * `Phase0SelfTest` which only exercises the **single-process** self-pair.
 *
 * Symptom under investigation (2026-05-13 status log): after fixing the
 * discovering-browser bug, the iPhone↔Mac handshake still stalls at
 * `Connecting` despite discovery succeeding in both directions.
 * `--mc-phase0-test` passes in 86ms intra-process, so the new failure
 * mode is cross-process-specific.
 *
 * Each probe writes structured log lines tagged `[host]` / `[joiner]`
 * so two simultaneous logs interleave readably. Pair with
 * `log show --predicate 'process == "us.tractat.fireworks"' --info`
 * for the Apple framework view alongside this Kotlin trace.
 */
public object MultipeerCrossProcessProbe {
    public data class Result(
        val passed: Boolean,
        val message: String,
    )

    /**
     * Host role. Advertises forever (until [timeoutMs]), logs every peer-
     * set transition and every incoming frame. Does **not** spin up a
     * `LiveLeader` — this is pure transport-layer observation so a stuck
     * `Connecting` can be attributed to either the MC layer or downstream.
     */
    public fun runHost(
        displayName: String,
        timeoutMs: Long = 5L * 60L * 1000L,
        nowMs: () -> Long = System::currentTimeMillis,
        log: (String) -> Unit = ::println,
    ): Result {
        log("[host] starting factory displayName=$displayName serviceType=${MultipeerService.SERVICE_TYPE}")
        val factory = MultipeerPeerLinkFactory(displayName)
        val t0 = nowMs()
        return try {
            runBlocking {
                val link =
                    runCatching { factory.open(Pattern(displayName)) }
                        .getOrElse { e ->
                            log("[host] factory.open failed: ${e::class.simpleName}: ${e.message}")
                            return@runBlocking Result(passed = false, message = "open failed: ${e.message}")
                        }
                log("[host] link opened in ${nowMs() - t0}ms; selfId=${link.selfId.value}; waiting for peers…")
                val peersJob =
                    launch {
                        link.peers.collect { current ->
                            log("[host] peers=${current.map { it.value }} (elapsed ${nowMs() - t0}ms)")
                        }
                    }
                val incomingJob =
                    launch {
                        link.incoming.collect { frame ->
                            log(
                                "[host] frame from=${frame.sender?.value} bytes=${frame.payload.size} " +
                                    "(elapsed ${nowMs() - t0}ms)",
                            )
                        }
                    }
                try {
                    withTimeout(timeoutMs) { awaitCancellation() }
                } catch (_: TimeoutCancellationException) {
                    log("[host] timeout after ${timeoutMs}ms — exiting cleanly")
                }
                peersJob.cancel()
                incomingJob.cancel()
                runCatching { link.close() }
                Result(passed = true, message = "host ran cleanly for ${nowMs() - t0}ms")
            }
        } finally {
            runCatching { (factory as? MultipeerPeerLinkFactory)?.close() }
        }
    }

    /**
     * Joiner role. Browses for peers, ignores anything not starting with
     * [targetPrefix] (skips self if the host has a different prefix),
     * invites the first match, logs every peer-set transition and incoming
     * frame on the resulting link. The discoveries Flow stays collected
     * across the join — same constraint as the lobby.
     */
    public fun runJoinFirst(
        displayName: String,
        targetPrefix: String,
        discoveryTimeoutMs: Long = 30L * 1000L,
        joinTimeoutMs: Long = 15L * 1000L,
        runForMs: Long = 60L * 1000L,
        nowMs: () -> Long = System::currentTimeMillis,
        log: (String) -> Unit = ::println,
    ): Result {
        log(
            "[joiner] starting factory displayName=$displayName targetPrefix=$targetPrefix " +
                "serviceType=${MultipeerService.SERVICE_TYPE}",
        )
        val factory = MultipeerPeerLinkFactory(displayName)
        val browser = MultipeerServiceBrowser(factory)
        val t0 = nowMs()
        return try {
            runBlocking {
                val firstAd = CompletableDeferred<MultipeerAdvertisement>()
                val browseJob =
                    launch {
                        browser.discoveries().collect { ad ->
                            if (ad is MultipeerAdvertisement) {
                                if (ad.displayName.startsWith(targetPrefix) && !firstAd.isCompleted) {
                                    log(
                                        "[joiner] found target=${ad.displayName} handle=${ad.handle} " +
                                            "(elapsed ${nowMs() - t0}ms)",
                                    )
                                    firstAd.complete(ad)
                                } else {
                                    log("[joiner] saw (skipped) ${ad.displayName}")
                                }
                            }
                        }
                    }
                val ad =
                    runCatching { withTimeout(discoveryTimeoutMs) { firstAd.await() } }
                        .getOrElse { e ->
                            log("[joiner] discovery timeout: ${e::class.simpleName}: ${e.message}")
                            browseJob.cancel()
                            return@runBlocking Result(
                                passed = false,
                                message = "no peer matching '$targetPrefix' within ${discoveryTimeoutMs}ms",
                            )
                        }

                log("[joiner] invoking factory.join (timeout ${joinTimeoutMs}ms)")
                val tInvite = nowMs()
                val link =
                    runCatching { withTimeout(joinTimeoutMs) { factory.join(ad) } }
                        .getOrElse { e ->
                            log("[joiner] factory.join failed: ${e::class.simpleName}: ${e.message}")
                            browseJob.cancel()
                            return@runBlocking Result(
                                passed = false,
                                message = "factory.join failed: ${e.message}",
                            )
                        }
                log("[joiner] factory.join returned in ${nowMs() - tInvite}ms; selfId=${link.selfId.value}; awaiting peer connection…")

                val peersJob =
                    launch {
                        link.peers.collect { current ->
                            log("[joiner] peers=${current.map { it.value }} (elapsed ${nowMs() - t0}ms)")
                        }
                    }
                val incomingJob =
                    launch {
                        link.incoming.collect { frame ->
                            log(
                                "[joiner] frame from=${frame.sender?.value} bytes=${frame.payload.size} " +
                                    "(elapsed ${nowMs() - t0}ms)",
                            )
                        }
                    }
                try {
                    withTimeout(runForMs) { awaitCancellation() }
                } catch (_: TimeoutCancellationException) {
                    log("[joiner] runForMs=${runForMs}ms elapsed — exiting cleanly")
                }
                peersJob.cancel()
                incomingJob.cancel()
                browseJob.cancel()
                runCatching { link.close() }
                Result(passed = true, message = "joiner ran cleanly for ${nowMs() - t0}ms")
            }
        } finally {
            runCatching { (factory as? MultipeerPeerLinkFactory)?.close() }
        }
    }
}
