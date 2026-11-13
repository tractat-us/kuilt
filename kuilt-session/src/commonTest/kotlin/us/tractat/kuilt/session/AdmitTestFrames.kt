package us.tractat.kuilt.session

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.admit.ProtocolVersion

/**
 * `AdmitMessage.encode(Hello("Ada", "s-1", targetRoom = "room-7", protocolVersion = 2))` as hex: the
 * wire a v2 joiner sends, captured from the encoder before v3 added the device key and the joiner
 * nonce (#3210). A test standing in for a v2 joiner sends these bytes rather than encoding a Hello
 * itself, since this build's encoder is the thing a v2 peer does not run.
 */
internal const val GOLDEN_V2_HELLO_HEX: String =
    "619f6568656c6c6fbf6b646973706c61794e616d65634164616973657373696f6e496463732d316a746172676574526f6f6d" +
        "66726f6f6d2d376f70726f746f636f6c56657273696f6e02ffff"

/**
 * Two P-256 `did:key`s from the W3C did:key test vectors, built **without suspending**, for tests that
 * write a key into a hand-built frame.
 *
 * Why not `DeviceKey.generate()` or `DidKey.parse`: on wasmJs both are WebCrypto promises, and while a
 * `runTest` body waits on one, virtual time runs on and every timer a room has armed fires — an admit
 * deadline, a heartbeat window. A key made this way runs [DidKey]'s syntax check only, through its
 * serializer, which is all a frame on the wire gets either.
 */
internal val TEST_KEY_A: DidKey = syntaxCheckedDidKey("did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv")

/** The second of the two; see [TEST_KEY_A]. */
internal val TEST_KEY_B: DidKey = syntaxCheckedDidKey("did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun syntaxCheckedDidKey(value: String): DidKey =
    Cbor.decodeFromByteArray(DidKey.serializer(), Cbor.encodeToByteArray(String.serializer(), value))

/**
 * A fresh device, its key already made, for a test that builds a [SeamRoom] directly: the
 * [RoomDevice] a [SeamRoomFactory] would have read before building the room (#3210).
 *
 * Call it from the test body before the first room in the test starts. On wasmJs making a key is a
 * WebCrypto promise, and while the test body waits on one under `runTest` virtual time runs on, so
 * every room already started sees its timers fire.
 *
 * It does not make a wasmJs test whole. Measured on #3210: one `InMemoryDeviceKeyStore().deviceKey()`
 * at the top of an unchanged `RoomChannelTest` body, before any room exists, reds that test on wasmJs
 * and only there. A WebCrypto await anywhere in a `runTest` body changes what the virtual-time calls
 * after it see.
 */
internal suspend fun keyedDevice(): RoomDevice = RoomDevice.of(InMemoryDeviceKeyStore())

/**
 * A Hello as a current joiner sends it, for tests that drive the admit wire by hand: the current
 * [ProtocolVersion], a device key and a joiner nonce. Since v3 (#3210) a Hello missing the key or
 * the nonce is refused before any other gate runs, so a raw Hello built without them tests that
 * refusal and nothing else.
 *
 * [key] defaults to [TEST_KEY_A]. Nothing reads keys at admission yet, but a test admitting two of
 * these as two devices passes [TEST_KEY_B] to the second.
 */
internal fun currentHello(
    displayName: String,
    sessionId: String = "session-$displayName",
    targetRoom: String? = null,
    key: DidKey = TEST_KEY_A,
): AdmitMessage.Hello = AdmitMessage.Hello(
    displayName = displayName,
    sessionId = sessionId,
    targetRoom = targetRoom,
    protocolVersion = ProtocolVersion.CURRENT,
    key = key,
    joinerNonce = ByteArray(AdmitMessage.Hello.JOINER_NONCE_SIZE),
)
