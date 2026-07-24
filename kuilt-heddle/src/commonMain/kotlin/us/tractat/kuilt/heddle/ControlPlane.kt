package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftNode

// ═════════════════════════════════════════════════════════════════════════════
// The three non-monotone questions of design §9 — "the embroidery" — serialized on
// the Raft log: root mint, topology reconfiguration, and the (specified-only) fencing
// seam. Everything else in :kuilt-heddle is coordination-free cloth; the control plane
// is the only place :kuilt-raft appears (normative invariant §10.13).
// ═════════════════════════════════════════════════════════════════════════════

/**
 * One control-plane act, proposed to the Raft log and applied — in log order — on every
 * peer (design §9). The log **is** the serialization: two overlapping acts are ordered by
 * their commit index, and the loser is surfaced as a structured [ControlConflict], never
 * resolved by a timestamp (§4.6, §10.11).
 *
 * `internal` wire type — consumers drive it through the [GovernedHeddleNode] verbs
 * ([GovernedHeddleNode.mint] / [GovernedHeddleNode.prepare] / …), never by encoding a command.
 */
@Serializable
internal sealed interface ControlCommand {
    /**
     * Introduce root supply: credit [holder] with [amount] units under [mintId] (§9 #1). The one
     * non-conserving act, and the whole reason mint is consensus-gated — two halves of a split can
     * never both commit against the same supply because a minority can never reach quorum.
     */
    @Serializable
    data class Mint(val mintId: MintId, val holder: ReplicaId, val amount: Long) : ControlCommand

    /** Introduce a new attachment generation ([EntitlementLedger.prepare]); idempotent per id. */
    @Serializable
    data class Prepare(val record: AttachmentRecord) : ControlCommand

    /**
     * Open delegation across [edge] ([EntitlementLedger.activate]) — **the reshape serialization
     * point** (§9 #2). Serialized through the log so that if two peers concurrently attach a
     * *different* inbound edge to the same child, the first committed activate wins and the second
     * is refused as a [ControlConflict.DualInbound] rather than both applying and quarantining the
     * child's lineage (§5.2, §10.11).
     */
    @Serializable
    data class Activate(val edge: AttachmentId) : ControlCommand

    /** Stop new delegation across [edge] ([EntitlementLedger.close]); idempotent from closing. */
    @Serializable
    data class Close(val edge: AttachmentId) : ControlCommand

    /** Retire a drained [edge] ([EntitlementLedger.retire]); refused while entitlement is outstanding. */
    @Serializable
    data class Retire(val edge: AttachmentId) : ControlCommand
}

/**
 * The outcome of a committed control act, keyed to the log [index] it committed at. Because every
 * peer applies the committed log deterministically, every peer derives the **same** outcome for a
 * given index — the proposer simply reads back its own act's outcome once the local apply loop
 * reaches the committed index.
 */
public sealed interface ControlOutcome {
    /** The Raft log index this act committed at (its serialization position). */
    public val index: Long

    /** The act was admitted and applied to the replicated ledger. */
    public data class Applied(override val index: Long) : ControlOutcome

    /**
     * The act was serialized but **refused** — it lost a race (an overlapping reshape) or its
     * precondition no longer held once ordered. The entitlement state is untouched by a refused
     * act; the loser is surfaced here as a structured [conflict], never silently dropped and never
     * resolved by a clock (design §4.6, §9).
     */
    public data class Conflict(override val index: Long, public val conflict: ControlConflict) : ControlOutcome
}

/** Why a serialized control act was refused (see [ControlOutcome.Conflict]). */
public sealed interface ControlConflict {
    /**
     * An [Activate] that would give [child] a **second live inbound generation**: [incumbent] is
     * already live (ACTIVE/CLOSING) when the log reached [rejected]. This is the overlapping-reshape
     * loser — the log picked [incumbent], so [rejected] is refused (design §5.2, §10.11). Resolving
     * the fork (retire-and-abandon the loser) is a further control-plane act, not an in-ledger merge.
     */
    public data class DualInbound(
        public val child: GroupId,
        public val incumbent: AttachmentId,
        public val rejected: AttachmentId,
    ) : ControlConflict

    /**
     * A serialized act whose ledger precondition no longer held once ordered — an activate/close of
     * an unknown or divergent edge, or a retire of an edge that is not closing or not yet drained.
     * The [reason] is a diagnostic string.
     */
    public data class Refused(public val reason: String) : ControlConflict
}

/**
 * The seam the control plane drives to apply a committed act into the replicated ledger. The
 * governed node backs this with its [Quilter][us.tractat.kuilt.quilter.Quilter] (so applied acts
 * replicate over the data-plane seam too); tests back it with a plain in-memory holder. [mutate]
 * runs [block] against the **current** replicated state and applies its patch atomically, so the
 * control plane can read state and apply in one step under the sink's own lock.
 */
internal fun interface LedgerControl {
    /** Apply [block]'s patch to the current replicated ledger, atomically with reading it. */
    fun mutate(block: (EntitlementLedger) -> Patch<EntitlementLedger>)
}

/**
 * The Raft-backed control plane of design §9 — the serializer for the three non-monotone acts.
 * It proposes each act to the [raft] log and, on the committed-log apply loop that runs on **every**
 * peer, applies the act (in log order) to the replicated ledger via [ledger], recording a
 * per-index [ControlOutcome] the proposer reads back.
 *
 * The spend/schedule/reserve path never calls in here — that is the whole point (§10.13): consensus
 * appears only at mint and overlapping reshape, at no frequency on the data plane.
 *
 * @param nextIndex the first log index to replay from — `1` for a fresh node so no early committed
 *   act is missed (`committed` is replay-0; [committedFrom] replays from the start with no gap).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class HeddleControlPlane(
    private val raft: RaftNode,
    scope: CoroutineScope,
    private val ledger: LedgerControl,
    nextIndex: Long = 1L,
) {
    private val lock = reentrantLock()
    private val outcomes = HashMap<Long, ControlOutcome>()
    private val appliedIndex = MutableStateFlow(0L)

    /**
     * The specified-but-unshipped `readIndex()`-fenced revocation seam (design §9 #3). Defined so a
     * later reclamation feature has its extension point; v1 reclaims nothing (part of #1602).
     */
    val revocation: RevocationSeam = FencedRevocationSeam(raft)

    init {
        // The committed-log apply loop — runs on every peer, applies acts in log order, records the
        // per-index outcome. committedFrom(nextIndex) replays from the start with no gap (replay-0
        // `committed` would miss an act committed before this collector subscribed).
        scope.launch {
            raft.committedFrom(nextIndex).collect { committed ->
                when (committed) {
                    is Committed.Entry -> applyEntry(committed.entry)
                    // v1 does not publish snapshots to raft (compaction disabled), so no Install
                    // arrives; the replicated ledger is separately carried by the data-plane Quilter.
                    is Committed.Install -> Unit
                }
            }
        }
    }

    /** Propose [command], suspend until it commits, then read back the outcome the apply loop recorded. */
    suspend fun submit(command: ControlCommand): ControlOutcome {
        val entry = raft.propose(Cbor.encodeToByteArray(ControlCommand.serializer(), command))
        appliedIndex.first { it >= entry.index }
        return lock.withLock { outcomes[entry.index] }
            ?: error("control plane applied index ${entry.index} but recorded no outcome")
    }

    private fun applyEntry(entry: LogEntry) {
        val command = runCatchingCancellable {
            Cbor.decodeFromByteArray(ControlCommand.serializer(), entry.command)
        }.getOrNull()
        if (command != null) {
            var outcome: ControlOutcome = ControlOutcome.Applied(entry.index)
            ledger.mutate { state ->
                val (patch, oc) = evaluate(command, state, entry.index)
                outcome = oc
                patch ?: Patch(EntitlementLedger.ZERO)
            }
            lock.withLock { outcomes[entry.index] = outcome }
        }
        // A non-heddle entry (e.g. a config change) advances the index but records no outcome;
        // no heddle proposer ever awaits such an index, so submit()'s invariant still holds.
        appliedIndex.value = entry.index
    }

    /**
     * Decide a serialized act against [state] and return `(patch, outcome)`. The dual-inbound gate
     * for [ControlCommand.Activate] is the control plane's reason for existing: reading the child's
     * live inbound edges under the log's single order lets the loser be refused cleanly instead of
     * quarantined. A `null` patch means "apply nothing" (a conflict, or an idempotent no-op).
     */
    private fun evaluate(
        command: ControlCommand,
        state: EntitlementLedger,
        index: Long,
    ): Pair<Patch<EntitlementLedger>?, ControlOutcome> = when (command) {
        is ControlCommand.Mint ->
            state.mint(command.mintId, command.holder, command.amount) to ControlOutcome.Applied(index)

        is ControlCommand.Prepare ->
            // A duplicate prepare (id already known) yields a null patch — an idempotent no-op, applied.
            state.prepare(command.record) to ControlOutcome.Applied(index)

        is ControlCommand.Activate -> {
            val record = state.record(command.edge)
            when {
                record == null ->
                    null to ControlOutcome.Conflict(
                        index,
                        ControlConflict.Refused("activate: unknown or divergent edge ${command.edge.value}"),
                    )
                else -> {
                    val incumbent = state.liveInboundEdges(record.child).firstOrNull { it != command.edge }
                    if (incumbent != null) {
                        null to ControlOutcome.Conflict(
                            index,
                            ControlConflict.DualInbound(record.child, incumbent, command.edge),
                        )
                    } else {
                        val patch = state.activate(command.edge)
                        if (patch == null) {
                            null to ControlOutcome.Conflict(
                                index,
                                ControlConflict.Refused("activate refused (retired/divergent) ${command.edge.value}"),
                            )
                        } else {
                            patch to ControlOutcome.Applied(index)
                        }
                    }
                }
            }
        }

        is ControlCommand.Close -> {
            val patch = state.close(command.edge)
            if (patch == null) {
                null to ControlOutcome.Conflict(index, ControlConflict.Refused("close refused ${command.edge.value}"))
            } else {
                patch to ControlOutcome.Applied(index)
            }
        }

        is ControlCommand.Retire -> {
            val patch = state.retire(command.edge)
            if (patch == null) {
                null to ControlOutcome.Conflict(
                    index,
                    ControlConflict.Refused("retire refused (not closing / not drained) ${command.edge.value}"),
                )
            } else {
                patch to ControlOutcome.Applied(index)
            }
        }
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
