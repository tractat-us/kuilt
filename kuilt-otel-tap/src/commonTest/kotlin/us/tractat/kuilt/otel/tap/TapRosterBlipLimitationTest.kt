@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A known limit, found in the review of #3100: a roster blip that only the puller sees silences the
 * pull for good. The puller drops the departed device from its admitted set, but the device never saw
 * the puller go, so nothing re-challenges and nothing re-proves. The gate logs it (see
 * `DroppedFrameLogTest`) and `module.md` says so; there is deliberately no recovery here.
 *
 * If this reds because a blip now recovers, delete it and fix "The honest limitations" in `module.md`.
 */
class TapRosterBlipLimitationTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = t0 }
    private val code = "CODE1234"
    private val config = LogTapConfig(quilterConfig = QuilterConfig(expectVirtualTime = true))

    private fun rec(i: Int, body: String) = LogRecord(recordId = ByteString(ByteArray(8) { i.toByte() }), body = body)

    /** A seam whose roster can be edited by the test, so only one side sees the blip. */
    private class RosterViewSeam(private val raw: Seam, scope: CoroutineScope) : Seam by raw {
        val view = MutableStateFlow(raw.peers.value)
        var mirror = true
        override val peers: StateFlow<Set<PeerId>> get() = view

        init {
            scope.launch { raw.peers.collect { if (mirror) view.value = it } }
        }
    }

    private suspend fun TestScope.pullWithOptionalBlip(blip: Boolean): List<String> {
        val loom = InMemoryLoom()
        val exporter = WarpLogRecordExporter(replica = ReplicaId("device"), store = InMemoryDurableStore())
        exporter.export(rec(1, "before"))
        val token = LogTapJoinToken(code, t0, 5.minutes)
        val host = installLogTap(loom, exporter, backgroundScope, config, LogTapAdmission.Verify(token, clock, Random(7)))
        val raw = loom.join(InMemoryTag("puller"))
        val seam = RosterViewSeam(raw, backgroundScope)
        val client = LogTapClient(seam, backgroundScope, config, LogTapAdmission.Present(code, Random(11)))
        val seen = mutableListOf<String>()
        backgroundScope.launch { client.tail().collect { seen += it.body.orEmpty() } }
        runCurrent()
        assertEquals(listOf("before"), seen, "rig: the first exchange must have landed")
        if (blip) {
            seam.mirror = false
            seam.view.value = setOf(raw.selfId)
            runCurrent()
            seam.view.value = setOf(raw.selfId, host.selfId)
            runCurrent()
        }
        exporter.export(rec(2, "after"))
        host.sync()
        advanceTimeBy(10.minutes)
        runCurrent()
        client.close()
        host.close()
        return seen
    }

    @Test
    fun withoutABlipALaterRecordArrives() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(listOf("before", "after"), pullWithOptionalBlip(blip = false))
    }

    @Test
    fun aBlipOnlyThePullerSawSilencesTheTailForGood() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(listOf("before"), pullWithOptionalBlip(blip = true))
    }
}
