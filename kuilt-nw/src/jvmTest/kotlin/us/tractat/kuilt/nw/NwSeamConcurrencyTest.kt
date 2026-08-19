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
    }
}
