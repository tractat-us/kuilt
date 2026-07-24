package us.tractat.kuilt.heddle

import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a [HeddleNode] needs beyond the fabric: the reference policy's quanta and
 * caps, the §8.2 bound caps, the demand board's staleness window, and the injected
 * randomness/replication/liveness knobs.
 *
 * The house rule "time and randomness are dependencies" applies: [random] is injected
 * (never an unseeded default reached at runtime), and the wall clock is supplied to
 * [heddleStatic] separately as a `() -> Instant`.
 *
 * @property policy the EEVDF allocation caps ([PolicyConfig]): quantum, per-child
 *   outstanding cap, sleeper credit.
 * @property maxHoldingsPerPeer the §8.2 cap `E` — the most entitlement any one peer may
 *   hold at a parent and therefore independently steer among that parent's children.
 *   The coarse fairness-error bound is `n·E` for `n` peers (design §8.2). Must be `> 0`.
 * @property demandTtl how long a peer's advertised demand stays live without refresh
 *   (measured by **local receive time**, design §6). A crashed peer's demand ages out
 *   after this window and stops steering. Must be positive.
 * @property quilter the ledger-replication cadence ([QuilterConfig]); its
 *   `antiEntropyInterval` is the partition-heal knob.
 * @property heartbeat the liveness detector timing ([HeartbeatConfig]); `timeout` marks
 *   a peer unresponsive (partition), `reconnectWindow` marks it lost (crash).
 * @property random injected RNG for the replicators' anti-entropy peer selection —
 *   seed it in tests for reproducibility.
 */
public data class HeddleConfig(
    public val policy: PolicyConfig,
    public val maxHoldingsPerPeer: Long,
    public val demandTtl: Duration = 30.seconds,
    public val quilter: QuilterConfig = QuilterConfig(),
    public val heartbeat: HeartbeatConfig = HeartbeatConfig(),
    public val random: Random = Random.Default,
) {
    init {
        require(maxHoldingsPerPeer > 0L) { "maxHoldingsPerPeer must be positive, was $maxHoldingsPerPeer" }
        require(demandTtl.isPositive()) { "demandTtl must be positive, was $demandTtl" }
    }
}
