@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package us.tractat.kuilt.raft

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.internal.ForwardOutcome
import us.tractat.kuilt.raft.internal.RaftMessage
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The wire half of #2155: [ForwardOutcome.PayloadTooLarge], tag `ptl`, carried in a
 * [RaftMessage.ForwardResponse].
 *
 * Three things are pinned. The bytes, so the variant cannot drift on one target and not another
 * (the reason `RaftWireGoldenVectorTest` exists). The round trip, so every field survives. And what
 * a build **without** the variant does with it — the mixed-version answer the ruling on #2155 asked
 * for before the decode path was written.
 */
class ForwardOutcomePayloadTooLargeWireTest {

    private val refusal = ForwardOutcome.PayloadTooLarge(payloadBytes = 1024, budgetBytes = 768, reservedBytes = 256)

    private val frame: RaftMessage = RaftMessage.ForwardResponse(clientRequestId = 11, outcome = refusal)

    @Test
    fun theFrameMatchesItsGoldenVectorOnEveryTarget() {
        assertEquals(FORWARD_RESPONSE_PAYLOAD_TOO_LARGE, hexOf(raftCbor.encodeToByteArray(RaftMessage.serializer(), frame)))
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val decoded = raftCbor.decodeFromByteArray(RaftMessage.serializer(), unhex(FORWARD_RESPONSE_PAYLOAD_TOO_LARGE))
        assertEquals(frame, decoded)
    }

    /**
     * A refusal that contradicts itself is not a refusal, so it does not decode: the caller would
     * otherwise be told its command is too large for a limit it actually fits.
     */
    @Test
    fun aSelfContradictingRefusalIsNotDecoded() {
        val fitted = FORWARD_RESPONSE_PAYLOAD_TOO_LARGE.replace("6c7061796c6f616442797465731904", "6c7061796c6f616442797465731902")
        assertTrue(fitted != FORWARD_RESPONSE_PAYLOAD_TOO_LARGE, "rig: the payload field must actually have been rewritten")
        val outcome = runCatchingCancellable { raftCbor.decodeFromByteArray(RaftMessage.serializer(), unhex(fitted)) }
        assertTrue(
            outcome.isFailure,
            "a 512 B payload 'refused' against a 768 B budget must be refused as a frame: got ${outcome.getOrNull()}",
        )
    }

    /**
     * What a build that predates `ptl` does with it: the polymorphic decoder has no unknown-subtype
     * fallback, so the frame is **refused** — reported as `FrameUndecodable`, the forward left
     * outstanding — rather than misread as some other outcome.
     *
     * [PrePtlWire] is that build's decoder for this frame, so it has to be shown to be a decoder and
     * not a stub: it decodes today's `NotLeader` reply, and only then does its refusal of `ptl` say
     * anything about `ptl`.
     */
    @Test
    fun aBuildWithoutTheVariantRefusesItRatherThanMisreadingIt() {
        val notLeader = raftCbor.encodeToByteArray(
            RaftMessage.serializer(),
            RaftMessage.ForwardResponse(clientRequestId = 11, outcome = ForwardOutcome.NotLeader),
        )
        val outcome = runCatchingCancellable {
            raftCbor.decodeFromByteArray(PrePtlWire.serializer(), unhex(FORWARD_RESPONSE_PAYLOAD_TOO_LARGE))
        }
        assertAll(
            {
                assertEquals(
                    PrePtlWire.ForwardResponse(11, PrePtlOutcome.NotLeader),
                    raftCbor.decodeFromByteArray(PrePtlWire.serializer(), notLeader),
                    "rig: the old decoder must accept a reply it knows",
                )
            },
            { assertIs<SerializationException>(outcome.exceptionOrNull(), "and refuse `ptl` as a decode failure: ${outcome.getOrNull()}") },
        )
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0') }

    private fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(radix = 16).toByte() }

    private companion object {
        /** `ForwardResponse(11, PayloadTooLarge(1024, 768, 256))`. */
        const val FORWARD_RESPONSE_PAYLOAD_TOO_LARGE =
            "9f63667772bf6f636c69656e745265717565737449640b676f7574636f6d659f6370746cbf6c7061796c6f6164" +
                "42797465731904006b62756467657442797465731903006d72657365727665644279746573190100ffffffff"
    }
}

/** The `ForwardResponse` half of a build from before `ptl` existed: the same tags, minus that one. */
@Serializable
private sealed interface PrePtlWire {
    @Serializable
    @SerialName("fwr")
    data class ForwardResponse(val clientRequestId: Long, val outcome: PrePtlOutcome) : PrePtlWire
}

@Serializable
private sealed interface PrePtlOutcome {
    @Serializable
    @SerialName("cm")
    data class Committed(val index: Long, val term: Long) : PrePtlOutcome

    @Serializable
    @SerialName("nl")
    data object NotLeader : PrePtlOutcome

    @Serializable
    @SerialName("fl")
    data object Failed : PrePtlOutcome
}
