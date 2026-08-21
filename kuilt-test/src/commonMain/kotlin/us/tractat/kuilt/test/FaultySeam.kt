package us.tractat.kuilt.test

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Spool
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.core.TransportCapability

/**
 * A [Seam] wrapper that injects configurable faults for use in tests.
 *
 * Faults are driven by a [FaultProfile] which can be swapped atomically at
 * any point during a test via [setFaultProfile]. The profile change takes
 * effect on the next frame — frames already in-flight at the moment of the
 * switch complete under the old profile.
 *
 * **Determinism guarantee:** every probabilistic and randomised fault takes
 * an explicit seed. No wall-clock time is consumed — delays go through
 * [kotlinx.coroutines.delay] so [kotlinx.coroutines.test.runTest] controls
 * virtual time.
 *
 * **Teardown is a second, orthogonal axis** — [TeardownFault], swappable via [setTeardownFault] and
 * defaulting to [TeardownFault.None]. A [FaultProfile] describes what happens to *frames* and is
 * never consulted by [close]; see [TeardownFault]'s KDoc for why the two are not one hierarchy.
 *
 * **Inspection hooks:** [framesDropped], [framesDelayed], [framesDelivered] and
 * [teardownFaultsFired] counters are updated atomically so tests can assert on
 * fault behaviour without inspecting internal channels.
 *
 * Consumed by partition / reconnect test suites. Exposes the same
 * [Seam] contract as [InMemoryLoom]-produced links.
 */
public class FaultySeam(
    private val delegate: Seam,
    private val scope: CoroutineScope,
    initialProfile: FaultProfile = FaultProfile.Healthy,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    initialTeardownFault: TeardownFault = TeardownFault.None,
) : Seam {
    private val faultState = FaultState(initialProfile)
    private val mutex = Mutex()

    // An AtomicRef rather than a plain `var`: [close] is reachable from any thread (a teardown path,
    // a best-effort cleanup loop, a test's own scope) while a test swaps the arm from another, and
    // this type must be correct under a multi-threaded dispatcher. `FaultState.profile` is a plain
    // `var` guarded by [mutex] on every read; a teardown has no such critical section to join, so it
    // gets its own primitive rather than inheriting a lock it does not need.
    private val _teardownFault = atomic<TeardownFault>(initialTeardownFault)

    // Incoming — bounded per the injected DeliveryPolicy (the Spool invariant, #701).
    private val spool = Spool<Swatch>(policy)

    // Counters — atomic so concurrent sender coroutines and the inbound pump race-free.
    private val _framesDropped = atomic(0L)
    private val _framesDelayed = atomic(0L)
    private val _framesDelivered = atomic(0L)
    private val _teardownFaultsFired = atomic(0L)

    public val framesDropped: Long get() = _framesDropped.value
    public val framesDelayed: Long get() = _framesDelayed.value
    public val framesDelivered: Long get() = _framesDelivered.value

    /**
     * How many times [close] has **selected** a non-[TeardownFault.None] arm — counted at selection,
     * before the arm runs.
     *
     * At selection, not at completion, so a caller that bounds [close] in a `withTimeout` and never
     * lets [TeardownFault.Slow] finish still leaves evidence the rig fired. A test asserting on
     * teardown behaviour reads this as its **precondition**: a suite whose fault never reached the
     * seam under test would otherwise pass by absence, which is the vacuity this knob exists to
     * remove rather than relocate.
     */
    public val teardownFaultsFired: Long get() = _teardownFaultsFired.value

    init {
        // Pipe from the delegate's incoming flow through fault injection.
        scope.launch {
            delegate.incoming.collect { frame -> injectInbound(frame) }
            spool.close()
        }
    }

    /** Replace the active [FaultProfile] atomically. */
    public fun setFaultProfile(profile: FaultProfile) {
        faultState.profile = profile
    }

    /** Shorthand for [setFaultProfile] with [FaultProfile.Healthy]. */
    public fun heal(): Unit = setFaultProfile(FaultProfile.Healthy)

    /** Shorthand for [setFaultProfile] with [FaultProfile.DropAll]. */
    public fun partition(direction: Direction = Direction.Both): Unit = setFaultProfile(FaultProfile.DropAll(direction))

    /**
     * Replace the active [TeardownFault] atomically.
     *
     * Independent of [setFaultProfile]: a link can be partitioned and have a failing teardown at the
     * same time, and healing one leaves the other alone.
     */
    public fun setTeardownFault(fault: TeardownFault) {
        _teardownFault.value = fault
    }

    // ── Seam ─────────────────────────────────────────────────────────────────

    override val selfId: PeerId get() = delegate.selfId

    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers

    override val state: StateFlow<SeamState> get() = delegate.state

    override val incoming: Flow<Swatch> = spool.incoming

    override suspend fun broadcast(payload: ByteArray) {
        val decision = mutex.withLock { faultState.evaluateOutbound(payload) }
        applyOutboundDecision(decision) { delegate.broadcast(it) }
    }

    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        // BEFORE the fault evaluation, not after. A `Drop`/`Buffer` decision never calls the
        // delegate at all, so leaving this to the wrapped seam would make the refusal a coin flip on
        // the injected fault profile — a lossy link is allowed to lose frames, never to launder a
        // caller's programming error into one (#2428).
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        val decision = mutex.withLock { faultState.evaluateOutbound(payload) }
        applyOutboundDecision(decision) { delegate.sendTo(peer, it) }
    }

    /**
     * Close the link, applying the active [TeardownFault].
     *
     * [FaultProfile] is deliberately **not** consulted here — it describes frame handling, and a
     * teardown has no frame and no [Direction]. With the default [TeardownFault.None] this is the
     * unconditional passthrough it has always been.
     */
    override suspend fun close(reason: CloseReason) {
        when (val fault = _teardownFault.value) {
            is TeardownFault.None -> delegate.close(reason)
            is TeardownFault.Slow -> {
                _teardownFaultsFired.incrementAndGet()
                delay(fault.delay)
                delegate.close(reason)
            }
            is TeardownFault.Fails -> {
                _teardownFaultsFired.incrementAndGet()
                // Delegate FIRST, then throw — see TeardownFault.Fails' KDoc. The link underneath is
                // genuinely closed, so the rest of the harness stays assertable after the failure.
                delegate.close(reason)
                throw fault.cause
            }
        }
    }

    // ── Internal outbound dispatch ────────────────────────────────────────────

    private suspend fun applyOutboundDecision(
        decision: OutboundDecision,
        send: suspend (ByteArray) -> Unit,
    ) {
        when (decision) {
            is OutboundDecision.Send -> {
                send(decision.payload)
                _framesDelivered.incrementAndGet()
            }
            is OutboundDecision.Delay -> {
                _framesDelayed.incrementAndGet()
                delay(decision.delay)
                send(decision.payload)
                _framesDelivered.incrementAndGet()
            }
            is OutboundDecision.Drop -> {
                _framesDropped.incrementAndGet()
            }
            is OutboundDecision.Buffer -> {
                // Frame is held in FaultState's reorder window; nothing to do here.
            }
            is OutboundDecision.SendBurst -> {
                for (p in decision.payloads) {
                    send(p)
                    _framesDelivered.incrementAndGet()
                }
            }
            is OutboundDecision.CloseLink -> {
                delegate.close(decision.reason)
            }
        }
    }

    // ── Internal inbound injection ────────────────────────────────────────────

    private suspend fun injectInbound(frame: Swatch) {
        val toDeliver = mutex.withLock { faultState.evaluateInbound(frame) }
        val inboundDelay = faultState.inboundDelay(faultState.profile)

        if (toDeliver.isEmpty()) {
            _framesDropped.incrementAndGet()
            return
        }
        if (inboundDelay != null) {
            _framesDelayed.incrementAndGet()
            delay(inboundDelay)
        }
        for (f in toDeliver) {
            spool.deliver(f)
            _framesDelivered.incrementAndGet()
        }
    }
}

/**
 * A [Loom] wrapper that constructs [FaultySeam] instances.
 *
 * A [defaultProfile] applies to every link the factory creates. Individual
 * links can override their profile via [FaultySeam.setFaultProfile].
 *
 * [defaultTeardownFault] is the same idea on the orthogonal teardown axis, overridable per-link via
 * [FaultySeam.setTeardownFault].
 *
 * Useful for fault scenarios where **all** links should start partitioned
 * or delayed, then selectively healed per-peer.
 *
 * [scope] must be a [CoroutineScope] that outlives the test — the standard
 * pattern is to pass the [kotlinx.coroutines.test.TestScope] from [runTest].
 */
public class FaultyLoom(
    private val delegate: Loom,
    private val scope: CoroutineScope,
    private val defaultProfile: FaultProfile = FaultProfile.Healthy,
    private val defaultTeardownFault: TeardownFault = TeardownFault.None,
) : Loom {
    private val _links = MutableStateFlow<List<FaultySeam>>(emptyList())

    /** All [FaultySeam] instances created so far, in creation order. */
    public val links: List<FaultySeam> get() = _links.value

    override suspend fun weave(rendezvous: Rendezvous): FaultySeam = wrap(delegate.weave(rendezvous))

    override suspend fun host(pattern: Pattern): FaultySeam = wrap(delegate.host(pattern))

    override suspend fun join(tag: Tag): FaultySeam = wrap(delegate.join(tag))

    /**
     * The [delegate]'s verdict, verbatim — including when a [defaultProfile] partitions every link.
     *
     * A [FaultProfile] describes **link behaviour** (delay, drop, partition) on a link the delegate's
     * fabric already carries; [us.tractat.kuilt.core.FabricAvailability] answers the different,
     * pre-connect question of whether that fabric is usable *on this runtime at all*. This loom has
     * no answer of its own to that — it weaves whatever the delegate weaves — so substituting one
     * would only discard the delegate's established verdict (#1936). A simulated partition belongs
     * on the seam's live surface, not in a claim that the fabric does not exist here.
     */
    override fun capability(): TransportCapability = delegate.capability()

    /** Apply [profile] to every link the factory has created so far. */
    public fun setFaultProfileOnAll(profile: FaultProfile) {
        _links.value.forEach { it.setFaultProfile(profile) }
    }

    /** Apply [fault] to every link the factory has created so far. */
    public fun setTeardownFaultOnAll(fault: TeardownFault) {
        _links.value.forEach { it.setTeardownFault(fault) }
    }

    private fun wrap(delegate: Seam): FaultySeam {
        val link = FaultySeam(delegate, scope, defaultProfile, initialTeardownFault = defaultTeardownFault)
        _links.value = _links.value + link
        return link
    }
}
