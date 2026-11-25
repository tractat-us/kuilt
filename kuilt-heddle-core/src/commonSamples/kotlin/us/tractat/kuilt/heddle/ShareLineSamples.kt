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
