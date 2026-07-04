package harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.multipeer.MultipeerAdvertisement
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.multipeer.MultipeerServiceBrowser
import us.tractat.kuilt.otel.tap.LogTapAdmission
import us.tractat.kuilt.otel.tap.LogTapClient
import us.tractat.kuilt.otel.tap.LogTapConfig
import us.tractat.kuilt.otel.tap.MetricTapClient
import us.tractat.kuilt.otel.tap.MetricTapConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Mac-side puller for `docs/otel-tap-multipeer-validation.md`.
 *
 * Usage: `run.sh <log|metric> <code> [discoveryTimeoutSeconds]`
 *
 * Discovers the iPhone advertising the matching tap's service type, joins, presents
 * [code] (pass anything wrong to exercise the wrong-code checklist item), and prints
 * the pulled result. Run it again with a fresh code after toggling the phone's link
 * (off/on) to exercise the reconnect checklist item.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <log|metric> <code> [discoveryTimeoutSeconds]" }
    val mode = args[0]
    val code = args[1]
    val discoveryTimeout = (args.getOrNull(2)?.toLongOrNull() ?: 20L).seconds

    val serviceType = when (mode) {
        "log" -> LOG_SERVICE_TYPE
        "metric" -> METRIC_SERVICE_TYPE
        else -> error("unknown mode '$mode', expected 'log' or 'metric'")
    }

    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = MultipeerPeerLinkFactory("Mac", serviceType)
        val browser = MultipeerServiceBrowser(factory)

        // The browse Flow must stay collected across the join() call itself — cancelling it
        // (e.g. via Flow.first()) tears down the native browser before mc_runtime_join can use
        // it, which fails with "mc_runtime session open failed". See MultipeerCrossProcessProbe's
        // runJoinFirst KDoc: "discoveries Flow stays collected across the join".
        val firstAd = CompletableDeferred<MultipeerAdvertisement>()
        val browseJob = launch {
            browser.discoveries().collect { ad ->
                if (ad is MultipeerAdvertisement && !firstAd.isCompleted) firstAd.complete(ad)
            }
        }

        println("Discovering an iPhone advertising '$serviceType' (timeout ${discoveryTimeout})...")
        val advertisement = try {
            withTimeout(discoveryTimeout) { firstAd.await() }
        } catch (e: TimeoutCancellationException) {
            println("FAILED: no peer discovered within $discoveryTimeout — is the iPhone hosting this tap?")
            browseJob.cancelAndJoin()
            factory.close()
            return@runBlocking
        }
        println("Discovered ${advertisement.sessionName} (handle=${advertisement.peerKey}); joining...")

        try {
            val seam = (factory as Loom).join(advertisement)
            println("Joined as ${seam.selfId}. Presenting code and pulling (this can legitimately time out on a wrong code)...")

            when (mode) {
                "log" -> {
                    val client = LogTapClient(
                        seam,
                        scope,
                        LogTapConfig(pullTimeout = 30.seconds),
                        admission = LogTapAdmission.Present(code),
                    )
                    val records = client.pull()
                    println("PULLED ${records.size} record(s), in order:")
                    records.forEach { println("  - ${it.recordId} ${it.body}") }
                    client.close()
                }
                "metric" -> {
                    val client = MetricTapClient(
                        seam,
                        scope,
                        MetricTapConfig(pullTimeout = 30.seconds),
                        admission = LogTapAdmission.Present(code),
                    )
                    val snapshot = client.pull()
                    println("PULLED metric snapshot: $snapshot")
                    client.close()
                }
            }
            println("SUCCESS")
        } catch (e: TimeoutCancellationException) {
            println("PULL TIMED OUT — expected outcome for a wrong/expired code: $e")
        } finally {
            browseJob.cancelAndJoin()
            factory.close()
        }
    }
}
