@file:Suppress("ForbiddenImport") // deliberate: see the ALLOW-realDispatcher marker on the import below.

package us.tractat.kuilt.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the roster watcher must run on a DIFFERENT OS thread from close(), because this probe suspends the watcher *inside* its check-then-act on `_state` and then tears the seam from the test thread — under any test dispatcher the two are one thread and the window cannot exist.
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The terminal-`Torn` latch against an in-flight roster promotion (#1879).
 *
 * [NearbySeam]'s roster watcher promotes `Weaving → Woven` with a check-then-act on `_state`:
 *
 * ```
 * if (_state.value is SeamState.Weaving && session.any { it != selfId }) {
 *     _state.value = SeamState.Woven          // ← read and write are not atomic
 * }
 * ```
 *
 * Between that read and that write, `close()` can run to completion on another thread — CAS the
 * `closed` latch, collapse the roster, publish `Torn`, cancel the scope and close the spool — and the
 * watcher's in-flight write then stamps `Woven` over the terminal `Torn`. **Scope cancellation does
 * not prevent it:** cancellation is cooperative and there is no suspension point between the read and
 * the write, so a cancelled-but-not-yet-suspended coroutine still completes a plain field write.
 *
 * The resulting state is contract-impossible and permanent — `closed`, `incoming` completed, `peers`
 * collapsed to `{ selfId }`, and `state` reporting `Woven` forever with no further emission able to
 * correct it, because the collector is gone. A consumer on `state.first { it is Torn }` never wakes.
 *
 * ## Why this is deterministic rather than a stress probe
 *
 * The issue expected this to need a thousands-of-iterations stress harness with the window
 * artificially widened, because read-then-write is two adjacent bytecodes. It does not, and the
 * reason is that **the window is not empty**: `&&` is short-circuit and left-to-right, so
 * `session.any { it != selfId }` is evaluated *after* the `_state` read and *before* the `_state`
 * write. That predicate iterates the roster the test supplies — which makes the window a piece of
 * **test-controlled** code, with no production hook needed to reach it.
 *
 * [WindowTrap] is a `Set<PeerId>` whose `iterator()` blocks. The watcher touches the set twice per
 * emission (once building the roster mirror, once in `any`), and the trap tells the two apart by a
 * signal production already publishes between them — the mirror. So it fires on exactly the second
 * touch, which is exactly the window, and the probe reproduces the race in one iteration.
 *
 * ## What this covers, and what it does not
 *
 * This drives [NearbySeam] over a hand-built [NearbyApi] that never moves any bytes. It proves the
 * *seam's* state machine is correct when its own roster watcher races its own `close()` — which is
 * where the defect lives. It says nothing about Google Nearby Connections: `:kuilt-nearby`'s real
 * transport needs `play-services-nearby` and an Android device, so no unit test in this module can
 * prove what the GMS runtime emits.
 */
class NearbySeamStateLatchConcurrencyTest {

    private companion object {
        /**
         * Wedge backstop for the cross-thread rendezvous, not an assertion about timing. Every wait
         * here is on a real thread handing off to another real thread, so the only thing a tight
         * value would buy is a load-sensitive false red; a blown budget means the rig deadlocked,
         * and the two `assertTrue`s below name which half.
         */
        const val RENDEZVOUS_BACKSTOP_SECONDS = 30L
    }

    private val self = PeerId("self")
    private val remote = PeerId("remote")

    /** A [NearbyApi] that emits nothing and moves nothing — the seam under test is driven directly. */
    private class SilentNearbyApi : NearbyApi {
        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startAdvertising(displayName: String, serviceId: String) {}
        override suspend fun stopAdvertising() {}
        override suspend fun startDiscovery(serviceId: String) {}
        override suspend fun stopDiscovery() {}
        override suspend fun requestConnection(displayName: String, endpointId: String) {}
        override suspend fun acceptConnection(endpointId: String) {}
        override suspend fun disconnect(endpointId: String) {}
        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {}

        override val endpointFound: Flow<EndpointFound> = emptyFlow()
        override val connectionInitiated: Flow<ConnectionInitiated> = emptyFlow()
        override val connectionResult: Flow<ConnectionResult> = emptyFlow()
        override val payloadReceived: Flow<PayloadReceived> = emptyFlow()
        override val endpointDisconnected: Flow<EndpointDisconnected> = emptyFlow()
    }

    /**
     * A roster that stops the watcher dead in the middle of its `Weaving → Woven` promotion.
     *
     * The watcher iterates this set **twice** for one emission:
     *
     *  1. building `session + selfId` for the roster mirror — *before* the `_state` read;
     *  2. inside `session.any { it != selfId }` — *after* the `_state` read and before the write.
     *
     * Only the second is the window. Rather than counting touches (which would silently pick the
     * wrong one the moment either expression changes shape), the trap keys on the mirror itself:
     * touch 1 *produces* `_peers`, so [marker] appearing in [mirroredPeers] is production's own
     * receipt that touch 1 is finished. That receipt is unavailable at touch 1 by construction and
     * unavoidable at touch 2, so the trap cannot fire on the wrong one.
     *
     * [onWindow] runs once, on the watcher's thread, with no lock held — the mirror's critical
     * section has already been released, so the tearing thread is free to take it.
     */
    private class WindowTrap(
        private val elements: Set<PeerId>,
        private val marker: PeerId,
        private val mirroredPeers: () -> Set<PeerId>,
        private val onWindow: () -> Unit,
    ) : AbstractSet<PeerId>() {

        private val fired = AtomicBoolean(false)

        /** Whether the watcher ever reached the promotion window. A `false` here voids the probe. */
        val windowEntered: Boolean get() = fired.get()

        override val size: Int get() = elements.size

        override fun iterator(): Iterator<PeerId> {
            if (marker in mirroredPeers() && fired.compareAndSet(false, true)) onWindow()
            return elements.iterator()
        }
    }

    @Test
    fun aRosterPromotionCaughtMidFlightCannotStampWovenOverTheTerminalTorn() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val weavePeers = MutableStateFlow<Set<PeerId>>(emptySet())
        val seam = NearbySeam(
            selfId = self,
            endpointPeers = mutableMapOf(),
            endpointPeersMutex = Mutex(),
            registry = registryOver(self, emptyMap()),
            api = SilentNearbyApi(),
            weavePeers = weavePeers,
            scope = scope,
            msgIdCounter = MsgIdCounter(),
        )
        assertIs<SeamState.Weaving>(seam.state.value, "precondition: nothing has promoted this seam yet")

        val reachedWindow = CountDownLatch(1)
        val tornPublished = CountDownLatch(1)
        val stateInsideWindow = AtomicReference<SeamState?>(null)
        val sawTheTear = AtomicBoolean(false)

        val trap = WindowTrap(
            elements = setOf(remote),
            marker = remote,
            mirroredPeers = { seam.peers.value },
        ) {
            // On the watcher's thread, between its `_state` read and its `Woven` write.
            stateInsideWindow.set(seam.state.value)
            reachedWindow.countDown()
            sawTheTear.set(tornPublished.await(RENDEZVOUS_BACKSTOP_SECONDS, TimeUnit.SECONDS))
        }

        // One emission carrying a non-self id: everything the watcher needs to promote.
        weavePeers.value = trap

        assertTrue(
            reachedWindow.await(RENDEZVOUS_BACKSTOP_SECONDS, TimeUnit.SECONDS),
            "rig precondition: the roster watcher never reached the promotion window, so nothing " +
                "below was actually exercised",
        )

        // The whole tear runs while the watcher sits mid-promotion — the interleaving under test.
        runBlocking { seam.close(CloseReason.Normal) }
        tornPublished.countDown()

        // close() cancelled the scope, so joining it is how we know the watcher has finished its
        // in-flight write (if any) before the terminal state is sampled. Without this the probe
        // could read `state` before the clobber lands and pass by racing the race.
        runBlocking { scope.coroutineContext[Job]?.join() }

        assertAll(
            {
                assertTrue(trap.windowEntered, "rig precondition: the trap must have fired exactly once")
            },
            {
                assertTrue(
                    sawTheTear.get(),
                    "rig precondition: the watcher must have been released by close() publishing Torn, " +
                        "not by the backstop expiring",
                )
            },
            {
                assertIs<SeamState.Weaving>(
                    stateInsideWindow.get(),
                    "rig precondition: the watcher must have been mid-promotion — having read " +
                        "`Weaving` and about to write `Woven`. Any other value here means the trap " +
                        "fired somewhere that is not the window, and the assertion below is vacuous",
                )
            },
            {
                assertIs<SeamState.Torn>(
                    seam.state.value,
                    "a roster promotion caught mid-flight must not stamp `Woven` over the terminal " +
                        "`Torn` (#1879). The seam is closed, `incoming` has completed and `peers` has " +
                        "collapsed to { selfId }, so a `Woven` here is contract-impossible AND " +
                        "permanent — the collector is gone, so no later emission can correct it and " +
                        "every consumer on `state.first { it is Torn }` hangs forever",
                )
            },
        )
    }
}
