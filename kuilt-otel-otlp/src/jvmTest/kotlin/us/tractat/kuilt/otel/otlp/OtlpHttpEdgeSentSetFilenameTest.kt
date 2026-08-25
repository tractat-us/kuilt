package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What #2513's fix now rests on.
 *
 * Putting the endpoint URL into the sent-set key verbatim closes the collision and
 * trades it for a length question: a file-backed `DurableStore` percent-encodes the key
 * name onto a filename — every byte outside `[a-z0-9-]` costing three rather than one —
 * and that encoder deliberately promises **no** length bound. So the new load-bearing
 * property is "a realistic collector URL still addresses a writable file", and this is
 * what reds when it stops being true.
 *
 * Deliberately measured **through a real filesystem** rather than against the encoder:
 * the encoder is `internal` to `:kuilt-store`, and a re-implementation of its rule here
 * would agree with itself forever. Writing the entries and reading the directory back
 * asks the filesystem, which is the thing that actually refuses. JVM-only for the same
 * reason — it is the target where the test can hold a real POSIX directory. `NAME_MAX`
 * is 255 bytes on APFS, ext4, XFS and NTFS alike, so the bound is not JVM-specific even
 * though the measurement is.
 *
 * ## Two arms, because they fail on different filesystems
 *
 * On a 255-byte filesystem an over-long key never reaches the length assertion: the
 * write itself throws, which is how it was confirmed to red — a base of 240 `a`s gives
 * `FileNotFoundException: … (File name too long)` out of [OtlpHttpEdge.send]. That is
 * also the reassuring half of the trade: the failure mode swapped in by #2513 is a loud
 * write error, never the silent under-delivery it removed. The explicit byte-length
 * arm is what survives a **laxer** filesystem, where the write succeeds here and the
 * same key would fail on the tightest target the consumer ships to.
 */
class OtlpHttpEdgeSentSetFilenameTest {

    /** POSIX `NAME_MAX`, in bytes — the ceiling on one path component. */
    private val nameMaxBytes = 255

    /** Spans, logs and metrics each get their own key; `metrics` is the longest. */
    private val keysPerEndpoint = 3

    private fun span(b: Byte) = SpanRecord(
        ByteString(ByteArray(16) { b }),
        ByteString(ByteArray(8) { b }),
        null,
        "op",
        SpanKind.INTERNAL,
        1L,
        2L,
    )

    /**
     * Collector URLs a consumer plausibly configures. The last two are the stress arms:
     * a long internal FQDN with a path prefix (every `.` and `/` triples), and a path
     * segment of mixed-case token text (every uppercase letter triples too).
     */
    private val realisticEndpoints = listOf(
        "http://localhost:4318",
        "https://collector:4318",
        "https://collector.example.com:4318",
        "https://otlp-gateway-prod-us-central-0.grafana.net/otlp",
        "http://otel-collector.observability.svc.cluster.local:4318",
        "https://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:4318",
        "https://otel-collector-gateway.observability.platform.internal" +
            ".us-east-1.prod.example-corp.com:4318/otlp",
        "https://collector.example.com:4318/v1/T0kEnAbCdEfGhIjKlMnOpQrStUvWxYz012345",
    )

    @Test
    fun everyRealisticCollectorUrlAddressesAWritableFile() = runTest {
        val dir = Files.createTempDirectory("otlp-sent-set-2513").toFile()
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }

        // One store shared by every endpoint — the same shape #2513 is about, and what
        // makes the listing below the union of all their filenames. All three signals
        // are exercised so the measurement covers the longest of the three key names.
        val store = FileChannelDurableStore(dir)
        realisticEndpoints.forEachIndexed { index, endpoint ->
            val edge = OtlpHttpEdge(HttpClient(engine), endpoint, store)
            edge.send(setOf(span(index.toByte())))
            edge.sendLogs(emptySet())
            edge.sendMetrics(emptySet())
        }

        val files = dir.listFiles().orEmpty().map { it.name }
        val overLimit = files.filter { it.toByteArray(Charsets.UTF_8).size > nameMaxBytes }

        // Read every sent-set back through a fresh edge, so the bound asserted below is
        // a property of files that were really written to and really read from disk.
        val unrecovered = realisticEndpoints.filterIndexed { index, endpoint ->
            !OtlpHttpEdge(HttpClient(engine), endpoint, store)
                .digest().spanIds.contains(ByteString(ByteArray(8) { index.toByte() }))
        }

        assertAll(
            {
                // The rig has to have fired: an empty directory would pass the bound
                // assertion by asserting nothing at all.
                assertEquals(
                    realisticEndpoints.size * keysPerEndpoint,
                    files.size,
                    "each endpoint must write its own three sent-set files, but the store holds: $files",
                )
            },
            {
                assertTrue(
                    overLimit.isEmpty(),
                    "a realistic collector URL must encode within $nameMaxBytes bytes, but these did not: " +
                        overLimit.map { "${it.toByteArray(Charsets.UTF_8).size}B $it" },
                )
            },
            {
                assertTrue(
                    unrecovered.isEmpty(),
                    "every endpoint's sent-set must read back from its own file, but these did not: $unrecovered",
                )
            },
        )

        dir.deleteRecursively()
    }
}
