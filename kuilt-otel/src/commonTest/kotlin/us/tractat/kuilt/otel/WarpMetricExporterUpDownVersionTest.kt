package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version an up-down level is written at (#3307, ruled on #3546). A merge keeps each replica's
 * entry at its higher version, so a level reaches a peer that already holds an older one only if its
 * version is higher. The version therefore comes from one counter per replica, claimed durably
 * ahead of use (`otel.metrics.updown.version`), never from the series, so it survives what drops a
 * series here: an eviction, a [WarpMetricExporter.clear], a [MetricUpdate.Forget] and a restart.
 *
 * A peer here is what a tap puller is: it joins every [WarpMetricExporter.snapshotAll] it is shown
 * and never sends anything back. Every arm asserts the peer held the old level first.
 */
class WarpMetricExporterUpDownVersionTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")
    private val inFlight = MetricKey("http.in_flight", MetricKind.UP_DOWN_SUM)
    private val other = MetricKey("other", MetricKind.UP_DOWN_SUM)
    private val requests = MetricKey("requests", MetricKind.SUM)

    /** A tap puller's view: everything it was shown, joined. */
    private class Peer {
        var catalog = MetricCatalog()
            private set

        fun see(exporter: WarpMetricExporter) {
            catalog = catalog.piece(exporter.snapshotAll())
        }

        fun level(key: MetricKey): Long? = catalog.upDownSums[key]?.upDownTotalOrNull()

        fun doubleLevel(key: MetricKey): Double? = catalog.doubleUpDownSums[key]?.upDownTotal()
    }

    private suspend fun exporter(replica: ReplicaId, store: DurableStore, maxMetrics: Int = DEFAULT_MAX_METRICS) =
        WarpMetricExporter(replica = replica, store = store, maxMetrics = maxMetrics).also { it.recover() }

    // ── (a) A dropped series heals at a peer on the next set ─────────────────────────────────

    /** One of the two up-down maps, as a peer and a writer see it. */
    private class Flavor(
        val name: String,
        val set: suspend WarpMetricExporter.(MetricKey, Long) -> Unit,
        val peerLevel: (Peer, MetricKey) -> Double?,
        val held: (MetricCatalog, MetricKey) -> Boolean,
    )

    private val flavors = listOf(
        Flavor("long", { key, level -> setUpDownSum(key, level) }, { peer, key -> peer.level(key)?.toDouble() }) { catalog, key ->
            key in catalog.upDownSums
        },
        Flavor("double", { key, level -> setUpDownSumDouble(key, level.toDouble()) }, { peer, key -> peer.doubleLevel(key) }) { catalog, key ->
            key in catalog.doubleUpDownSums
        },
    )

    /** One way this exporter drops a series. */
    private class Drop(val name: String, val maxMetrics: Int, val action: suspend WarpMetricExporter.(Flavor, MetricKey) -> Unit)

    private val drops = listOf(
        // The cap is one series, so a set of another evicts it.
        Drop("evict", maxMetrics = 1) { flavor, _ -> flavor.set(this, other, 1L) },
        Drop("clear", maxMetrics = DEFAULT_MAX_METRICS) { _, _ -> clear() },
        Drop("forget", maxMetrics = DEFAULT_MAX_METRICS) { _, key -> record(listOf(MetricUpdate.Forget(key))) },
    )

    @Test
    fun aSeriesDroppedHereReachesAPeerAtItsNewLevel() = runTest {
        val checks = drops.flatMap { drop ->
            flavors.flatMap { flavor ->
                val name = "${drop.name}, ${flavor.name}"
                val onA = exporter(a, RecordingStore(), drop.maxMetrics)
                val peer = Peer()
                flavor.set(onA, inFlight, 10L)
                peer.see(onA)
                val before = flavor.peerLevel(peer, inFlight)
                drop.action(onA, flavor, inFlight)
                val dropped = !flavor.held(onA.snapshotAll(), inFlight)
                flavor.set(onA, inFlight, 4L)
                peer.see(onA)
                listOf<() -> Unit>(
                    { assertEquals(10.0, before, "$name: the rig: the peer held 10 first") },
                    { assertTrue(dropped, "$name: the rig: the series was dropped here") },
                    { assertEquals(4.0, flavor.peerLevel(peer, inFlight), "$name: the peer kept the old level") },
                )
            }
        }
        assertAll(*checks.toTypedArray())
    }

    // ── (b) And so does one a restart dropped ────────────────────────────────────────────────

    @Test
    fun aRestartBeforeOrAfterAClearStillReachesAPeerAtTheNewLevel() = runTest {
        class Arm(val name: String, val between: suspend (RecordingStore, WarpMetricExporter) -> WarpMetricExporter)
        val arms = listOf(
            Arm("restart") { store, _ -> exporter(a, store) },
            Arm("clear, then restart") { store, onA -> onA.clear(); exporter(a, store) },
            Arm("restart, then clear") { store, _ -> exporter(a, store).also { it.clear() } },
            Arm("clear, then restart without recover") { store, onA -> onA.clear(); WarpMetricExporter(a, store) },
        )
        val checks = arms.flatMap { arm ->
            val store = RecordingStore()
            val onA = exporter(a, store)
            val peer = Peer()
            onA.setUpDownSum(inFlight, 10L)
            peer.see(onA)
            val before = peer.level(inFlight)
            val next = arm.between(store, onA)
            val set = next.setUpDownSum(inFlight, 4L)
            peer.see(next)
            listOf<() -> Unit>(
                { assertEquals(10L, before, "${arm.name}: the rig: the peer held 10 first") },
                { assertEquals(MetricExportResult.Success, set, "${arm.name}: the set") },
                { assertEquals(4L, peer.level(inFlight), "${arm.name}: the peer kept the old level") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    // ── (c) A level published but never made durable is overruled after a restart ────────────

    /**
     * A refused write leaves the level in memory, where a tap puller can read it. The process dies
     * before any write lands, and comes back with the durable level, the one before. Its first set,
     * even of that same durable level, must overrule what the peer saw, whichever way round the two
     * are joined. Writing nothing leaves the peer at 5 for good; writing at the durable entry's
     * version plus one ties the peer's, and a tie keeps whichever side the join started from.
     */
    @Test
    fun aLevelPublishedButNeverDurableIsOverruledByTheFirstSetAfterARestart() = runTest {
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing) { it.name == "otel.metrics.updown" }
        val onA = exporter(a, store)
        onA.setUpDownSum(inFlight, 0L)
        store.refuseWrites()
        val refused = onA.setUpDownSum(inFlight, 5L)
        val published = onA.snapshotAll()
        // The process dies; the next one finds the level before the refused write.
        val restarted = exporter(a, backing)
        val durable = restarted.upDownSumValue(inFlight)
        restarted.setUpDownSum(inFlight, 0L)
        val fresh = restarted.snapshotAll()
        assertAll(
            { assertIs<MetricExportResult.Partial>(refused, "the rig: the write of 5 was refused") },
            { assertEquals(5L, published.upDownSums.getValue(inFlight).upDownTotalOrNull(), "the rig: 5 was published") },
            { assertEquals(0L, durable, "the rig: the restart read back 0") },
            { assertEquals(0L, published.piece(fresh).upDownSums.getValue(inFlight).upDownTotalOrNull(), "peer first") },
            { assertEquals(0L, fresh.piece(published).upDownSums.getValue(inFlight).upDownTotalOrNull(), "fresh first") },
        )
    }

    // ── (d) A refused claim refuses the up-down updates, and only them ───────────────────────

    @Test
    fun aRefusedVersionClaimLeavesTheUpDownUpdatesUncountedAndTheRestLands() = runTest {
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing) { it.name == "otel.metrics.updown.version" }
        val onA = exporter(a, store)
        store.refuseWrites()
        val upDown = listOf(
            MetricUpdate.SetUpDownSum(inFlight, 3L),
            MetricUpdate.SetUpDownSumDouble(inFlight, 1.5),
        )
        val result = onA.record(upDown + MetricUpdate.IncrementSum(requests, 2L))
        val catalog = onA.snapshotAll()
        val restarted = exporter(a, backing)
        assertAll(
            { assertTrue(store.refusedWrites() >= 1, "the rig: the store refused the claim") },
            { assertEquals(upDown, assertIs<MetricExportResult.Partial>(result).uncounted, "the up-down updates are uncounted") },
            { assertTrue(catalog.upDownSums.isEmpty() && catalog.doubleUpDownSums.isEmpty(), "and unpublished: $catalog") },
            { assertEquals(2L, onA.sumValue(requests), "the sum still landed") },
            { assertEquals(2L, restarted.sumValue(requests), "durably") },
            { assertTrue(restarted.snapshotAll().upDownSums.isEmpty(), "no up-down map was written") },
        )
    }

    // ── (e) A refused read of the claim fails closed ─────────────────────────────────────────

    @Test
    fun aRefusedReadOfTheVersionClaimAdmitsNoUpDownUpdateUntilItLands() = runTest {
        val backing = RecordingStore()
        val peer = Peer()
        exporter(a, backing).also { it.setUpDownSum(inFlight, 10L); peer.see(it) }
        val writesBefore = backing.operations().count { it.kind == StoreOpKind.WRITE }
        val store = ReadRefusingStore(backing, StoreKey("otel.metrics.updown.version"))
        val restarted = WarpMetricExporter(a, store)
        val refused = restarted.setUpDownSum(inFlight, 4L)
        val writesWhileRefused = backing.operations().count { it.kind == StoreOpKind.WRITE } - writesBefore
        store.heal()
        // Past the claim's backoff window, a set reads the claim and lands.
        var landed: MetricExportResult? = null
        for (turn in 1..BACKOFF_TURNS) {
            landed = restarted.setUpDownSum(inFlight, 4L)
            if (landed == MetricExportResult.Success) break
        }
        peer.see(restarted)
        assertAll(
            { assertTrue(store.refusals >= 1, "the rig: the store refused the claim's read") },
            {
                val partial = assertIs<MetricExportResult.Partial>(refused)
                assertEquals(listOf(MetricUpdate.SetUpDownSum(inFlight, 4L)), partial.uncounted)
                assertIs<MetricKindUnreadException>(partial.causes.single())
            },
            { assertEquals(0, writesWhileRefused, "the refused set wrote") },
            { assertEquals(MetricExportResult.Success, landed, "a set lands once the claim is read") },
            { assertEquals(4L, peer.level(inFlight), "at a version past the claim") },
        )
    }

    // ── (f) A merge never takes this replica's own entry from elsewhere ──────────────────────

    @Test
    fun aMergeKeepsPeersEntriesButNeverThisReplicasOwn() = runTest {
        class Arm(val name: String, val drop: suspend WarpMetricExporter.() -> Unit)
        val arms = listOf(
            Arm("forget") { record(listOf(MetricUpdate.Forget(inFlight))) },
            Arm("clear") { clear() },
        )
        val checks = arms.flatMap { arm ->
            val onA = exporter(a, RecordingStore())
            val onB = exporter(b, RecordingStore())
            onA.setUpDownSum(inFlight, 10L)
            onB.setUpDownSum(inFlight, 4L)
            onA.setUpDownSumDouble(inFlight, 10.0)
            onB.setUpDownSumDouble(inFlight, 4.0)
            onA.mergeUpDownSum(inFlight, onB.upDownSumSnapshot(inFlight))
            onA.mergeUpDownSumDouble(inFlight, onB.upDownSumDoubleSnapshot(inFlight))
            val old = onA.upDownSumSnapshot(inFlight)
            val oldDouble = onA.upDownSumDoubleSnapshot(inFlight)
            arm.drop(onA)
            onA.mergeUpDownSum(inFlight, old)
            onA.mergeUpDownSumDouble(inFlight, oldDouble)
            listOf<() -> Unit>(
                { assertEquals(14L, old.upDownTotalOrNull(), "${arm.name}: the rig: the old snapshot holds both levels") },
                { assertNull(onA.upDownSumSnapshot(inFlight)[a], "${arm.name}: this replica's old level came back") },
                { assertEquals(4L, onA.upDownSumValue(inFlight), "${arm.name}: the peer's level") },
                { assertNull(onA.upDownSumDoubleSnapshot(inFlight)[a], "${arm.name}: the old fractional level came back") },
                { assertEquals(4.0, onA.upDownSumDoubleValue(inFlight), "${arm.name}: the peer's fractional level") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    // ── (g) No (series, version) is ever published with two levels ───────────────────────────

    /**
     * A store whose reads and writes of chosen keys the property turns away, and whose refused writes
     * may land anyway: a refused write's bytes are indeterminate, and a claim that landed while
     * reported refused must not let a version be minted twice either.
     */
    private class FickleStore : DurableStore {
        val backing = RecordingStore()
        private val lock = reentrantLock()
        private val refusedWrites = HashSet<String>()
        private val refusedReads = HashSet<String>()
        var landAnyway = false
        var writeRefusals = 0
            private set
        var readRefusals = 0
            private set

        fun refuseWrites(key: String, refuse: Boolean): Unit = lock.withLock { if (refuse) refusedWrites += key else refusedWrites -= key }
        fun refuseReads(key: String, refuse: Boolean): Unit = lock.withLock { if (refuse) refusedReads += key else refusedReads -= key }
        fun heal(): Unit = lock.withLock { refusedWrites.clear(); refusedReads.clear() }

        override suspend fun read(key: StoreKey): ByteArray? {
            if (lock.withLock { (key.name in refusedReads).also { if (it) readRefusals++ } }) error("refused read of ${key.name}")
            return backing.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            val refuse = lock.withLock { (key.name in refusedWrites).also { if (it) writeRefusals++ } }
            if (refuse) {
                if (landAnyway) backing.write(key, bytes)
                error("refused write of ${key.name}")
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    @Test
    fun noVersionIsEverPublishedWithTwoLevelsAndThePeerEndsAtTheLastLevel() = runTest {
        val keys = listOf(inFlight, other, MetricKey("third", MetricKind.UP_DOWN_SUM))
        val storeKeys = listOf("otel.metrics.updown", "otel.metrics.updown.double", "otel.metrics.updown.version")
        var restarts = 0
        var refusals = 0
        var claimsWritten = 0
        val violations = mutableListOf<String>()
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val store = FickleStore()
            // A lease of 2 renews the claim every other set, so the claim's own refusals are reached.
            suspend fun start() = WarpMetricExporter(a, store, maxMetrics = 2, versionLease = 2L).also {
                if (random.nextBoolean()) it.recover()
            }
            var onA = start()
            val peer = Peer()
            val seen = HashMap<Triple<String, MetricKey, Long>, Any>()
            fun publish() {
                peer.see(onA)
                val catalog = onA.snapshotAll()
                fun <V : Any> walk(map: String, series: Map<MetricKey, LWWMap<ReplicaId, V>>) {
                    series.keys.sortedBy { it.name }.forEach { key ->
                        val entries = series.getValue(key)
                        val version = entries.tagOf(a) ?: return@forEach
                        val level = entries[a] ?: return@forEach
                        val earlier = seen.getOrPut(Triple(map, key, version)) { level }
                        if (earlier != level) violations += "seed $seed: $map ${key.name} v$version published as $earlier and $level"
                    }
                }
                walk("long", catalog.upDownSums)
                walk("double", catalog.doubleUpDownSums)
            }
            repeat(OPS) {
                val key = keys[random.nextInt(keys.size)]
                when (random.nextInt(10)) {
                    0, 1, 2 -> onA.setUpDownSum(key, random.nextLong(3L))
                    3 -> onA.setUpDownSumDouble(key, random.nextInt(3) * 0.5)
                    4 -> onA.addUpDownSum(key, if (random.nextBoolean()) 1L else -1L)
                    5 -> onA.record(listOf(MetricUpdate.Forget(key)))
                    6 -> onA.clear()
                    7 -> {
                        restarts++
                        onA = start()
                    }
                    8 -> store.refuseWrites(storeKeys[random.nextInt(storeKeys.size)], random.nextBoolean())
                    else -> {
                        store.refuseReads(storeKeys[random.nextInt(storeKeys.size)], random.nextBoolean())
                        store.landAnyway = random.nextBoolean()
                    }
                }
                publish()
            }
            refusals += store.writeRefusals + store.readRefusals
            claimsWritten += store.backing.operations().count { it.kind == StoreOpKind.WRITE && it.key.name == "otel.metrics.updown.version" }
            // Heal, restart, and set every series to a level it never held: the peer must end there.
            store.heal()
            store.landAnyway = false
            onA = start()
            // The cap is two series, so each is published before the next set can evict it.
            keys.forEachIndexed { i, key ->
                repeat(BACKOFF_TURNS) {
                    if (onA.setUpDownSum(key, 100L + i) == MetricExportResult.Success) {
                        publish()
                        return@forEachIndexed
                    }
                }
            }
            keys.forEachIndexed { i, key ->
                if (peer.level(key) != 100L + i) violations += "seed $seed: the peer ends at ${peer.level(key)} for ${key.name}, not ${100L + i}"
            }
        }
        assertAll(
            { assertTrue(restarts >= SEEDS, "the rig: $restarts restarts over $SEEDS seeds") },
            { assertTrue(refusals >= SEEDS, "the rig: $refusals refusals over $SEEDS seeds") },
            { assertTrue(claimsWritten >= SEEDS * 2, "the rig: $claimsWritten claims written over $SEEDS seeds") },
            { assertEquals(emptyList<String>(), violations.take(10), "${violations.size} violations") },
        )
    }

    private companion object {
        const val SEEDS = 60
        const val OPS = 60

        /** More turns than the longest refused-read backoff window, so a healed store is read again. */
        const val BACKOFF_TURNS = 70
    }
}
