package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.fail

/**
 * #1725: a WebSocket [Seam]'s [Seam.capability] follows the device-reachability observer injected
 * into its loom, instead of sitting on the roleless [FabricAvailability.Unknown] floor forever.
 *
 * ## What this proves, and what nothing in CI can
 * These cases prove the **seam reacts** — that a reading published by a [ConnectivityObserver]
 * reaches `capability` on a live, real-socket seam on both the client and the server side. That
 * the *platform* emits such readings — that `ConnectivityManager.NetworkCallback` fires on
 * Android, that `online`/`offline` fire in a browser — is not provable from here: a fake-injected
 * signal demonstrates the consumer's reaction, never the transport's emission. Those halves are
 * device-only, and no static analysis reads either observer — the repo has no linter (#2540).
 *
 * ## Why the fake moves rather than sits
 * Every case here asserts the value **changes** across at least two readings. Asserting only
 * "reports Available" against a fake pinned to [NetworkReachability.Reachable] would pass against
 * a seam that ignored the observer entirely, if the floor happened to be Available — the vacuity
 * this whole lane exists to remove. [unobservedByDefaultReportsTheUnknownFloor] pins the other end:
 * the default binding stays honest, which is what lets `KtorClientLoom` be used unwired on the
 * desktop JVM without fabricating a verdict.
 */
class WebSocketSeamCapabilityTest {

    private val serverPath = "/ws/capability-test"

    // ── the observer moves a live seam's capability ──────────────────────────

    @Test
    fun `client seam capability tracks the injected observer across every reading`() =
        testApplication {
            val observer = FakeConnectivityObserver()
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom =
                KtorClientLoom(createClient { install(WebSockets) }, connectivity = observer)

            val (_, clientSeam) = connectPair(serverLoom, clientLoom)

            // Woven, nothing observed yet: the floor, not a verdict inherited from "a socket opened".
            val beforeAnyReading = clientSeam.capability.value.availability

            observer.emit(NetworkReachability.Reachable)
            val reachable: FabricAvailability = clientSeam.awaitAvailability<FabricAvailability.Available>()

            observer.emit(NetworkReachability.Unreachable)
            val unreachable: FabricAvailability = clientSeam.awaitAvailability<FabricAvailability.Unavailable>()

            observer.emit(NetworkReachability.Indeterminate)
            val indeterminate: FabricAvailability = clientSeam.awaitAvailability<FabricAvailability.Unknown>()

            assertAll(
                {
                    assertIs<FabricAvailability.Unknown>(
                        beforeAnyReading,
                        "a woven seam with no reading yet must not claim the path is up",
                    )
                },
                { assertEquals(FabricAvailability.Available, reachable, "Reachable ⇒ Available") },
                {
                    assertNotEquals(
                        beforeAnyReading,
                        indeterminate,
                        "the observer's shrug is a different fact from no observer, and the seam " +
                            "must carry the difference through rather than flatten it",
                    )
                },
                {
                    // The rig is non-vacuous: it visited three DISTINCT verdicts, so a later edit
                    // that pins the fold to one value fails here rather than passing quietly.
                    assertEquals(
                        3,
                        setOf(reachable, unreachable, indeterminate).size,
                        "three readings must produce three distinct availabilities",
                    )
                },
            )

            clientSeam.close(CloseReason.Normal)
        }

    @Test
    fun `server seam capability tracks the injected observer too`() =
        testApplication {
            val observer = FakeConnectivityObserver(NetworkReachability.Reachable)
            val serverLoom = KtorServerLoom(application, serverPath, connectivity = observer)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverSeam, clientSeam) = connectPair(serverLoom, clientLoom)

            val reachable: FabricAvailability = serverSeam.awaitAvailability<FabricAvailability.Available>()
            observer.emit(NetworkReachability.Unreachable)
            val unreachable: FabricAvailability = serverSeam.awaitAvailability<FabricAvailability.Unavailable>()

            assertAll(
                { assertEquals(FabricAvailability.Available, reachable, "Reachable ⇒ Available") },
                {
                    assertNotEquals(
                        reachable,
                        unreachable,
                        "the host's own reachability moving must move its seam's capability",
                    )
                },
                {
                    // The server seam is wrapped by `withPrincipal` on the accept path; this pins
                    // that the capability override survives that second layer of delegation.
                    assertEquals(
                        RELAY_ROLES,
                        serverSeam.capability.value.roles,
                        "the live view keeps the fabric's static roles through every wrapper",
                    )
                },
            )

            clientSeam.close(CloseReason.Normal)
        }

    // ── the honest default, and the roles half ───────────────────────────────

    /**
     * The unwired default is the desktop JVM's production configuration, so this is the case that
     * keeps #1712's floor intact for it. It is also what stops `reportsLiveCapability = true` on
     * `WebSocketConformanceTest` from being read as "the JVM has an observer" — it does not.
     */
    @Test
    fun unobservedByDefaultReportsTheUnknownFloor() =
        testApplication {
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            val (serverSeam, clientSeam) = connectPair(serverLoom, clientLoom)

            assertAll(
                {
                    assertIs<FabricAvailability.Unknown>(
                        clientSeam.capability.value.availability,
                        "an unwired client loom must report the floor, not a fabricated Available",
                    )
                },
                {
                    assertIs<FabricAvailability.Unknown>(
                        serverSeam.capability.value.availability,
                        "an unwired server loom must report the floor too",
                    )
                },
            )

            clientSeam.close(CloseReason.Normal)
        }

    /**
     * Roles are **static** on this fabric and do not narrow with reachability — the deliberate
     * difference from `:kuilt-nearby`, whose medium roles come and go with its radios. A relay
     * fabric on a dead radio is still a relay fabric; that it cannot be used right now is the
     * availability half's job to say (#1712).
     */
    @Test
    fun `roles stay the fabric's static set whatever the device reports`() =
        testApplication {
            val observer = FakeConnectivityObserver(NetworkReachability.Reachable)
            val serverLoom = KtorServerLoom(application, serverPath)
            val clientLoom =
                KtorClientLoom(createClient { install(WebSockets) }, connectivity = observer)

            val (_, clientSeam) = connectPair(serverLoom, clientLoom)

            clientSeam.awaitAvailability<FabricAvailability.Available>()
            val whenReachable = clientSeam.capability.value.roles

            observer.emit(NetworkReachability.Unreachable)
            clientSeam.awaitAvailability<FabricAvailability.Unavailable>()
            val whenUnreachable = clientSeam.capability.value.roles

            assertAll(
                {
                    assertEquals(
                        setOf(TransportRole.ServerRelay, TransportRole.Data),
                        whenReachable,
                        "the live view carries the relay fabric's roles, not the roleless floor",
                    )
                },
                {
                    assertEquals(
                        whenReachable,
                        whenUnreachable,
                        "going offline does not change WHAT this fabric is, only whether it is usable",
                    )
                },
            )

            clientSeam.close(CloseReason.Normal)
        }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Await the capability reaching an availability of type [A] and return it.
     *
     * A real wall-clock bound, not a virtual one: these seams run on real sockets and real
     * dispatchers, so there is no virtual clock to fast-forward. It is a **wedge backstop** — the
     * awaited transition is published synchronously by a `MutableStateFlow` write, so a healthy run
     * never approaches it.
     *
     * The expiry is converted to a named assertion rather than left as a bare
     * `TimeoutCancellationException`, because the two failures a reader must tell apart look
     * identical otherwise: *the seam is not wired to its observer at all* and *the seam is wired
     * but folded this reading wrongly*. Printing what it waited for alongside what it actually
     * holds decides that from the failure line, with nothing to re-run.
     */
    private suspend inline fun <reified A : FabricAvailability> Seam.awaitAvailability(): A =
        withTimeoutOrNull(TEST_WEDGE_BACKSTOP) {
            capability.first { it.availability is A }.availability as A
        } ?: fail(
            "capability never reached ${A::class.simpleName} within $TEST_WEDGE_BACKSTOP — it is " +
                "still ${capability.value.availability}. Either the seam never consumed the " +
                "injected ConnectivityObserver, or the fold sent that reading somewhere else (#1725).",
        )

    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        clientLoom: KtorClientLoom,
    ): Pair<Seam, Seam> =
        withTimeout(TEST_WEDGE_BACKSTOP) {
            val advertisement =
                WebSocketAdvertisement(
                    url = "ws://localhost$serverPath",
                    serverPeerId = serverLoom.selfPeerId,
                    sessionName = "capability-client",
                )
            val clientSeam = clientLoom.join(advertisement)
            val serverSeam = serverLoom.nextLink()
            serverSeam to clientSeam
        }
}
