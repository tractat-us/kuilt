@file:OptIn(ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpSignal
import us.tractat.kuilt.otel.SpanKind
import us.tractat.kuilt.otel.SpanLink
import us.tractat.kuilt.otel.SpanRecord
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One signal's request, split by encoded size (#3286): every body under the cap in bytes, every
 * record sent once, and the sent-set folded per acknowledged chunk and written once per signal.
 *
 * Item bodies and names are mostly CJK text, three UTF-8 bytes to a character, so a split that
 * counted characters rather than bytes would overshoot the cap and fail the size assertions.
 * Each test asserts the precondition that makes its property reachable: that the request did
 * split, that the rigged answer fired, that a hook ran where it was placed.
 */
class OtlpHttpEdgeSplitTest {

    private val endpoint = "https://c.example:4318"
    private val resource = mapOf("service.name" to "svc-split")
    private val scope = "us.tractat.kuilt.test" to "1.0"

    private enum class Signal(val path: String) { SPANS("/v1/traces"), LOGS("/v1/logs"), METRICS("/v1/metrics") }

    /** Answers each POST with [answer], recording the request and the status it was given. */
    private class Collector(
        private val wire: OtlpWireFormat,
        var answer: (index: Int, post: Post) -> HttpStatusCode = { _, _ -> HttpStatusCode.OK },
    ) {
        val posts = mutableListOf<Post>()
        val statuses = mutableListOf<HttpStatusCode>()

        /** Runs inside every POST, before it is answered. */
        var during: (suspend (index: Int) -> Unit)? = null

        val client: HttpClient = HttpClient(
            MockEngine { req ->
                val raw = when (val b = req.body) {
                    is TextContent -> b.text.encodeToByteArray()
                    is OutgoingContent.ByteArrayContent -> b.bytes()
                    else -> error("unexpected body ${b::class}")
                }
                val post = decode(req.url.encodedPath, wire, raw)
                val index = posts.size
                posts += post
                during?.invoke(index)
                val status = answer(index, post)
                statuses += status
                respond("{}", status)
            },
        )

        /** The posts this collector answered with a 2xx. */
        val accepted: List<Post> get() = posts.filterIndexed { i, _ -> statuses.getOrNull(i)?.isSuccess() == true }
    }

    /**
     * Counts durable writes per sent-set key family (`spans`, `logs`, `metrics`). Every read and
     * write first yields, as a real store suspends on I/O: so a cancelled caller is refused at the
     * store unless it is shielded, and two sends on one edge interleave unless something serializes
     * them. An in-memory store never suspends, and would hide both.
     */
    private class CountingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        val writes = mutableMapOf<String, Int>()
        var refuseNextMetricWrite = false
        var refusedWrites = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? {
            yield()
            return delegate.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            yield()
            val family = key.name.removePrefix("otlp.sent.").substringBefore('@')
            if (family == "metrics" && refuseNextMetricWrite) {
                refuseNextMetricWrite = false
                refusedWrites++
                error("rigged: write refused")
            }
            delegate.write(key, bytes)
            writes[family] = (writes[family] ?: 0) + 1
        }

        override suspend fun delete(key: StoreKey) = delegate.delete(key)
    }

    // ── Builders: item i carries its index in its id, body or name ─────────────

    private fun cjk(i: Int) = "日本語のテキスト".repeat(5) + "-$i"

    private fun id8(i: Int) = ByteString(ByteArray(8) { b -> if (b < 4) 0 else (i shr (8 * (7 - b))).toByte() })

    private fun span(i: Int) =
        SpanRecord(ByteString(ByteArray(16) { 1 }), id8(i), null, "span-$i-${cjk(i)}", SpanKind.INTERNAL, 1L, 2L)

    private fun log(i: Int) = LogRecord(recordId = id8(i), body = "log-$i-${cjk(i)}")

    private fun metricKey(i: Int) = MetricKey("m.$i.${cjk(i)}", MetricKind.SUM)

    private fun metric(i: Int, value: Long = i.toLong()) =
        MetricPoint.Sum(metricKey(i), value, startEpochNanos = 0L, timeEpochNanos = 1L)

    private fun edge(
        collector: Collector,
        store: DurableStore,
        wire: OtlpWireFormat,
        cap: Int,
        url: String = endpoint,
    ) = OtlpHttpEdge(
        collector.client, url, store, wire = wire,
        resource = resource, scopeName = scope.first, scopeVersion = scope.second, maxRequestBytes = cap,
    )

    private suspend fun OtlpHttpEdge.sendSignal(signal: Signal, items: List<Int>, links: List<SpanLink> = emptyList()) {
        when (signal) {
            Signal.SPANS -> send(items.mapTo(LinkedHashSet()) { span(it) }, links)
            Signal.LOGS -> sendLogs(items.mapTo(LinkedHashSet()) { log(it) })
            Signal.METRICS -> sendMetrics(items.mapTo(LinkedHashSet()) { metric(it) })
        }
    }

    /** What the sent-set on disk holds for [signal], read through a fresh edge with no cache. */
    private suspend fun onDisk(store: DurableStore, signal: Signal): Set<Int> {
        val fresh = OtlpHttpEdge(Collector(OtlpWireFormat.JSON).client, endpoint, store)
        return when (signal) {
            Signal.SPANS -> fresh.digest().spanIds.mapTo(HashSet()) { it.index() }
            Signal.LOGS -> fresh.logDigest().recordIds.mapTo(HashSet()) { it.index() }
            Signal.METRICS -> fresh.metricDigest().versions.keys.mapTo(HashSet()) { it.name.metricIndex() }
        }
    }

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    // ── 1. The fast path ─────────────────────────────────────────────────────────

    @Test
    fun aRequestUnderTheCapGoesInOnePost() = runTest {
        for (wire in OtlpWireFormat.entries) {
            val collector = Collector(wire)
            val store = CountingStore()
            val cap = 4096
            edge(collector, store, wire, cap).sendSignal(Signal.LOGS, (1..5).toList())
            assertAll(
                { assertTrue(collector.posts.single().bytes <= cap, "$wire: precondition, the request fits the cap") },
                { assertEquals(listOf((1..5).toList()), collector.posts.map { it.items }, "$wire: one POST, every record") },
                { assertEquals(mapOf("logs" to 1), store.writes, "$wire: one write") },
            )
        }
    }

    // ── 2 and 9. The split, on both wires, for every signal ─────────────────────

    @Test
    fun aRequestOverTheCapSplitsIntoChunksUnderTheCapOnBothWires() = runTest {
        val cap = 2048
        val items = (1..60).toList()
        val checks = mutableListOf<() -> Unit>()
        for (wire in OtlpWireFormat.entries) for (signal in Signal.entries) {
            val collector = Collector(wire)
            val store = CountingStore()
            edge(collector, store, wire, cap).sendSignal(signal, items)
            val posts = collector.posts
            val sentItems = posts.flatMap { it.items }
            val disk = onDisk(store, signal)
            val tag = "$wire $signal"
            checks += listOf(
                { assertTrue(posts.size >= 3, "$tag: precondition, the request split into >= 3 POSTs, got ${posts.size}") },
                { assertTrue(posts.sumOf { it.bytes } > 2 * cap, "$tag: precondition, the delta is over twice the cap") },
                {
                    assertTrue(
                        posts.dropLast(1).all { it.items.size >= 3 },
                        "$tag: precondition, the cap holds >= 3 items a chunk: ${posts.map { it.items.size }}",
                    )
                },
                { assertTrue(posts.all { it.path == signal.path }, "$tag: every POST goes to ${signal.path}") },
                { assertTrue(posts.all { it.bytes <= cap }, "$tag: every body is under the cap: ${posts.map { it.bytes }}") },
                { assertEquals(items, sentItems.sorted(), "$tag: every record is sent exactly once") },
                { assertEquals(items, sentItems, "$tag: in delta order") },
                { assertTrue(posts.all { it.resource == resource && it.scope == scope }, "$tag: every chunk carries the envelope") },
                { assertEquals(items.toSet(), disk, "$tag: the sent-set holds the whole delta") },
                { assertEquals(mapOf(signal.family to 1), store.writes, "$tag: one sent-set write for the signal") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    // ── 3. A refused chunk ───────────────────────────────────────────────────────

    @Test
    fun aRefusedChunkKeepsOnlyTheDeliveredChunksAndTheNextDrainSendsTheRest() = runTest {
        val collector = Collector(OtlpWireFormat.JSON) { index, _ ->
            if (index == 1) HttpStatusCode.InternalServerError else HttpStatusCode.OK
        }
        val store = CountingStore()
        val edge = edge(collector, store, OtlpWireFormat.JSON, cap = 2048)
        val t = WarpTelemetry(ReplicaId("device-3286"), InMemoryDurableStore()).also { it.recover() }
        val items = (1..40).toList()
        t.logs.export(items.map { log(it) })
        val bridge = WarpOtlpBridge(t, clock)

        val failed = bridge.drain(edge)
        val firstChunk = collector.posts.first().items
        val disk = onDisk(store, Signal.LOGS)
        val postsInFirstDrain = collector.posts.size
        collector.answer = { _, _ -> HttpStatusCode.OK }
        val retried = bridge.drain(edge)
        val retriedItems = collector.posts.drop(postsInFirstDrain).flatMap { it.items }

        assertAll(
            { assertEquals(2, postsInFirstDrain, "precondition: the first drain stopped at the refused chunk") },
            { assertEquals(HttpStatusCode.InternalServerError, collector.statuses.getOrNull(1), "precondition: the 500 fired") },
            { assertTrue(firstChunk.size in 3 until items.size, "precondition: chunk 1 is a real chunk, $firstChunk") },
            { assertIs<DrainResult.Failure>(failed, "the refused chunk fails the log leg") },
            { assertEquals(OtlpSignal.LOGS, (failed as DrainResult.Failure).failures.single().signal) },
            { assertEquals(firstChunk.toSet(), disk, "the sent-set holds exactly the acknowledged chunk") },
            { assertEquals(DrainResult.Success(spansSent = 0, logsSent = items.size - firstChunk.size), retried) },
            { assertEquals((items - firstChunk.toSet()).sorted(), retriedItems.sorted(), "the next drain sends only the rest") },
        )
    }

    // ── 4. Cancellation between chunks ───────────────────────────────────────────

    @Test
    fun aCancellationBetweenChunksStillRecordsTheDeliveredChunks() = runTest {
        val collector = Collector(OtlpWireFormat.JSON)
        val store = CountingStore()
        val edge = edge(collector, store, OtlpWireFormat.JSON, cap = 2048)
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        collector.during = { index ->
            if (index == 1) {
                reached.complete(Unit)
                gate.await()
            }
        }
        val job = launch { edge.sendSignal(Signal.LOGS, (1..40).toList()) }
        job.invokeOnCompletion { reached.complete(Unit) }
        reached.await()
        val postsWhenCancelled = collector.posts.size
        job.cancel()
        job.join()
        val disk = onDisk(store, Signal.LOGS)

        assertAll(
            { assertEquals(2, postsWhenCancelled, "precondition: cancelled inside the second POST") },
            { assertEquals(1, collector.accepted.size, "precondition: exactly one chunk was acknowledged") },
            { assertTrue(job.isCancelled, "precondition: the send was cancelled") },
            { assertEquals(collector.accepted.single().items.toSet(), disk, "the delivered chunk is recorded") },
            { assertEquals(mapOf("logs" to 1), store.writes, "in one write") },
        )
    }

    // ── 5. A record bigger than the cap ──────────────────────────────────────────

    @Test
    fun anOversizedRecordGoesAloneAfterEveryNormalChunk() = runTest {
        val cap = 2048
        val big = 99
        val items = (1..20).toList() + big
        val checks = mutableListOf<() -> Unit>()
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.PayloadTooLarge, HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError)) {
            val collector = Collector(OtlpWireFormat.JSON) { _, post ->
                if (big in post.items) status else HttpStatusCode.OK
            }
            val store = CountingStore()
            val edge = edge(collector, store, OtlpWireFormat.JSON, cap)
            // The big record first in delta order, so "last" is the edge's choice, not the input's.
            val records = LinkedHashSet<LogRecord>().apply {
                add(LogRecord(recordId = id8(big), body = "log-$big-" + "大".repeat(cap)))
                addAll((1..20).map { log(it) })
            }
            val outcome = runCatchingCancellable { edge.sendLogs(records) }
            val folded = status == HttpStatusCode.OK || status == HttpStatusCode.PayloadTooLarge
            val disk = onDisk(store, Signal.LOGS)
            checks += listOf(
                { assertTrue(collector.posts.size >= 3, "$status: precondition, the normal records split, ${collector.posts.size}") },
                { assertTrue(collector.posts.last().bytes > cap, "$status: precondition, the big record is over the cap") },
                { assertEquals(listOf(big), collector.posts.last().items, "$status: the big record goes alone") },
                { assertTrue(collector.posts.dropLast(1).none { big in it.items }, "$status: and last, after every normal chunk") },
                { assertEquals(status, collector.statuses.last(), "$status: precondition, the rigged answer fired") },
                { assertEquals(folded, outcome.isSuccess, "$status: the send succeeds only when the record is folded") },
                { assertEquals(if (folded) items.toSet() else items.toSet() - big, disk, "$status: the sent-set") },
                { assertEquals(mapOf("logs" to 1), store.writes, "$status: one write") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    // ── 6. A proxy limit below the cap ───────────────────────────────────────────

    @Test
    fun aProxyLimitBelowTheCapIsMetByHalvingOn413() = runTest {
        val proxyLimit = 1200
        val collector = Collector(OtlpWireFormat.JSON) { _, post ->
            if (post.bytes > proxyLimit) HttpStatusCode.PayloadTooLarge else HttpStatusCode.OK
        }
        val store = CountingStore()
        val items = (1..40).toList()
        edge(collector, store, OtlpWireFormat.JSON, cap = 4096).sendSignal(Signal.LOGS, items)
        val accepted = collector.accepted
        val disk = onDisk(store, Signal.LOGS)
        assertAll(
            { assertTrue(collector.statuses.count { it == HttpStatusCode.PayloadTooLarge } >= 1, "precondition: a 413 fired") },
            { assertTrue(accepted.all { it.bytes <= proxyLimit }, "every accepted body is under the proxy's limit") },
            { assertEquals(items, accepted.flatMap { it.items }.sorted(), "every record is delivered exactly once") },
            { assertEquals(items.toSet(), disk, "and recorded") },
            { assertEquals(mapOf("logs" to 1), store.writes, "in one write") },
        )
    }

    @Test
    fun a400IsNeverReadAsTooBig() = runTest {
        val checks = mutableListOf<() -> Unit>()
        for (count in listOf(5, 40)) {
            val collector = Collector(OtlpWireFormat.JSON) { _, _ -> HttpStatusCode.BadRequest }
            val store = CountingStore()
            val outcome = runCatchingCancellable {
                edge(collector, store, OtlpWireFormat.JSON, cap = 2048).sendSignal(Signal.LOGS, (1..count).toList())
            }
            val disk = onDisk(store, Signal.LOGS)
            checks += listOf(
                { assertTrue(outcome.isFailure, "$count records: a 400 fails the send") },
                { assertEquals(1, collector.posts.size, "$count records: a 400 is not split and retried") },
                { assertEquals(emptySet(), disk, "$count records: nothing recorded") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Ruling 1 folds a `413` on a lone record only when that record is bigger than this edge's own
     * cap. A record inside the cap that is still refused means something in front of the collector
     * caps bodies below [OtlpHttpEdge]'s `maxRequestBytes`. Folding it would halve every chunk down
     * to single records, record them all as sent, and report the drain a success while the collector
     * holds nothing. Two ways to reach a lone record: halving a refused chunk, and a one-record
     * batch on the fast path.
     */
    @Test
    fun a413OnALoneRecordInsideTheCapFailsTheSignal() = runTest {
        val cap = 2048
        val checks = mutableListOf<() -> Unit>()
        for (count in listOf(40, 1)) {
            val collector = Collector(OtlpWireFormat.JSON) { _, _ -> HttpStatusCode.PayloadTooLarge }
            val store = CountingStore()
            val t = WarpTelemetry(ReplicaId("device-3286"), InMemoryDurableStore()).also { it.recover() }
            t.logs.export((1..count).map { log(it) })
            val result = WarpOtlpBridge(t, clock).drain(edge(collector, store, OtlpWireFormat.JSON, cap))
            val disk = onDisk(store, Signal.LOGS)
            val last = collector.posts.lastOrNull()
            val tag = "$count records"
            checks += listOf(
                {
                    val reached = if (count == 1) collector.posts.size == 1 else collector.posts.first().items.size > 1
                    assertTrue(reached, "$tag: precondition, the fast path or a halved chunk: ${collector.posts.map { it.items.size }}")
                },
                { assertEquals(1, last?.items?.size, "$tag: precondition, a 413 reached a lone record") },
                { assertTrue((last?.bytes ?: Int.MAX_VALUE) <= cap, "$tag: precondition, and that record is inside the cap") },
                { assertTrue(collector.statuses.all { it == HttpStatusCode.PayloadTooLarge }, "$tag: precondition, every POST got a 413") },
                { assertIs<DrainResult.Failure>(result, "$tag: the drain fails, not Success: $result") },
                { assertEquals(emptySet(), disk, "$tag: nothing is recorded as sent") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * A proxy that takes the normal records but refuses one bigger record, still inside this edge's
     * cap. The chunks acknowledged before it are recorded; the refused record is not, and the send
     * stops there and fails, so the next drain retries it.
     */
    @Test
    fun a413OnALoneRecordInsideTheCapKeepsTheDeliveredChunks() = runTest {
        val cap = 4096
        val proxyLimit = 1200
        val big = 20
        val collector = Collector(OtlpWireFormat.JSON) { _, post ->
            if (post.bytes > proxyLimit) HttpStatusCode.PayloadTooLarge else HttpStatusCode.OK
        }
        val store = CountingStore()
        val records = (1..40).mapTo(LinkedHashSet()) { i ->
            if (i == big) LogRecord(recordId = id8(big), body = "log-$big-" + "大".repeat(400)) else log(i)
        }
        val outcome = runCatchingCancellable { edge(collector, store, OtlpWireFormat.JSON, cap).sendLogs(records) }
        val disk = onDisk(store, Signal.LOGS)
        val refusedAlone = collector.posts.filterIndexed { i, post ->
            post.items == listOf(big) && collector.statuses[i] == HttpStatusCode.PayloadTooLarge
        }
        val delivered = collector.accepted.flatMap { it.items }
        assertAll(
            { assertEquals(1, refusedAlone.size, "precondition: the big record was refused on its own") },
            { assertTrue(refusedAlone.single().bytes in (proxyLimit + 1)..cap, "precondition: over the proxy's limit, inside the cap") },
            { assertTrue(delivered.isNotEmpty(), "precondition: chunks were acknowledged before it") },
            { assertTrue(outcome.isFailure, "the send fails") },
            { assertEquals(listOf(big), collector.posts.last().items, "and stops at the refused record") },
            { assertEquals(delivered.toSet(), disk, "the acknowledged chunks are recorded, the refused record is not") },
            { assertEquals(mapOf("logs" to 1), store.writes, "in one write") },
        )
    }

    // ── 7. Links travel with their span ──────────────────────────────────────────

    @Test
    fun aSpanTravelsWithTheLinksItOwns() = runTest {
        val items = (1..30).toList()
        // Each span links to its predecessor, so links cross every chunk boundary.
        val links = items.drop(1).map { SpanLink(fromSpanId = id8(it), linkedTraceId = ByteString(ByteArray(16) { 1 }), linkedSpanId = id8(it - 1)) }
        val checks = mutableListOf<() -> Unit>()
        for (wire in OtlpWireFormat.entries) {
            val collector = Collector(wire)
            edge(collector, CountingStore(), wire, cap = 2048).sendSignal(Signal.SPANS, items, links)
            val sentLinks = collector.posts.flatMap { post -> post.links.flatMap { (from, to) -> to.map { from to it } } }
            checks += listOf(
                { assertTrue(collector.posts.size >= 2, "$wire: precondition, the spans split") },
                {
                    assertEquals(
                        items.drop(1).map { it to it - 1 },
                        sentLinks.sortedBy { it.first },
                        "$wire: every link is sent once, on its owning span",
                    )
                },
                { assertTrue(collector.posts.all { post -> post.items.containsAll(post.links.keys) }, "$wire: links only on spans in the body") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Two stamped copies of one span id can sit in one batch (see [SpanRecord]). Each must carry the
     * span's links once, as an unsplit request always did, not once per copy.
     */
    @Test
    fun twoCopiesOfOneSpanIdEachCarryItsLinksOnce() = runTest {
        val checks = mutableListOf<() -> Unit>()
        for (wire in OtlpWireFormat.entries) {
            val collector = Collector(wire)
            val copies = linkedSetOf(span(7), span(7).copy(name = "span-7-restamped"))
            val link = SpanLink(fromSpanId = id8(7), linkedTraceId = ByteString(ByteArray(16) { 1 }), linkedSpanId = id8(6))
            edge(collector, CountingStore(), wire, cap = 2048).send(copies, listOf(link))
            val post = collector.posts.single()
            checks += listOf(
                { assertEquals(listOf(7, 7), post.items, "$wire: precondition, both copies went in one request") },
                { assertEquals(listOf(listOf(6), listOf(6)), post.linkLists, "$wire: each copy carries the link once") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    // ── 8. Metric resend after a lost write ─────────────────────────────────────

    @Test
    fun aMetricResendAfterALostWriteCarriesTheSameTotals() = runTest {
        val collector = Collector(OtlpWireFormat.JSON)
        val store = CountingStore()
        val edge = edge(collector, store, OtlpWireFormat.JSON, cap = 2048)
        val t = WarpTelemetry(ReplicaId("device-3286"), InMemoryDurableStore(), maxMetrics = 100).also { it.recover() }
        val bridge = WarpOtlpBridge(t, clock)
        val items = (1..30).toList()
        items.forEach { t.metrics.incrementSum(metricKey(it), by = it.toLong()) }

        store.refuseNextMetricWrite = true
        val lost = bridge.drain(edge)
        val firstPosts = collector.posts.size
        val resent = bridge.drain(edge)
        val resentValues = collector.posts.drop(firstPosts).flatMap { it.values.entries }.associate { it.key to it.value }
        items.forEach { t.metrics.incrementSum(metricKey(it)) }
        val bumped = bridge.drain(edge)
        val latest = collector.posts.flatMap { it.values.entries }.associate { it.key to it.value }

        assertAll(
            { assertTrue(firstPosts >= 3, "precondition: the first drain split into >= 3 POSTs, got $firstPosts") },
            { assertEquals(1, store.refusedWrites, "precondition: the write was refused") },
            { assertIs<DrainResult.Failure>(lost, "a lost write fails the metric leg") },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = items.size), resent, "the next drain resends all") },
            { assertEquals(items.associateWith { "$it" }, resentValues, "a resend carries the same cumulative totals") },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = items.size), bumped) },
            { assertEquals(items.associateWith { "${it + 1}" }, latest, "the collector's last word is the producer's total") },
        )
    }

    // ── Construction ─────────────────────────────────────────────────────────────

    @Test
    fun aCapTooSmallForOneRecordFailsAtConstruction() {
        val client = Collector(OtlpWireFormat.JSON).client
        assertAll(
            { assertFailsWith<IllegalArgumentException> { OtlpHttpEdge(client, endpoint, InMemoryDurableStore(), maxRequestBytes = 16) } },
            {
                assertFailsWith<IllegalArgumentException> {
                    OtlpHttpEdge(client, endpoint, InMemoryDurableStore(), resource = mapOf("k" to "v".repeat(4096)), maxRequestBytes = 4096)
                }
            },
            // The control: the same resource under a cap that holds it constructs fine.
            { OtlpHttpEdge(client, endpoint, InMemoryDurableStore(), resource = mapOf("k" to "v".repeat(4096)), maxRequestBytes = 8192) },
        )
    }

    // ── A concurrent writer between chunks ───────────────────────────────────────

    /**
     * Two sends on one edge at once, each recording its ids. The store yields inside every read and
     * write, so unless the edge serializes its read-merge-write the two interleave: both read the
     * same set, and the second write drops the first's ids.
     */
    @Test
    fun twoConcurrentSendsOnOneEdgeKeepEachOthersIds() = runTest {
        val collector = Collector(OtlpWireFormat.JSON)
        val store = CountingStore()
        val edge = edge(collector, store, OtlpWireFormat.JSON, cap = 2048)
        val a = (1..40).toList()
        val b = (101..140).toList()
        val first = launch { edge.sendSignal(Signal.LOGS, a) }
        val second = launch { edge.sendSignal(Signal.LOGS, b) }
        first.join()
        second.join()
        val disk = onDisk(store, Signal.LOGS)
        assertAll(
            { assertTrue(collector.posts.size >= 6, "precondition: both sends split, ${collector.posts.size} POSTs") },
            { assertEquals(2, store.writes["logs"], "precondition: each send wrote once") },
            { assertEquals((a + b).toSet(), disk, "neither send's write drops the other's ids") },
        )
    }

    /**
     * A second edge on the same store and endpoint (#1053) writes while the first is between
     * chunks. The first edge's one write per signal is built after its last chunk, from a fresh
     * read, so it must keep what the second recorded.
     */
    @Test
    fun aWriteByAnotherEdgeBetweenChunksIsKept() = runTest {
        val store = CountingStore()
        val firstCollector = Collector(OtlpWireFormat.JSON)
        val secondCollector = Collector(OtlpWireFormat.JSON)
        val first = edge(firstCollector, store, OtlpWireFormat.JSON, cap = 2048)
        val second = edge(secondCollector, store, OtlpWireFormat.JSON, cap = 2048, url = "$endpoint/")
        val items = (1..40).toList()
        val other = 500
        val g = MetricKey("queue.depth", MetricKind.GAUGE)
        var logHookFired = 0
        firstCollector.during = { index ->
            if (index == 1) {
                logHookFired++
                second.sendLogs(setOf(log(other)))
            }
        }
        first.sendSignal(Signal.LOGS, items)
        val logsOnDisk = onDisk(store, Signal.LOGS)
        val postsBeforeMetrics = firstCollector.posts.size
        var metricHookFired = 0
        firstCollector.during = { index ->
            if (index == postsBeforeMetrics + 1) {
                metricHookFired++
                second.sendMetrics(setOf(MetricPoint.Gauge(g, value = 3.0, timeEpochNanos = 3L)))
            }
        }
        first.sendSignal(Signal.METRICS, items)
        val versions = OtlpHttpEdge(Collector(OtlpWireFormat.JSON).client, endpoint, store).metricDigest().versions

        assertAll(
            { assertTrue(postsBeforeMetrics >= 3, "precondition: the log send split, $postsBeforeMetrics POSTs") },
            { assertTrue(firstCollector.posts.size - postsBeforeMetrics >= 3, "precondition: the metric send split") },
            { assertEquals(1, logHookFired, "precondition: the second edge wrote between log chunks") },
            { assertEquals(1, metricHookFired, "precondition: the second edge wrote between metric chunks") },
            { assertEquals(2, secondCollector.posts.size, "precondition: both of the second edge's sends were acknowledged") },
            { assertEquals(items.toSet() + other, logsOnDisk, "the first edge's log write keeps the second's record") },
            { assertEquals(3.0.toRawBits(), versions[g], "the first edge's metric write keeps the second's gauge") },
            { assertTrue(items.all { metricKey(it) in versions }, "and records its own series") },
        )
    }

    private val Signal.family: String
        get() = when (this) {
            Signal.SPANS -> "spans"
            Signal.LOGS -> "logs"
            Signal.METRICS -> "metrics"
        }
}

private val lenient = Json { ignoreUnknownKeys = true }

private fun ByteString.index(): Int = toHexString().toLong(16).toInt()

private fun ByteArray.index(): Int = ByteString(this).index()

private fun String.hexIndex(): Int = toLong(16).toInt()

private fun String.logIndex(): Int = substringAfter("log-").substringBefore('-').toInt()

/** The index a [metricKey] name carries, or -1 for a series the builders did not make. */
private fun String.metricIndex(): Int = if (startsWith("m.")) substringAfter("m.").substringBefore('.').toInt() else -1

private fun jsonAttrs(attrs: List<KeyValue>?): Map<String, String>? = attrs?.associate { it.key to it.value.stringValue }

private fun protoAttrs(attrs: List<ProtoKeyValue>?): Map<String, String>? = attrs?.associate { it.key to it.value.stringValue }

/** One request as the collector decoded it. Items are the indices the builders below encode. */
private class Post(
    val path: String,
    val bytes: Int,
    val items: List<Int>,
    val links: Map<Int, List<Int>>,
    val values: Map<Int, String>,
    val resource: Map<String, String>?,
    val scope: Pair<String, String>?,
    /** Each span's links, in span order; unlike [links], keeps two copies of one span id apart. */
    val linkLists: List<List<Int>> = emptyList(),
)

private fun decode(path: String, wire: OtlpWireFormat, raw: ByteArray): Post = when (wire) {
    OtlpWireFormat.JSON -> when (path) {
        "/v1/traces" -> lenient.decodeFromString(TracesRequest.serializer(), raw.decodeToString()).resourceSpans.single().let { rs ->
            val ss = rs.scopeSpans.single()
            Post(
                path, raw.size, ss.spans.map { it.spanId.hexIndex() },
                ss.spans.filter { it.links.isNotEmpty() }.associate { s -> s.spanId.hexIndex() to s.links.map { it.spanId.hexIndex() } },
                emptyMap(), jsonAttrs(rs.resource?.attributes), ss.scope?.let { it.name to it.version },
                linkLists = ss.spans.map { s -> s.links.map { it.spanId.hexIndex() } },
            )
        }
        "/v1/logs" -> lenient.decodeFromString(LogsRequest.serializer(), raw.decodeToString()).resourceLogs.single().let { rl ->
            val sl = rl.scopeLogs.single()
            Post(
                path, raw.size, sl.logRecords.map { it.body!!.stringValue.logIndex() }, emptyMap(), emptyMap(),
                jsonAttrs(rl.resource?.attributes), sl.scope?.let { it.name to it.version },
            )
        }
        "/v1/metrics" -> lenient.decodeFromString(MetricsRequest.serializer(), raw.decodeToString()).resourceMetrics.single().let { rm ->
            val sm = rm.scopeMetrics.single()
            Post(
                path, raw.size, sm.metrics.map { it.name.metricIndex() }, emptyMap(),
                sm.metrics.filter { it.sum != null }.associate { it.name.metricIndex() to it.sum!!.dataPoints.single().asInt!! },
                jsonAttrs(rm.resource?.attributes), sm.scope?.let { it.name to it.version },
            )
        }
        else -> error("unexpected path $path")
    }
    OtlpWireFormat.PROTOBUF -> when (path) {
        "/v1/traces" -> ProtoBuf.decodeFromByteArray(ProtoTracesRequest.serializer(), raw).resourceSpans.single().let { rs ->
            val ss = rs.scopeSpans.single()
            Post(
                path, raw.size, ss.spans.map { it.spanId.index() },
                ss.spans.filter { it.links.isNotEmpty() }.associate { s -> s.spanId.index() to s.links.map { it.spanId.index() } },
                emptyMap(), protoAttrs(rs.resource?.attributes), ss.scope?.let { it.name to it.version },
                linkLists = ss.spans.map { s -> s.links.map { it.spanId.index() } },
            )
        }
        "/v1/logs" -> ProtoBuf.decodeFromByteArray(ProtoLogsRequest.serializer(), raw).resourceLogs.single().let { rl ->
            val sl = rl.scopeLogs.single()
            Post(
                path, raw.size, sl.logRecords.map { it.body!!.stringValue.logIndex() }, emptyMap(), emptyMap(),
                protoAttrs(rl.resource?.attributes), sl.scope?.let { it.name to it.version },
            )
        }
        "/v1/metrics" -> ProtoBuf.decodeFromByteArray(ProtoMetricsRequest.serializer(), raw).resourceMetrics.single().let { rm ->
            val sm = rm.scopeMetrics.single()
            Post(
                path, raw.size, sm.metrics.map { it.name.metricIndex() }, emptyMap(),
                sm.metrics.filter { it.sum != null }.associate { it.name.metricIndex() to it.sum!!.dataPoints.single().asInt!!.toString() },
                protoAttrs(rm.resource?.attributes), sm.scope?.let { it.name to it.version },
            )
        }
        else -> error("unexpected path $path")
    }
}
