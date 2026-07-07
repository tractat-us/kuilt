package us.tractat.kuilt.demo.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.demo.Cell
import us.tractat.kuilt.demo.Colour
import us.tractat.kuilt.demo.PatchworkSession
import us.tractat.kuilt.demo.StitchClock
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [PatchworkCli] is a thin wrapper over [PatchworkSession]; these tests drive
 * the command surface — stitch / tunnel / reconnect / show / status — over an
 * [InMemoryLoom] under virtual time (same ceremony as `PatchworkSessionTest`:
 * `StandardTestDispatcher`, bounded `delay` settles, sessions on
 * `backgroundScope`, `expectVirtualTime = true`).
 */
class PatchworkCliTest {

    private fun TestScope.session(loom: InMemoryLoom, name: String) = PatchworkSession(
        loom = loom,
        stitcher = ReplicaId(name),
        scope = backgroundScope,
        clock = StitchClock { 1_000L },
        quilterConfig = QuilterConfig(expectVirtualTime = true),
    )

    @Test
    fun commandsDriveTheSessionThroughTheTunnelAndBack() = runTest(
        StandardTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val host = session(loom, "host")
        host.host(Pattern("patchwork"))
        delay(1)

        val cli = PatchworkCli(session(loom, "cli"), InMemoryTag("cli"))

        // reconnect from the initial offline state = the first join.
        assertContains(cli.execute("reconnect"), "online")
        delay(1)

        assertContains(cli.execute("stitch 0 0 red"), "stitched (0, 0) #e94f37")
        delay(10)
        assertEquals(PALETTE.getValue("red"), host.quilt.value[Cell(0, 0)], "stitch reaches the host")

        // Into the tunnel: local stitches must not leak to the host.
        assertContains(cli.execute("tunnel"), "offline")
        assertContains(cli.execute("status"), "offline")
        assertContains(cli.execute("stitch 1 1 #4062bb"), "kept local")
        delay(10)
        assertNull(host.quilt.value[Cell(1, 1)], "offline stitch must not reach the host")

        // Back out: the offline patch merges into the host's quilt.
        assertContains(cli.execute("reconnect"), "merging")
        delay(10)
        assertEquals(Colour("#4062bb"), host.quilt.value[Cell(1, 1)], "offline stitch merges on reconnect")
        assertContains(cli.execute("status"), "online")
        assertContains(cli.execute("show"), "2 patches")
    }

    @Test
    fun malformedCommandsReportUsageWithoutTouchingTheSession() = runTest(
        StandardTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val loom = InMemoryLoom()
        val session = session(loom, "cli")
        val cli = PatchworkCli(session, InMemoryTag("cli"))

        val noArgs = cli.execute("stitch")
        val nonNumeric = cli.execute("stitch one two red")
        val negative = cli.execute("stitch -1 0 red")
        val badColour = cli.execute("stitch 0 0 mauve-ish")
        val unknown = cli.execute("frobnicate")
        val blank = cli.execute("   ")
        val tunnelOffline = cli.execute("tunnel")
        assertAll(
            { assertContains(noArgs, "usage") },
            { assertContains(nonNumeric, "usage") },
            { assertContains(negative, "usage") },
            { assertContains(badColour, "usage") },
            { assertContains(unknown, "unknown command") },
            { assertEquals("", blank) },
            { assertContains(tunnelOffline, "already offline") },
            { assertTrue(session.quilt.value.isEmpty(), "no command above may stitch") },
            { assertFalse(session.connected.value, "no command above may connect") },
        )
    }

    @Test
    fun renderQuiltDrawsTheBoundingBoxWithTally() {
        val empty = renderQuilt(emptyMap())
        val one = renderQuilt(mapOf(Cell(1, 0) to Colour("#e94f37")))
        assertAll(
            { assertContains(empty, "empty") },
            { assertContains(one, "1 patch") },
            { assertTrue(one.startsWith("· "), "cell (0,0) is unstitched") },
            { assertContains(one, "\u001B[48;2;233;79;55m") },
        )
    }
}
