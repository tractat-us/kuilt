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
 * What #2513's fix rests on: **the sent-set key's encoded length does not depend on the
 * endpoint.**
 *
 * The obvious fix for the `base.hashCode()` collision was to put the URL in the key
 * verbatim, and it is wrong for a reason that is invisible from the key alone. A
 * file-backed `DurableStore` percent-encodes the key name onto a filename — every byte
 * outside `[a-z0-9-]` costing three rather than one — and the encoder deliberately
 * promises no length bound. Past that bound the failure is **not** loud:
 *
 * - `FileChannelDurableStore.read` guards on `File.exists()`, which answers `false` for
 *   a too-long name rather than throwing, so the read degrades to an empty digest;
 * - the matching `write` *does* throw, but `WarpOtlpBridge.drain` catches it, logs at
 *   debug, and reports only a generic failure.
 *
 * Steady state is every drain re-POSTing the whole buffer, forever, visible only at debug
 * level — and in the band where one key fits and another does not, `drain` returns
 * `DrainResult.Success` while a signal's sent-set silently never persists. A fixed-width
 * tag removes the class, and this test is what reds if the key ever becomes
 * endpoint-shaped again.
 *
 * Measured **through a real filesystem** rather than against the encoder: the encoder is
 * `internal` to `:kuilt-store`, and a re-implementation of its rule here would agree with
 * itself forever. JVM-only for the same reason — it is the target where the test can hold
 * a real POSIX directory — but `NAME_MAX` is 255 bytes on APFS, ext4, XFS and NTFS alike,
 * so the bound is not JVM-specific even though the measurement is.
 */
class OtlpHttpEdgeSentSetFilenameTest {

    /**
     * The real ceiling on an entry name — **251**, not `NAME_MAX`'s 255.
     *
     * Both file backends write through a sibling temp file (`FileChannelDurableStore`'s
     * `key.filename + ".tmp"`, and `NSFileManagerDurableStore`'s `"$dest.tmp"`), and the
     * temp file is written *first*. So the four bytes of `.tmp` are what actually bind:
     * a 252-byte entry name writes fine on its own and fails through the store.
     */
    private val entryNameMaxBytes = 255 - ".tmp".length

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
     * Endpoints chosen to span the *widest* range of encoded lengths a verbatim key would
     * have produced — from a 21-character localhost URL to a 300-character one that mixes
     * every expensive case at once (uppercase, an embedded credential, an IPv6 literal, a
     * long token path). Under a fixed-width tag they must all encode to one length; under
     * a verbatim key the last one alone would have been unwritable.
     */
    private val endpoints = listOf(
        "http://localhost:4318",
        "https://collector.example.com:4318",
        "https://otlp-gateway-prod-us-central-0.grafana.net/otlp",
        "https://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:4318",
        "https://otel-collector-gateway.observability.platform.internal" +
            ".us-east-1.prod.example-corp.com:4318/otlp",
        "https://collector.example.com:4318/v1/T0kEnAbCdEfGhIjKlMnOpQrStUvWxYz012345",
        // Pathological, and the arm that matters: a verbatim key here encodes past 251 and
        // the entry becomes unwritable. Also carries a credential, which a verbatim key
        // would have written into a directory listing.
        "https://svc-account:S3cr3tT0k3n@[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:4318" +
            "/v1/TENANT-A1B2C3D4E5F6/PROJECT-9Z8Y7X6W5V4U/INGEST-QWERTYUIOPASDFGHJKL" +
            "/otlp/COLLECTOR-ENDPOINT-WITH-A-DELIBERATELY-RIDICULOUS-PATH-PREFIX-0123456789",
    )

    @Test
    fun theSentSetKeyLengthDoesNotDependOnTheEndpoint() = runTest {
        val dir = Files.createTempDirectory("otlp-sent-set-2513").toFile()
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }

        // One store shared by every endpoint — the shape #2513 is about, and what makes
        // the listing below the union of all their filenames. All three signals are
        // exercised, so the measurement covers the longest of the three key names.
        val store = FileChannelDurableStore(dir)
        endpoints.forEachIndexed { index, endpoint ->
            val edge = OtlpHttpEdge(HttpClient(engine), endpoint, store)
            edge.send(setOf(span(index.toByte())))
            edge.sendLogs(emptySet())
            edge.sendMetrics(emptySet())
        }

        val files = dir.listFiles().orEmpty().map { it.name }
        val byteLengths = files.map { it.toByteArray(Charsets.UTF_8).size }
        val overLimit = files.filter { it.toByteArray(Charsets.UTF_8).size > entryNameMaxBytes }

        // Read every sent-set back through a fresh edge, so the properties asserted below
        // are properties of files that were really written to and really read from disk.
        val unrecovered = endpoints.filterIndexed { index, endpoint ->
            !OtlpHttpEdge(HttpClient(engine), endpoint, store)
                .digest().spanIds.contains(ByteString(ByteArray(8) { index.toByte() }))
        }

        // A verbatim key over this corpus would have spanned well over 200 bytes of
        // spread. Three distinct lengths — one per signal prefix — is the whole claim.
        val distinctLengths = byteLengths.distinct().sorted()

        assertAll(
            {
                // The rig has to have fired: an empty directory would pass every assertion
                // below by asserting nothing at all.
                assertEquals(
                    endpoints.size * keysPerEndpoint,
                    files.size,
                    "each endpoint must write its own three sent-set files, but the store holds: $files",
                )
            },
            {
                assertEquals(
                    keysPerEndpoint,
                    distinctLengths.size,
                    "encoded key length must vary only with the signal prefix, never with the endpoint, " +
                        "but ${endpoints.size} endpoints produced lengths $distinctLengths",
                )
            },
            {
                assertTrue(
                    overLimit.isEmpty(),
                    "every sent-set key must encode within $entryNameMaxBytes bytes, but these did not: " +
                        overLimit.map { "${it.toByteArray(Charsets.UTF_8).size}B $it" },
                )
            },
            {
                assertTrue(
                    unrecovered.isEmpty(),
                    "every endpoint's sent-set must read back from its own file, but these did not: $unrecovered",
                )
            },
            {
                // The tag must not be the endpoint wearing a hat: no filename may carry a
                // recognisable piece of the URL, least of all the credential.
                val leaked = files.filter { it.contains("collector", ignoreCase = true) || it.contains("S3cr3t") }
                assertTrue(leaked.isEmpty(), "the endpoint must not appear in a filename, but these do: $leaked")
            },
        )

        dir.deleteRecursively()
    }
}
