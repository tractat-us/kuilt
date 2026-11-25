package us.tractat.kuilt.heddle

/**
 * Show every group's share of the pool on one table: read one line, then look each row up. A
 * team's share is what its own lanes are owed between them, and a team with nothing queued is
 * owed nothing, so the rest of the pool splits its part.
 */
@Suppress("unused")
internal fun sampleShareLine() {
    val tree = draft("pool") {
        group("research", Weight.of(2)) {
            lane("gpu", Weight.of(3))
            lane("cpu")
        }
        lane("ops")
        lane("docs")
    }
    val pool = tree.groupId()
    val research = tree.groupId("research")
    val gpu = tree.groupId("research", "gpu")
    val cpu = tree.groupId("research", "cpu")
    val ops = tree.groupId("ops")
    val docs = tree.groupId("docs")
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    check(booted.refusals.isEmpty())

    // Docs has nothing queued, so research (weight 2) and ops (weight 1) split the pool 2 : 1.
    val queued = setOf(gpu, cpu, ops)
    val line = heddle.shareLine(queued)
    check(line.share(pool) == Rational.ONE)
    check(line.share(research) == Rational.of(2L, 3L))
    check(line.share(ops) == Rational.of(1L, 3L))
    check(line.share(docs) == Rational.ZERO)
    // Research's share is its lanes' entitlements of one slot, added up.
    check(line.share(gpu) == heddle.entitlement(gpu, queued, totalSlots = 1L))
    check(line.share(research) == heddle.entitlement(gpu, queued, 1L) + heddle.entitlement(cpu, queued, 1L))

    // With nothing queued anywhere, nothing competes, and even the pool is owed nothing.
    check(heddle.shareLine(emptySet()).share(pool) == Rational.ZERO)
}

/**
 * Show how far each lane stands from its share: a lane just served reads below zero, the lane it
 * went ahead of reads what it is owed, and a lane back from idle starts level with the rest.
 */
@Suppress("unused")
internal fun sampleShareLineLag() {
    val tree = draft("pool") {
        lane("web")
        lane("mobile")
        lane("docs")
    }
    val pool = tree.groupId()
    val web = tree.groupId("web")
    val mobile = tree.groupId("mobile")
    val docs = tree.groupId("docs")
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 2L)
    val both = setOf(web, mobile)

    // Both wait, but this slot can run only web: web takes one unit, half a unit past its share.
    check(heddle.acquire(both, eligible = setOf(web), startedAt = 0L) is AcquireOutcome.Acquired)
    val line = heddle.shareLine(both)
    check(line.lag(web) == Rational.of(-1L, 2L))
    check(line.lag(mobile) == Rational.of(1L, 2L))
    // Docs has nothing queued and the pool has no siblings: neither stands anywhere.
    check(line.lag(docs) == null && line.lag(pool) == null)

    // explain reads the same distance: web's side stands half a unit past the front.
    val why = heddle.explain(web, waiting = both, eligible = both)
    check(why is WaitExplanation.BehindAtLevel && why.nextChild == mobile)
    check(checkNotNull(why.frontWide).toDouble() - checkNotNull(why.leafSideVirtualServiceWide).toDouble() == -0.5)

    // Docs was idle all along. Queued now, it joins at the front: owed nothing, owing nothing.
    check(heddle.shareLine(setOf(web, mobile, docs)).lag(docs) == Rational.ZERO)
}
