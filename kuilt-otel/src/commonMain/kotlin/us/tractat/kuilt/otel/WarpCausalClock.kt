@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpCausalClock")

/**
 * Mints causal [Dot]s for spans and tracks the current happens-before frontier.
 *
 * Call [tick] once per span, in the order the spans are created: it allocates the
 * span's own [Dot] and records the frontier observed so far as that span's
 * predecessors. The frontier then collapses to the new dot, so the *next* span's
 * predecessors point back here — building the happens-before chain a
 * [SpanLink][inferCausalLinks] is later derived from.
 *
 * The clock is **span-scoped**: it advances on span events only, which keeps
 * inference total — every predecessor dot resolves to a span in the set.
 * Cross-signal causality (a log or metric happening-before a span) is future work.
 *
 * ## Recovery is mandatory
 *
 * A restart that reset [seq] to 0 would re-mint dots already used by earlier spans
 * and silently corrupt causality. Always [recover] from a [DurableStore] at startup,
 * and [persist] after the spans of a batch are durably exported. [tick] itself stays
 * pure (non-suspending) so the lock stays tight; persistence is the caller's
 * explicit step.
 *
 * ## Thread-safety
 *
 * [seq] and the frontier are guarded by an explicit reentrant lock, so the clock is
 * correct under a real multi-threaded dispatcher — not merely under a test
 * dispatcher. `limitedParallelism(1)` confinement is BANNED (see CLAUDE.md).
 *
 * @param replica the stable [ReplicaId] for this device/process; namespaces every dot.
 */
public class WarpCausalClock(private val replica: ReplicaId) {

    private val lock = reentrantLock()
    private var seq: Long = 0L
    private var frontier: Set<Dot> = emptySet()

    internal companion object {
        /** The durable-store key this clock persists under. Exposed `internal` so a
         *  crash-window test can target it precisely rather than duplicating the literal. */
        internal val STORE_KEY = StoreKey("otel.causal.clock")
        private val cbor = Cbor { alwaysUseByteString = true }
        private val stateSerializer = PersistedClock.serializer()
    }

    /**
     * Mint the next span's [CausalStamp]: a fresh [Dot] whose predecessors are the
     * current frontier (empty on the very first tick). Advances the frontier to the
     * new dot.
     */
    public fun tick(): CausalStamp = lock.withLock {
        val predecessors = frontier
        val newDot = Dot(replica, ++seq)
        frontier = setOf(newDot)
        CausalStamp(dot = newDot, predecessors = predecessors)
    }

    /**
     * Fold a [remote] frontier (received from another replica via anti-entropy) into
     * the local frontier, so the next [tick] records those remote dots as
     * predecessors — the cross-replica causal path.
     */
    public fun observe(remote: Set<Dot>): Unit = lock.withLock {
        frontier = frontier + remote
    }

    /** A snapshot of the current causal frontier. */
    public fun frontier(): Set<Dot> = lock.withLock { frontier }

    /**
     * Reload `(seq, frontier)` from [store]. Call once at startup before any [tick];
     * a missing or corrupt entry leaves the clock fresh (seq 0, empty frontier).
     */
    public suspend fun recover(store: DurableStore) {
        val bytes = store.read(STORE_KEY) ?: return
        val state = runCatchingCancellable {
            cbor.decodeFromByteArray(stateSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.causal.clock: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock {
            seq = state.seq
            frontier = state.frontier
        }
    }

    /**
     * Durably persist the current `(seq, frontier)` to [store]. Call after a batch of
     * [tick]s is durably exported so a restart never re-mints a used dot.
     */
    public suspend fun persist(store: DurableStore) {
        val snapshot = lock.withLock { PersistedClock(seq, frontier) }
        store.write(STORE_KEY, cbor.encodeToByteArray(stateSerializer, snapshot))
    }
}

/** Serialized clock state: the high-water [seq] and the causal [frontier]. */
@Serializable
private data class PersistedClock(val seq: Long, val frontier: Set<Dot>)
