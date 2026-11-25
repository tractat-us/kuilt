@file:OptIn(us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class)

package us.tractat.kuilt.conformance

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.RoomFactory
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The reference return rig (#3159, Task 1.9): a [MuxServerLoom] the host rooms over, and for each
 * device a [MuxClientLoom] over in-memory connections, which is the resumable stack a relay client
 * uses. Everything runs on the test's own dispatcher and clock.
 *
 * A [MuxClientLoom] heals in place: a torn base is re-woven on the next `join`, and every channel
 * handle it gave out — the joiner room's seam — re-points onto the fresh base under the same peer id.
 * So [ReturnDevice.drop] closes the base and makes re-weaves fail until [ReturnDevice.restore], and
 * the room's own reconnect does the rest. [ReturnDevice.heals] counts the base weaves after a
 * process's first, which only the room's re-weave makes.
 *
 * The server keys connections by peer id and lets a newer connection take an id over, which is how
 * [ReturnDevice.impostor] reaches the host under a member's id. It does not close the connection it
 * displaced, so a live victim is muted for the impostor's stay — a frame it sent would re-register
 * its own connection for the id and steal the impostor's answer — and [ReturnDevice.restore] then
 * closes that displaced connection, as a server that deduplicates links would. The victim's room
 * reads the tear and comes back through its own re-weave, which takes the id back from the impostor.
 *
 * [ReturnDevice.launchBeside] and [ReturnHarness.hostTab] each start a process of their own over
 * a key store index already in use — the device's, or the host's 0 — so their rooms prove that key
 * under an id of their own while the other process stays live.
 *
 * The host runs a short hold ([REFERENCE_HOST_HEARTBEAT]) and a device a long reconnect window
 * ([REFERENCE_JOINER_HEARTBEAT]), so a joiner is still trying when the host lets its seat go.
 *
 * With [planting], every device's Proof also carries the passes it plants (`PlantedPassRefusalTest`);
 * the host's own room, its tabs and the impostors are left alone.
 */
internal fun TestScope.referenceReturnHarness(planting: PassPlanting? = null): ReturnHarness {
    val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) { "runTest supplies a dispatcher" }
    val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    val source = InMemoryConnectionSource()
    val server = MuxServerLoom(
        source = source,
        scope = backgroundScope,
        selfId = PeerId("host"),
        authorizer = RoomAuthorizer.AllowAll,
        dispatcher = dispatcher,
        random = Random(13),
    )
    val seeds = atomic(1)
    val devices = atomic(0)
    val hostTabs = atomic(0)

    // One process: its own scope, its own client loom and its own link, under one peer id, over the
    // key store of the device it runs on.
    class Process(val peerId: PeerId, store: DeviceKeyStore, planting: PassPlanting? = null) {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        // Atomics, not plain vars: the rig is driven from the test body and read from the rooms'
        // own coroutines, and nothing here may lean on the test dispatcher being single-threaded.
        val down = atomic(false)
        val muted = atomic(false)
        val weaves = atomic(0)
        /** An impostor took this process's id while it was live; [ReturnDevice.restore] tears the link it displaced. */
        val displaced = atomic(false)
        private val base = object : Loom {
            override suspend fun weave(rendezvous: Rendezvous): Seam {
                check(!down.value) { "the reference return rig holds ${peerId.value}'s link down" }
                val (serverEnd, clientEnd) = connectionPair()
                source.offer(serverEnd)
                val mesh = hubMesh(peerId, listOf(clientEnd), dispatcher, Random(seeds.getAndIncrement()))
                weaves.incrementAndGet()
                return object : Seam by mesh {
                    override suspend fun broadcast(payload: ByteArray) {
                        if (!muted.value) mesh.broadcast(payload)
                    }

                    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                        if (!muted.value) mesh.sendTo(peer, payload)
                    }
                }
            }
        }
        val mux = MuxClientLoom(base, Rendezvous.New(Pattern("base")), scope, ::sessionNameOf)
        val factory: RoomFactory = SeamRoomFactory(
            loom = planting?.over(mux, store) ?: mux,
            scope = scope,
            clock = clock,
            deviceKeyStore = store,
            heartbeatConfig = REFERENCE_JOINER_HEARTBEAT,
        )

        /** The base weaves after this process's first: each one is a heal its room asked for. */
        fun heals(): Int = (weaves.value - 1).coerceAtLeast(0)

        suspend fun end(socketCloses: Boolean) {
            scope.cancel()
            // The socket a killed app leaves behind is closed by the system, not by the app. Left open,
            // the server keeps the connection, so the host goes on seeing the seat connected.
            if (socketCloses) mux.closeBase(CloseReason.Normal)
        }
    }

    fun newDevice(): ReturnDevice {
        val index = devices.incrementAndGet()
        // The device's one store, for every process it ever runs: a pass one launch keeps is the next
        // launch's to present, as a platform store keeps it across launches (#3223).
        val store = fakeKeyStore(index)
        val launches = atomic(0)
        val besides = atomic(0)
        val current = atomic<Process?>(null)
        val priorHeals = atomic(0)
        return object : ReturnDevice {
            override suspend fun launch(reusePeerId: Boolean): RoomFactory {
                val previous = current.value
                check(previous == null || !previous.scope.coroutineContext.job.isActive) {
                    "kill() the running process before launching another"
                }
                val launch = launches.incrementAndGet()
                val id = when {
                    reusePeerId -> checkNotNull(previous) { "no earlier process to take a peer id from" }.peerId
                    launch == 1 -> PeerId("device-$index")
                    else -> PeerId("device-$index.$launch")
                }
                previous?.let { priorHeals.addAndGet(it.heals()) }
                return Process(id, store, planting).also { current.value = it }.factory
            }

            override suspend fun launchBeside(): RoomFactory {
                val running = checkNotNull(current.value) { "launch() a process before launching another beside it" }
                check(running.scope.coroutineContext.job.isActive) { "the process launch() started has ended; launch() again" }
                // Its own link and peer id, on this device's key store; never `current`, so the
                // other hooks keep acting on the first process.
                return Process(PeerId("device-$index.beside-${besides.incrementAndGet()}"), store, planting).factory
            }

            override suspend fun kill(socketCloses: Boolean) {
                checkNotNull(current.value) { "nothing launched to kill" }.end(socketCloses)
            }

            override suspend fun drop() {
                val process = checkNotNull(current.value) { "nothing launched to drop" }
                process.down.value = true
                process.mux.closeBase()
            }

            override suspend fun restore() {
                val process = checkNotNull(current.value) { "nothing launched to restore" }
                process.down.value = false
                process.muted.value = false
                // The link an impostor displaced is still open on this side and routes nothing: close it,
                // so the room tears and re-weaves, and the fresh connection takes the id back.
                if (process.displaced.getAndSet(false)) process.mux.closeBase()
            }

            override suspend fun impostor(): RoomFactory {
                val victim = checkNotNull(current.value) { "no process whose id an impostor could present" }
                if (victim.scope.coroutineContext.job.isActive) {
                    victim.muted.value = true
                    victim.down.value = true
                    victim.displaced.value = true
                }
                // A different device — a key of its own — under the victim's peer id.
                return Process(victim.peerId, fakeKeyStore(IMPOSTOR_KEY_OFFSET + index)).factory
            }

            override val heals: Int
                get() = priorHeals.value + (current.value?.heals() ?: 0)
        }
    }

    // The host's device's one store: the host's own room and every host tab share it, as the tabs of
    // one browser share its IndexedDB, so a pass one tab keeps is the next tab's to present (#3222).
    val hostStore = fakeKeyStore(0)

    return ReturnHarness(
        hostFactory = SeamRoomFactory(
            loom = server,
            scope = backgroundScope,
            clock = clock,
            deviceKeyStore = hostStore,
            heartbeatConfig = REFERENCE_HOST_HEARTBEAT,
        ),
        hostPattern = Pattern(REFERENCE_SESSION),
        joinTag = InMemoryTag(REFERENCE_SESSION),
        hostHeartbeat = REFERENCE_HOST_HEARTBEAT,
        joinerHeartbeat = REFERENCE_JOINER_HEARTBEAT,
        device = { newDevice() },
        // The host's own key store, under a joiner's link and an id of its own.
        hostTab = { Process(PeerId("host-tab-${hostTabs.incrementAndGet()}"), hostStore).factory },
    )
}

private fun sessionNameOf(rendezvous: Rendezvous): String = when (rendezvous) {
    is Rendezvous.New -> rendezvous.pattern.sessionName
    is Rendezvous.Existing -> rendezvous.tag.sessionName
}

private const val REFERENCE_SESSION = "return-rig"

/** Keys for impostors, clear of every device the rig will make. */
private const val IMPOSTOR_KEY_OFFSET = 1_000

/** The host notices a silent joiner after 200 ms and holds its seat for 300 ms more. */
internal val REFERENCE_HOST_HEARTBEAT = HeartbeatConfig(
    interval = 100.milliseconds,
    timeout = 200.milliseconds,
    reconnectWindow = 300.milliseconds,
)

/** A joiner keeps trying for a second: past the host's detection plus its hold, with room to spare. */
internal val REFERENCE_JOINER_HEARTBEAT = HeartbeatConfig(
    interval = 100.milliseconds,
    timeout = 200.milliseconds,
    reconnectWindow = 1_000.milliseconds,
)

/**
 * Passes put into a device's Proof beside the ones its room chose to present (#3223): the hook the
 * reference rig alone has, for the refusals a real joiner cannot reach. A joiner presents only its
 * host's passes for this room or for every room of that host's, and keeps only passes naming its own
 * key, so a pass for another room, for another device, or from another host never leaves a real
 * device. This does it on the wire instead: it rewrites each Proof the device sends, keeping the
 * signed statement the room made and appending what [passes] returns for it.
 *
 * Deliberately not on [ReturnHarness] or [RoomConformanceSuite]: a published hook that puts arbitrary
 * passes into a Proof is a forging tool, which a harness over a real fabric has no way to offer.
 *
 * [passes] gets the statement the joiner signed, which names the room and the host's key, and the key
 * of the device sending it. Every Proof it rewrote is counted, and every pass it planted kept, so a
 * row can assert its pass really was presented rather than pass on a Proof that never carried it.
 */
internal class PassPlanting(private val passes: suspend (proof: AdmissionProof, sender: DidKey) -> List<Grant>) {
    private val lock = reentrantLock()
    private val planted = mutableListOf<List<Grant>>()

    /** The passes planted in each Proof the device sent, oldest Proof first. */
    fun plantedPerProof(): List<List<Grant>> = lock.withLock { planted.toList() }

    /** [loom] with every Proof its seams send rewritten, for a device whose key is in [store]. */
    fun over(loom: Loom, store: DeviceKeyStore): Loom = object : Loom by loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = loom.weave(rendezvous)
            return object : Seam by seam {
                override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
                    seam.sendTo(peer, rewrite(payload, store))
                }
            }
        }
    }

    private suspend fun rewrite(payload: ByteArray, store: DeviceKeyStore): ByteArray {
        val proof = AdmitMessage.decode(payload) as? AdmitMessage.Proof ?: return payload
        val statement = checkNotNull(AdmissionProof.decode(proof.statement)) { "the room sent a Proof whose statement does not decode" }
        val extra = passes(statement, store.deviceKey().id)
        lock.withLock { planted += extra }
        return AdmitMessage.encode(AdmitMessage.Proof(proof.statement, proof.grants + extra.map { it.wire }))
    }
}
