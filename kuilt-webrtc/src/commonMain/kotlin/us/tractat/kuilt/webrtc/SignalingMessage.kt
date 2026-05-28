package us.tractat.kuilt.webrtc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire-format messages exchanged between two WebRTC peers via the
 * [SignalingChannel] during connection bootstrap.
 *
 * Once the data channel is open, no further [SignalingMessage]s flow —
 * application bytes ride the data channel directly.
 */
@Serializable
public sealed interface SignalingMessage {
    @Serializable
    @SerialName("offer")
    public data class Offer(
        val sdp: String,
    ) : SignalingMessage

    @Serializable
    @SerialName("answer")
    public data class Answer(
        val sdp: String,
    ) : SignalingMessage

    @Serializable
    @SerialName("ice")
    public data class IceCandidate(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
    ) : SignalingMessage

    @Serializable
    @SerialName("bye")
    public data object Bye : SignalingMessage

    /**
     * Server-assigned role frame, sent once on attach before any offer/answer
     * exchange. [host] is `true` for the first attacher (who runs the offerer
     * path), `false` for the second (who runs the answerer path).
     *
     * This frame originates from the relay, not from a peer. The relay is the
     * only party that observes attach order, so it is the natural place to
     * break the symmetric tie. The vanilla-JS smoke test ignores unknown
     * `type` values (the codec has `ignoreUnknownKeys = true`), so this new type
     * does not break existing consumers.
     */
    @Serializable
    @SerialName("role")
    public data class Role(
        val host: Boolean,
    ) : SignalingMessage
}

/**
 * JSON codec for [SignalingMessage]. The wire form uses a `"type"`
 * discriminator (`offer` / `answer` / `ice` / `bye` / `role`).
 */
public object SignalingMessageCodec {
    private val json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

    public fun encode(message: SignalingMessage): String = json.encodeToString(SignalingMessage.serializer(), message)

    public fun decode(text: String): SignalingMessage = json.decodeFromString(SignalingMessage.serializer(), text)
}
