@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.RejectCode
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectController
import us.tractat.kuilt.session.partition.JoinerReconnectEvent
import us.tractat.kuilt.session.partition.ResumeResult
import us.tractat.kuilt.session.partition.ResumeSecret
import us.tractat.kuilt.session.partition.ResumeToken
import us.tractat.kuilt.session.partition.RoomId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * One member cannot resume as another (#3165).
 *
 * Every member learns every other member's id and the room id from the roster, so before the
 * resume secret any admitted member could write a valid [ResumeToken] for a partitioned victim.
 * The host accepted it and **spent the victim's window**: the victim's own resume then drew
 * `ResumeWindowExpired`, which is terminal, so it stopped retrying and had to re-join from scratch.
 *
 * Each test here asserts the two halves the fix promises, because either alone is vacuous:
 * the forgery is **refused**, *and* the victim's real resume **afterwards succeeds** — the second
 * is what proves the refusal left the window unspent. The window is opened by driving the host's
 * real [DefaultJoinerReconnectController] directly (the same seam `:kuilt-conformance`'s resume rig
 * uses), and its opening is asserted before any resume is attempted.
 */
class ForgedResumeTest {

    /** The host's real controller plus every verdict it rendered, in order. */
    private class ObservedController {
        var real: DefaultJoinerReconnectController? = null
        val verdicts = mutableListOf<ResumeResult.HostVerdict>()

        val factory: JoinerReconnectControllerFactory = { roomId, scope, clock ->
            val controller = DefaultJoinerReconnectController(
                roomId = roomId,
                clock = { clock().toEpochMilliseconds() },
                scope = scope,
            )
            real = controller
            object : JoinerReconnectController by controller {
                override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
                    controller.tryResume(token, at).also { verdicts += it }
            }
        }
    }

    private fun TestScope.factory(loom: Loom, observed: ObservedController? = null) =
        SeamRoomFactory(
            loom = loom,
            scope = backgroundScope,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            reconnectControllerFactory = observed?.factory,
        )

    /** Opens [victim]'s window on the host's real controller, and proves it opened. */
    private suspend fun TestScope.openWindowFor(victim: PeerId, observed: ObservedController) {
        val real = assertNotNull(observed.real, "the host must have built the observed controller")
        val opened = async {
            real.events.filterIsInstance<JoinerReconnectEvent.WindowOpened>().first { it.peerId == victim }
        }
        runCurrent()
        real.onPeerUnresponsive(victim, testScheduler.currentTime)
        runCurrent()
        assertEquals(victim, opened.await().peerId, "precondition: the victim's window must be open")
    }

    @Test
    fun `a member presenting another members token with a guessed secret is refused and the window survives`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val observed = ObservedController()
            val host = factory(loom, observed).host(Pattern("Host"), memberName = "Host")
            val victim = factory(loom).join(InMemoryTag("Victim"), memberName = "Victim")
            victim.roster.first { it.isNotEmpty() }
            val attacker = factory(loom).join(InMemoryTag("Attacker"), memberName = "Attacker")
            attacker.roster.first { it.size == 2 }
            host.roster.first { it.size == 2 }
            try {
                val victimToken = assertNotNull(victim.resumeToken, "the victim must hold its own token")
                openWindowFor(victim.selfId, observed)

                // Everything an attacker can learn from the roster, plus a secret it has to guess.
                val forged = ResumeToken(
                    peerId = victim.selfId,
                    roomId = assertNotNull(attacker.roomId.value, "every member learns the room id"),
                    issuedAt = 0L,
                    secret = ResumeSecret.generate(),
                )
                val forgedResult = attacker.resume(forged)
                val realResult = victim.resume(victimToken)

                assertAll(
                    {
                        assertEquals(
                            RejectCode.ResumeTokenInvalid,
                            assertIs<ResumeResult.Refused>(forgedResult, "the forgery must be refused").code,
                        )
                    },
                    {
                        assertEquals(
                            ResumeResult.Success,
                            realResult,
                            "the victim's own resume must succeed: the forgery must not have spent its window",
                        )
                    },
                    {
                        assertEquals(
                            listOf<ResumeResult.HostVerdict>(ResumeResult.Success),
                            observed.verdicts,
                            "the room must refuse the forgery before the controller is asked",
                        )
                    },
                )
            } finally {
                attacker.leave()
                victim.leave()
                host.leave()
            }
        }

    /**
     * A `Resume` frame with no secret at all — what a joiner built before the secret sends — is
     * refused before it reaches the controller, and so cannot touch the victim's window either.
     */
    @Test
    fun `a resume frame carrying no secret is refused and the window survives`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val observed = ObservedController()
            val host = factory(loom, observed).host(Pattern("Host"), memberName = "Host")
            val victim = factory(loom).join(InMemoryTag("Victim"), memberName = "Victim")
            victim.roster.first { it.isNotEmpty() }
            host.roster.first { it.size == 1 }
            val legacy = loom.join(InMemoryTag("Legacy"))
            try {
                val victimToken = assertNotNull(victim.resumeToken, "the victim must hold its own token")
                openWindowFor(victim.selfId, observed)

                val reply = async {
                    legacy.incoming
                        .mapNotNull { AdmitMessage.decode(it.toByteArray()) }
                        .filterIsInstance<AdmitMessage.Reject>()
                        .first()
                }
                runCurrent()
                legacy.sendTo(
                    host.selfId,
                    AdmitMessage.encode(
                        AdmitMessage.Resume(
                            tokenPeerId = victimToken.peerId.value,
                            tokenRoomId = victimToken.roomId.value,
                            issuedAt = victimToken.issuedAt,
                        ),
                    ),
                )
                val reject = reply.await()
                val realResult = victim.resume(victimToken)

                assertAll(
                    { assertEquals(RejectCode.ResumeTokenInvalid, reject.code, "a secretless resume is refused") },
                    { assertEquals(ResumeResult.Success, realResult, "and the victim's window is still unspent") },
                    {
                        assertEquals(
                            listOf<ResumeResult.HostVerdict>(ResumeResult.Success),
                            observed.verdicts,
                            "the secretless frame must never reach the controller",
                        )
                    },
                )
            } finally {
                legacy.close()
                victim.leave()
                host.leave()
            }
        }

    /**
     * No frame a bystander receives carries another member's secret, across admission and two
     * resume episodes — and so a bystander that sniffs everything still cannot forge (#3165).
     *
     * Two leaks this guards, each of which once existed or nearly did. `admitPeer` builds one
     * `Welcome` for both the joiner and the roster copy every other member gets, so the copy must be
     * stripped. And a joiner's `Resume` carries the secret too: it was sent as a **broadcast**, which
     * on a flat fabric such as this one reaches every peer, so one sniffed resume armed a forgery for
     * the life of the host. The tap records every decoded admit frame, the bystander then forges with
     * whatever victim secret it saw (or a guess), and the victim's second resume must still succeed.
     */
    @Test
    fun `a bystander sniffing every frame across two resume episodes cannot forge the victims resume`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val observed = ObservedController()
            val sniffed = mutableListOf<AdmitMessage>()
            val bystanderLoom = TappedLoom(loom) { swatch -> AdmitMessage.decode(swatch.toByteArray())?.let { sniffed += it } }
            val host = factory(loom, observed).host(Pattern("Host"), memberName = "Host")
            val bystander = factory(bystanderLoom).join(InMemoryTag("Bystander"), memberName = "Bystander")
            bystander.roster.first { it.isNotEmpty() }
            val victim = factory(loom).join(InMemoryTag("Victim"), memberName = "Victim")
            victim.roster.first { it.size == 2 }
            bystander.roster.first { it.size == 2 }
            host.roster.first { it.size == 2 }
            try {
                val victimToken = assertNotNull(victim.resumeToken, "the victim must hold its own token")

                // Episode 1: a genuine resume, sent while the bystander records everything.
                openWindowFor(victim.selfId, observed)
                val first = victim.resume(victimToken)

                // Episode 2: the bystander forges first, with any secret of the victim's it captured.
                openWindowFor(victim.selfId, observed)
                val captured = sniffed.mapNotNull { it.secretFor(victim.selfId) }
                val forged = victimToken.copy(secret = captured.firstOrNull() ?: ResumeSecret.generate())
                val forgedResult = bystander.resume(forged)
                val second = victim.resume(victimToken)

                val ownWelcome = sniffed.filterIsInstance<AdmitMessage.Welcome>()
                    .single { it.assignedPeerId == bystander.selfId.value }
                val rosterCopies = sniffed.filterIsInstance<AdmitMessage.Welcome>()
                    .filter { it.assignedPeerId == victim.selfId.value }
                assertAll(
                    // Positive control: the tap does see a secret when a frame carries one.
                    { assertNotNull(ownWelcome.resumeSecret, "the bystander's own Welcome carries its secret") },
                    { assertEquals(1, rosterCopies.size, "the rig must deliver the victim's roster copy") },
                    { assertEquals(ResumeResult.Success, first, "episode 1 must send a real resume") },
                    { assertEquals(emptyList(), captured, "no frame the bystander received may carry the victim's secret") },
                    {
                        assertEquals(
                            RejectCode.ResumeTokenInvalid,
                            assertIs<ResumeResult.Refused>(forgedResult, "the bystander's forgery must be refused").code,
                        )
                    },
                    { assertEquals(ResumeResult.Success, second, "the victim's episode-2 resume must still succeed") },
                )
            } finally {
                victim.leave()
                bystander.leave()
                host.leave()
            }
        }

    /** A member that leaves takes its resume secret with it; expiry is pinned in [TransportCloseWindowTest]. */
    @Test
    fun `a members secret is pruned when it leaves`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val host = factory(loom, ObservedController()).host(Pattern("Host"), memberName = "Host") as SeamRoom
            val leaver = factory(loom).join(InMemoryTag("Leaver"), memberName = "Leaver")
            host.roster.first { it.size == 1 }
            try {
                val held = host.heldResumeSecretCount()
                leaver.leave()
                host.roster.first { it.isEmpty() }
                assertAll(
                    { assertEquals(1, held, "precondition: the admitted member's secret is held") },
                    { assertEquals(0, host.heldResumeSecretCount(), "a member that leaves takes its secret with it") },
                )
            } finally {
                host.leave()
            }
        }

    /**
     * A token naming another room is refused by the room itself, so even a hold policy that skips
     * the room-id check never sees it (#3165 review). Before, the room let a wrong-room token past
     * its secret check on the strength of the controller's own room check, and a policy that did
     * not make one — this one rewrites the room id, the shape of "the room already checked it" —
     * granted the forgery and locked the victim out.
     */
    @Test
    fun `a wrong-room forgery is refused even by a hold policy that skips the room check`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            var real: DefaultJoinerReconnectController? = null
            val verdicts = mutableListOf<ResumeResult.HostVerdict>()
            val trusting: JoinerReconnectControllerFactory = { roomId, scope, clock ->
                val controller = DefaultJoinerReconnectController(
                    roomId = roomId,
                    clock = { clock().toEpochMilliseconds() },
                    scope = scope,
                )
                real = controller
                object : JoinerReconnectController by controller {
                    override suspend fun tryResume(token: ResumeToken, at: Long): ResumeResult.HostVerdict =
                        controller.tryResume(token.copy(roomId = roomId), at).also { verdicts += it }
                }
            }
            val host = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                reconnectControllerFactory = trusting,
            ).host(Pattern("Host"), memberName = "Host")
            val victim = factory(loom).join(InMemoryTag("Victim"), memberName = "Victim")
            victim.roster.first { it.isNotEmpty() }
            val attacker = factory(loom).join(InMemoryTag("Attacker"), memberName = "Attacker")
            attacker.roster.first { it.size == 2 }
            host.roster.first { it.size == 2 }
            try {
                val victimToken = assertNotNull(victim.resumeToken, "the victim must hold its own token")
                val controller = assertNotNull(real, "the host must have built the trusting policy")
                val opened = async {
                    controller.events.filterIsInstance<JoinerReconnectEvent.WindowOpened>().first()
                }
                runCurrent()
                controller.onPeerUnresponsive(victim.selfId, testScheduler.currentTime)
                runCurrent()
                val openedFor = opened.await().peerId

                val forged = ResumeToken(victim.selfId, RoomId("not-this-room"), 0L, ResumeSecret.generate())
                val forgedResult = attacker.resume(forged)
                val victimResult = victim.resume(victimToken)

                assertAll(
                    { assertEquals(victim.selfId, openedFor, "precondition: the victim's window must be open") },
                    {
                        assertEquals(
                            ResumeResult.Refused("resume-token-invalid: session-mismatch", RejectCode.ResumeTokenInvalid),
                            forgedResult,
                            "a wrong-room forgery must be refused by the room",
                        )
                    },
                    { assertEquals(ResumeResult.Success, victimResult, "the victim's window must be unspent") },
                    {
                        assertEquals(
                            listOf<ResumeResult.HostVerdict>(ResumeResult.Success),
                            verdicts,
                            "the wrong-room token must never reach the policy",
                        )
                    },
                )
            } finally {
                attacker.leave()
                victim.leave()
                host.leave()
            }
        }

    /**
     * A token for a member that has gone is answered by the room as an elapsed window, and never
     * reaches the controller — so a forgery cannot spend the window the departed member left open,
     * and the window still expires (#3165 review). A `Goodbye` removes the member while its window
     * may be open; before, a forgery with any secret then reached the controller, resumed the empty
     * seat and cancelled the expiry timer, so the `WindowExpired` — and on the `PeerLost`-first path
     * the only `Farewell(expired = true)` a star bystander hears — never came.
     */
    @Test
    fun `a forgery for a departed member is refused and its window still expires`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val observed = ObservedController()
            val host = factory(loom, observed).host(Pattern("Host"), memberName = "Host")
            val victim = factory(loom).join(InMemoryTag("Victim"), memberName = "Victim")
            victim.roster.first { it.isNotEmpty() }
            val attacker = factory(loom).join(InMemoryTag("Attacker"), memberName = "Attacker")
            attacker.roster.first { it.size == 2 }
            host.roster.first { it.size == 2 }
            try {
                val victimId = victim.selfId
                val roomId = assertNotNull(attacker.roomId.value, "every member learns the room id")
                val controller = assertNotNull(observed.real)
                val events = mutableListOf<JoinerReconnectEvent>()
                backgroundScope.launch { controller.events.collect { events += it } }
                openWindowFor(victimId, observed)

                victim.leave() // Goodbye: the host removes the member and its secret; the window stays open.
                host.roster.first { it.size == 1 }
                val forgedResult = attacker.resume(ResumeToken(victimId, roomId, 0L, ResumeSecret.generate()))
                advanceTimeBy(DefaultJoinerReconnectController.DEFAULT_RECONNECT_WINDOW_MS + 1_000)
                runCurrent()

                assertAll(
                    {
                        assertEquals(
                            RejectCode.ResumeWindowExpired,
                            assertIs<ResumeResult.Refused>(forgedResult, "the forgery must be refused").code,
                        )
                    },
                    { assertEquals(emptyList(), observed.verdicts, "a departed member's token must never reach the controller") },
                    {
                        assertTrue(
                            events.any { it is JoinerReconnectEvent.WindowExpired && it.peerId == victimId },
                            "the departed member's window must still expire: ${events.map { it::class.simpleName }}",
                        )
                    },
                    {
                        assertTrue(
                            events.none { it is JoinerReconnectEvent.Resumed },
                            "nobody may resume onto the departed member's seat",
                        )
                    },
                )
            } finally {
                attacker.leave()
                host.leave()
            }
        }

    /** The victim secret a frame carries, if any — what a sniffing bystander would harvest. */
    private fun AdmitMessage.secretFor(victim: PeerId): ResumeSecret? = when (this) {
        is AdmitMessage.Welcome -> resumeSecret.takeIf { assignedPeerId == victim.value }
        is AdmitMessage.Resume -> resumeSecret.takeIf { tokenPeerId == victim.value }
        else -> null
    }

    /** A [Loom] whose seams report every incoming frame to [tap] before the room sees it. */
    private class TappedLoom(private val inner: Loom, private val tap: (Swatch) -> Unit) : Loom {
        override fun capability() = inner.capability()

        override suspend fun weave(rendezvous: Rendezvous): Seam {
            val seam = inner.weave(rendezvous)
            return object : Seam by seam {
                override val incoming: Flow<Swatch> = seam.incoming.onEach(tap)
            }
        }
    }
}
