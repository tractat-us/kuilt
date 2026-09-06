/**
 * A channel view's **own** lifecycle (#2372).
 *
 * `MuxBase.ChannelView` used to delegate `state` and `peers` straight to the base seam, so a view
 * that had been closed went on reporting the base's `Woven` forever and went on advertising a roster
 * of peers it would never deliver to again — the two things `Seam.state` and `Seam.peers` exist to
 * tell a holder, both wrong, in the one situation the holder cares about.
 *
 * The other half of that delegation is deliberate and is **not** what this file changes: closing one
 * channel must not tear the shared socket every other channel is riding (#949). Two lifecycles were
 * being conflated by one delegated field — the base's, which must stay live, and the view's, which
 * the caller just ended. The tests below pin both halves at once: every case asserts the view's own
 * terminal state *and* that the base is still `Woven`.
 *
 * `MuxSeamTest` / `NamedMuxTest` own the delivery, framing and isolation properties; this file owns
 * only the lifecycle contract, for both muxers, because it is one shared implementation in
 * `MuxBase`.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MuxChannelViewOwnLifecycleTest {

    // ── close() drives the VIEW to Torn, and leaves the base Woven ───────────

    /**
     * The ungated-core obligation `SeamConformanceSuite.closeDrivesStateTornNormal`, asserted
     * directly on a channel view. The `Woven` half of the assertion is what stops this from being a
     * restatement of #949's `baseSeamRemainsLiveAfterChannelClose`: a fix that tore the base would
     * satisfy the first arm and fail the second.
     */
    @Test
    fun closingAChannelViewLatchesTornOnTheViewOnly() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("view-own-state"))
        val mux = NamedMux(base, backgroundScope)

        val chat = mux.channel("chat")
        val cursors = mux.channel("cursors")
        assertIs<SeamState.Woven>(chat.state.value, "precondition: a fresh view mirrors the live base")

        chat.close()

        assertAll(
            { assertIs<SeamState.Torn>(chat.state.value, "the closed view must latch Torn — the caller closed THIS seam") },
            { assertIs<SeamState.Woven>(base.state.value, "#949: the base must stay live for the other channels") },
            { assertIs<SeamState.Woven>(cursors.state.value, "a sibling view must stay live") },
        )
    }

    /** The byte-keyed muxer shares one implementation with the named one, so it inherits this. */
    @Test
    fun closingAByteChannelViewLatchesTornOnTheViewOnly() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("byte-view-own-state"))
        val mux = MuxSeam(base, backgroundScope)

        val a = mux.channel(0x0A)
        val b = mux.channel(0x0B)

        a.close()

        assertAll(
            { assertIs<SeamState.Torn>(a.state.value, "the closed view must latch Torn") },
            { assertIs<SeamState.Woven>(base.state.value, "#949: the base must stay live") },
            { assertIs<SeamState.Woven>(b.state.value, "a sibling view must stay live") },
        )
    }

    /** `close(reason)` carries the caller's reason onto the view's terminal state, verbatim. */
    @Test
    fun theViewsTornCarriesTheCallersReason() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val mux = NamedMux(loom.host(Pattern("view-close-reason")), backgroundScope)
        val chat = mux.channel("chat")

        chat.close(CloseReason.RemoteRequested)

        assertEquals(
            CloseReason.RemoteRequested,
            assertIs<SeamState.Torn>(chat.state.value).reason,
            "the view must publish the reason it was closed with",
        )
    }

    /**
     * `stateStaysTornAfterClose`, on a view: post-close churn must not move the terminal state off
     * `Torn`. The base is still publishing — it is `Woven` and its roster is still moving — so an
     * unlatched mirror would be overwritten by the very next base emission, which is exactly the
     * failure mode a plain `MutableStateFlow` would have.
     */
    @Test
    fun theViewStaysTornWhileTheBaseKeepsPublishing() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("view-stays-torn"))
        val mux = NamedMux(base, backgroundScope)
        val chat = mux.channel("chat")

        chat.close()
        val torn = assertIs<SeamState.Torn>(chat.state.value, "precondition: close() must latch Torn")

        // A joiner arriving AFTER the close moves the base's roster and re-publishes its state —
        // the churn that would clobber an unlatched terminal write.
        val joiner = loom.join(InMemoryTag("late-joiner"))
        base.peers.first { joiner.selfId in it }

        val after = assertIs<SeamState.Torn>(chat.state.value, "the view must STAY Torn under base churn")
        assertEquals(torn.reason, after.reason, "the terminal reason must not change under churn")
    }

    // ── peers collapses to { selfId } on the view's own close ────────────────

    /**
     * `peersCollapseToSelfIdWhenTorn`, on a view. A closed view that keeps advertising the base's
     * roster is naming peers it will never deliver to again — the lie `Seam.peers`' KDoc forbids —
     * and the base's roster is precisely what a delegating view reported.
     */
    @Test
    fun aTornViewAdvertisesExactlyItsOwnId() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("view-peers-collapse"))
        val remote = loom.join(InMemoryTag("remote"))
        val mux = NamedMux(base, backgroundScope)
        val chat = mux.channel("chat")

        // A roster worth collapsing — otherwise a correct collapse is indistinguishable from a view
        // that never had a remote peer to lose.
        chat.peers.first { remote.selfId in it }

        chat.close()

        assertAll(
            {
                assertEquals(
                    emptySet(),
                    chat.peers.value - chat.selfId,
                    "a Torn view must advertise NO reachable remote peer",
                )
            },
            { assertTrue(chat.selfId in chat.peers.value, "the collapsed roster is { selfId }, not empty") },
            {
                assertTrue(
                    remote.selfId in base.peers.value,
                    "#949: the BASE's roster is untouched — closing one channel departs nobody",
                )
            },
            {
                assertTrue(
                    remote.selfId in mux.channel("cursors").peers.value,
                    "a sibling view still advertises the remote it can still reach",
                )
            },
        )
    }

    /**
     * The collapse is published **before, or atomically with**, the terminal `Torn` — `Seam.peers`
     * requires the ordering, not just the settled value.
     *
     * A collector dispatched normally always reads the settled roster and would pass against an
     * implementation that latched first and collapsed after. Collecting on an
     * [UnconfinedTestDispatcher] resumes this collector **inline** inside the terminal state write,
     * so what it reads from `peers` is the value at exactly the instant `Torn` became observable.
     * Same device, and same reason, as `CoreSeamPeersCollapseOnTearTest`.
     *
     * The observation is **recorded rather than awaited**, so a view that never tears at all reds
     * here as a `null` reading in about a millisecond instead of wedging on a `first { Torn }` that
     * will never complete — the shape this test had on its first run against the unfixed view.
     */
    @Test
    fun theViewCollapsesItsRosterNoLaterThanItLatchesTorn() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("view-collapse-order"))
        val remote = loom.join(InMemoryTag("remote"))
        val mux = NamedMux(base, backgroundScope)
        val chat = mux.channel("chat")
        chat.peers.first { remote.selfId in it }

        var peersAtTorn: Set<PeerId>? = null
        backgroundScope.launch {
            chat.state.collect { if (it is SeamState.Torn && peersAtTorn == null) peersAtTorn = chat.peers.value }
        }

        chat.close()

        assertEquals(
            setOf(chat.selfId),
            peersAtTorn,
            "the roster read at the instant Torn became observable must already be collapsed " +
                "(a null reading means the view never published Torn at all)",
        )
    }

    // ── a Torn view refuses sends ────────────────────────────────────────────

    /**
     * `sendOnTornSeamThrows`, on a view — and the behaviour change this issue's decision forces.
     *
     * The view used to swallow a post-close send, which was defensible only while it also reported
     * itself `Woven`: a seam that tells its holder it is `Torn` owes that holder an
     * `IllegalStateException` rather than a silent drop (`Seam`'s own KDoc — "Either call when
     * `Torn`: throws `IllegalStateException`"). Swallowing hides a delivery failure from the one
     * caller who could act on it.
     */
    @Test
    fun sendingOnATornViewThrows() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("view-send-on-torn"))
        val remote = loom.join(InMemoryTag("remote"))
        val mux = NamedMux(base, backgroundScope)
        val chat = mux.channel("chat")
        chat.peers.first { remote.selfId in it }

        chat.close()

        assertFailsWith<IllegalStateException>("broadcast on a Torn view must throw") {
            chat.broadcast(byteArrayOf(1))
        }
        assertFailsWith<IllegalStateException>("sendTo on a Torn view must throw") {
            chat.sendTo(remote.selfId, byteArrayOf(2))
        }
        assertIs<SeamState.Woven>(base.state.value, "#949: refusing the send must not have torn the base")
    }

    /**
     * A refused send reaches nothing on the wire — the base must not carry a frame the view has
     * already told its caller it cannot deliver.
     *
     * **Green both before and after the fix, deliberately.** The old view swallowed the send and the
     * new one refuses it; either way nothing reaches peer B, and that is the point — this is the arm
     * that stops "make a closed view throw" from being satisfied by a view that throws *after*
     * handing the frame to the base. The sentinel on the sibling channel is what stops an empty inbox
     * being read as a dead fixture.
     */
    @Test
    fun aRefusedSendPutsNothingOnTheBase() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val muxA = NamedMux(loom.host(Pattern("view-refused-send-silent")), backgroundScope)
        val muxB = NamedMux(loom.join(InMemoryTag("b")), backgroundScope)

        val chatA = muxA.channel("chat")
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { muxB.channel("chat").incoming.collect { received.add(it) } }

        chatA.close()
        runCatching { chatA.broadcast(byteArrayOf(99)) }

        // Prove the base is still carrying traffic, so an empty inbox is a refusal rather than a
        // dead fixture.
        val sentinel = async { muxB.channel("cursors").incoming.first() }
        muxA.channel("cursors").broadcast(byteArrayOf(1))
        sentinel.await()

        assertTrue(received.isEmpty(), "the refused broadcast must not have reached peer B's chat channel")
    }

    // ── the base's own tear still reaches the view ───────────────────────────

    /**
     * Owning a `SeamState` must not cost the view the base's. A torn base is a torn channel — every
     * `MuxClientLoom` resume decision reads a handle's `state` to learn its generation died — and a
     * view that mirrored the base only while it suited it would leave that consumer waiting forever.
     *
     * **Green before the fix as well as after, deliberately.** A delegating view got this for free;
     * the whole risk of giving the view its own state is that this is what gets dropped on the way.
     * It is a preservation guard, not a reproduction — the roster arm is the half that was never true.
     */
    @Test
    fun aTornBaseTearsEveryView() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val base = loom.host(Pattern("base-tear-reaches-views"))
        val mux = NamedMux(base, backgroundScope)
        val chat = mux.channel("chat")
        val cursors = mux.channel("cursors")

        mux.closeBase(CloseReason.RemoteRequested)

        assertAll(
            { assertIs<SeamState.Torn>(chat.state.value, "a torn base must tear its views") },
            { assertIs<SeamState.Torn>(cursors.state.value, "…every one of them") },
            {
                assertEquals(
                    CloseReason.RemoteRequested,
                    assertIs<SeamState.Torn>(chat.state.value).reason,
                    "the base's reason must reach the view, not be replaced by a local Normal",
                )
            },
            { assertEquals(setOf(chat.selfId), chat.peers.value, "a view torn by its base collapses its roster too") },
        )
    }

    /** Double-close stays a no-op, and the first reason is the one that sticks. */
    @Test
    fun doubleCloseKeepsTheFirstReason() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val mux = NamedMux(loom.host(Pattern("view-double-close")), backgroundScope)
        val chat = mux.channel("chat")

        chat.close(CloseReason.RemoteRequested)
        chat.close(CloseReason.Normal)

        assertEquals(
            CloseReason.RemoteRequested,
            assertIs<SeamState.Torn>(chat.state.value).reason,
            "the terminal reason is single-shot — the second close must not rewrite it",
        )
    }
}
