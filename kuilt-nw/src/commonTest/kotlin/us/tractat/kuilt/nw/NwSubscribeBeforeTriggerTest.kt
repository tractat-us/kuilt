package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Subscribe-before-trigger: every collector that must not miss a hot, no-replay [NwApi] event is
 * **subscribed by the time its construction site returns** — before anything can trigger the event
 * (#2478).
 *
 * Seven launches state that obligation, and each is `CoroutineStart.UNDISPATCHED` for this reason
 * alone:
 *
 * | launch | flow | site |
 * |---|---|---|
 * | `connectionOpenedLoop` | [NwApi.connectionOpened] | `NwSeam`'s `init` |
 * | `bytesReceivedLoop` | [NwApi.bytesReceived] | `NwSeam`'s `init` |
 * | `connectionClosedLoop` | [NwApi.connectionClosed] | `NwSeam`'s `init` |
 * | `connectionStatesLoop` | [NwApi.connectionStates] | `NwSeam`'s `init` |
 * | `pathStateLoop` | [NwApi.pathState] | `NwSeam`'s `init` |
 * | `endpointFound` collector | [NwApi.endpointFound] | `RedialCoordinator.start()` |
 * | `endpointLost` collector | [NwApi.endpointLost] | `RedialCoordinator.start()` |
 *
 * ## Two of the seven are STATE, and their obligation is weaker — pinned anyway
 * [NwApi.connectionStates] and [NwApi.pathState] are `StateFlow`s, so a late subscriber is handed the
 * current value on attach and cannot lose an event outright the way the three no-replay `SharedFlow`s
 * can; the most it can miss is an intermediate value conflated away, and `reconcileStates` re-reads
 * `api.connectionStates.value` under the lock regardless. Their launches are `UNDISPATCHED` all the
 * same, and stating that in the source without pinning it is how the claim goes stale — so both are
 * asserted here, with this paragraph recording that a red on either means "the shipped intent
 * changed", not "an event was lost".
 *
 * ## Why this asserts the SUBSCRIPTION and not the lost event
 * The obvious test — emit an event during the window and show it is dropped — cannot be written here,
 * because the window is **structurally unreachable on the fake**. Between `NwSeam(…)` and `NwLoom`'s
 * first trigger the scheduler is pumped many times before any *connection* event can exist: a
 * connection needs a dial, which needs discovery, which needs several dispatches. Measured on
 * `4eec711a`, rewriting all four `NwSeam` launches to a plain `scope.launch { … }` left the whole
 * deterministic `:kuilt-nw` JVM suite green — 91 pass / 0 fail. The window is real on a real
 * transport and invisible to every behavioural test in this module.
 *
 * So the detector reads the property directly instead: on a [StandardTestDispatcher], which **defers**
 * a `CoroutineStart.DEFAULT` body until the scheduler is pumped, each flow's
 * `MutableSharedFlow.subscriptionCount` must already be non-zero the instant the construction site
 * returns, with no `runCurrent()` in between. It is `0` for any launch that is not `UNDISPATCHED`.
 * The technique is `BridgeNwApi.bytesSubscriberCountForTest`'s, generalised to one accessor per flow
 * on [FakeNwApi] — and asserted **per flow**, so a single regressed collector cannot hide behind five
 * healthy ones.
 *
 * ## The rig check
 * Both tests launch a `DEFAULT`-start canary before the construction under test and assert it has
 * **not** run. That single assertion closes both ways this test could go vacuous: a future edit
 * inserting a `runCurrent()` before the assertions, and a swap to an eager dispatcher
 * ([kotlinx.coroutines.test.UnconfinedTestDispatcher] runs a `DEFAULT` body inline at the launch
 * site). Under either, every subscription assertion below would pass with `UNDISPATCHED` deleted
 * everywhere — so the canary reddens instead.
 *
 * ## What it does not cover
 * It pins the seven launches that state the obligation, not the two that do not: `deliveryDrainLoop`
 * is `UNDISPATCHED` but subscribes to no [NwApi] flow (it drains an internal channel), and
 * `inboundSilenceLoop` is deliberately `DEFAULT`. It says nothing about `RealNwApi`, whose flows live
 * behind Network.framework. And it proves the collector is *attached*, not that a real transport's
 * first event lands after construction — that ordering is the production claim this attachment is the
 * precondition for.
 */
class NwSubscribeBeforeTriggerTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"

        const val RIG_BROKEN =
            "rig broken: a DEFAULT-start coroutine ran before the assertions, so the scheduler was " +
                "pumped (or the dispatcher is eager). Every subscription assertion in this test then " +
                "passes with UNDISPATCHED deleted from all six launches — it proves nothing. Remove " +
                "the pump, or restore the StandardTestDispatcher."

        fun missing(flow: String, site: String) =
            "$flow had no subscriber when $site returned — its collector is not " +
                "CoroutineStart.UNDISPATCHED, so an event emitted before the scheduler is next pumped " +
                "is silently lost on a hot, no-replay flow (#2478)"
    }

    /**
     * `NwSeam`'s `init` block subscribes every [NwApi] flow it collects synchronously, before the
     * constructor returns — so `NwLoom` cannot advertise, browse or dial into an unsubscribed seam.
     */
    @Test
    fun seamConstructionSubscribesEveryLifecycleFlowBeforeItReturns() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")

        // Rig canary: DEFAULT start, so it can only have run if something pumped the scheduler.
        var pumped = false
        backgroundScope.launch { pumped = true }

        NwSeam(PeerId("peer-0"), api, backgroundScope, Random(0))

        assertAll(
            { assertFalse(pumped, RIG_BROKEN) },
            { assertTrue(api.connectionOpenedSubscriberCountForTest() >= 1, missing("connectionOpened", "NwSeam(…)")) },
            { assertTrue(api.bytesReceivedSubscriberCountForTest() >= 1, missing("bytesReceived", "NwSeam(…)")) },
            { assertTrue(api.connectionClosedSubscriberCountForTest() >= 1, missing("connectionClosed", "NwSeam(…)")) },
            { assertTrue(api.connectionStatesSubscriberCountForTest() >= 1, missing("connectionStates", "NwSeam(…)")) },
            { assertTrue(api.pathStateSubscriberCountForTest() >= 1, missing("pathState", "NwSeam(…)")) },
        )
    }

    /**
     * `RedialCoordinator.start()` subscribes both discovery flows before `NwLoom.weave` advertises or
     * browses — so no `endpointFound`/`endpointLost` sighting provoked by this loom's own advertise or
     * browse can be missed.
     *
     * ## Why this drives `weave` rather than the coordinator
     * `RedialCoordinator` is file-private to `NwLoom.kt`, so `weave` is the reachable construction site.
     * Starting it `UNDISPATCHED` runs the body inline on the calling thread up to `weave`'s first real
     * suspension, which is well after `redial.start()` — the seam, the coordinator, `start()`, the
     * initial `startListening` and `startBrowsing` all run inside this one uninterrupted stretch. The
     * assertions therefore observe the coordinator's subscriptions at a point where this loom has
     * *already* advertised and browsed, which is the trigger the claim names.
     *
     * A future edit that inserts a genuine dispatch into `weave` ahead of `redial.start()` reddens this
     * test. That is the intended signal, not a false red: it would move the subscription behind a
     * scheduler turn, and the advertise/browse trigger is on the other side of it.
     */
    @Test
    fun weaveSubscribesBothDiscoveryFlowsBeforeItAdvertisesOrBrowses() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("peer-0")
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0", peerId = selfId.value)
        val loom = NwLoom(api, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

        // Rig canary: DEFAULT start, so it can only have run if something pumped the scheduler.
        var pumped = false
        backgroundScope.launch { pumped = true }

        // Runs inline to weave's first suspension — past NwSeam(…), RedialCoordinator(…), start(),
        // startListening and startBrowsing. A lone device reaches no peer, so weave parks on its first
        // remote; backgroundScope cancels it at teardown.
        val weaving = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = "lobby"))) }
        }

        assertAll(
            { assertFalse(pumped, RIG_BROKEN) },
            { assertTrue(api.endpointFoundSubscriberCountForTest() >= 1, missing("endpointFound", "weave")) },
            { assertTrue(api.endpointLostSubscriberCountForTest() >= 1, missing("endpointLost", "weave")) },
        )

        weaving.cancel()
    }
}
