@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
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
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    /** The host holds a secret per current member and no longer: leaving or expiring prunes it. */
    @Test
    fun `a members secret is pruned when it leaves or its window expires`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val observed = ObservedController()
            val host = factory(loom, observed).host(Pattern("Host"), memberName = "Host") as SeamRoom
            val leaver = factory(loom).join(InMemoryTag("Leaver"), memberName = "Leaver")
            val expirer = factory(loom).join(InMemoryTag("Expirer"), memberName = "Expirer")
            host.roster.first { it.size == 2 }
            try {
                val held = host.heldResumeSecretCount()
                leaver.leave()
                host.roster.first { it.size == 1 }
                val afterLeave = host.heldResumeSecretCount()
                assertNotNull(observed.real).expire(expirer.selfId, testScheduler.currentTime)
                host.roster.first { it.isEmpty() }
                val afterExpiry = host.heldResumeSecretCount()
                assertAll(
                    { assertEquals(2, held, "precondition: one secret per admitted member") },
                    { assertEquals(1, afterLeave, "a member that leaves takes its secret with it") },
                    { assertEquals(0, afterExpiry, "a member whose window expires takes its secret with it") },
                )
            } finally {
                expirer.leave()
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
