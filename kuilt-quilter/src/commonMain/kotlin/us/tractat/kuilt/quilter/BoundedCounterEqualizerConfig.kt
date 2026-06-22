package us.tractat.kuilt.quilter

import kotlin.random.Random
import kotlin.time.Duration

/**
 * Configuration for [BoundedCounterTransferCoordinator]'s proactive background equalizer.
 *
 * The equalizer is a periodic task that proactively redistributes surplus quota toward the
 * peer with the lowest quota. It fires when this replica's quota exceeds the fair share
 * (`bound / liveN`) by more than [minImbalanceThreshold] — avoiding idle-session noise when
 * quotas are already balanced.
 *
 * ## Optional = tuning
 *
 * Passing this config to the coordinator is **opt-in**: `equalizerConfig = null` (the default)
 * disables the equalizer and leaves the reactive targeted-borrow path fully correct on its own.
 * The equalizer only reduces how often reactive borrows fire under stable load.
 *
 * ## Determinism
 *
 * [random] is injected so tests can pass a seeded [Random] for reproducible tie-breaking
 * (choosing the lowest-quota peer when multiple peers are tied). Production may pass
 * [Random.Default].
 *
 * @param cadence how often the equalizer ticks. The background loop uses [kotlinx.coroutines.delay]
 *   on the injected scope's dispatcher — virtual-time-compatible when the scope runs on a
 *   test dispatcher.
 * @param minImbalanceThreshold the equalizer skips a tick when `quota(self) − fairShare`
 *   is at or below this value. Prevents unnecessary transfers in stable sessions where
 *   quotas are already near-even.
 * @param random injectable RNG for tie-breaking. Seed it in tests for determinism.
 */
public data class BoundedCounterEqualizerConfig(
    val cadence: Duration,
    val minImbalanceThreshold: Long = 0L,
    val random: Random = Random.Default,
)
