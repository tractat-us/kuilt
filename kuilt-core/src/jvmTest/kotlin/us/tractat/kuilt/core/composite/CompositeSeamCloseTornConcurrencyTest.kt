@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: the lost-terminal-Torn race only manifests under a real multi-threaded dispatcher — scope.cancel() is asynchronous, so a rollup collector can write a non-terminal state AFTER close()'s Torn only on genuinely parallel threads.
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import us.tractat.kuilt.test.runConcurrencyStress

/**
 * Regression probe for the **lost terminal-`Torn` transition** race in [CompositeSeam.close] (#1135).
 *
 * [CompositeSeam] is the one seam that *derives* its aggregate `state` from a live collector —
 * the `init` block's `_plies.onEach { _state.value = rollup(...) }.launchIn(scope)`. Before the fix,
 * `close()` cancelled that collector's scope with the **asynchronous, non-joining** `scope.cancel()`
 * and *then* published `SeamState.Torn`. Under a real multi-threaded dispatcher a rollup collector
 * already resumed for an in-flight `_plies` emission completes its non-suspending
 * `_state.value = rollup(...)` write (a non-terminal `Woven`/`Weaving`) **after** cancel — and, racing
 * `close()`, that write can land **after** the terminal `Torn`. `close()` is single-shot and the scope
 * is now dead, so nothing ever corrects it: `state` stays non-terminal **forever** and any
 * `state.first { it is Torn }` waiter (a caller awaiting orderly teardown) hangs. In #1135 this
 * surfaced as `CompositeSeamConcurrencyTest` hanging at `awaitTorn` until the 5-minute cap. The fix
 * makes `close()` [kotlinx.coroutines.cancelAndJoin] the internal scope BEFORE publishing `Torn`, so
 * no in-flight rollup can survive the terminal write.
 *
 * **Why this is a stress probe, not a deterministic test.** The clobber is an instant-level race —
 * the rollup body executes in nanoseconds, so it is only occasionally in-flight at the exact `close()`
 * instant — and it is genuinely multi-thread-only (under any single dispatcher, `cancel` and the
 * collector body are serialised, so the race cannot occur — see the coroutine-determinism rules).
 * A single close therefore clobbers only rarely; the probe closes **many** seams under the same
 * broadcast + ply-churn contention that reproduced #1135 so that at least one clobber is overwhelmingly
 * likely pre-fix. The per-iteration bounded `awaitTorn` makes any clobber fail **fast and by name**
 * (printing the observed non-terminal state), instead of hanging to the 5-minute cap; post-fix, every
 * iteration reaches `Torn` promptly.
 *
 * **JVM-hosted on purpose** (see [CompositeSeamConcurrencyTest]): the race is real-OS-thread only.
 */
class CompositeSeamCloseTornConcurrencyTest {

    @Test
    fun closePublishesTerminalTornEvenWhenPliesAreChurning() = runConcurrencyStress { stage ->
        val iterations = 6000
        val plyCount = 4
        // A composite absorbs a ply's attach/detach failure and keeps reconciling (#1784) — which is
        // right, but it means this probe would otherwise never see one. Before #1784 the failure was an
        // UNCAUGHT exception that killed the reconcile pump, and this suite emitted ~3,100 of them per
        // *passing* run: visible only in the report XML's system-err, which nothing asserts on. So watch
        // the signal instead of the stderr, and fail on it.
        val plyFailures = AtomicInteger()
        val firstPlyFailure = AtomicReference<PlyReconcileException?>(null)
        val watch: (PlyReconcileException) -> Unit = { failure ->
            plyFailures.incrementAndGet()
            firstPlyFailure.compareAndSet(null, failure)
        }
        repeat(iterations) { iter ->
            val dispatcher = Dispatchers.Default
            val plies = (0 until plyCount).map { PlyId("ply-$it") to (InMemoryLoom() as Loom) }
            val desired = MutableStateFlow(plies)
            // Read by the snapshot instead of a literal. The close-vs-churn stage SPANS host.close(), so a
            // hardcoded `closed = false` there asserts a seam is open that is already Torn — the inverse of
            // the "(close() was called)" defect this snapshot exists to end, and it is exactly the field an
            // investigator uses to decide whether the peers-strand verdict is interpretable.
            val hostCloseEntered = AtomicBoolean()

            val host = CompositeLoom(desired, dispatcher, onPlyFailure = watch).host(Pattern("host"))
            val joiner = CompositeLoom(desired, dispatcher, onPlyFailure = watch).join(InMemoryTag("join"))

            stage.at("iter=$iter host.peers==2") { snapshot(iter, host, joiner, plies, hostCloseEntered) }
            host.peers.first { it.size == 2 }
            stage.at("iter=$iter joiner.peers==2") { snapshot(iter, host, joiner, plies, hostCloseEntered) }
            joiner.peers.first { it.size == 2 }

            // Reproduce the #1135 contention: a broadcast flood keeps the dispatcher threads busy while
            // a ply-churn loop (detach ply-0, re-attach it) drives a continuous _plies → rollup stream;
            // close() races that live rollup. The yield() between churn steps defeats StateFlow
            // conflation (both the drop and the re-add are observed as real reconciles).
            stage.at("iter=$iter close-vs-churn") { snapshot(iter, host, joiner, plies, hostCloseEntered) }
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val flood = async(Dispatchers.Default) {
                    ready.await()
                    repeat(100) { runCatchingClosed { joiner.broadcast(byteArrayOf(2)) } }
                }
                val churn = async(Dispatchers.Default) {
                    ready.await()
                    repeat(40) {
                        desired.value = plies.drop(1)
                        yield()
                        desired.value = plies
                        yield()
                    }
                }
                ready.complete(Unit)
                // Close while the flood + churn are still running — close() must race a live rollup.
                hostCloseEntered.set(true)
                host.close()
                awaitAll(flood, churn)
            }
            joiner.close()

            // The per-iteration bounded assertion: a lost Torn makes `state.first { Torn }` hang (the
            // clobber is permanent), so a tight timeout converts it into a fast, self-naming failure that
            // prints the exact violated invariant and the observed (non-terminal-despite-close) state.
            stage.at("iter=$iter awaitTorn") { snapshot(iter, host, joiner, plies, hostCloseEntered) }
            try {
                withTimeout(3.seconds) { host.state.first { it is SeamState.Torn } }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(
                    "iter=$iter: close() returned but state never reached the terminal Torn — a rollup " +
                        "write clobbered close()'s Torn. Observed " +
                        "${snapshot(iter, host, joiner, plies, hostCloseEntered)}. " +
                        "Invariant: state.value must be Torn once close() has returned.",
                    e,
                )
            }
            assertIs<SeamState.Torn>(host.state.value, "iter=$iter: state must be Torn after close()")

            // Churning plies across a close() must never make the composite fail to attach or detach one.
            // The failure this catches is a reconcile pass that keeps running after close() drained `live`
            // and re-weaves the whole desired set onto the dead seam (#1784) — which, on a fabric that
            // rejects a second concurrent host, throws.
            val failures = plyFailures.get()
            if (failures != 0) {
                throw AssertionError(
                    "at or before iter=$iter: $failures ply attach/detach failure(s) while churning across " +
                        "close(). The counter spans iterations (a failure raised by a still-draining pump " +
                        "can land after its own iteration returned), so trust the ply named here, not the " +
                        "iteration. First: ${firstPlyFailure.get()}. Invariant: reconciling a composite " +
                        "must not fail for a ply whose fabric is healthy.",
                )
            }
        }
    }

    /** Run [op]; a clean closed-seam [IllegalStateException] is acceptable once the seam is torn. */
    private suspend fun runCatchingClosed(op: suspend () -> Unit) {
        try {
            op()
        } catch (_: IllegalStateException) {
            // Clean closed-seam signal — acceptable.
        }
    }

    /**
     * An observed-state snapshot for the harness's on-timeout diagnostic (see #1135), reporting
     * **identities and state, never sizes** — which is what makes a stall name its own cause.
     *
     * It reports both composites, each ply's underlying mesh membership, and — the load-bearing part —
     * [peersStrand]. Mesh membership answers whether the transport came up at all: if a ply's
     * `InMemoryLoom.peers` holds both transport ids the mesh formed, and if it does not the transport
     * never did. But it cannot say why a *formed* mesh failed to become `peers`, because it reads as
     * formed whether the `(plyId, transportId) → compositeId` mapping was never learned or was learned
     * and never published. Only the learned `idMap` beside the published `peers` separates those — see
     * [CompositeSeam.learnedIdMapOrNull].
     *
     * [hostCloseEntered] is a live per-iteration flag, not a per-call-site literal. The original snapshot
     * appended a hardcoded "(close() was called)" to *every* stage including the pre-close `peers` waits,
     * which is how #1784's first diagnosis went looking for a close-path cause for a setup stall; a literal
     * `false` on the stage that spans `close()` is the same defect with the sign flipped.
     */
    private fun snapshot(
        iter: Int,
        host: Seam,
        joiner: Seam,
        plies: List<Pair<PlyId, Loom>>,
        hostCloseEntered: AtomicBoolean,
    ): String = buildString {
        append("iter=").append(iter)
        append(" hostCloseEntered=").append(hostCloseEntered.get())
        append("\n  host{id=").append(host.selfId.value)
        append(" state=").append(host.state.value)
        append(" peers=").append(host.peers.value.map { it.value })
        append(" plies=").append(host.plies.value.mapKeys { it.key.value })
        append(" ").append(peersStrand(host))
        append("}\n  joiner{id=").append(joiner.selfId.value)
        append(" state=").append(joiner.state.value)
        append(" peers=").append(joiner.peers.value.map { it.value })
        append(" plies=").append(joiner.plies.value.mapKeys { it.key.value })
        append(" ").append(peersStrand(joiner))
        append("}\n  mesh{")
        plies.joinTo(this) { (id, loom) ->
            val members = (loom as? InMemoryLoom)?.peers?.value?.map { it.value }
            "${id.value}=$members"
        }
        append("}")
    }

    /**
     * The composite's peers strand: the learned mappings, the still-live plies, what a recompute **would**
     * publish now, and the published `peers` — with the verdict spelled out rather than left to be
     * re-derived.
     *
     * The verdict compares `peers` against [CompositeSeam.PeersStrand.wouldPublish], **never** against
     * `idMap` directly. An `idMap` entry missing from `peers` is *correct* whenever `recomputePeers`'
     * predicate rejects it — its ply is no longer live, or its transport peer has left that ply's peer
     * set — and in this close-heavy probe both hold routinely: `close()` clears `live` without purging
     * `idMap` or recomputing, and a far composite closing its ply seams removes its transport ids from the
     * shared `InMemoryLoom` mesh. Comparing against `idMap` would therefore print a lost-publish verdict on
     * essentially every post-close render — a confident, named, wrong mechanism, which is precisely the
     * failure the hardcoded `(close() was called)` caused and this snapshot exists to end. The one asymmetry
     * that *is* a finding is a peer the fold would publish and `peers` lacks.
     *
     * **The named mechanism is kept current with the code, or it becomes the very thing it exists to
     * prevent.** Since #1784 the publish is serialised on a single `peersWriter` and the fold reads each
     * ply's *mirrored* peer set, so "a `recomputePeers` publish was lost" — what this string used to say —
     * is no longer representable, and `recomputePeers` no longer publishes at all. A stall now divides into a
     * lost *trigger* (an input advanced without a `trySend`), a dead writer, or a stale *input*; the verdicts
     * below say so. Anyone changing the peers strand must re-read these strings, not just the KDoc.
     */
    private fun peersStrand(seam: Seam): String {
        val composite = seam as? CompositeSeam ?: return "strand=<not a CompositeSeam>"
        val strand = composite.peersStrandOrNull() ?: return "strand=<lock busy — not read>"
        val entries = strand.idMap.entries.map { (key, compositeId) ->
            "(${key.first.value}, ${key.second.value})->${compositeId.value}"
        }
        val published = seam.peers.value
        val owed = (strand.wouldPublish - published).map { it.value }
        val retained = (published - strand.wouldPublish).map { it.value }
        val verdict = when {
            strand.idMap.isEmpty() -> "no mapping recorded — stall is upstream of the peers strand"
            owed.isNotEmpty() ->
                "RECOMPUTE OWED $owed — the fold would publish a peer `peers` lacks. The publish is " +
                    "serialised on peersWriter (#1784), so a lost PUBLISH is not representable: if VERDICT " +
                    "above reads QUIESCENT this is a lost TRIGGER — some fold input advanced without a " +
                    "trySend — or peersWriter itself is dead; otherwise a request is still in flight"
            retained.isNotEmpty() ->
                "peers still advertises $retained that a recompute would now drop — EXPECTED after " +
                    "close(), which clears live without recomputing; not a finding"
            else ->
                "peers matches what the fold would publish — publishing is NOT the fault. If peers is " +
                    "nonetheless short of what the stage expected, the fold's INPUTS are: a ply mirror " +
                    "that never advanced (PlyHandle.transportPeers), a missing (plyId, transportId) idMap " +
                    "entry (both announce sends are best-effort and swallowed), or a ply absent from " +
                    "livePlies — compare wouldPublish against the plies' live mesh membership above"
        }
        return "idMap=$entries livePlies=${strand.livePlies.map { it.value }} " +
            "wouldPublish=${strand.wouldPublish.map { it.value }} verdict=[$verdict]"
    }
}
