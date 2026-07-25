package us.tractat.kuilt.warp

/**
 * An **opaque admission gate** over warp's free ([CoordinationKind.Free]) execution path.
 *
 * Before a ring owner runs a task, [WarpNode] asks its [AdmissionControl] whether the
 * task may proceed *right now*. Returning an [AdmissionTicket] admits it; returning
 * `null` **defers** it — the task stays pending and is retried on a later claim cycle,
 * never dropped. After the task finishes (successfully or with a terminal error) the
 * node calls [AdmissionTicket.settle] exactly once.
 *
 * Warp core supplies the meaning-free default [OPEN], which admits every task
 * immediately with a no-op ticket — so an untagged workload behaves bit-for-bit as it
 * did before admission control existed. The interesting implementation lives in a
 * satellite: `:kuilt-warp-heddle` backs [admit] with a weighted fair-share ledger,
 * reserving a task's [Lane] leaf entitlement here and completing it in [settle].
 * Warp core references no such type — it only knows the two functional interfaces.
 *
 * Calls are non-suspending on purpose: an admission decision is local bookkeeping (a
 * reservation against already-converged holdings), never a network round-trip. This
 * keeps the free path free — admission adds zero consensus.
 */
public fun interface AdmissionControl {
    /**
     * Decide whether [descriptor] may execute now.
     *
     * @return an [AdmissionTicket] to run the task (its [AdmissionTicket.settle] is
     *   invoked once the task completes), or `null` to **defer** — leaving the task
     *   pending for a later claim cycle without dropping it.
     */
    public fun admit(descriptor: TaskDescriptor): AdmissionTicket?

    public companion object {
        /** Admit every task immediately with a no-op ticket — the un-gated default. */
        public val OPEN: AdmissionControl = AdmissionControl { AdmissionTicket.NOOP }
    }
}

/**
 * A handle returned by [AdmissionControl.admit] for an admitted task. [WarpNode] calls
 * [settle] exactly once when the task completes, so an enforcement adapter can release
 * or charge whatever it reserved at admission time.
 */
public fun interface AdmissionTicket {
    /** Finish the admission — called once after the admitted task completes. */
    public fun settle()

    public companion object {
        /** A ticket that does nothing on settle — the [AdmissionControl.OPEN] default. */
        public val NOOP: AdmissionTicket = AdmissionTicket { }
    }
}
