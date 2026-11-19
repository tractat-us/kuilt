package us.tractat.kuilt.heddle

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * One lane at its lifetime limit while the root and the other lane are healthy (#3571). The pool
 * asks the engine about each waiting leaf on its own before refusing that leaf's waiters; asked
 * beside a full lane that is next in line, a healthy lane reads as `LifetimeExhausted` too, so a
 * pool asking with the whole waiting set would refuse it.
 *
 * Overruns cannot reach this state: the root mints every one, so its minted supply is at least any
 * edge's issuance and trips first ([FairSharePoolTest.aFullRootRefusesEveryLaneWithLifetimeExhausted]).
 * Leases reserved at [LocalHeddle.MAX_RESERVATION] and handed back unspent do reach it, since each
 * raises web's issued and returned counters by the same amount and mints nothing after the first
 * (measured on JVM: four such leases left the mint at its first value), but it takes about half a
 * million of them. So the fixture writes that end state into a snapshot: web's issued and returned
 * counters raised by the same amount, web's balance and the root's mint unchanged.
 *
 * The fill lands while both waiters are queued, through an overrun of one unit on web's open lease.
 * Filled beforehand, web's waiter would be refused at arrival, never queue, and the healthy lane
 * would never be asked about beside it. Mobile's waiter queues first, so a refusal pass that asks
 * each leaf with the live waiting set reaches mobile while web is still in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FairSharePoolLaneLifetimeTest {

    private val root: GroupId = farmTopology.groupId()
    private val both: Set<GroupId> = setOf(web, mobile)

    /** What mobile's one lease is charged in the seed: a hundred times the default reservation. */
    private val mobileCost = 300L

    /** The answers [LocalHeddle.explain] gives the moment the pool sees the full lane. */
    private data class Rig(val webAlone: WaitExplanation, val mobileAlone: WaitExplanation, val mobileBesideWeb: WaitExplanation)

    @Test
    fun aCompleteThatFillsOneLaneRefusesOnlyThatLanesWaiter() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (engine, held) = webAtTheWatermark()
        val before = Rig(engine.alone(web), engine.alone(mobile), engine.explain(mobile, both, both))
        val p = pool(slots = 1, engine = engine)
        val mobileWaiter = backgroundScope.async { p.acquire(mobile) }
        runCurrent()
        val webWaiter = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val queued = p.waitersForTest
        // Under the lock, after the engine charged the overrun and before the pool hands out the slot.
        var atDispatch: Rig? = null
        p.afterCompleteForTest = { _, _, _ -> atDispatch = Rig(engine.alone(web), engine.alone(mobile), engine.explain(mobile, both, both)) }
        val closed = p.complete(held.id, held.reserved + 1L)
        runCurrent()
        assertRefusesOnlyWeb(before, queued, atDispatch, closed, webWaiter.settled(), mobileWaiter.settled())
    }

    @Test
    fun aWithEngineBlockThatFillsOneLaneRefusesOnlyThatLanesWaiter() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val (engine, held) = webAtTheWatermark()
        val before = Rig(engine.alone(web), engine.alone(mobile), engine.explain(mobile, both, both))
        val p = pool(slots = 1, engine = engine)
        val mobileWaiter = backgroundScope.async { p.acquire(mobile) }
        runCurrent()
        val webWaiter = backgroundScope.async { p.acquire(web) }
        runCurrent()
        val queued = p.waitersForTest
        val (closed, atCatchUp) = p.withEngine { e, view ->
            e.complete(held.id, held.reserved + 1L, view.waiting) to
                Rig(e.alone(web), e.alone(mobile), e.explain(mobile, both, both))
        }
        runCurrent()
        assertRefusesOnlyWeb(before, queued, atCatchUp, closed, webWaiter.settled(), mobileWaiter.settled())
    }

    private fun assertRefusesOnlyWeb(
        before: Rig,
        queued: Int,
        atRefusal: Rig?,
        closed: LeaseOutcome,
        webOut: SlotOutcome?,
        mobileOut: SlotOutcome?,
    ) = assertAll(
        // The rig, before: both lanes leasable alone, so both waiters queue rather than being refused
        // at arrival, and web is next in line, so a pick over both waiting lanes reaches web's edge.
        { assertEquals(WaitExplanation.WouldBePickedNext, before.webAlone) },
        { assertEquals(WaitExplanation.WouldBePickedNext, before.mobileAlone) },
        { assertEquals(web, (before.mobileBesideWeb as? WaitExplanation.BehindAtLevel)?.nextChild, "${before.mobileBesideWeb}") },
        { assertEquals(2, queued, "both waiters queue behind the one held slot") },
        // The overrun of one unit was recorded whole, on web's edge.
        { assertEquals(LeaseOutcome.Done(4L, 1L, 0L), closed) },
        // The rig, as the pool sees it: web full alone; the root still funds mobile alone, so the full
        // counter is web's edge and not the root's mint; and mobile asked beside web reads full too,
        // which is the answer a pool asking with the whole waiting set would act on.
        { assertEquals(WaitExplanation.LifetimeExhausted(root), atRefusal?.webAlone) },
        { assertEquals(WaitExplanation.WouldBePickedNext, atRefusal?.mobileAlone) },
        { assertEquals(WaitExplanation.LifetimeExhausted(root), atRefusal?.mobileBesideWeb) },
        // The property.
        { assertEquals(SlotOutcome.Refused(WaitExplanation.LifetimeExhausted(root)), webOut) },
        { assertEquals(mobile, (mobileOut as? SlotOutcome.Granted)?.lease?.leaf, "the healthy lane's waiter is granted, never refused: $mobileOut") },
    )

    /** What a waiter's acquire answered, or `null` while it still waits. */
    private fun Deferred<SlotOutcome>.settled(): SlotOutcome? = if (isCompleted) getCompleted() else null

    private fun LocalHeddle.alone(leaf: GroupId): WaitExplanation = explain(leaf, setOf(leaf), setOf(leaf))

    /**
     * The farm restored with one web lease open at the default reservation and web's edge one
     * default reservation below the watermark: a web pick still fits, and one unit more on the edge
     * leaves no room for the next.
     *
     * Mobile was charged [mobileCost] while both lanes waited, so web is far behind it and stays next
     * in line after its own overrun of one unit. Both lanes wait throughout, since a lane that wakes
     * is clamped level with the other, and one unit would then tip the order to mobile, which is
     * picked without ever reaching web's full edge (measured: the probe that sized this fixture).
     */
    private fun webAtTheWatermark(): Pair<LocalHeddle, Lease> {
        val seed = farm()
        seed.reconcileCapacity(2L)
        val charged = seed.acquire(both, setOf(mobile), 0L, 3L).acquired()
        val held = seed.acquire(both, setOf(web), 0L, 3L).acquired()
        seed.complete(charged.id, mobileCost, both)
        val snapshot = Json.encodeToJsonElement(LocalHeddleSnapshot.serializer(), seed.persist())
        val target = Long.MAX_VALUE - LocalHeddle.LIFETIME_HEADROOM - seed.defaultReservation
        val restored = LocalHeddle.decode(Json.encodeToString(JsonElement.serializer(), raiseWebIssuance(snapshot, target)), Json)
        val engine = LocalHeddle.restore(assertIs<RestoreResult.Restored>(restored).snapshot, LocalPolicy(defaultReservation = 3L), epoch = 1L)
        return engine to held
    }

    /**
     * [snapshot] with web's edge's issued counter at [target] and its returned counter raised by the
     * same amount, so its balance is unchanged. Format 1: `[formatVersion, state]`, the ledger's
     * counters keyed by edge, each a replica-to-count map.
     */
    private fun raiseWebIssuance(snapshot: JsonElement, target: Long): JsonElement {
        val (version, state) = snapshot.jsonArray
        val ledger = state.jsonObject.getValue("ledger").jsonObject
        val edge = ledger.getValue("records").jsonObject.entries
            .single { (_, records) -> records.jsonArray.single().jsonObject.getValue("child").jsonPrimitive.content == web.value }.key
        val issued = ledger.getValue("issued").jsonObject
        val replicaCounts = issued.getValue(edge).jsonObject.getValue("counts").jsonObject
        val (replica, count) = replicaCounts.entries.single()
        val raise = target - count.jsonPrimitive.long
        val returned = ledger.getValue("returned").jsonObject
        check(edge !in returned) { "the seed returned on web's edge already: $returned" }
        fun counter(n: Long) = JsonObject(mapOf("counts" to JsonObject(mapOf(replica to JsonPrimitive(n)))))
        val filled = JsonObject(
            ledger + mapOf(
                "issued" to JsonObject(issued + (edge to counter(target))),
                "returned" to JsonObject(returned + (edge to counter(raise))),
            ),
        )
        return JsonArray(listOf(version, JsonObject(state.jsonObject + ("ledger" to filled))))
    }

    private fun AcquireOutcome.acquired(): Lease = (this as? AcquireOutcome.Acquired)?.lease ?: fail("expected a lease, was $this")
}
