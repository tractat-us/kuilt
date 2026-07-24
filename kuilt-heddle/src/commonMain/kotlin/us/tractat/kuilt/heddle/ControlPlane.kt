package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftNode
import kotlin.random.Random
import kotlin.time.Duration

// ═════════════════════════════════════════════════════════════════════════════
// The three non-monotone questions of design §9 — "the embroidery" — serialized on
// the Raft log: root mint, topology reconfiguration, and the (specified-only) fencing
// seam. Everything else in :kuilt-heddle is coordination-free cloth; the control plane
// is the only place :kuilt-raft appears (normative invariant §10.13).
// ═════════════════════════════════════════════════════════════════════════════

/**
 * One control-plane act, proposed to the Raft log and applied — in log order — on every peer
 * (design §9). `internal` wire type — consumers drive it through the [GovernedHeddleNode] verbs.
 *
 * A mint carries **no** [MintId]: identity is derived from the act's [ControlEnvelope.requestKey]
 * ([HeddleControlPlane]), which is unique per logical act and stable across a retry — so a distinct
 * act never collides (no silent mint loss) and a retry never double-mints.
 */
@Serializable
internal sealed interface ControlCommand {
    /** Introduce root supply: credit [holder] with [amount] units (§9 #1). */
    @Serializable
    data class Mint(val holder: ReplicaId, val amount: Long) : ControlCommand

    /** Introduce a new attachment generation ([EntitlementLedger.prepare]); idempotent per id. */
    @Serializable
    data class Prepare(val record: AttachmentRecord) : ControlCommand

    /** Open delegation across [edge] ([EntitlementLedger.activate]) — the reshape serialization point (§9 #2). */
    @Serializable
    data class Activate(val edge: AttachmentId) : ControlCommand

    /** Stop new delegation across [edge] ([EntitlementLedger.close]); idempotent from closing. */
    @Serializable
    data class Close(val edge: AttachmentId) : ControlCommand

    /** Retire a drained [edge] ([EntitlementLedger.retire]); gated on the log-order lifecycle being CLOSING. */
    @Serializable
    data class Retire(val edge: AttachmentId) : ControlCommand
}

/**
 * The wire framing for one act: the [command] plus a [requestKey] that is **unique per logical
 * act and stable across a retry** (design §9 exactly-once). The apply loop dedups on it, and a
 * mint derives its [MintId] from it — so a retried act is applied at most once and distinct acts
 * never collide, restart-safe.
 */
@Serializable
internal data class ControlEnvelope(val requestKey: String, val command: ControlCommand)

/**
 * The outcome of a committed control act, keyed to the log [index] it committed at. Because every
 * peer applies the committed log against a **log-pure projection** (see [HeddleControlPlane]), every
 * peer derives the **same** outcome for a given index — the proposer reads back its own act's outcome.
 */
public sealed interface ControlOutcome {
    /** The Raft log index this act committed at, or [NOT_COMMITTED] for a locally-refused act. */
    public val index: Long

    /** The act was admitted and applied to the log-order control state (and published to the data plane). */
    public data class Applied(override val index: Long) : ControlOutcome

    /**
     * The act was **refused** — it lost an overlapping-reshape race, its precondition no longer held
     * once ordered, or it was refused locally before proposing (a non-drained retire). The
     * entitlement state is untouched; the loser is surfaced as a structured [conflict], never
     * silently dropped and never resolved by a clock (design §4.6, §9).
     */
    public data class Conflict(override val index: Long, public val conflict: ControlConflict) : ControlOutcome

    public companion object {
        /** [index] sentinel for an act refused locally, before it ever reached the log. */
        public const val NOT_COMMITTED: Long = -1L
    }
}

/** Why a serialized (or locally pre-checked) control act was refused (see [ControlOutcome.Conflict]). */
public sealed interface ControlConflict {
    /**
     * An [ControlCommand.Activate] that would give [child] a **second live inbound generation**:
     * [incumbent] is already live (ACTIVE/CLOSING) in the log-order state when the log reached
     * [rejected]. This is the overlapping-reshape loser — the log picked [incumbent], so [rejected]
     * is refused (design §5.2, §10.11). Because the decision reads log-order state, every peer
     * derives it identically, so the rejected generation never enters any peer's replicated ledger.
     */
    public data class DualInbound(
        public val child: GroupId,
        public val incumbent: AttachmentId,
        public val rejected: AttachmentId,
    ) : ControlConflict

    /**
     * A serialized act whose precondition no longer held once ordered — an activate/close of an
     * unknown/divergent or retired edge, a retire of a non-CLOSING edge, or a retire refused
     * **locally** because the edge is not drained (retiring a non-drained edge would strand its
     * outstanding entitlement, since a RETIRED edge is no longer on the live lineage the data plane
     * drains through). The [reason] is a diagnostic string.
     */
    public data class Refused(public val reason: String) : ControlConflict
}

/**
 * The seam the control plane uses to **publish an approved act into the data-plane replicated
 * ledger** (the [Quilter][us.tractat.kuilt.quilter.Quilter]) so data-plane consumers converge. The
 * governed node backs this with its Quilter; tests back it with a plain in-memory holder.
 *
 * Publication is one-directional: the accept/refuse **decision** is made upstream against the
 * control plane's own log-pure projection, never against whatever the Quilter has gossip-merged. A
 * rejected act is never published, so it never enters any Quilter and never gossips out.
 */
internal fun interface ControlLedgerSink {
    /** Publish an approved control patch into the data-plane replicated ledger. */
    fun publish(patch: Patch<EntitlementLedger>)
}

/**
 * The Raft-backed control plane of design §9. It proposes each act to the [raft] log and, on the
 * committed-log apply loop that runs on **every** peer, applies the act — in log order — against a
 * **private log-pure control-state projection** it owns, then publishes the approved patch into the
 * data-plane [sink].
 *
 * ## Why a private projection, and why it is the crux of consensus safety
 *
 * The projection ([projection]) is a [EntitlementLedger] mutated **only** by [applyEntry], applying
 * committed commands in index order. Every accept/refuse gate — the dual-inbound check, the lifecycle
 * checks — reads **only** the projection. It is therefore a deterministic function of the log prefix,
 * so every peer derives the same outcome for a given index (Raft §5.4.3 State Machine Safety).
 *
 * This is deliberate and load-bearing. The data-plane Quilter is a CRDT whose `lifecycle`
 * max-register merges anti-entropy traffic from peers on an independent transport at an independent
 * rate — so its state at the moment a command is applied is **not** a function of the log prefix (it
 * can already carry max-merged effects of *later* log entries applied elsewhere). Gating on that
 * merged state would make apply non-deterministic: a peer replaying [RaftNode.committedFrom] after
 * its Quilter had merged the converged state could approve an activate that an in-order peer refused,
 * creating the exact [LedgerConflict.DualActiveInbound] quarantine H5 exists to prevent. Refusing to
 * merge ahead is impossible; refusing to **gate** on merge-ahead is the fix. Because the loser is
 * decided on the log-order projection, its patch is never published — a rogue activation never enters
 * **any** Quilter.
 *
 * ## Exactly-once
 *
 * Each act carries a [ControlEnvelope.requestKey] unique per logical act and stable across a retry
 * (`self#incarnation#seq`; a fresh random [incarnation] per construction makes it restart-safe). The
 * apply loop dedups on it ([applied]), and a mint derives its [MintId] from it — so a distinct act
 * never collides (no silent mint loss) and a retry never double-mints. [submit] retries on
 * [LeadershipLostException] reusing the same key + Raft `requestId`, and can be bounded by a timeout.
 *
 * @param initial the projection's initial state — the same ledger the data-plane node bootstraps from.
 * @param nextIndex the first log index to replay from — `1` for a fresh node ([RaftNode.committedFrom]
 *   replays from the start with no gap; replay-0 `committed` would miss an act committed before subscription).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class HeddleControlPlane(
    private val raft: RaftNode,
    private val self: ReplicaId,
    scope: CoroutineScope,
    private val sink: ControlLedgerSink,
    initial: EntitlementLedger,
    random: Random,
    nextIndex: Long = 1L,
) {
    private val lock = reentrantLock()

    /** The log-pure control-state projection — mutated ONLY by [applyEntry], in index order. */
    private var projection: EntitlementLedger = initial

    /** In-flight local submits awaiting their committed outcome, keyed by requestKey. */
    private val pending = HashMap<String, CompletableDeferred<ControlOutcome>>()

    /** Dedup table: requestKey → the outcome it applied at (exactly-once under retry / re-commit). */
    private val applied = HashMap<String, ControlOutcome>()

    /** Per-incarnation nonce so a restart's regenerated keys never collide with the prior run's. */
    private val incarnation: Long = random.nextLong()
    private var seq: Long = 0L

    /**
     * The specified-but-unshipped `readIndex()`-fenced revocation seam (design §9 #3). Defined so a
     * later reclamation feature has its extension point; v1 reclaims nothing (part of #1602).
     */
    val revocation: RevocationSeam = FencedRevocationSeam(raft)

    init {
        scope.launch {
            raft.committedFrom(nextIndex).collect { committed ->
                when (committed) {
                    is Committed.Entry -> applyEntry(committed.entry)
                    // v1 does not publish snapshots to raft (compaction disabled — see heddleGoverned),
                    // so no Install arrives; the replicated ledger is carried by the data-plane Quilter.
                    is Committed.Install -> Unit
                }
            }
        }
    }

    /** The log-order control state as applied so far — test/inspection support (never the Quilter). */
    fun projectionSnapshot(): EntitlementLedger = lock.withLock { projection }

    /**
     * Propose [command], suspend until it commits (or is deduped), and return the outcome the apply
     * loop recorded. Retries on [LeadershipLostException] with the **same** requestKey + Raft
     * `requestId`, so a leader step-down mid-flight never double-applies. If [timeout] is non-null the
     * await is bounded, so a leader *crash* surfaces as a timeout instead of hanging.
     */
    suspend fun submit(command: ControlCommand, timeout: Duration? = null): ControlOutcome {
        val (key, requestId) = lock.withLock {
            val k = "${self.value}#$incarnation#$seq"
            val r = seq
            seq++
            k to r
        }
        val deferred = CompletableDeferred<ControlOutcome>()
        lock.withLock {
            applied[key]?.let { return it }
            pending[key] = deferred
        }
        val bytes = Cbor.encodeToByteArray(ControlEnvelope.serializer(), ControlEnvelope(key, command))
        try {
            // The timeout spans BOTH the propose and the apply-await, so a leader *crash* (a forwarded
            // proposal that never commits and never rejects) surfaces instead of hanging forever.
            return if (timeout == null) {
                proposeWithRetry(bytes, requestId)
                deferred.await()
            } else {
                withTimeout(timeout) {
                    proposeWithRetry(bytes, requestId)
                    deferred.await()
                }
            }
        } finally {
            lock.withLock { pending.remove(key) }
        }
    }

    private suspend fun proposeWithRetry(bytes: ByteArray, requestId: Long) {
        var attempts = 0
        while (true) {
            try {
                raft.propose(bytes, requestId)
                return
            } catch (e: LeadershipLostException) {
                // A forwarded proposal was rejected by a stepping-down leader; retry on the next leader
                // with the SAME requestId (Raft §8 dedup) and requestKey (apply-side dedup) — exactly-once.
                if (++attempts >= MAX_PROPOSE_RETRIES) throw e
            }
        }
    }

    private fun applyEntry(entry: LogEntry) {
        val envelope = runCatchingCancellable {
            Cbor.decodeFromByteArray(ControlEnvelope.serializer(), entry.command)
        }.getOrNull() ?: return // a non-heddle entry (e.g. a config change) — no outcome, no projection change
        lock.withLock {
            val prior = applied[envelope.requestKey]
            if (prior != null) {
                // A retry that still committed a second entry — never apply twice; hand back the first outcome.
                pending[envelope.requestKey]?.complete(prior)
                return
            }
            val outcome = decideAndApply(envelope.command, envelope.requestKey, entry.index)
            applied[envelope.requestKey] = outcome
            pending[envelope.requestKey]?.complete(outcome)
        }
    }

    /**
     * Decide [command] against the **log-pure [projection]** and, if approved, apply its patch to the
     * projection AND publish it to the data-plane [sink]. Called under [lock] so the projection read
     * and mutation are atomic in log order. The dual-inbound gate reading `projection.liveInboundEdges`
     * is why the control plane exists (see the class KDoc).
     */
    private fun decideAndApply(command: ControlCommand, requestKey: String, index: Long): ControlOutcome =
        when (command) {
            is ControlCommand.Mint -> {
                // Mint identity is derived from the (unique, retry-stable, restart-safe) requestKey, so
                // distinct acts never max-collide into one lost mint and a retry never double-mints.
                apply(projection.mint(MintId("mint#$requestKey"), command.holder, command.amount))
                ControlOutcome.Applied(index)
            }

            is ControlCommand.Prepare -> {
                // A duplicate prepare (id already known) yields a null patch — an idempotent no-op, applied.
                projection.prepare(command.record)?.let { apply(it) }
                ControlOutcome.Applied(index)
            }

            is ControlCommand.Activate -> {
                val record = projection.record(command.edge)
                when {
                    record == null ->
                        ControlOutcome.Conflict(
                            index,
                            ControlConflict.Refused("activate: unknown or divergent edge ${command.edge.value}"),
                        )
                    else -> {
                        val incumbent = projection.liveInboundEdges(record.child).firstOrNull { it != command.edge }
                        if (incumbent != null) {
                            ControlOutcome.Conflict(
                                index,
                                ControlConflict.DualInbound(record.child, incumbent, command.edge),
                            )
                        } else {
                            val patch = projection.activate(command.edge)
                            if (patch == null) {
                                ControlOutcome.Conflict(
                                    index,
                                    ControlConflict.Refused("activate refused (retired/divergent) ${command.edge.value}"),
                                )
                            } else {
                                apply(patch)
                                ControlOutcome.Applied(index)
                            }
                        }
                    }
                }
            }

            is ControlCommand.Close -> {
                val patch = projection.close(command.edge)
                if (patch == null) {
                    ControlOutcome.Conflict(index, ControlConflict.Refused("close refused ${command.edge.value}"))
                } else {
                    apply(patch)
                    ControlOutcome.Applied(index)
                }
            }

            is ControlCommand.Retire -> {
                // Gated on the log-order lifecycle being CLOSING (the projection has no data-plane
                // counters, so its `outstanding` is 0 — the drain condition is enforced pre-propose by
                // GovernedHeddleNode.retire against the data-plane view, since drain is a data-plane fact).
                val patch = projection.retire(command.edge)
                if (patch == null) {
                    ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused("retire refused (edge not CLOSING in log order) ${command.edge.value}"),
                    )
                } else {
                    apply(patch)
                    ControlOutcome.Applied(index)
                }
            }
        }

    /** Apply an approved patch to the log-pure projection and publish it for data-plane replication. */
    private fun apply(patch: Patch<EntitlementLedger>) {
        projection = projection.piece(patch.delta)
        sink.publish(patch)
    }

    private companion object {
        /** Bounded retries on a leader step-down before surfacing [LeadershipLostException]. */
        const val MAX_PROPOSE_RETRIES: Int = 5
    }
}

/**
 * The `readIndex()`-fenced revocation seam of design §9 #3 — **specified, not shipped in v1**.
 *
 * When a peer crashes, its holdings and earmarks stay safely *stranded* (a wrong reclaim is an
 * overspend — the one unforgivable failure, §8.1). Reclaiming them is a control-plane act that must
 * be **fenced**: the leader confirms it still holds a voter quorum at its term via [RaftNode.readIndex]
 * (the same deposed-leader-cannot-pass-the-fence mechanism the coordinated path already uses) before
 * proposing the revocation, so a partitioned ex-leader can never revoke against stale authority.
 *
 * v1 defines this interface as the extension point and ships **no reclamation** — [revoke] performs
 * none and returns [RevocationOutcome.NotShipped]. Reclamation is an explicit later feature
 * (part of #1602); this seam is where it will hook. The [raft] handle is retained so a future impl
 * can call `raft.readIndex()` without a signature change.
 */
public interface RevocationSeam {
    /**
     * Revoke [holder]'s stranded holdings at group [at], fenced behind a `readIndex()` quorum.
     * **Not shipped in v1** — returns [RevocationOutcome.NotShipped] and reclaims nothing.
     */
    public suspend fun revoke(holder: ReplicaId, at: GroupId): RevocationOutcome
}

/** The result of a [RevocationSeam.revoke] request. */
public sealed interface RevocationOutcome {
    /**
     * v1 sentinel: the fencing seam is defined but reclamation is not shipped (design §9;
     * part of #1602). No entitlement was reclaimed; the crashed peer's holdings remain stranded.
     */
    public data object NotShipped : RevocationOutcome
}

/** The v1 [RevocationSeam]: retains [raft] for the future `readIndex()` fence but reclaims nothing. */
internal class FencedRevocationSeam(@Suppress("unused") private val raft: RaftNode) : RevocationSeam {
    override suspend fun revoke(holder: ReplicaId, at: GroupId): RevocationOutcome = RevocationOutcome.NotShipped
}
