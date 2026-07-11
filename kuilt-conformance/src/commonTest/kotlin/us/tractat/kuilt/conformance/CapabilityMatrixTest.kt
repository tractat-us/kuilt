package us.tractat.kuilt.conformance

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapabilityMatrixTest {

    private companion object {
        const val SEND_TO_GAP = "https://github.com/tractat-us/kuilt/issues/1409"
        const val MESH_GAP = "https://github.com/tractat-us/kuilt/issues/1404"
    }

    @Test
    fun rendersStableMarkdownTable() {
        val entries = listOf(
            MatrixEntry(
                fabric = "WebSocket",
                capabilities = SeamCapabilities.FULL,
                gaps = emptyMap(),
                meshEvidence = "2-peer vacuous (SeamConformanceSuite)",
            ),
            MatrixEntry(
                fabric = "WebRTC",
                capabilities = SeamCapabilities.FULL.copy(supportsSendTo = false, meshDelivery = false),
                gaps = mapOf("supportsSendTo" to SEND_TO_GAP, "meshDelivery" to MESH_GAP),
            ),
        )

        val expected = listOf(
            "| Fabric | ordersDelivery | reportsPeerLoss | terminatesIncomingOnClose | " +
                "staysTornAfterClose | throwsOnSendToTorn | supportsSendTo | securesTransport | " +
                "meshDelivery | mesh evidence |",
            "|---|---|---|---|---|---|---|---|---|---|",
            "| WebSocket | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | 2-peer vacuous (SeamConformanceSuite) |",
            "| WebRTC | ✓ | ✓ | ✓ | ✓ | ✓ | [–]($SEND_TO_GAP) | ✓ | [–]($MESH_GAP) |  |",
        ).joinToString("\n")

        assertEquals(expected, renderMatrix(entries))
    }

    @Test
    fun rendersFalseCellAsIssueLinkedDashAndTrueCellAsCheck() {
        val rendered = renderMatrix(
            listOf(
                MatrixEntry(
                    fabric = "Relay",
                    capabilities = SeamCapabilities.FULL.copy(meshDelivery = false, securesTransport = false),
                    gaps = mapOf("meshDelivery" to MESH_GAP, "securesTransport" to SEND_TO_GAP),
                ),
            ),
        )

        assertAll(
            { assertTrue(rendered.contains("[–]($MESH_GAP)"), "mesh gap link present") },
            { assertTrue(rendered.contains("[–]($SEND_TO_GAP)"), "secures gap link present") },
            { assertTrue(rendered.contains("✓"), "true flags render a check") },
            { assertTrue(rendered.trimEnd().endsWith("|"), "rows are well-formed table rows") },
        )
    }

    @Test
    fun falseFlagWithoutGapUrlIsARenderError() {
        assertFailsWith<IllegalArgumentException> {
            renderMatrix(
                listOf(
                    MatrixEntry(
                        fabric = "Broken",
                        capabilities = SeamCapabilities.FULL.copy(meshDelivery = false),
                        gaps = emptyMap(),
                        meshEvidence = null,
                    ),
                ),
            )
        }
    }

    @Test
    fun falseFlagWithBlankGapUrlIsARenderError() {
        assertFailsWith<IllegalArgumentException> {
            renderMatrix(
                listOf(
                    MatrixEntry(
                        fabric = "Broken",
                        capabilities = SeamCapabilities.FULL.copy(meshDelivery = false),
                        gaps = mapOf("meshDelivery" to "   "),
                    ),
                ),
            )
        }
    }

    @Test
    fun meshDeliveryTrueWithoutEvidenceIsARenderError() {
        assertFailsWith<IllegalArgumentException> {
            renderMatrix(
                listOf(
                    MatrixEntry(
                        fabric = "Mesh",
                        capabilities = SeamCapabilities.FULL,
                        gaps = emptyMap(),
                        meshEvidence = null,
                    ),
                ),
            )
        }
    }

    @Test
    fun meshDeliveryTrueWithBlankEvidenceIsARenderError() {
        assertFailsWith<IllegalArgumentException> {
            renderMatrix(
                listOf(
                    MatrixEntry(
                        fabric = "Mesh",
                        capabilities = SeamCapabilities.FULL,
                        gaps = emptyMap(),
                        meshEvidence = "  ",
                    ),
                ),
            )
        }
    }
}
