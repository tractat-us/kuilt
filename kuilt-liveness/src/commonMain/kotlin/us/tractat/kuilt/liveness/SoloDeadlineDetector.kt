package us.tractat.kuilt.liveness

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Notices when a session nobody else ever joined has been waiting long enough to give up on.
 *
 * Think of a two-player table someone opens and then walks away from, or a lobby created by a
 * misfired tap. The connection is perfectly healthy — the person is simply alone in it. After
 * the deadline you probably want to tidy that session away rather than leave it open forever.
 *
 * Give it the smallest number of members the session needs, how long to wait, a clock and a
 * scope, then feed it the membership roster whenever it changes. It answers once, on [events]:
 *
 * - [SoloDeadlineEvent.Paired] — somebody arrived in time. The detector disarms for good.
 * - [SoloDeadlineEvent.NeverPaired] — the deadline passed and the session was still short.
 *
 * It only tells you; it never closes anything. Whether an abandoned session is closed, logged
 * or archived is your policy, not the library's.
 *
 * **This detects *never* paired, not *currently* solo.** The first roster at or above
 * [minimumMembers] disarms it permanently, so a session that fills up and later empties out
 * emits nothing more. Losing a peer that *was* present is
 * [HeartbeatPartitionDetector]'s job — the two are complementary, not alternatives.
 *
 * In kuilt terms it is a projection of a state the contract already documents:
 * [us.tractat.kuilt.core.SeamState]'s KDoc blesses `Woven` with `peers == {selfId}` as a fully
 * legitimate, well-defined state — the fabric is live and this peer is simply alone. This type
 * adds only the deadline on top of that state. It is deliberately **not** a [PartitionEvent]
 * variant: every [PartitionEvent] carries a non-null [PartitionEvent.peerId], and "nobody ever
 * came" has no peer to name.
 *
 * The detector arms on construction. It is safe to call [observeMembership] from any thread.
 *
 * @param minimumMembers the smallest membership that counts as paired, including this peer.
 *   `2` means "never paired with anyone". Must be at least `2`.
 * @param deadline how long to wait for that membership. Must be positive.
 * @param clock supplies the [SoloDeadlineEvent.at] timestamps; injected so tests can fix it —
 *   never `Clock.System` reached for directly.
 * @param scope the scope the deadline timer runs on. Required: a default real dispatcher would
 *   silently decouple the timer from a test's virtual clock.
 *
 * @sample us.tractat.kuilt.liveness.reapNeverPairedRoomSample
 */
public class SoloDeadlineDetector(
    private val minimumMembers: Int,
    private val deadline: Duration,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    init {
        require(minimumMembers >= MINIMUM_PAIRED_MEMBERS) {
            "minimumMembers must be at least $MINIMUM_PAIRED_MEMBERS (this peer plus one), was $minimumMembers"
        }
        require(deadline > Duration.ZERO) { "deadline must be positive, was $deadline" }
    }

    // replay = 1 so a collector that subscribes after the single verdict still sees it, and so
    // tryEmit from the non-suspending observeMembership can never fail.
    private val mutableEvents = MutableSharedFlow<SoloDeadlineEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The single verdict, replayed to late subscribers.
     *
     * At most one [SoloDeadlineEvent] is ever emitted; the flow then stays quiet forever.
     */
    public val events: SharedFlow<SoloDeadlineEvent> = mutableEvents.asSharedFlow()

    /** Latches on the first verdict so exactly one event escapes, whichever coroutine wins. */
    private val decided = atomic(false)

    /** Most recent membership size, for [SoloDeadlineEvent.NeverPaired.observed]. */
    private val lastObserved = atomic(0)

    private val deadlineJob: Job = scope.launch {
        delay(deadline)
        // No suspend call inside the decision — the CAS is the whole mutual exclusion.
        if (decided.compareAndSet(expect = false, update = true)) {
            mutableEvents.tryEmit(
                SoloDeadlineEvent.NeverPaired(
                    observed = lastObserved.value,
                    required = minimumMembers,
                    at = clock.now(),
                ),
            )
        }
    }

    /**
     * Feed the detector the current session membership, including this peer.
     *
     * Call it on every roster change — for example from a
     * `us.tractat.kuilt.core.Seam.peers` collector. Once the roster reaches
     * [minimumMembers] the detector emits [SoloDeadlineEvent.Paired] and disarms; further
     * calls are no-ops.
     */
    public fun observeMembership(members: Set<PeerId>) {
        lastObserved.value = members.size
        if (members.size < minimumMembers) return
        if (!decided.compareAndSet(expect = false, update = true)) return
        deadlineJob.cancel()
        mutableEvents.tryEmit(SoloDeadlineEvent.Paired(at = clock.now()))
    }

    private companion object {
        /** This peer plus at least one other — the smallest membership that can be "paired". */
        const val MINIMUM_PAIRED_MEMBERS = 2
    }
}
