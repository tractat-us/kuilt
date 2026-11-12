package us.tractat.kuilt.heddle

/**
 * One free slot, one lease: ask who goes next, run their work, and charge what it really cost.
 * The heavy group goes first twice as often as the light one. A job that runs long is charged its
 * overrun; a short one hands the rest back.
 */
@Suppress("unused")
internal fun sampleLocalLease() {
    val root = GroupId("pool")
    val heavy = GroupId("heavy")
    val light = GroupId("light")
    // Booting from a Topology and topping up supply are the next two tasks (#3173); until they
    // land, this sample builds its two-group tree and its supply through the module's own seams.
    var tree = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "genesis")
    for (record in listOf(
        AttachmentRecord(AttachmentId("pool>heavy"), root, heavy, Weight.of(2)),
        AttachmentRecord(AttachmentId("pool>light"), root, light, Weight.ONE),
    )) {
        tree = tree.piece(checkNotNull(tree.prepare(record)).delta)
        tree = tree.piece(checkNotNull(tree.activate(record.id)).delta)
    }
    val heddle = LocalHeddle.over(tree, root, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    heddle.mintSupply(1_000L)

    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // Over many slots the shares follow the weights, 2 : 1.
    val picks = (0 until 30).map {
        val lease = (heddle.acquire(queued, queued, startedAt = it.toLong()) as AcquireOutcome.Acquired).lease
        heddle.complete(lease.id, actualCost = 3L)
        lease.leaf
    }
    check(picks.count { it == heavy } in 19..21)
}
