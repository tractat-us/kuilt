package us.tractat.kuilt.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalingMessageTest {
    @Test
    fun offerRoundTrip() {
        val msg = SignalingMessage.Offer(sdp = "v=0\r\no=- 1 1 IN IP4 0\r\n")
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun answerRoundTrip() {
        val msg = SignalingMessage.Answer(sdp = "v=0\r\n")
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun iceRoundTripWithSdpMidAndIndex() {
        val msg =
            SignalingMessage.IceCandidate(
                candidate = "candidate:842163049 1 udp 1677729535 192.0.2.1 51234 typ srflx",
                sdpMid = "0",
                sdpMLineIndex = 0,
            )
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun iceRoundTripWithNullSdpMid() {
        val msg =
            SignalingMessage.IceCandidate(
                candidate = "candidate:1 1 udp 2113937151 ::1 12345 typ host",
                sdpMid = null,
                sdpMLineIndex = null,
            )
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun byeRoundTrip() {
        val msg = SignalingMessage.Bye
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun offerEncodesWithExpectedShape() {
        val msg = SignalingMessage.Offer(sdp = "test-sdp")
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals("""{"type":"offer","sdp":"test-sdp"}""", encoded)
    }

    @Test
    fun byeEncodesAsBareTypeField() {
        val encoded = SignalingMessageCodec.encode(SignalingMessage.Bye)
        assertEquals("""{"type":"bye"}""", encoded)
    }

    @Test
    fun roleHostRoundTrip() {
        val msg = SignalingMessage.Role(host = true)
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun roleJoinerRoundTrip() {
        val msg = SignalingMessage.Role(host = false)
        val encoded = SignalingMessageCodec.encode(msg)
        assertEquals(msg, SignalingMessageCodec.decode(encoded))
    }

    @Test
    fun roleEncodesWithTypeField() {
        val encoded = SignalingMessageCodec.encode(SignalingMessage.Role(host = true))
        assertEquals("""{"type":"role","host":true}""", encoded)
    }

    @Test
    fun unknownTypeIsIgnoredByDecoder() {
        // The vanilla-JS smoke test ignores unknown type values; verify our own codec
        // honours ignoreUnknownKeys and does not throw on a future unknown type.
        // We can't decode an unknown discriminator to a known variant, but we can
        // confirm a *known* type with *extra* unknown fields doesn't throw.
        val withExtraField = """{"type":"bye","unknown":"ignored"}"""
        assertEquals(SignalingMessage.Bye, SignalingMessageCodec.decode(withExtraField))
    }
}
