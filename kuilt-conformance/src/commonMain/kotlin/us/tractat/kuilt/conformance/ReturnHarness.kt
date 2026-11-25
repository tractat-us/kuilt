package us.tractat.kuilt.conformance

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Tag
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Duration

/**
 * Whether a harness can make a device drop off and come back — and, when it cannot, **where that is
 * written down**.
 *
 * The same two-armed shape as [RoomConformanceSuite.FaultInjection], for the same reason: declining
 * has to name the issue that would have to close for the harness to say [Supported], so a gap is
 * declared rather than merely taken (#2306).
 */
public sealed interface ReturnFixture {

    /** The harness can drop, heal, kill and rebuild a device's link: the return rows run on [harness]. */
    public data class Supported(val harness: ReturnHarness) : ReturnFixture

    /**
     * The harness cannot reach its devices' links, so the return rows — a held seat restored, a
     * process rebuilt, a different key refused, a readmission — are **unproven** on it.
     *
     * [trackingUrl] is not optional and not defaulted, for [RoomConformanceSuite.FaultInjection.Unsupported]'s
     * reason: point it at the harness's *own* blocking issue.
     */
    public data class Unsupported(val trackingUrl: String) : ReturnFixture
}

/**
 * The rig behind the return rows of [RoomConformanceSuite] (#3159, Task 1.9): one host, and as many
 * devices as a row asks for, each of which can lose its link and get it back.
 *
 * A device's return has to be the room's **own** work. Since `Room.resume` was retired (#3215), a
 * joiner whose link tears re-weaves through its factory's loom and says Hello again, and the host's
 * key → seat table decides. That loop runs only on a loom that heals the **same** seam in place: a
 * loom that hands back a new seam leaves the room torn, and it ends `HostLost`. So the harness owns
 * the fabric, and the rows own the room: nothing here gives a row a way to drive a return through the
 * room it is checking.
 *
 * [hostHeartbeat] and [joinerHeartbeat] are the configs the harness's rooms run, and they are not a
 * detail. A joiner's own reconnect window has to outlast the host's detection plus its hold, or a
 * joiner gives up before the host lets its seat go, and no readmission is reachable at all:
 * [RoomConformanceSuite] checks that before any row that needs it, and fails naming the numbers.
 *
 * Rooms run on the test scheduler, so the rows wait in **virtual** time. A harness whose fabric does
 * real I/O cannot use these rows as written.
 */
public class ReturnHarness(
    /** The host's device. The rows call [RoomFactory.host] on it exactly once. */
    public val hostFactory: RoomFactory,
    /** The session the host opens; [joinTag] must reach it. */
    public val hostPattern: Pattern,
    /** What a device's room joins to reach [hostPattern]'s session. */
    public val joinTag: Tag,
    /** The config the host's room runs: how fast it notices a silent joiner, and how long it holds a seat. */
    public val hostHeartbeat: HeartbeatConfig,
    /** The config every device's room runs: how long a joiner keeps trying to come back. */
    public val joinerHeartbeat: HeartbeatConfig,
    private val device: suspend () -> ReturnDevice,
    private val hostTab: suspend () -> RoomFactory,
) {
    /** A new device, with a key of its own and no process running yet. */
    public suspend fun newDevice(): ReturnDevice = device()

    /**
     * Start another process on the **host's** device — a second tab of the browser the host runs in —
     * and return its factory. Rooms it makes prove the host's own key, under a peer id of their own:
     * a fresh one on every call, never the host's.
     *
     * It is a joiner like any other, and there is nothing to drop or kill on it: the host-key row
     * needs only that the key is the host's. The row reads the key back off the host's roster and
     * off a joiner's view of the host, so a hook handing out some other key reds as the rig's own
     * failure. What it cannot see is *how* the key got there — a shared key store or a copy of one —
     * which the host cannot see either.
     */
    public suspend fun hostTab(): RoomFactory = hostTab.invoke()
}

/**
 * One device in a [ReturnHarness]: a key that outlives its processes, and the link of the process
 * running now.
 *
 * **What the rows prove with it, and what they cannot.** Every hook here acts on the fabric, never on
 * a room: none takes a [Room] or calls one. So a return the rows observe was driven by the room's own
 * reconnect, or by the one `join` a relaunched process makes; the rows count their own calls on the
 * handles they hold. They cannot see a hook that keeps a room of its own and drives the return through
 * it — a harness written to cheat. [heals] is the counterweight: it counts the re-weaves the device's
 * rooms made themselves, so a row can assert its drop was answered by the room's own reconnect.
 */
public interface ReturnDevice {

    /**
     * Start a process on this device and return its factory. Rooms the factory makes prove this
     * device's key.
     *
     * The first launch presents a peer id of the harness's choosing. A later launch follows a [kill]:
     * with [reusePeerId] it presents the id the killed process had, as a fabric with durable peer ids
     * does; without it, a fresh one, as most fabrics do.
     */
    public suspend fun launch(reusePeerId: Boolean = false): RoomFactory

    /**
     * Start a **second** process on this device while the one [launch] started is still running — a
     * second tab over the same key store — and return its factory. Rooms it makes prove this device's
     * key under a fresh peer id of their own, so the host meets a known key under a new id with every
     * seat of that key still connected (Decided 30).
     *
     * The process [launch] started is untouched, and stays the one every other hook acts on: [kill],
     * [drop], [restore], [impostor] and [heals] never reach a process launched beside it. Nothing here
     * ends it: its room leaves when the row calls `leave`, and the harness keeps its process and link
     * up until the test ends (the reference's live in the test's background scope). So a row can end
     * the first process's link and watch the second hold its seat. The hook cannot show that the two share a key *store* rather than two stores holding one
     * key; a row reads the key off the host's roster, which is all the host decides on.
     */
    public suspend fun launchBeside(): RoomFactory

    /**
     * End the running process without a goodbye, as an operating system kills an app: its rooms send
     * nothing more.
     *
     * With [socketCloses], its link closes as the system closes a dead process's socket, and the host
     * sees the seat go at once. Without it the link lingers, as a half-open connection does until
     * something times it out, so the host still holds the seat **connected**: a process launched at once
     * under the same peer id comes back faster than the host noticed it go.
     */
    public suspend fun kill(socketCloses: Boolean = true)

    /**
     * Tear the running process's link. Its room reads the tear and starts its own reconnect; every
     * re-weave it attempts fails until [restore].
     */
    public suspend fun drop()

    /**
     * Let this device's link come back: the room's next re-weave heals the **same** seam, under the
     * same peer id.
     *
     * After an [impostor] took the id from a live process, this also ends the hold: the link the
     * impostor displaced is torn, so the room reads a tear, re-weaves, says Hello, and takes the id
     * back — the room's own reconnect, as after a [drop].
     */
    public suspend fun restore()

    /**
     * A factory for a **different** device — a key of its own — whose rooms present this device's
     * peer id.
     *
     * If this device's process is still running, its room is a connected member when the impostor
     * arrives, and is cut off for as long as the impostor holds the id: it sends nothing and cannot
     * re-weave until [restore]. That is what makes the host meet the impostor's Hello with the member
     * still connected, so a refusal has to come from the key → seat table, not from a seat the host
     * already let go.
     *
     * After a [kill], the impostor is this device's own id under a key it has never held: a device
     * whose key store was cleared while its peer id persisted. The host then meets its Hello with the
     * seat partitioned.
     */
    public suspend fun impostor(): RoomFactory

    /** How many times this device's rooms healed their seam in place through their own re-weave. */
    public val heals: Int
}

/**
 * Every [MembershipEvent] a room emits after this is built, so a row can read what happened between
 * two points without racing a collector against the trigger.
 *
 * Started undispatched, so it is collecting before the constructor returns. A room's events replay a
 * recent tail to a new collector; that tail arrives inside the constructor and is left out, so
 * [events] begins where the tape did.
 */
internal class EventTape(room: Room, scope: CoroutineScope) {
    private val lock = reentrantLock()
    private val seen = mutableListOf<MembershipEvent>()
    private val origin: Int

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { room.events.collect { event -> lock.withLock { seen += event } } }
        origin = lock.withLock { seen.size }
    }

    /** What the room emitted since the tape started, oldest first. */
    fun events(): List<MembershipEvent> = lock.withLock { seen.drop(origin) }

    /** The membership edges among [events]: the ones a restore must not fake and a readmission must not skip. */
    fun edges(): List<MembershipEvent> = events().filter {
        it is MembershipEvent.Joined || it is MembershipEvent.Left || it is MembershipEvent.Resumed
    }
}

/**
 * Every call the rows make on a device's [RoomFactory] and on the [Room]s it returns (#3159).
 *
 * This catches only the row's own code: a call a later edit adds between a drop and a return. It is
 * not what proves a device came back by itself. That is the bounded wait for the return and the rig's
 * [ReturnDevice.heals], which a return driven by the app would not move.
 *
 * Reads are not calls: [Room.roster], [Room.events] and the other flows observe a room without asking
 * it to do anything. Every method that does something is counted, including the ones that cannot
 * start a return, so a row that later grows a call anywhere in its drop-to-return span is caught.
 */
internal class AppCalls {
    private val lock = reentrantLock()
    private val made = mutableListOf<String>()

    /** The calls made so far, oldest first. */
    fun snapshot(): List<String> = lock.withLock { made.toList() }

    private fun record(call: String) = lock.withLock { made += call }

    fun factory(inner: RoomFactory): RoomFactory = object : RoomFactory {
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?): Room {
            record("host")
            return CountedRoom(inner.host(pattern, memberName, roomId))
        }

        override suspend fun join(tag: Tag, memberName: String?, deviceId: String?): Room {
            record("join")
            return CountedRoom(inner.join(tag, memberName, deviceId))
        }
    }

    /**
     * Counts the [Room] methods that act. Delegation forwards everything else uncounted, so a method
     * that acts and is added to [Room] later must be overridden here too, or the counter misses it.
     */
    private inner class CountedRoom(private val inner: Room) : Room by inner {
        override fun incomingFrom(member: PeerId): Flow<RoomFrame> {
            record("incomingFrom")
            return inner.incomingFrom(member)
        }

        override suspend fun broadcast(bytes: ByteArray) {
            record("broadcast")
            inner.broadcast(bytes)
        }

        override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
            record("sendTo")
            inner.sendTo(peer, bytes)
        }

        override fun channel(id: String): Seam {
            record("channel")
            return inner.channel(id)
        }

        override suspend fun leave(reason: LeaveReason) {
            record("leave")
            inner.leave(reason)
        }

        override suspend fun flush(timeout: Duration): FlushOutcome {
            record("flush")
            return inner.flush(timeout)
        }
    }
}
