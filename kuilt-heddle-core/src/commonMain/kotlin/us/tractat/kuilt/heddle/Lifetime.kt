package us.tractat.kuilt.heddle

/**
 * The lifetime limit (design §7.1, #3475): the highest value a pick may carry a lifetime counter
 * to, `Long.MAX_VALUE` less [LocalHeddle.LIFETIME_HEADROOM].
 *
 * Two `Long`s a charge advances are bounded by nothing else, and both are lifetime counters: an
 * edge's **effective issuance** (`issued + issuedRelocIn`, summed over replicas,
 * [EntitlementLedger.effectiveIssuance]) and the root's **minted supply**, the sum of every
 * epoch's mint record ([EntitlementLedger.mintedSupply]). Everything else a charge moves is bounded
 * by one of them. A call that picks, [LocalHeddle.acquire] and [FairShareAllocator.schedule], moves
 * neither past this watermark ([pickFits]) and answers `LifetimeExhausted` instead. A call that
 * charges work already running, [LocalHeddle.admit], [LocalHeddle.extend] and
 * [LocalHeddle.complete], never refuses for it: it uses the headroom above the watermark, up to
 * `Long.MAX_VALUE` itself ([chargeFits]), and records a charge that still does not fit nowhere,
 * reporting it in [LeaseOutcome.Done.unrecorded]. So is an overrun above
 * [LocalHeddle.MAX_RECORDED_OVERRUN], whether it fits or not.
 */
internal const val LIFETIME_WATERMARK: Long = Long.MAX_VALUE - LocalHeddle.LIFETIME_HEADROOM

/**
 * Whether a pick may add [amount] to a lifetime counter standing at [counter]: whether the sum stays
 * at or below [LIFETIME_WATERMARK]. The sum is never formed, so neither side can overflow for any
 * non-negative [amount].
 */
internal fun pickFits(counter: Long, amount: Long): Boolean = counter <= LIFETIME_WATERMARK - amount

/**
 * Whether a charge of [amount] fits a lifetime counter standing at [counter]: whether the sum stays
 * at or below `Long.MAX_VALUE`. A charge uses the headroom a pick leaves, and nothing past it.
 */
internal fun chargeFits(counter: Long, amount: Long): Boolean = counter <= Long.MAX_VALUE - amount
