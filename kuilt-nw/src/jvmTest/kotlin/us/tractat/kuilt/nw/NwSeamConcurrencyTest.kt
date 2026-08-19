@file:Suppress("ForbiddenImport") // deliberate: real OS-thread concurrency stress harness — NwSeam's registry/conns/draining races only manifest under genuine cross-thread access, so this probe needs a real multi-threaded dispatcher, not a virtual one.

package us.tractat.kuilt.nw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real OS-thread concurrency stress harness — NwSeam's four lifecycle collectors are serialised onto one thread by any test dispatcher, which is exactly what makes a missing `withLock` invisible.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.StageTracker
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.runConcurrencyStress
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
 * ## What each test drives
 * One hazard each, and each one is backed by a revert receipt in the PR that added it — the specific
 * `withLock` that makes it safe was removed and the probe observed to redden **reliably**, not once.
 * A probe that cannot be made to red is green by absence.
 *
 *  1. [broadcastNeverLeaksARaceExceptionWhilePeersAreEvicted] — `broadcast` snapshotting `registry`
 *     while a lifecycle collector evicts out of it.
 *  2. [closeRacingARemoteDepartureProducesOneCleanTorn] — consumer `close()` against a remote's
 *     departure, both of which tear down `registry`/`conns`.
 *  3. [drainsAreNeverOrphanedWhenTheirLinkDiesUnderThem] — a displacement drain's start racing its
 *     own end.
 *
 * ## The dedup has deliberately NO probe here, and that is a finding rather than an omission
 * #2481 asked for one on "concurrent double-dial dedup on one pair", reasoning from `NwSeam`'s own
 * KDoc that the pre-port direction-based rule "could wedge a pair to zero under a multi-threaded
 * dispatcher". That premise does not survive contact with the code: `resolveIdentity` is reached
 * **only** from the single `bytesReceivedLoop`, so two resolutions on one seam can never race each
 * other however hard both ends dial — the dedup is not a two-writer hazard. Removing its `withLock`
 * outright left a dedicated double-dial probe green over 5 consecutive rounds.
 *
 * What genuinely races a resolution is another *coroutine* touching the same two maps, which is what
 * the three probes below already drive, and the resolve path's own `registry`/`conns` writes are
 * covered there. Trying to contend it directly with a broadcast hammer during formation is a trap
 * worth recording: `broadcast`'s send-failure arm calls `removeByConn`, which legitimately evicts the
 * peer, and with no `NwLoom` to redial the pair settles at zero peers — so the rig manufactures the
 * very wedge it claims to detect. That is a rig bug, not a seam bug, and a probe built on it would
 * have shipped as an intermittent false red.
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

    /** One simulated device: its transport, its seam, and the scope the seam's collectors live on. */
    private class Node(
        val peerId: PeerId,
        val deviceId: String,
        val api: FakeNwApi,
        val seam: NwSeam,
        val scope: CoroutineScope,
    )

    /**
     * A node on a **genuinely multi-threaded** scope with its own [SupervisorJob], so one seam's
     * `close()` — which cancels the scope it was handed — cannot take its peers' collectors with it.
     *
     * `random` is seeded per node so the canonical-nonce dedup is reproducible run to run: the probe
     * is about the *locking*, and an unseeded nonce would make which link wins a second, uncontrolled
     * variable. `inboundSilenceProbe` is deliberately TINY rather than disabled — the watchdog sweep
     * takes the same lock and iterates the same `conns` map, so a fast one is a fifth concurrent
     * contender for it rather than a dormant loop. `drainBound` is small so a drain whose `GOODBYE`
     * can never arrive still terminates inside an iteration instead of outliving it.
     */
    private fun node(radio: FakeNwRadio, index: Int, seed: Long): Node {
        val deviceId = "dev-$index"
        val peerId = PeerId("peer-$index")
        val api = FakeNwApi(radio, deviceId = deviceId, serviceName = "svc-$index")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val seam = NwSeam(
            selfId = peerId,
            api = api,
            scope = scope,
            random = Random(seed + index),
            inboundSilenceProbe = WATCHDOG_PROBE,
            drainBound = DRAIN_BOUND,
        )
        return Node(peerId, deviceId, api, seam, scope)
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

    // ── hazard 3: close() racing a remote departure ─────────────────────────────

    /**
     * `close()` and the remote's departure race into the same two maps from opposite directions.
     *
     * `close()` snapshots `registry.values + draining.keys`, then clears `registry`, `draining`,
     * `conns` and `graceJobs` in one critical section; `connectionClosedLoop` removes a single
     * connection from `conns`, tombstones it, and evicts its peer from `registry` in another. Both
     * are read-then-write over the same state, and both run on different threads here.
     *
     * The invariant is that teardown is **single-shot and total**: exactly one `Torn`, a roster of
     * exactly `{selfId}`, and — the part a roster check alone would miss — no `registry` entry left
     * naming a connection `conns` no longer has.
     */
    @Test
    fun closeRacingARemoteDepartureProducesOneCleanTorn() = runConcurrencyStress { stage ->
        repeat(PAIR_ITERATIONS) { iter ->
            val radio = FakeNwRadio()
            val nodes = listOf(node(radio, 0, iter.toLong()), node(radio, 1, iter.toLong()))
            val (a, b) = nodes

            stage.at("iter=$iter form pair") { dump(iter, radio, nodes) }
            a.api.connect(endpointFor(1))
            a.seam.peers.first { it.size == 2 }
            b.seam.peers.first { it.size == 2 }

            stage.at("iter=$iter close vs departure") { dump(iter, radio, nodes) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                // B's close disconnects the link, which drives A's connectionClosed + connectionStates
                // collectors into eviction — concurrently with A's OWN close clearing the same maps.
                val departure = async(Dispatchers.Default) { ready.await(); b.seam.close(CloseReason.Normal) }
                val closer = async(Dispatchers.Default) { ready.await(); a.seam.close(CloseReason.Normal) }
                ready.complete(Unit)
                awaitAll(departure, closer)
            }

            stage.at("iter=$iter await both torn") { dump(iter, radio, nodes) }
            a.seam.state.first { it is SeamState.Torn }
            b.seam.state.first { it is SeamState.Torn }

            assertAll(
                { assertIs<SeamState.Torn>(a.seam.state.value, "A did not latch a clean Torn") },
                { assertIs<SeamState.Torn>(b.seam.state.value, "B did not latch a clean Torn") },
                { assertEquals(setOf(a.peerId), a.seam.peers.value, "A's roster corrupted by close/departure: ${dump(iter, radio, nodes)}") },
                { assertEquals(setOf(b.peerId), b.seam.peers.value, "B's roster corrupted by close/departure: ${dump(iter, radio, nodes)}") },
                { assertRegistryAndConnsAgree(nodes, iter, radio) },
            )
            teardown(nodes)
        }
    }

    // ── hazard 4: drain start racing drain end ──────────────────────────────────

    /**
     * A displacement drain's **start** raced against its own **end**.
     *
     * `startDrain` runs outside `lock`: it arms the peer's ordering hold, builds the bound job, and
     * only then re-acquires the lock to attach that job to the `Drain` — which by then may already
     * have been taken by `connectionClosedLoop` or `removeByConn` on the link dying underneath it.
     * `NwSeam` has an explicit branch for losing that race (cancel the bound, un-arm the hold unless
     * another drain to the same peer still owns it). This probe is what makes that branch reachable:
     * it double-dials so a dedup loser exists, then tears links out concurrently.
     *
     * The invariant is that **no drain is orphaned**. A drain left in `draining` after everything has
     * settled holds a socket and the peer's receiver ordering hold open with nothing left that could
     * ever release them — the one shape this mechanism can fail in, and one that is invisible to a
     * roster check because a drained link is deliberately still in `conns` and still resolved.
     * `formationSnapshot()` reports it directly as a link whose role is `draining`.
     */
    @Test
    fun drainsAreNeverOrphanedWhenTheirLinkDiesUnderThem() = runConcurrencyStress { stage ->
        repeat(PAIR_ITERATIONS) { iter ->
            val radio = FakeNwRadio()
            val nodes = listOf(node(radio, 0, iter.toLong()), node(radio, 1, iter.toLong()))
            val (a, b) = nodes

            stage.at("iter=$iter double dial into dedup") { dump(iter, radio, nodes) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val dials = listOf(
                    async(Dispatchers.Default) { ready.await(); a.api.connect(endpointFor(1)) },
                    async(Dispatchers.Default) { ready.await(); b.api.connect(endpointFor(0)) },
                )
                // Racing the dedup's drain-start: whichever link this reaches, its drain (if one was
                // armed) is being ended by the close path while `startDrain` is still arming it.
                val churn = async(Dispatchers.Default) {
                    ready.await()
                    val links = radio.openedLinks
                    for (link in links) radio.disconnect(link.dialerDeviceId, link.dialerConnectionId)
                }
                ready.complete(Unit)
                awaitAll(churn, *dials.toTypedArray())
            }

            // Definitive terminator: closing both seams disposes of every link either still holds, so
            // anything left `draining` afterwards was genuinely orphaned rather than merely in flight.
            stage.at("iter=$iter close both") { dump(iter, radio, nodes) }
            a.seam.close(CloseReason.Normal)
            b.seam.close(CloseReason.Normal)
            a.seam.state.first { it is SeamState.Torn }
            b.seam.state.first { it is SeamState.Torn }

            stage.at("iter=$iter await drains to settle") { dump(iter, radio, nodes) }
            val stillDraining = awaitStable { nodes.sumOf { n -> n.seam.formationSnapshot().links.count { it.role == DRAINING_ROLE } } }

            assertAll(
                { assertEquals(0, stillDraining, "a drain was orphaned — it holds a socket and the peer's ordering hold with nothing left to release them: ${dump(iter, radio, nodes)}") },
                { assertEquals(setOf(a.peerId), a.seam.peers.value, "A's roster after teardown") },
                { assertEquals(setOf(b.peerId), b.seam.peers.value, "B's roster after teardown") },
                { assertRegistryAndConnsAgree(nodes, iter, radio) },
            )
            teardown(nodes)
        }
    }

    // ── shared invariant + helpers ──────────────────────────────────────────────

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
     * Poll [read] until it stops moving for [stableFor] consecutive reads, and return where it
     * settled; give up at [SETTLE_BUDGET_NANOS] and return the last reading anyway.
     *
     * Quiescing rather than waiting for an expected value is deliberate: a wait keyed to the number
     * the caller is about to assert makes its failing arm an uninformative timeout, while this hands
     * back whatever the system actually settled on and lets `assertEquals` print both sides. The
     * budget is a **wedge backstop**, never the assertion — a genuinely stuck run is caught by
     * `runConcurrencyStress`'s own cap with a full coroutine dump attached, which is the diagnostic
     * that matters.
     *
     * A real-time bound is legitimate here in a way it never is under virtual time: this probe's
     * trajectory *is* wall-clock, so there is no virtual-time schedule for a ceiling to distort.
     */
    private suspend fun awaitStable(stableFor: Int = STABLE_READS, read: () -> Int): Int {
        val deadline = System.nanoTime() + SETTLE_BUDGET_NANOS
        var last = read()
        var stable = 0
        while (System.nanoTime() < deadline) {
            delay(POLL_MILLIS)
            val now = read()
            if (now == last) {
                stable += 1
                if (stable >= stableFor) return now
            } else {
                stable = 0
                last = now
            }
        }
        return read()
    }

    /**
     * Run [broadcast]; fail loudly the instant a race exception escapes. A clean closed-seam
     * [IllegalStateException] is the contract once the seam is torn — accept it. Any send that lands
     * before teardown simply succeeds.
     */
    private suspend fun broadcastRefusingCleanly(broadcast: suspend () -> Unit) {
        try {
            broadcast()
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
        /**
         * Rounds per pair-shaped probe. Each round builds two seams, forms them over real threads and
         * tears them down, so the window under test is re-entered once per round; the count is what
         * turns a narrow interleaving into a reliable red rather than an occasional one.
         */
        const val PAIR_ITERATIONS = 150

        /** Rounds for the star-shaped broadcast probe — heavier per round (six seams), so fewer. */
        const val BROADCAST_ITERATIONS = 80

        /** `yield()`s of stagger per departing peer, so evictions land THROUGHOUT the broadcast hammer. */
        const val DEPARTURE_STAGGER_YIELDS = 8

        /** Remotes on the hub in the broadcast probe: enough that eviction and iteration genuinely overlap. */
        const val PEER_COUNT = 5

        /** Concurrent `broadcast` callers hammering one seam. */
        const val BROADCASTERS = 4
        const val SENDS_PER_BROADCASTER = 200

        /** [NwSeam.formationSnapshot]'s role labels — the two this probe reads. */
        const val LIVE_ROLE = "live"
        const val DRAINING_ROLE = "draining"

        /** Consecutive unchanged reads that count as settled in [awaitStable]. */
        const val STABLE_READS = 20
        const val POLL_MILLIS = 1L

        /** Wedge backstop for [awaitStable] — five seconds, mirroring `MeshSeamConcurrencyTest`'s. */
        const val SETTLE_BUDGET_NANOS = 5_000_000_000L

        /**
         * Watchdog sweep interval. Tiny on purpose: the sweep takes the seam's lock and walks `conns`,
         * so a fast one is another concurrent contender for the state under test rather than a loop
         * that never fires within a millisecond-scale round.
         */
        val WATCHDOG_PROBE = kotlin.time.Duration.parse("5ms")

        /** Zombie-link backstop for a displacement drain, small so a drain cannot outlive its round. */
        val DRAIN_BOUND = kotlin.time.Duration.parse("100ms")
    }
}
