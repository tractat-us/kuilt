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
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.RoomFrame
import us.tractat.kuilt.session.admit.AdmissionPolicy
import us.tractat.kuilt.session.admit.HostAdmission
import us.tractat.kuilt.session.partition.RoomId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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
 * Whether a harness can put passes into the Proof a device sends that its room would never put there
 * — and, when it cannot, **where that is written down** (#3710).
 *
 * The host must ignore a pass for another room, for another device's key, or from an issuer it does
 * not trust. A device's room filters all three out before it sends its Proof: it presents only its
 * host's passes for this room or for every room of that host's, and keeps only passes naming its own
 * key. So a row driven through [ReturnDevice] alone never shows the host one, and stays green with
 * the host's checks deleted. The planted-pass rows need the harness to forge one.
 *
 * **This is a forging tool in a published API**, and that is accepted for this purpose (ruled on
 * #3710): what it reaches is the host's own checks, which nothing else can. A harness over a real
 * fabric that has no way to rewrite a Proof declares [Unsupported] rather than building one.
 *
 * The same two-armed shape as [ReturnFixture] and [RoomConformanceSuite.FaultInjection], for the
 * same reason: declining has to name the issue that would have to close for the harness to say
 * [Supported], so a gap is declared rather than merely taken (#2306).
 */
public sealed interface PassPlanting {

    /** The harness can plant passes: the planted-pass rows run on [rig]. */
    public data class Supported(val rig: PlantedPassRig) : PassPlanting

    /**
     * The harness cannot rewrite a device's Proof, so the host's refusals of a pass for another room,
     * another device or an untrusted issuer are **unproven** on it.
     *
     * [trackingUrl] is not optional and not defaulted, for [RoomConformanceSuite.FaultInjection.Unsupported]'s
     * reason: point it at the harness's *own* blocking issue.
     */
    public data class Unsupported(val trackingUrl: String) : PassPlanting
}

/**
 * The forging half of a [ReturnHarness] (#3710): devices whose Proof carries passes a row chose, and
 * the passes to put there.
 *
 * A row asks for a fresh device, which has never held a pass, and has its one Proof carry a pass that
 * is good in every respect but the one the row changes. Every row asserts its rig fired: [newDevice]'s
 * planter was asked once, for a Proof the room itself put no pass in, and returned exactly one pass.
 * A control row plants a pass with nothing wrong with it and expects the device in without its policy
 * being asked, so a rig that drops what the planter returns reds there, not as a host refusing it.
 */
public interface PlantedPassRig {

    /**
     * A new device, as [ReturnHarness.newDevice] makes, except that every Proof its rooms send also
     * carries what [planter] returns for it, after the passes its room chose to present.
     *
     * The harness calls [planter] once per Proof, before the Proof leaves the device, and must not
     * call it for any other frame, for the host's room, or for any other device's.
     */
    public suspend fun newDevice(planter: PassPlanter): ReturnDevice

    /**
     * A pass the host's own key signs, letting [subject] back into [scope]: issued at the host's
     * now and good on the host's clock for at least an hour, so the only thing wrong with it is
     * what a row put there.
     *
     * The suite requires the host to accept any pass its key validly signed, so a host that also
     * keeps a list of the passes it issued, and refuses one not on it, fails the control row.
     */
    public suspend fun hostPass(subject: DidKey, scope: GrantScope): Grant

    /** As [hostPass], but signed by a key that is not the host's and that the host does not trust. */
    public suspend fun strangerPass(subject: DidKey, scope: GrantScope): Grant
}

/** What a [PlantedPassRig] device puts into each Proof it sends, beside its room's own passes. */
public fun interface PassPlanter {

    /**
     * The passes to append to the Proof a device is about to send.
     *
     * [statement] is the admission statement the device's room signed, which names the room and the
     * host's key; [sender] is the key of the device sending it; [presented] are the passes its room
     * put in the Proof itself, in wire form.
     */
    public suspend fun plant(statement: AdmissionProof, sender: DidKey, presented: List<ByteArray>): List<Grant>
}

/**
 * Whether a harness can start a process under a peer id an **earlier** process of its device had,
 * while a later one is running — and, when it cannot, **where that is written down** (#3710).
 *
 * The row it reaches: a device's process dies, a new one under a fresh id takes its held seat over,
 * and then a process comes up under the dead one's id and key. The host must answer its Hello with a
 * named `Reject(Superseded)` (Decided 32), and the process ends `Left(self, Superseded(by))` rather
 * than get an additional seat of the key. [ReturnDevice.launch] with `reusePeerId` can reuse only the
 * id of the process just killed, and [ReturnDevice.launchBeside] only a fresh one, so neither reaches
 * it.
 *
 * Two arms, as [PassPlanting] has, for the same reason.
 */
public sealed interface ReusedIdLaunch {

    /** The harness can launch under an earlier id: the superseded-id row runs on [rig]. */
    public data class Supported(val rig: ReusedIdRig) : ReusedIdLaunch

    /**
     * The harness's fabric gives each process an id of its own choosing, so the host's refusal of a
     * superseded id is **unproven** on it. [trackingUrl] as [PassPlanting.Unsupported]'s.
     */
    public data class Unsupported(val trackingUrl: String) : ReusedIdLaunch
}

/** The half of a [ReturnHarness] that makes [ReusedIdDevice]s (#3710). */
public fun interface ReusedIdRig {
    /** A new device, as [ReturnHarness.newDevice] makes, that can also launch under an id of the row's choosing. */
    public suspend fun newDevice(): ReusedIdDevice
}

/** A [ReturnDevice] that can start a process under a peer id the row names (#3710). */
public interface ReusedIdDevice : ReturnDevice {

    /**
     * Start a process on this device **beside** the one [launch] started, which keeps running, and
     * return its factory. Rooms it makes prove this device's key, over the device's one key store,
     * under [peerId]: the row names an id an earlier process of this device presented.
     *
     * Like [launchBeside], the process [launch] started stays the one every other hook acts on.
     */
    public suspend fun launchBesideAs(peerId: PeerId): RoomFactory
}

/**
 * Whether a harness can lose a pass renewal on a device's link after its host sent it — and, when it
 * cannot, **where that is written down** (#3745).
 *
 * The row it reaches: the host signs a renewal and sends it, the link takes it and loses it, and the
 * device goes on holding the pass before it. A host that lets the device back in only on a pass the
 * device presents then sends it to its admission policy while the pass the host signed last is still
 * good (Decided 34). A device's link through [ReturnDevice] alone either carries a frame or tears, so
 * no row driven through it reaches a renewal lost while the link stays up.
 *
 * Two arms, as [PassPlanting] has, for the same reason.
 */
public sealed interface RenewalLoss {

    /** The harness can lose renewals on a device's link: the lost-renewal rows run on [rig]. */
    public data class Supported(val rig: RenewalLossRig) : RenewalLoss

    /**
     * The harness cannot drop one kind of frame on a link that stays up, so the host's honouring of a
     * pass it signed that never arrived is **unproven** on it. [trackingUrl] as [PassPlanting.Unsupported]'s.
     */
    public data class Unsupported(val trackingUrl: String) : RenewalLoss
}

/** The half of a [ReturnHarness] that makes [RenewalLosingDevice]s (#3745). */
public fun interface RenewalLossRig {
    /** A new device, as [ReturnHarness.newDevice] makes, whose link can lose its host's pass renewals. */
    public suspend fun newDevice(): RenewalLosingDevice
}

/**
 * A [ReturnDevice] whose link can lose the pass renewals its host sends, and that counts what it lost
 * and what it carried (#3745).
 *
 * The two lists are what a row asserts its rig fired on: [lostRenewals] says the host signed a
 * renewal and the device never got it, and [deliveredPasses] says which passes the device could hold
 * at all. A row reads the newest delivered pass as the one the device holds, so a harness must count
 * every pass its links carried to the device, on a `Welcome` or a renewal, from every process.
 */
public interface RenewalLosingDevice : ReturnDevice {

    /**
     * From now on, the link of the process [launch] started loses every
     * [us.tractat.kuilt.session.admit.AdmitMessage.GrantRenewal] its host sends, after the host's send
     * returned, and carries every other frame as before. The host sees nothing go wrong. A process
     * launched later is not affected.
     */
    public fun loseRenewals()

    /** Every renewal a link of this device lost, as its host signed it, oldest first. */
    public val lostRenewals: List<Grant>

    /** Every pass a link of this device carried to it, on a `Welcome` or a renewal, oldest first. */
    public val deliveredPasses: List<Grant>
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
 *
 * That includes a device keeping the pass its host's `Welcome` carried: a room keeps it in work it
 * schedules after handling the `Welcome`, and a row that is about to kill the process waits for it by
 * running the test scheduler's pending work once the device sees its host. A harness whose rooms keep
 * the pass anywhere else — another dispatcher, a key store that waits on real I/O — loses it to the
 * kill. The relaunch then has nothing to present, and the pass rows red on the admission policy being
 * asked: `returnInsideGrantNoVetting`, the in-term arm of `expiredGrantIsVetted`, and the last arm of
 * `revokedSubjectIsVetted`.
 *
 * The rows read the host's clock only off the host's own roster (a partition's `since`), so the
 * harness's clock may start wherever it likes; it has to move with the test scheduler.
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
    /** Whether the harness can put a pass into a device's Proof that its room would never present: the planted-pass rows. */
    public val passPlanting: PassPlanting,
    /** Whether the harness can start a process under an id an earlier process of its device had: the superseded-id row. */
    public val reusedIdLaunch: ReusedIdLaunch,
    /** Whether the harness can lose a pass renewal on a link that stays up: the lost-renewal rows. */
    public val renewalLoss: RenewalLoss,
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
     * failure.
     *
     * Every tab works on the host device's **one** key store, as the tabs of a browser share its
     * storage: a pass the host hands one tab is kept where the next tab finds it (#3222). The row's
     * last tab comes back on that pass, so a harness that gives each tab a copy of the key and a store
     * of its own reds there, on the policy being asked.
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
     *
     * Every launch of a device works on the device's **one** key store, as an app's launches share its
     * platform store: a pass the host handed an earlier process is kept where the next one finds it
     * (#3223). The relaunch rows come back on that pass, so a harness that gives each launch a copy of
     * the key and a store of its own reds there, on the policy being asked.
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
 * The host's admission policy for a return row (#3220): it admits everyone, as
 * [AdmissionPolicy.open] does on a term of [readmitFor], and records the key of every newcomer
 * it is asked about, oldest first.
 *
 * The friction bar is also a count of vetting. A restore — the same seat back, under the same id or a
 * new one — never asks the policy, and nor does a return on a good pass once the seat is gone (#3222).
 * A release of a seat to a different key, a seat gone with no good pass, and an additional seat for a
 * second live process are new memberships, which it asks about once each (plan Amendments C, H and
 * J). The rows read [keys] before and after their return to say which happened.
 */
internal class VettingCount(readmitFor: Duration = 1.hours) {
    private val lock = reentrantLock()
    private val asked = mutableListOf<DidKey>()
    private val open = AdmissionPolicy.open(readmitFor)

    /** The host's revocations, which a row revokes a device in. Its own per row: no row shares another's. */
    val revocations: RevocationList = RevocationList.inMemory()

    /** What a row hosts its room with. */
    val admission: HostAdmission = HostAdmission(
        policy = AdmissionPolicy { request ->
            lock.withLock { asked += request.key }
            open.decide(request)
        },
        revocations = revocations,
    )

    /** The key of every newcomer the policy was asked about so far, oldest first. */
    fun keys(): List<DidKey> = lock.withLock { asked.toList() }
}

/**
 * The planted-pass rows' [PassPlanter] (#3710): it plants what [passes] returns, and keeps, per Proof
 * it was asked about, what it planted and how many passes the room presented itself. That is what a
 * row asserts its rig fired on: a planter never asked, asked twice, or asked about a Proof that
 * already carried a pass reds as the rig's failure, not the host's.
 */
internal class RecordingPlanter(private val passes: suspend (statement: AdmissionProof, sender: DidKey) -> List<Grant>) : PassPlanter {
    private val lock = reentrantLock()
    private val planted = mutableListOf<List<Grant>>()
    private val presented = mutableListOf<Int>()

    override suspend fun plant(statement: AdmissionProof, sender: DidKey, presented: List<ByteArray>): List<Grant> {
        val extra = passes(statement, sender)
        lock.withLock {
            planted += extra
            this.presented += presented.size
        }
        return extra
    }

    /** The passes planted in each Proof, oldest Proof first. */
    fun plantedPerProof(): List<List<Grant>> = lock.withLock { planted.toList() }

    /** How many passes the device's room put in each of those Proofs itself. */
    fun presentedPerProof(): List<Int> = lock.withLock { presented.toList() }
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
        override suspend fun host(pattern: Pattern, memberName: String?, roomId: RoomId?, admission: HostAdmission): Room {
            record("host")
            return CountedRoom(inner.host(pattern, memberName, roomId, admission))
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
