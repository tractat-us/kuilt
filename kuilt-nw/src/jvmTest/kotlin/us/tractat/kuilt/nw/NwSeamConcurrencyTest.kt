@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — NwSeam's registry/conns/draining races only manifest under genuine cross-thread access, so this probe needs a real multi-threaded dispatcher, not a virtual one.

package us.tractat.kuilt.nw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency stress harness — NwSeam's four lifecycle collectors are serialised onto one thread by any test dispatcher, which is exactly what makes a missing `withLock` invisible.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.StageTracker
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Thread-safety probe for [NwSeam] (#2481).
 *
 * ## Why this module needed one at all
 * `:kuilt-core` has seven `*ConcurrencyTest`s; `:kuilt-nw` had **none**. `NwSeam` shares `registry`,
 * `conns`, `graceJobs`, `draining`, `tombstones` and every mutable `var` on its per-connection state
 * across four concurrent lifecycle collectors and the caller-driven `broadcast`/`sendTo`/`close`,
 * behind one atomicfu `reentrantLock`. Under a `StandardTestDispatcher` every one of those collectors
 * is serialised onto a single thread, so **a missing `withLock` is invisible to the entire existing
 * suite** — the only thing that had ever run them truly concurrently was the real-transport
 * conformance suites, and only probabilistically.
 *
 * ## ONE probe, because one hazard turned out to be a genuine two-writer race
 * #2481 named four: the double-dial dedup, `broadcast` against eviction, `close()` against a remote
 * departure, and a drain's start against its own end. Each was built and then **mutation-tested** by
 * deleting the specific `withLock` that makes it safe. Only the second reddens reliably, and only it
 * ships — a probe that cannot be made to red is green by absence, and shipping one inflates the
 * apparent coverage of exactly the file it is supposed to protect.
 *
 * [broadcastNeverLeaksARaceExceptionWhilePeersAreEvicted] is that probe: with `broadcast`'s target
 * snapshot un-guarded it fails **6 of 6 rounds**, with a `ConcurrentModificationException` raised out
 * of `broadcast` while a lifecycle collector evicts from `registry` underneath its iteration.
 *
 * ## Why the other three are not probes, which is a finding about the issue rather than an omission
 * All three ask a collector to race *itself*, and `NwSeam` gives each collector exactly one coroutine:
 *
 *  - **The dedup.** `resolveIdentity` is reached **only** from the single `bytesReceivedLoop`, so two
 *    resolutions on one seam can never race however hard both ends dial. Un-guarded, a dedicated
 *    double-dial probe stayed green 5 of 5.
 *  - **`close()` against a departure.** `close()` calls `latchTorn` *first*, which latches the `closed`
 *    flag and cancels [scope] — so every collector is already gone before its teardown critical section
 *    runs, and there is nothing left to race. Un-guarded: green 0 of 6 (2-node), green 0 of 6 (a 6-node
 *    star built specifically to widen it). Removing `connectionClosedLoop`'s guard instead reddened
 *    1 of 6 — real, but nowhere near reliable, and a 1-in-6 probe in CI is a flake generator.
 *  - **Drain start against drain end.** Un-guarded `endDrain`: green 0 of 6. Every teardown path
 *    converges on the same end state whatever the interleaving, so "no orphaned drain" cannot separate
 *    them.
 *
 * A trap found on the way and worth recording, because it would have shipped as an intermittent false
 * red: contending the dedup with a `broadcast` hammer *during formation* wedges the pair to zero peers
 * on **unmutated** code. That is the rig, not the seam — `broadcast`'s send-failure arm calls
 * `removeByConn`, which legitimately evicts the peer, and with no `NwLoom` to redial there is nothing
 * to bring it back. The probe's own [StageTracker] snapshot is what named it (`liveLinks=0
 * openedLinks=8`, both seams back to `Weaving`) rather than leaving it as an opaque 5-minute hang.
 *
 * ## The invariant every test shares
 * [assertRegistryAndConnsAgree] re-asserts `auditRegistryLocked`'s **contract-impossible** condition
 * from outside: a `registry` entry naming a connection absent from `conns`. `NwSeam` logs that at
 * ERROR precisely because its own model says it cannot happen, so it is the single cheapest detector
 * of the two maps having been mutated without mutual exclusion — whichever path did it.
 *
 * ## JVM-hosted on purpose
 * The code under test lives in `commonMain`, but the race only manifests under real OS-thread
 * parallelism. wasmJs is single-threaded and Kotlin/Native's pool is too slow for the iteration
 * count; the JVM gives fast, reliable real-thread coverage. `BridgeNwApiReceiveBackpressureTest` is
 * the in-module precedent for the same reasoning.
 *
 * `-P`-gated out of the normal build by the `*ConcurrencyTest` name contract in
 * `kuilt-nw/build.gradle.kts` (copied from `:kuilt-core`'s), and run alone on a dedicated CI runner
 * by the `nw-concurrency-probes` job. Both fakes underneath it were given real locks first (#2481) —
 * an unguarded harness produces its own races and blames them on the seam.
 */
class NwSeamConcurrencyTest {

    /**
     * One simulated device: its transport, its seam, the scope the seam's collectors live on, and
     * every throwable that ESCAPED one of those collectors.
     *
     * [escaped] is the second probe's whole detector, and it is what the first one structurally cannot
     * have: a race inside `broadcast` is raised on the *caller's* coroutine and can simply be caught,
     * but a race inside one of the seam's seven collectors is raised on a coroutine nobody awaits. Under
     * the [SupervisorJob] below it kills that one collector, is handed to the context's
     * [CoroutineExceptionHandler], and the seam carries on **looking healthy with a lifecycle loop
     * silently dead** — which is indistinguishable from an idle session and is the failure mode #2420
     * exists to remove. Recording it is therefore the only way a missing `withLock` anywhere off the
     * caller's path is observable at all.
     */
    private class Node(
        val peerId: PeerId,
        val deviceId: String,
        val api: FakeNwApi,
        val seam: NwSeam,
        val scope: CoroutineScope,
        val escaped: CopyOnWriteArrayList<Throwable>,
    )

    /**
     * A node on a **genuinely multi-threaded** scope with its own [SupervisorJob], so one seam's
     * `close()` — which cancels the scope it was handed — cannot take its peers' collectors with it.
     *
     * `random` is seeded per node so the canonical-nonce dedup is reproducible run to run: the probe
     * is about the *locking*, and an unseeded nonce would make which link wins a second, uncontrolled
     * variable. `inboundSilenceProbe` is deliberately TINY rather than disabled — the watchdog sweep
     * takes the same lock and iterates the same `conns` map, so a fast one is a fifth concurrent
     * contender for it rather than a dormant loop. It is a **parameter with a default**, not a
     * hardcoded constant, because the two probes want different sweep rates and hardcoding it would
     * collapse both onto whichever one was written first. `drainBound` is small so a drain whose
     * `GOODBYE` can never arrive still terminates inside an iteration instead of outliving it.
     *
     * The [CoroutineExceptionHandler] is the collector-side detector — see [Node.escaped].
     */
    private fun node(
        radio: FakeNwRadio,
        index: Int,
        seed: Long,
        watchdogProbe: kotlin.time.Duration = WATCHDOG_PROBE,
    ): Node {
        val deviceId = "dev-$index"
        val peerId = PeerId("peer-$index")
        val api = FakeNwApi(radio, deviceId = deviceId, serviceName = "svc-$index")
        val escaped = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, failure -> escaped += failure },
        )
        val seam = NwSeam(
            selfId = peerId,
            api = api,
            scope = scope,
            random = Random(seed + index),
            inboundSilenceProbe = watchdogProbe,
            drainBound = DRAIN_BOUND,
        )
        return Node(peerId, deviceId, api, seam, scope, escaped)
    }

    /** The endpoint the radio maps back to `dev-<i>` (its `ep-<deviceId>` convention). */
    private fun endpointFor(index: Int) = NwEndpoint(id = "ep-dev-$index", serviceName = "svc-$index")

    /** Tear every node's collectors down, so 150 iterations do not leak 150 seams' worth of coroutines. */
    private fun teardown(nodes: List<Node>) = nodes.forEach { it.scope.cancel() }

    /** A compact cross-node dump for [StageTracker.at] — identities and state, never sizes. */
    private fun dump(iteration: Int, radio: FakeNwRadio, nodes: List<Node>): String =
        nodes.joinToString(
            prefix = "iter=$iteration liveLinks=${radio.liveLinkCount} openedLinks=${radio.openedLinkCount}\n",
            separator = "\n",
        ) { n ->
            val snapshot = n.seam.formationSnapshot()
            "  ${n.peerId.value}: state=${snapshot.state} peers=${snapshot.peers} " +
                "links=${snapshot.links.map { "${it.connId}(${it.role},peer=${it.resolvedPeer})" }}"
        }

    // ── hazard 2: broadcast against concurrent eviction ─────────────────────────

    /**
     * Hammer [NwSeam.broadcast] from several threads while every remote departs and the seam closes
     * underneath it — the `broadcast`-iterating-`registry`-while-a-collector-evicts race.
     *
     * `broadcast` takes its target snapshot inside `lock.withLock { registry.values.map { … } }`, and
     * the same critical section increments each chosen connection's outbound counter. Concurrently,
     * `connectionClosedLoop` and `reconcileStates` remove from `registry` and `conns`. Unguarded,
     * that is a `ConcurrentModificationException` out of a `Seam` method the contract says throws only
     * `IllegalStateException` once torn.
     *
     * What is asserted is therefore the *shape* of the failure, not merely its absence: a raced
     * exception fails loudly ([broadcastRefusingCleanly]), a clean closed-seam signal is accepted, and
     * the seam must still land exactly one terminal `Torn` with `peers == {selfId}` — never a
     * half-written roster.
     */
    @Test
    fun broadcastNeverLeaksARaceExceptionWhilePeersAreEvicted() = runConcurrencyStress { stage ->
        repeat(BROADCAST_ITERATIONS) { iter ->
            val radio = FakeNwRadio()
            val nodes = (0..PEER_COUNT).map { node(radio, it, iter.toLong() * 100) }
            val hub = nodes.first()
            val peers = nodes.drop(1)

            stage.at("iter=$iter build star") { dump(iter, radio, nodes) }
            for (p in peers) hub.api.connect(endpointFor(nodes.indexOf(p)))
            hub.seam.peers.first { it.size == nodes.size }

            stage.at("iter=$iter broadcast vs eviction hammer") { dump(iter, radio, nodes) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val senders = (0 until BROADCASTERS).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(SENDS_PER_BROADCASTER) {
                            broadcastRefusingCleanly { hub.seam.broadcast(byteArrayOf(1)) }
                        }
                    }
                }
                // The remotes leave STAGGERED, not in one burst at instant zero: each departure drives
                // the hub's connectionClosed + connectionStates collectors into `registry`/`conns`, and
                // spreading them across the hammer is what keeps evictions landing while the senders
                // are actually mid-iteration. Bursting them all at `ready` — the first cut — fired every
                // eviction before the broadcasters had started, and the probe dropped to 4/5 red.
                val leavers = peers.mapIndexed { i, p ->
                    async(Dispatchers.Default) {
                        ready.await()
                        repeat(i * DEPARTURE_STAGGER_YIELDS) { yield() }
                        p.seam.close(CloseReason.Normal)
                    }
                }
                ready.complete(Unit)
                awaitAll(*leavers.toTypedArray(), *senders.toTypedArray())
            }

            // The hub closes AFTER the hammer, not inside it: a hub torn at instant zero makes every
            // remaining broadcast an immediate `IllegalStateException`, so the window under test would
            // close before most of the sends ever iterated `registry`. Racing close against departure
            // is a hazard in its own right and has its own probe.
            stage.at("iter=$iter await hub torn") { dump(iter, radio, nodes) }
            hub.seam.close(CloseReason.Normal)
            hub.seam.state.first { it is SeamState.Torn }

            assertAll(
                { assertIs<SeamState.Torn>(hub.seam.state.value, "teardown did not produce a clean Torn state") },
                { assertEquals(setOf(hub.peerId), hub.seam.peers.value, "roster corrupted by concurrent broadcast/eviction: ${dump(iter, radio, nodes)}") },
                { assertRegistryAndConnsAgree(nodes, iter, radio) },
            )
            teardown(nodes)
        }
    }

    // ── hazard 5: a race inside the seam's OWN collectors ───────────────────────

    /**
     * Redial every peer over and over while the hub broadcasts, and assert that **nothing ever escaped
     * one of the seam's seven collectors** and that its `registry`/`conns` binding stayed sound at every
     * instant it was sampled.
     *
     * ## What this adds over [broadcastNeverLeaksARaceExceptionWhilePeersAreEvicted]
     * That probe's detector is a `catch` on the caller's own `broadcast` call, so it can only see the
     * two critical sections a *caller* enters. Everything else `NwSeam` locks — `resolveIdentity`'s dedup
     * arms, `connectionClosedLoop`, `reconcileStates`, `removeByConn`, `startDrain`/`endDrain`,
     * `onGraceExpired` and the watchdog's `sweepInboundSilence` — runs on a coroutine nobody awaits. A
     * race there is handed to the scope's [CoroutineExceptionHandler] and the seam keeps running with one
     * lifecycle loop dead. That is why #2481 could close its `broadcast` hazard and still record the
     * broad question as untouched: nothing in the suite could see a missing `withLock` off the caller's
     * path. [Node.escaped] is that missing eye.
     *
     * ## Two independent detectors, because they fail differently
     *  - **[assertNoCollectorLeaked]** catches the LOUD failure: an unguarded `LinkedHashMap` iterated
     *    while another coroutine structurally modifies it throws, and the throw lands in the handler.
     *  - **[assertRegistryAndConnsAgree], sampled DURING the churn** catches the QUIET one: two maps
     *    mutated without mutual exclusion can also just end up disagreeing, with nothing thrown at all.
     *    `formationSnapshot()` is taken under the seam's own lock, so a sample is atomic and the
     *    invariant it re-asserts (`auditRegistryLocked`'s ERROR condition) must hold at *every* instant,
     *    not merely at rest. Sampling it mid-flight is what makes the quiet failure detectable; the
     *    at-rest assertion the other probe makes cannot see a violation that heals.
     *
     * ## The rig, and the receipt that it fired
     * The hammer is a **redial storm**: the hub dials each peer [REDIALS_PER_PEER] more times while it is
     * already connected, so every dial drives the double-dial dedup — a `registry` rebind, a drain armed
     * on the loser, a `conns` removal, a tombstone and an ordering hold, all from `bytesReceivedLoop`,
     * while `connectionOpenedLoop`, `connectionClosedLoop`, `connectionStatesLoop` and the 1 ms watchdog
     * work the same two maps. A probe whose hammer never actually overlapped would be green by absence,
     * so three counters are asserted rather than inferred from a side effect:
     *  - [linksOpened] — the rig's input volume (dials that really opened a link).
     *  - `dialsDuringSends` — dials that *began* while at least one `broadcast` was in flight.
     *  - `seamMutationsUnderLoad` — samples, taken only for the storm's duration, in which the hub's own
     *    link view (connIds, each one's live/draining/unbound role, the peer it carries) had CHANGED
     *    since the previous sample. This is the one that matters: it witnesses the seam mutating
     *    `conns`/`registry` under load, which is the precondition for every race under test. It is
     *    deliberately neither a `peers` watcher nor gated on a dial being in flight — the sampler's own
     *    comment records what each of those two earlier formulations measured instead, and how badly.
     *  - `auditSamples` — how many times the quiet detector actually looked.
     *
     * Each is asserted against a floor at the END of the run rather than per iteration, so one quiet
     * round cannot false-red a rig that fired everywhere else.
     *
     * ## Knobs, and what each one switches OFF
     * [CHURN_ITERATIONS] — rounds; too few and the window is never sampled. [PEER_COUNT] — at 1 the
     * `registry` iteration is a single entry and the concurrent-modification window all but vanishes.
     * [REDIALS_PER_PEER] — at 0 there is no dedup churn at all and this becomes the shipped probe with
     * extra steps. [DIAL_SPACING] — the knob with a floor as well as a ceiling: too wide and the dials
     * stop overlapping anything, too narrow and the storm passes the fake's own backpressure ceiling and
     * the probe measures the harness. [CHURN_WATCHDOG_PROBE] — the knob most able to make it vacuous: at
     * `Duration.ZERO` the watchdog loop returns immediately and `sweepInboundSilence` is never entered,
     * so its guard becomes unpinned while everything still passes. [CHURN_BROADCASTERS] /
     * [CHURN_SENDS_PER_BROADCASTER] — at 0 the caller never contends and `dialsDuringSends` can only be
     * 0. [AUDIT_SAMPLE_INTERVAL] — widening it thins the quiet detector toward never looking.
     */
    @Test
    fun noLifecycleCollectorLeaksARaceWhileTheRegistryChurns() = runConcurrencyStress { stage ->
        val sendsInFlight = AtomicInteger()
        val dialsDuringSends = AtomicLong()
        val seamMutationsUnderLoad = AtomicLong()
        val auditSamples = AtomicLong()
        var linksOpened = 0L

        repeat(CHURN_ITERATIONS) { iter ->
            val radio = FakeNwRadio()
            val nodes = (0..PEER_COUNT).map { node(radio, it, iter.toLong() * 100, CHURN_WATCHDOG_PROBE) }
            val hub = nodes.first()
            val peers = nodes.drop(1)
            // EVERY seam gets a consumer, and the first cut of this probe did not — which cost a
            // 5-minute wedge and is worth recording. `DeliveryPolicy.Reliable` backpressures: with
            // nothing collecting `incoming`, each peer's spool fills, `deliveryDrainLoop` parks in
            // `Spool.deliver`, the bounded `deliveryStage` fills behind it and `bytesReceivedLoop`
            // stops demuxing. The seam is then wedged on *backpressure* — a property this probe is
            // not about — and every later stage inherits it. The sibling probe never notices because
            // it only ever awaits the hub, and the hub receives nothing.
            val consumers = CoroutineScope(Dispatchers.Default + SupervisorJob())
            for (n in nodes) consumers.launch { n.seam.incoming.collect { /* discard: the point is that it drains */ } }

            stage.at("iter=$iter form star") { dump(iter, radio, nodes) }
            for (p in peers) hub.api.connect(endpointFor(nodes.indexOf(p)))
            hub.seam.peers.first { it.size == nodes.size }

            stage.at("iter=$iter redial storm vs broadcast") { dump(iter, radio, nodes) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val dialersRunning = AtomicInteger(peers.size)
                // The quiet detector AND the mutation-rate witness, in one sampler. `launch`ed rather
                // than `async`ed and cancelled explicitly below: it never completes on its own, so
                // awaiting it would wedge the enclosing `coroutineScope` forever.
                //
                // It counts the hub's own link view CHANGING — connIds, each one's live/draining/unbound
                // role, and the peer it carries — between two consecutive samples. The sampler exists
                // only for the duration of the storm, so every change it sees is a mutation the seam
                // made **under load**, which is the precondition for every race here.
                //
                // Two earlier formulations of this counter were wrong, and both were caught by their own
                // floor rather than by inspection. Counting `peers` transitions measured 7 across 25
                // rounds: a dedup rebind moves a peer onto a fresh connection without changing a
                // `Set<PeerId>` at all, so a roster watcher is blind to precisely the mutation this storm
                // produces. Gating the link-view counter on "a dial is in flight" measured 10 across 60
                // rounds, and for a subtler reason: `api.connect` returns in microseconds while the
                // collectors' reaction to it lands milliseconds later, so that gate was measuring the
                // sampler's own duty cycle, not the system's. Caller/collector simultaneity is what
                // `dialsDuringSends` is for; this counter measures the mutation rate and nothing else.
                val auditor = launch(Dispatchers.Default) {
                    ready.await()
                    var previous: List<String> = emptyList()
                    while (isActive) {
                        assertRegistryAndConnsAgree(nodes, iter, radio)
                        val links = hub.seam.formationSnapshot().links
                            .map { "${it.connId}:${it.role}:${it.resolvedPeer}" }
                        if (links != previous) seamMutationsUnderLoad.incrementAndGet()
                        previous = links
                        auditSamples.incrementAndGet()
                        delay(AUDIT_SAMPLE_INTERVAL)
                    }
                }
                // Senders run for as long as the DIALERS do, up to a cap. Fixed-count senders finished in
                // the storm's first few milliseconds and left the rest of it with no caller-side
                // contender at all — the overlap the probe depends on has to be structural, not a
                // coincidence between two independently chosen counts.
                val senders = (0 until CHURN_BROADCASTERS).map {
                    async(Dispatchers.Default) {
                        ready.await()
                        var sent = 0
                        while (dialersRunning.get() > 0 && sent < CHURN_SENDS_PER_BROADCASTER) {
                            sendsInFlight.incrementAndGet()
                            try {
                                broadcastRefusingCleanly { hub.seam.broadcast(byteArrayOf(2)) }
                            } finally {
                                sendsInFlight.decrementAndGet()
                            }
                            sent++
                            delay(SEND_SPACING)
                        }
                    }
                }
                val dialers = peers.mapIndexed { i, _ ->
                    async(Dispatchers.Default) {
                        ready.await()
                        try {
                            repeat(REDIALS_PER_PEER) {
                                if (sendsInFlight.get() > 0) dialsDuringSends.incrementAndGet()
                                hub.api.connect(endpointFor(i + 1))
                            // Spaced in REAL time, not with `yield()`. Two reasons, and the second cost
                            // a 5-minute wedge on this branch before it was understood. (1) The sibling
                            // probe's reason: a burst fires every dial before the senders are
                            // mid-iteration. (2) `FakeNwApi`'s event flows are `MutableSharedFlow`s with
                            // a small buffer, so an `emit` BLOCKS once a device's single demux loop falls
                            // behind — and a dedup's `GOODBYE` travels hub→peer while the peer's own
                            // hello travels peer→hub, so two saturated demux loops deadlock on each
                            // other. That is the RIG's ceiling, not the seam's: past it the probe stops
                            // measuring locking and measures the fake's backpressure instead. Spacing
                            // keeps the storm below it while leaving the sweep/collector overlap intact.
                                delay(DIAL_SPACING)
                            }
                        } finally {
                            dialersRunning.decrementAndGet()
                        }
                    }
                }
                ready.complete(Unit)
                awaitAll(*dialers.toTypedArray(), *senders.toTypedArray())
                auditor.cancel()
            }

            stage.at("iter=$iter close every node") { dump(iter, radio, nodes) }
            coroutineScope {
                val closers = nodes.map { n -> async(Dispatchers.Default) { closeRefusingCleanly { n.seam.close(CloseReason.Normal) } } }
                awaitAll(*closers.toTypedArray())
            }
            for (n in nodes) n.seam.state.first { it is SeamState.Torn }

            linksOpened += radio.openedLinkCount
            assertAll(
                { assertNoCollectorLeaked(nodes, iter, radio) },
                { assertRegistryAndConnsAgree(nodes, iter, radio) },
            )
            consumers.cancel()
            teardown(nodes)
        }

        // The rig receipt. A probe that never made the hammer overlap the seam's own mutations passes
        // for the wrong reason, so the overlap is COUNTED, and the counts are part of the verdict.
        //
        // Printed as well as asserted, so a GREEN run leaves the numbers in the CI artifact. A floor
        // that passes tells a reader only that the rig cleared a bar someone once chose; the numbers
        // tell them how far above it, which is what makes a slow drift toward vacuity visible before it
        // reaches the floor.
        println(
            "nw-probe.rig-receipt rounds=$CHURN_ITERATIONS linksOpened=$linksOpened " +
                "dialsDuringSends=${dialsDuringSends.get()} seamMutationsUnderLoad=${seamMutationsUnderLoad.get()} " +
                "auditSamples=${auditSamples.get()}",
        )
        assertAll(
            { assertTrue(linksOpened >= MIN_LINKS_OPENED, "rig did not fire: only $linksOpened links opened across $CHURN_ITERATIONS rounds (floor $MIN_LINKS_OPENED) — the redial storm never dialled") },
            { assertTrue(dialsDuringSends.get() >= MIN_OVERLAPS, "rig did not fire: only ${dialsDuringSends.get()} dials began while a broadcast was in flight (floor $MIN_OVERLAPS) — caller and collectors never contended") },
            { assertTrue(seamMutationsUnderLoad.get() >= MIN_OVERLAPS, "rig did not fire: the hub's link view changed across only ${seamMutationsUnderLoad.get()} of the samples taken during the storm (floor $MIN_OVERLAPS) — the seam never mutated conns/registry under load") },
            { assertTrue(auditSamples.get() >= MIN_AUDIT_SAMPLES, "rig did not fire: the mid-flight binding audit looked only ${auditSamples.get()} times (floor $MIN_AUDIT_SAMPLES)") },
        )
    }

    // ── shared invariant + helpers ──────────────────────────────────────────────

    /**
     * Assert no throwable escaped any node's collectors.
     *
     * **Every** escape fails, not merely the ones with a race's signature. A `ConcurrentModificationException`
     * or `NoSuchElementException` out of an unguarded map is the expected shape and is called out by name
     * in the message, but a lifecycle loop that dies of *anything* leaves the seam looking healthy with a
     * collector silently gone — so narrowing this to a class list would be the same "green by absence"
     * mistake one level down. Cancellation never arrives here: a cancelled coroutine is not reported to a
     * [CoroutineExceptionHandler].
     */
    private fun assertNoCollectorLeaked(nodes: List<Node>, iteration: Int, radio: FakeNwRadio) {
        val leaks = nodes.filter { it.escaped.isNotEmpty() }
        if (leaks.isEmpty()) return
        val detail = leaks.joinToString("; ") { n ->
            "${n.peerId.value}: " + n.escaped.joinToString { "${it::class.simpleName}: ${it.message}" }
        }
        throw AssertionError(
            "a throwable escaped a NwSeam lifecycle collector — the loop is dead and the seam still looks " +
                "healthy (a ConcurrentModificationException/NoSuchElementException here means registry/conns " +
                "were mutated without mutual exclusion). $detail. ${dump(iteration, radio, nodes)}",
            leaks.first().escaped.first(),
        )
    }

    /**
     * Run [close]; fail loudly on a race exception. `close` is idempotent, so nothing else is expected.
     * The cancellation arm is here for the reason [broadcastRefusingCleanly]'s KDoc gives — a guard
     * around a call the harness may cancel must never be the thing that eats the cancellation.
     */
    private suspend fun closeRefusingCleanly(close: suspend () -> Unit) {
        try {
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("close leaked a ConcurrentModificationException; registry/conns are not thread-safe", e)
        } catch (e: NoSuchElementException) {
            throw AssertionError("close leaked a NoSuchElementException; a map was mutated under its own iteration", e)
        }
    }


    /**
     * Re-assert `auditRegistryLocked`'s **contract-impossible** condition from outside the seam: every
     * peer on the roster must be carried by a connection the seam still tracks.
     *
     * `NwSeam` logs this at ERROR rather than WARN precisely because its own model says it cannot
     * happen — so an occurrence can only be a bookkeeping bug, which under this probe means the two
     * maps were mutated without mutual exclusion. It is the cheapest detector that covers *every*
     * mutation site at once, including ones a per-test assertion would not think to name.
     *
     * A torn seam has an empty roster, so this is trivially satisfied there; it earns its keep on the
     * settled arms, where a live roster and a live `conns` must correspond one-to-one.
     */
    private fun assertRegistryAndConnsAgree(nodes: List<Node>, iteration: Int, radio: FakeNwRadio) {
        for (n in nodes) {
            val snapshot = n.seam.formationSnapshot()
            val roster = snapshot.peers.toSet() - n.peerId.value
            val carried = snapshot.links.filter { it.role == LIVE_ROLE }.mapNotNull { it.resolvedPeer }.toSet()
            assertEquals(
                roster,
                carried,
                "${n.peerId.value}: registry and conns disagree — every rostered peer must be carried by a " +
                    "tracked connection (NwSeam.auditRegistryLocked's ERROR condition). ${dump(iteration, radio, nodes)}",
            )
        }
    }

    /**
     * Run [broadcast]; fail loudly the instant a race exception escapes. A clean closed-seam
     * [IllegalStateException] is the contract once the seam is torn — accept it. Any send that lands
     * before teardown simply succeeds.
     *
     * ## The `CancellationException` arm is load-bearing, and its absence cost a diagnosis
     * `TimeoutCancellationException` extends `CancellationException` extends **`IllegalStateException`**,
     * so without this rethrow the arm below catches the harness's own 5-minute cap firing. It then
     * reports `unexpected IllegalStateException from broadcast: Timed out waiting for 300000 ms` — an
     * assertion failure that reaches `runConcurrencyStress` *instead of* the
     * `TimeoutCancellationException` it is watching for, so the entire hang report (stage, progress,
     * dispatcher verdict, coroutine census) is never produced. Measured on this branch: a wedge in this
     * probe's first cut surfaced with no stage label and no census at all. Rethrowing first restores it.
     */
    private suspend fun broadcastRefusingCleanly(broadcast: suspend () -> Unit) {
        try {
            broadcast()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConcurrentModificationException) {
            throw AssertionError("broadcast leaked a ConcurrentModificationException; registry/conns are not thread-safe", e)
        } catch (e: NoSuchElementException) {
            throw AssertionError("broadcast leaked a NoSuchElementException; a map was mutated under its own iteration", e)
        } catch (e: IllegalStateException) {
            // Clean closed-seam signal — acceptable, and the only refusal this method may make.
            assertTrue(e.message?.contains("is closed") == true, "unexpected IllegalStateException from broadcast: ${e.message}")
        }
    }

    private companion object {
        /** Rounds for the star-shaped broadcast probe — heavier per round (six seams), so fewer. */
        const val BROADCAST_ITERATIONS = 80

        /** `yield()`s of stagger per departing peer, so evictions land THROUGHOUT the broadcast hammer. */
        const val DEPARTURE_STAGGER_YIELDS = 8

        /** Remotes on the hub in the broadcast probe: enough that eviction and iteration genuinely overlap. */
        const val PEER_COUNT = 5

        /** [NwSeam.formationSnapshot]'s label for a connection carrying a rostered peer. */
        const val LIVE_ROLE = "live"

        /** Concurrent `broadcast` callers hammering one seam. */
        const val BROADCASTERS = 4
        const val SENDS_PER_BROADCASTER = 200

        /**
         * Watchdog sweep interval. Tiny on purpose: the sweep takes the seam's lock and walks `conns`,
         * so a fast one is another concurrent contender for the state under test rather than a loop
         * that never fires within a millisecond-scale round.
         */
        val WATCHDOG_PROBE = kotlin.time.Duration.parse("5ms")

        /** Zombie-link backstop for a displacement drain, small so a drain cannot outlive its round. */
        val DRAIN_BOUND = kotlin.time.Duration.parse("100ms")

        // ── knobs for [noLifecycleCollectorLeaksARaceWhileTheRegistryChurns] ────
        //
        // Separate from the broadcast probe's, deliberately: that one is a star built once per round and
        // torn down, this one re-dials the same star for the whole round, so the two want different
        // shapes. Sharing a constant would silently retune whichever probe was not being edited.

        /** Rounds for the redial-storm probe. Each round forms, storms and tears a 6-node star. */
        const val CHURN_ITERATIONS = 60

        /** Extra dials the hub makes to EACH already-connected peer — every one drives the dedup. */
        const val REDIALS_PER_PEER = 8

        /**
         * Real-time gap between one dialer's successive dials. The knob that keeps the storm under the
         * fake's inter-device backpressure ceiling — see the call site. Shrinking it toward zero does not
         * make the probe stronger; past the ceiling it stops measuring `NwSeam` at all.
         */
        val DIAL_SPACING = kotlin.time.Duration.parse("3ms")

        /**
         * Concurrent `broadcast` callers during the storm, and how many sends each makes. Much smaller
         * than the sibling probe's: there the sends ARE the subject, here they are only the caller-side
         * contender against a dedup churn that is itself the load, and a flood tips the fake over.
         */
        const val CHURN_BROADCASTERS = 2
        const val CHURN_SENDS_PER_BROADCASTER = 60

        /** Real-time gap between one broadcaster's sends, so the caller contends for the whole storm. */
        val SEND_SPACING = kotlin.time.Duration.parse("1ms")

        /**
         * Watchdog sweep interval during the storm — faster than [WATCHDOG_PROBE] because
         * `sweepInboundSilence` is one of the critical sections under test here rather than merely
         * another contender. It iterates `registry` and writes four `ConnState` fields per entry, on the
         * one coroutine that touches neither map anywhere else; at [kotlin.time.Duration.ZERO] the loop
         * returns before its first sweep and that whole critical section leaves the probe's reach.
         */
        val CHURN_WATCHDOG_PROBE = kotlin.time.Duration.parse("1ms")

        /** How often the QUIET detector samples the seam's binding invariant mid-storm. */
        val AUDIT_SAMPLE_INTERVAL = kotlin.time.Duration.parse("1ms")

        /**
         * Floors for the rig receipts. Deliberately far BELOW what a healthy run produces (measured in
         * the thousands) — they exist to catch a rig that stopped firing altogether, e.g. a knob edited
         * to a value at which nothing overlaps, not to pin a rate the host's load moves around.
         */
        val MIN_LINKS_OPENED: Long = (CHURN_ITERATIONS * PEER_COUNT).toLong()
        val MIN_OVERLAPS: Long = CHURN_ITERATIONS.toLong()
        val MIN_AUDIT_SAMPLES: Long = CHURN_ITERATIONS.toLong()
    }
}
