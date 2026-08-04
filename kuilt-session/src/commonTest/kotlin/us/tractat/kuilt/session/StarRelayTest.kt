@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The star relay (#1994): a host forwards a spoke's frame to its co-spokes **and** delivers to
 * itself, and a spoke routes through the host once its roster outgrows what the transport can
 * address.
 *
 * ## Every negative is paired with a positive control in the same test
 *
 * An earlier revision's two security tests were bare `assertTrue(seen.none { … })` assertions and
 * were green *before any relay code existed* — an implementation that dropped every frame would
 * have passed them. So each refusal here shares a test with a frame that must arrive, and the
 * whole file is expected to be red before the feature and green after.
 */
class StarRelayTest {

    /**
     * A generous wedge backstop, **not** an assertion. It is wall-clock measured over a
     * virtual-time trajectory, so tightening it measures the host machine rather than this code
     * (#1739, #1891). Fast failure comes from the bounded awaits inside the harness and from
     * [membershipBudget], both of which are bounded in *virtual* time.
     */
    private val backstop = 30.seconds

    /**
     * Virtual time allowed for a membership announcement to cross the star in §T13.
     *
     * Well under an admit lane's own send budget (`reconnectWindow + timeout` = 10.6 s for
     * [relayHeartbeat]) and far under what a relay flood parked on that queue would cost
     * (64 × 10.6 s). Comfortably over what the announcement actually needs: one detector timeout
     * (600 ms) plus a fan-out hop.
     */
    private val membershipBudget = 2.seconds

    /**
     * Virtual time allowed for the healthy spoke's forward to land in §T15.
     *
     * Five relay budgets ([relayHeartbeat]'s 200 ms interval) — generous by construction, since with
     * a lane per recipient the forward lands at the instant it is enqueued. See §T15 for why this
     * number no longer discriminates the relay budget from the membership one.
     */
    private val relayHeadOfLineBudget = 1.seconds

    // ── The host is a recipient, not just a router (B1) ───────────────────────

    /**
     * §T1. The regression guard for B1, which survived an entire earlier test set because nothing
     * asserted the host's own receipt.
     *
     * `admittedById` never contains `selfId`, so a resolver consulting it alone drops `One(host)`
     * and fans `Everyone` *past* the host. And the relayed path **replaces** `seam.broadcast`,
     * which reaches the host today — so getting this wrong does not merely fail to add delivery to
     * the host, it removes it.
     */
    @Test
    fun `a joiner's broadcast reaches the host and every co-joiner`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("hello"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("hello"),
                        star.host.appFramesFrom(star.joinerAId),
                        "the HOST must receive a joiner's broadcast. Today an un-relayed " +
                            "seam.broadcast reaches it; the relay must not regress that",
                    )
                },
                {
                    assertEquals(
                        listOf("hello"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "and so must the co-joiner — the thing the relay adds",
                    )
                },
                {
                    assertTrue(
                        star.joinerA.appFramesFrom(star.joinerAId).isEmpty(),
                        "the origin must not receive its own broadcast back",
                    )
                },
            )
        }

    /**
     * §T2. B1 for the `One` case: a unicast addressed to the host must resolve to LOCAL delivery.
     *
     * The wire assertion is what makes this a test of the **resolver** rather than of the direct
     * path. A diverged joiner relays everything (I2), so this unicast must cross the wire wrapped;
     * without that assertion the test is green on a build with no relay at all, where `sendTo`
     * simply hands the frame to the host directly and `One(host)` is never resolved by anyone.
     */
    @Test
    fun `a joiner's unicast to the host reaches the host and no one else`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.sendTo(star.hostId, appPayload("to-host"))
            testScheduler.runCurrent()

            assertAll(
                {
                    val wire = star.wireFramesFrom(star.joinerAId)
                    assertTrue(
                        wire.isNotEmpty() && wire.all { RelayEnvelope.isRelayFrame(it) },
                        "a diverged joiner relays even to the host, so this must cross the wire " +
                            "wrapped — otherwise the resolver below is never exercised. " +
                            "observed ${wire.size} data frames",
                    )
                },
                {
                    assertEquals(
                        listOf("to-host"),
                        star.host.appFramesFrom(star.joinerAId),
                        "One(host) must resolve to LOCAL delivery — the host is not in its own " +
                            "admittedById, so a roster-only resolver drops this silently",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "and must not be widened to the co-joiner",
                    )
                },
            )
        }

    // ── The relay arm carries its own admission gate (B2) ─────────────────────

    /**
     * §T3. The relay arm fires **before** the `isAdmittedPeer(sender)` arm it precedes, so it
     * inherits none of that arm's gating and must carry its own. Every other application-data path
     * in `dispatchIncoming` is admit-gated; this must not be the first that is not.
     *
     * ## The host assertion is the one that pins the host's gate
     *
     * Asserting only that a *co-joiner* never sees the frame does **not** pin it, and a mutation
     * proves so: delete the host's admission check and this test stays green, because the joiner's
     * own fourth gate (origin must be an admitted member) discards the forward at the far end. Two
     * guards in series, and the downstream one masks the upstream one — the paired-guard hazard
     * this arm's ordering creates.
     *
     * What the host's gate uniquely owns is visible only *on the host*: without it the host
     * **accepts and locally delivers** an unadmitted peer's payload (it is a recipient as well as a
     * router), and lets that peer drive an N-recipient fan-out per frame whose cost is paid before
     * any recipient discards anything.
     */
    @Test
    fun `an unadmitted peer cannot drive a relay fan-out`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.sendRelayFromUnadmitted(RelayDest.Everyone, appPayload("unadmitted"))
            // Positive control from an ADMITTED peer, so total non-delivery cannot pass this.
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("honest"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("honest"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "an admitted peer's relay must work — the control",
                    )
                },
                {
                    assertTrue(
                        star.host.appFrames().none { it == "unadmitted" },
                        "the HOST must refuse it at the relay arm's own admission gate: the arm " +
                            "fires BEFORE the isAdmittedPeer guard, so it carries its own. This " +
                            "is the assertion that pins that gate — the co-joiner one below is " +
                            "satisfied by the joiner-side origin check even when this gate is gone",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFrames().none { it == "unadmitted" },
                        "and no co-joiner may be reached by it either",
                    )
                },
            )
        }

    // ── Routing: the leak boundary ────────────────────────────────────────────

    /** §T4. A unicast must reach its target and nobody else — including the host that forwarded it. */
    @Test
    fun `a unicast reaches only its target`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("for-b"))
            testScheduler.runCurrent()

            assertAll(
                // Positive control — without it, a relay that dropped everything passes the two
                // negatives below.
                {
                    assertEquals(
                        listOf("for-b"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the addressed peer must receive it",
                    )
                },
                {
                    assertTrue(
                        star.joinerC.appFramesFrom(star.joinerAId).isEmpty(),
                        "an unaddressed co-joiner must not observe a unicast — the leak boundary",
                    )
                },
                {
                    // Only meaningful now that §T1/§T2 prove the host receives relayed frames at
                    // all; before them this negative was vacuous.
                    assertTrue(
                        star.host.appFramesFrom(star.joinerAId).isEmpty(),
                        "the relaying host must not surface a unicast it merely forwarded",
                    )
                },
            )
        }

    /** §T5. The first-hop rule: at the room layer there is no trusted relayer, so origin must equal sender. */
    @Test
    fun `a spoke cannot forge another peer's origin`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // A speaks for itself — accepted.
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("honest"))
            // A claims to be C — must be refused outright, not re-attributed.
            star.joinerA.sendRelayForgingOrigin(star.joinerCId, RelayDest.Everyone, appPayload("forged"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("honest"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the honest frame must arrive — the positive control for the refusal below",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerCId).isEmpty(),
                        "a frame whose origin names another peer must be refused, not relayed",
                    )
                },
            )
        }

    // ── Relayability is an allow-list (C1) ────────────────────────────────────

    /**
     * §T6. A relayed `Welcome` naming the sender as host must change nothing on the recipient.
     *
     * Re-entering the full `dispatchIncoming` would route any `0x61` payload to `handleAdmitFrame`,
     * and `handleWelcome` is host-authoritative only *after* a host exists — so an admitted joiner
     * could capture a co-joiner's `hostPeerId` and then drive every host-authoritative gate on it.
     */
    @Test
    fun `a relayed admit frame changes nothing on the recipient`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            val hostBefore = star.joinerB.hostPeer()

            val forgedWelcome = AdmitMessage.encode(
                AdmitMessage.Welcome(
                    assignedPeerId = star.joinerAId.value,
                    displayName = "attacker",
                    sessionId = star.joinerAId.value,
                ),
            )
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), forgedWelcome)
            // …and one legitimate application frame, so total non-delivery cannot pass this test.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("legit"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("legit"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the application frame must still arrive — otherwise this test is vacuous",
                    )
                },
                {
                    assertEquals(
                        hostBefore,
                        star.joinerB.hostPeer(),
                        "a relayed Welcome must not capture the recipient's hostPeerId",
                    )
                },
                {
                    assertEquals(
                        star.hostId,
                        star.joinerB.hostPeer(),
                        "sanity: the recipient's host is still the real host",
                    )
                },
            )
        }

    /** §T7. A relay envelope nested inside a relay payload must be dropped, not unwrapped. */
    @Test
    fun `a nested relay envelope is not honoured`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            val inner = RelayEnvelope.encode(
                RelayEnvelope(star.joinerAId, RelayDest.Everyone, appPayload("nested")),
            )

            star.joinerA.sendRelay(RelayDest.Everyone, inner)
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("plain"))
            testScheduler.runCurrent()

            assertEquals(
                listOf("plain"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "a relay envelope nested inside a relay payload must be dropped, not unwrapped; " +
                    "the plain frame proves delivery works at all",
            )
        }

    /** §T8. An unresolvable destination is dropped — never widened into a fan-out. */
    @Test
    fun `an unresolvable destination is dropped and never fanned`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.joinerA.sendRelay(RelayDest.One(PeerId("ghost")), appPayload("nowhere"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("somewhere"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("somewhere"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the resolvable unicast must arrive",
                    )
                },
                {
                    assertTrue(
                        star.joinerC.appFramesFrom(star.joinerAId).isEmpty(),
                        "an unresolvable dest must be DROPPED, never widened into a fan-out",
                    )
                },
                {
                    // The co-spoke assertions above observe only that nobody *else* received it, and
                    // they hold just as well if the host faithfully forwarded to "ghost" and the
                    // send failed on its own. That is a different program: `Resolved.Exactly` for an
                    // unknown dest would let an admitted spoke push bytes at any peer the host's
                    // transport can address but its roster does not hold — a peer mid-handshake, or
                    // one already evicted. The membership boundary is `admittedById`, so the refusal
                    // has to be asserted where the decision is: no forward may leave the host at all.
                    assertTrue(
                        star.wireFramesTo(PeerId("ghost")).none { RelayEnvelope.isRelayFrame(it) },
                        "a dest outside the roster must be refused at the resolver, not forwarded " +
                            "and left to fail on the wire — otherwise a spoke can reach a " +
                            "transport-connected non-member through the host",
                    )
                },
            )
        }

    /**
     * §T8b. A relayed frame is never delivered back to the peer that originated it — on either
     * call shape.
     *
     * Two guards enforce this and neither had a test of its own. `Everyone` excludes the origin
     * from the fan-out set; `One(self)` resolves to [Resolved.None] rather than falling through to
     * the roster-membership arm, which would match (an origin *is* admitted) and forward the frame
     * straight back.
     *
     * Both mutants survived the guard set of Task 8 as written, and the echo they produce is not
     * cosmetic: `Room.incoming` would surface a frame credited to `selfId`, which a `Quilter` reads
     * as a delta from a replica it *is*, and every consumer that assumes `sender != selfId` on an
     * inbound frame is entitled to that by the direct path's behaviour.
     *
     * The `Everyone` half is currently caught only by an unrelated test's wire assertion in §T12,
     * whose message speaks about the host's direct path — a red there names the wrong cause. This
     * states the property where it belongs.
     */
    @Test
    fun `a relayed frame is never echoed back to its origin`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("plain"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerAId), appPayload("to-self"))
            testScheduler.runCurrent()

            assertAll(
                {
                    // Positive control: without it every negative below is green on a build whose
                    // relay drops everything.
                    assertEquals(
                        listOf("plain"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the co-spoke must still receive the broadcast — otherwise this test is " +
                            "vacuous",
                    )
                },
                {
                    assertTrue(
                        star.joinerA.appFrames().isEmpty(),
                        "neither call shape may come back to the origin; observed " +
                            "${star.joinerA.appFrames()}",
                    )
                },
                {
                    assertTrue(
                        star.wireFramesTo(star.joinerAId).none { RelayEnvelope.isRelayFrame(it) },
                        "the host must not forward either frame back to joiner-a at all — asserted " +
                            "on the wire so the refusal is pinned at the resolver rather than at " +
                            "the origin's own inbound gates",
                    )
                },
            )
        }

    /**
     * §T9. The property `RoomChannelSeam.incoming`'s admitted-sender filter and every `Quilter`'s
     * per-replica accounting depend on: a relayed frame is credited to its **origin**, never to the
     * host that carried it.
     */
    @Test
    fun `a relayed frame is credited to its origin rather than to the relaying host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("plain"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("plain"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the frame must be attributed to its ORIGIN",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.hostId).isEmpty(),
                        "and never to the relaying host — crediting the host would misattribute " +
                            "the whole relayed stream and defeat the admitted-sender filter",
                    )
                },
            )
        }

    // ── The joiner-side gates ─────────────────────────────────────────────────

    /** §T10. Only the identified host may relay to this member. */
    @Test
    fun `a joiner ignores a relay frame that did not come from its host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // A genuine host-relayed frame (control), and a co-joiner injecting a relay frame
            // directly onto B's link — the flat-loom / compromised-peer case.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("honest"))
            star.joinerC.injectRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerBId), appPayload("forged")),
            )
            testScheduler.runCurrent()

            assertEquals(
                listOf("honest"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "only the identified host may relay; a co-joiner's injected relay frame is refused",
            )
        }

    /** §T11. `dest` is re-checked at the far end, so a misrouting host cannot widen a unicast. */
    @Test
    fun `a joiner ignores a relayed unicast addressed to someone else`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // The far-end leak-boundary re-check: the HOST is made to misroute, and the joiner
            // refuses anyway. Paired with a One(self) that IS honoured.
            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerCId), appPayload("forged")),
            )
            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerBId), appPayload("legit")),
            )
            testScheduler.runCurrent()

            assertEquals(
                listOf("legit"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "dest is re-checked at the far end — a misrouting host cannot widen a unicast",
            )
        }

    /**
     * §T11b. The joiner's fourth gate: the **origin** must be an admitted member.
     *
     * `Room.incoming`'s documented contract is that frames from unadmitted peers never reach it,
     * and for a plain application frame this gate is the only thing upholding it on the relayed
     * path — `routeApplicationFrame` has no admitted-sender filter downstream (the channel views
     * do, which is why the gate reads as redundant until you follow the non-channel branch).
     */
    @Test
    fun `a joiner refuses a relayed frame whose origin is not an admitted member`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(PeerId("ghost"), RelayDest.One(star.joinerBId), appPayload("forged")),
            )
            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerBId), appPayload("legit")),
            )
            testScheduler.runCurrent()

            assertEquals(
                listOf("legit"),
                star.joinerB.appFrames(),
                "a relayed frame whose origin is in nobody's roster must be refused — otherwise " +
                    "Room.incoming surfaces a frame credited to a non-member, which its own " +
                    "contract forbids. The admitted origin proves delivery works at all",
            )
        }

    // ── The send rules ────────────────────────────────────────────────────────

    /**
     * §T12 (I3). A host does **not** always satisfy `rosterPeers ⊆ seam.peers`: a member inside its
     * reconnect window stays in the roster while the transport has dropped it (#1557/#1614). So the
     * direct-send rule keys on the **role**, explicitly, and not on that subset test.
     *
     * A build that dropped the role check does not merely misroute here — `hostPeerId` is null on a
     * host, so `relayHostOrNull` throws and the test fails on the exception rather than on an
     * assertion. Either way it is red, which is what matters.
     *
     * The joiner half is the positive control, and it is not optional: "the host emitted no relay
     * frame" is trivially true of a build that has no relay, so a same-star assertion that a
     * *joiner* does relay is what turns the negative into an observation about the **role** rather
     * than about the feature's absence.
     */
    @Test
    fun `a host sends directly even while a member is inside its reconnect window`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.partition(star.joinerBId) // in the roster, gone from seam.peers

            star.host.room.broadcast(appPayload("plain"))
            star.joinerA.room.broadcast(appPayload("honest"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("plain"),
                        star.joinerA.appFramesFrom(star.hostId),
                        "the healthy joiner still receives it",
                    )
                },
                {
                    assertTrue(
                        star.wireFramesTo(star.joinerAId).none { RelayEnvelope.isRelayFrame(it) },
                        "the host must take the DIRECT path — keyed on role, not on the subset " +
                            "test, which a host with a partitioned member fails",
                    )
                },
                {
                    // Positive control: the SAME star, a member whose roster diverges identically,
                    // and it does relay. Without this the negative above holds on a build with no
                    // relay code at all.
                    val wire = star.wireFramesFrom(star.joinerAId)
                    assertTrue(
                        wire.isNotEmpty() && wire.all { RelayEnvelope.isRelayFrame(it) },
                        "a JOINER with the same diverged roster must relay — the control that " +
                            "makes the host's direct path an observation about the role. " +
                            "observed ${wire.size} data frames",
                    )
                },
            )
        }

    /**
     * §T13 (I2). Once any divergence exists, **everything** relays — including a frame to a peer
     * that is directly reachable. Keying `broadcast` on the roster subset but `sendTo` on the
     * individual peer would give one destination two different hop counts on a partial mesh, and a
     * `Quilter`'s ack could then overtake the delta it acknowledges.
     */
    @Test
    fun `a joiner relays both broadcast and unicast once its roster diverges`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("plain"))
            star.joinerA.room.sendTo(star.joinerBId, appPayload("for-b"))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf("plain", "for-b"), star.joinerB.appFramesFrom(star.joinerAId)) },
                {
                    val wire = star.wireFramesFrom(star.joinerAId)
                    assertTrue(
                        wire.isNotEmpty() && wire.all { RelayEnvelope.isRelayFrame(it) },
                        "BOTH call shapes must take the relayed path — mixing hop counts to one " +
                            "destination lets a Quilter ack overtake the delta it acks. " +
                            "observed ${wire.size} data frames",
                    )
                },
            )
        }

    // ── Head-of-line: relay traffic has its own queue (B3 / C3) ───────────────

    /**
     * §T14. The C3 property asserted on the thing that actually matters: a wedged relay recipient
     * must not stall the **membership** queue. Routing relay traffic onto the admit lanes would — an
     * admit lane is `UNLIMITED` with a `reconnectWindow + timeout` send budget, so 64 relay frames
     * aimed at one black-holed spoke would park every `Paused`/`Unpaused`/`Farewell` for that spoke
     * behind them for minutes. That is the permanent roster divergence #1781 built the queue to
     * prevent, reachable by one slow peer.
     *
     * The relay-frames-on-the-wire assertion is not decoration: without it this test is green
     * *before the relay exists at all*, because a build with no relay has no relay traffic to
     * stall anything with.
     */
    @Test
    fun `a relay forward to a wedged spoke does not delay membership announcements`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))

            // Fill the relay queue toward a wedged recipient. RELAY_FLOOD exceeds the queue's
            // capacity, so DROP_OLDEST is exercised too.
            repeat(RELAY_FLOOD) {
                star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain"))
            }
            testScheduler.runCurrent()

            // Now raise a membership transition and require it to land promptly.
            star.partition(star.joinerCId)
            testScheduler.advanceTimeBy(membershipBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        star.joinerA.sawPartitioned(star.joinerCId),
                        "a Paused for C must reach A while relay traffic to a wedged B is " +
                            "backed up — this is why relay does not share the admit lanes",
                    )
                },
                {
                    // Positive control: relay traffic really was aimed at the wedged spoke. Without
                    // this the test is green on a build that has no relay at all.
                    assertTrue(
                        star.wireFramesTo(star.joinerBId).any { RelayEnvelope.isRelayFrame(it) },
                        "sanity: the host really did try to relay to the wedged spoke",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "sanity: B really is wedged",
                    )
                },
                {
                    // Without this the "DROP_OLDEST is exercised" claim above the flood is a
                    // comment, not a test: the overflow emits nothing observable of its own, so
                    // this test passed identically against a build whose capacity was 4096.
                    assertTrue(
                        star.host.relayForwardsDropped() > 0,
                        "the flood must actually overflow the bounded queue — $RELAY_FLOOD frames " +
                            "against a capacity of 64. observed " +
                            "${star.host.relayForwardsDropped()} drops",
                    )
                },
            )
        }

    /**
     * §T15. A forward to a healthy spoke is served while a wedged one is outstanding.
     *
     * **This no longer pins the relay send budget, and the KDoc used to say it did (#2048).** It was
     * written against a single shared writer, where a healthy spoke waited one budget behind each
     * wedged item — so allowing five relay budgets (200 ms each) and forbidding a membership-sized
     * one (10.6 s) discriminated the two. With a lane and writer per recipient the healthy spoke is
     * served at the instant its forward is enqueued **whatever** the budget is, so this passes on a
     * build that set `relaySendBudget` to `reconnectWindow + timeout`. What it still pins is that the
     * two recipients do not share a queue — which `FanOutHeadOfLineTest` now asserts more sharply,
     * with no virtual time allowed at all.
     *
     * Pinning the budget's *magnitude* after per-recipient keying needs a different shape: the budget
     * now bounds only how long a wedged recipient's **own** next frame waits, so a test would have to
     * heal the wedge and assert on a second frame to that same peer — which needs an `unwedge` the
     * harness does not have. Deliberately not added here; tracked in #2068.
     */
    @Test
    fun `a relay forward to a wedged spoke does not delay one to a healthy spoke`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))

            // Queued first, and it will never complete.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerCId), appPayload("legit"))
            testScheduler.advanceTimeBy(relayHeadOfLineBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("legit"),
                        star.joinerC.appFramesFrom(star.joinerAId),
                        "the healthy spoke must be served while the wedged one is outstanding — it " +
                            "has its own lane and does not queue behind another recipient",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "sanity: B really is wedged",
                    )
                },
            )
        }

    // ── Data is relayed; liveness is not (S2) ─────────────────────────────────

    /**
     * §T16. A relayed payload must **not** refresh the origin's liveness detector.
     *
     * `HeartbeatPartitionDetector` treats *any* inbound frame from a peer as proof that peer is
     * alive. So if a relayed payload were emitted onto the stream the per-peer detectors collect,
     * stamped with its origin, then A's relayed **data** would keep B's detector for A alive — and
     * a genuinely dead A↔B link would never mature into a partition. That is the exact inverse of
     * the carve-out, which is why relayed payloads go to a separate flow that only the channel
     * views collect.
     *
     * Runs on a **flat mesh**, not the star, and that is load-bearing: the masking needs the
     * subject to hold a *direct* edge to the origin, and on a star no spoke has a detector for a
     * co-spoke at all (#1576). On a star this property is vacuously true, so a star test of it
     * would pin nothing — which is precisely how it came to be unpinned.
     */
    @Test
    fun `a relayed payload does not keep the origin's liveness detector alive`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val mesh = meshTrio()
            assertTrue(
                mesh.subjectHasDetectorFor(mesh.originId),
                "precondition: on a mesh the subject holds a direct edge to the origin, so it " +
                    "runs a detector for it — without this the test observes nothing",
            )

            // The origin's REAL traffic stops: its detector must now starve.
            mesh.silenceIntoSubject(mesh.originId)

            // …while the host keeps relaying the origin's DATA at better than heartbeat cadence.
            // Channel frames specifically: that is the only branch that emits onto a Swatch stream,
            // and therefore the only one that could reach a detector at all.
            repeat(RELAY_KEEPALIVE_ROUNDS) {
                mesh.relayChannelFrameToSubject("plain")
                testScheduler.advanceTimeBy(relayHeartbeat.interval)
                testScheduler.runCurrent()
            }

            assertAll(
                {
                    assertTrue(
                        mesh.subject.sawPartitioned(mesh.originId),
                        "the origin's detector must still starve — relayed DATA is not liveness " +
                            "evidence. If this fails, relayed payloads are reaching the per-peer " +
                            "detectors and a dead direct edge is being masked by relayed traffic",
                    )
                },
                {
                    // Positive control: the relayed frames really did arrive on the surface that is
                    // supposed to see them. Without it, a build that dropped every relayed frame
                    // would pass the assertion above for the opposite reason. Note this control
                    // holds under the failure mode too — the channel view sees the frames either
                    // way — so the partition assertion above is the sole discriminator, by design.
                    assertTrue(
                        mesh.subjectChannelFrames().isNotEmpty(),
                        "sanity: the relayed channel frames were delivered to the channel view",
                    )
                },
            )
        }

    // ── The allow-list agrees with the dispatcher's classifier (C1) ───────────

    /**
     * §T17. A plain application payload that merely **claims** a reserved prefix byte, without
     * being a frame of that family, must survive the relay — because the direct path delivers it.
     *
     * `"keepalive"` leads with `0x6b`, which [RoomFramePrefix.Heartbeat] reserves. But the real
     * classifier `HeartbeatPartitionDetector.isHeartbeatFrame` is a **full-string** test, so
     * `dispatchIncoming` falls through to `routeApplicationFrame` and the host receives it on a
     * mesh and on the direct edge today. An allow-list folding the single-byte `matches` over the
     * registry disagreed, and the frame vanished on a star with a debug log and no error — the B1
     * failure class, invisible to every other test in this file because the harness deliberately
     * steers its payloads away from the reserved bytes.
     *
     * Asserted on **both** hops: the host is a recipient, not just a router, and the co-spoke is
     * the whole point of the relay.
     */
    @Test
    fun `a payload that only claims a reserved byte still reaches the host and a co-spoke`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("keepalive"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("keepalive"),
                        star.host.appFramesFrom(star.joinerAId),
                        "the HOST must receive it — this is the frame class the direct path " +
                            "delivers and the relay silently swallowed",
                    )
                },
                {
                    assertEquals(
                        listOf("keepalive"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "and so must the co-spoke",
                    )
                },
            )
        }

    /**
     * §T18. The same disagreement, one family over: [RoomFramePrefix.Channel] claims `0x63` but
     * `RoomChannel.isChannelFrame` additionally requires the 3-byte header.
     *
     * So a 1- or 2-byte payload leading with `0x63` is ordinary application data to
     * `dispatchIncoming` — and was refused by an allow-list keyed on the byte alone. Compared as
     * raw bytes rather than text: `decodeToString` would turn the second byte into a replacement
     * character and compare something other than what was sent.
     */
    @Test
    fun `a short payload leading with the channel byte still reaches the host and a co-spoke`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            val shortPayload = byteArrayOf(RoomFramePrefix.Channel.byte, 0x01)

            star.joinerA.room.broadcast(shortPayload)
            testScheduler.runCurrent()

            assertAll(
                {
                    val received = star.host.rawAppFramesFrom(star.joinerAId)
                    assertTrue(
                        received.size == 1 && received.single().contentEquals(shortPayload),
                        "the host must receive a 2-byte 0x63 payload — too short to be a channel " +
                            "frame, so the direct path routes it as application data. " +
                            "observed ${received.size} frames",
                    )
                },
                {
                    val received = star.joinerB.rawAppFramesFrom(star.joinerAId)
                    assertTrue(
                        received.size == 1 && received.single().contentEquals(shortPayload),
                        "and so must the co-spoke. observed ${received.size} frames",
                    )
                },
            )
        }

    /**
     * §T19. The security half of the same change: widening the allow-list to agree with the
     * dispatcher must not make a **genuine** reserved frame relayable.
     *
     * [RoomFramePrefix.Heartbeat]'s classifier narrowed from "leads with `0x6b`" to "is a heartbeat
     * frame", so this is the guard that the narrowing stopped exactly where it should. A relayed
     * heartbeat would be liveness evidence minted by a peer that is not the one being vouched for;
     * a relayed admit frame is the `Welcome`-capture of #1180 that the allow-list exists to block.
     */
    @Test
    fun `a genuine heartbeat or admit frame is still never relayed`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            val genuinePing = HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()
            val genuineWelcome = AdmitMessage.encode(
                AdmitMessage.Welcome(
                    assignedPeerId = star.joinerAId.value,
                    displayName = "attacker",
                    sessionId = star.joinerAId.value,
                ),
            )

            star.joinerA.sendRelay(RelayDest.Everyone, genuinePing)
            star.joinerA.sendRelay(RelayDest.Everyone, genuineWelcome)
            // The positive control, without which both negatives hold on a build that relays nothing.
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("plain"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("plain"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "only the plain frame may cross — a relayed heartbeat or admit frame must " +
                            "be refused, and the plain one proves the relay is working at all",
                    )
                },
                {
                    assertEquals(
                        listOf("plain"),
                        star.host.appFramesFrom(star.joinerAId),
                        "the same holds for the host's own local delivery",
                    )
                },
            )
        }

    // ── broadcast is lossy without error; sendTo reports (Room contract) ──────

    /**
     * §T20. A spoke whose host link has dropped must still be able to [Room.broadcast] without
     * throwing — the contract is lossy-without-error, and this is the window in which it bites.
     *
     * `runJoinerTornWatcher` responds to a tear by attempting reconnect and does **not** set
     * `hostLost`, so the roster keeps the host and the relay branch stays selected for the whole
     * reconnect window. Relaying through a peer `Seam.peers` no longer holds throws
     * [PeerNotConnected] — from a call that was a silent no-op before #1994, and that a `Quilter`
     * drives on a timer. Killing that coroutine would take out the anti-entropy that heals the very
     * gap the drop opened.
     */
    @Test
    fun `a spoke's broadcast is lossy without error once its host link drops`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("plain"))
            testScheduler.runCurrent()

            star.joinerA.wire.disconnect(star.hostId)
            // Must not throw. The assertion for that is the test completing.
            star.joinerA.room.broadcast(appPayload("nowhere"))
            testScheduler.runCurrent()

            assertEquals(
                listOf("plain"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "control: with the host link up the relay delivers, so the relay branch really " +
                    "was selected — and the frame broadcast after the link dropped is genuinely " +
                    "lost rather than queued, which is what lossy-without-error means",
            )
        }

    /**
     * §T21. The other half of the split: an **addressed** send does report an undeliverable hop.
     *
     * This pins the behaviour deliberately *not* changed alongside §T20, and the pair is the point
     * — `broadcast` and `sendTo` differ because their contracts differ. Swallowing an addressed
     * send would re-create #1994's own symptom, silent non-delivery, at the send side.
     *
     * The peer named is the **host**: that is the hop that failed, and this member has no direct
     * route to [RelayStar.joinerBId] by construction.
     */
    @Test
    fun `a spoke's unicast reports an undeliverable hop once its host link drops`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.sendTo(star.joinerBId, appPayload("for-b"))
            testScheduler.runCurrent()

            star.joinerA.wire.disconnect(star.hostId)
            val thrown = assertFailsWith<PeerNotConnected> {
                star.joinerA.room.sendTo(star.joinerBId, appPayload("nowhere"))
            }

            assertAll(
                {
                    assertEquals(
                        listOf("for-b"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "control: the same call relays and lands while the host link is up",
                    )
                },
                {
                    assertEquals(
                        star.hostId,
                        thrown.peer,
                        "the peer named is the failed hop — the host — not the addressed co-spoke",
                    )
                },
            )
        }

    private companion object {
        /**
         * Relay frames aimed at the wedged spoke in §T14. Exceeds the relay queue's capacity (64)
         * so the `DROP_OLDEST` overflow path is exercised rather than merely declared.
         */
        const val RELAY_FLOOD = 80

        /**
         * Relay injections in §T16, one per heartbeat interval. Enough that their span comfortably
         * exceeds `relayHeartbeat.timeout` (600 ms = 3 intervals), so a detector being kept alive
         * by them is unambiguous rather than a race with the deadline.
         */
        const val RELAY_KEEPALIVE_ROUNDS = 8
    }
}
