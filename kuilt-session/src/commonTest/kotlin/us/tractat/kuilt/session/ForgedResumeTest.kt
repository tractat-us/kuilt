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
import kotlin.test.assertNull
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
                        assertIs<ResumeResult.TokenInvalid>(
                            observed.verdicts.firstOrNull(),
                            "the host's first verdict must refuse the forgery, not spend the window",
                        )
                    },
                    {
                        assertEquals(
                            ResumeResult.Success,
                            realResult,
                            "the victim's own resume must succeed: the forgery must not have spent its window",
                        )
                    },
                    { assertEquals(2, observed.verdicts.size, "exactly the two resumes reached the controller") },
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
     * The secret rides only the direct `Welcome` to the member it names (#3165). `admitPeer` builds
     * one `Welcome` and sends it both to the joiner and, as the roster copy, to every other member,
     * so this pins that the copy a bystander receives carries none.
     */
    @Test
    fun `a bystander never receives another members resume secret`() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val seen = mutableListOf<AdmitMessage.Welcome>()
            val bystanderLoom = TappedLoom(loom) { swatch ->
                (AdmitMessage.decode(swatch.toByteArray()) as? AdmitMessage.Welcome)?.let { seen += it }
            }
            val host = factory(loom, ObservedController()).host(Pattern("Host"), memberName = "Host")
            val bystander = factory(bystanderLoom).join(InMemoryTag("Bystander"), memberName = "Bystander")
            bystander.roster.first { it.isNotEmpty() }
            val newcomer = factory(loom).join(InMemoryTag("Newcomer"), memberName = "Newcomer")
            newcomer.roster.first { it.size == 2 }
            bystander.roster.first { it.size == 2 }
            try {
                val newcomerToken = assertNotNull(newcomer.resumeToken, "the newcomer must hold its own token")
                val ownWelcome = seen.single { it.assignedPeerId == bystander.selfId.value }
                val rosterCopy = seen.filter { it.assignedPeerId == newcomer.selfId.value }

                assertAll(
                    // Positive control: the tap does see a secret when the frame carries one.
                    { assertNotNull(ownWelcome.resumeSecret, "the bystander's own Welcome carries its secret") },
                    { assertEquals(1, rosterCopy.size, "the rig must deliver the newcomer's roster copy") },
                    { assertNull(rosterCopy.single().resumeSecret, "the roster copy must carry no secret") },
                    {
                        assertTrue(
                            seen.none { it.resumeSecret == newcomerToken.secret },
                            "the newcomer's secret must reach no frame the bystander received",
                        )
                    },
                )
            } finally {
                newcomer.leave()
                bystander.leave()
                host.leave()
            }
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
