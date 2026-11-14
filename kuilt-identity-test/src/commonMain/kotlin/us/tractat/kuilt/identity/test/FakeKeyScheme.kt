package us.tractat.kuilt.identity.test

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.hash.sha2.SHA512
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.identity.KeyScheme
import kotlin.random.Random

/**
 * A [KeyScheme] for tests: it makes keys, reads keys, signs and checks without ever waiting on the
 * platform's cryptographic provider, so a room test on wasmJs stays on virtual time. **Anyone can forge
 * its signatures**: a signature is SHA-512 of a fixed tag, the key's compressed point and the message.
 *
 * It still refuses everything a real scheme refuses for the reasons a room test checks: a signature
 * under another key, over another message, or with any byte changed. So a test that asserts a room
 * turns away a forged or replayed proof is not made vacuous by running on it.
 *
 * - [generate] makes a key whose 33-byte compressed point is `0x02` followed by 32 bytes from
 *   [random]. The point is not necessarily on P-256: nothing here does curve math.
 * - [key] makes the fixed key numbered `index`, without suspending.
 * - [parse] checks syntax only, as a frame's serializer does.
 * - [verify] recomputes the signature and compares it.
 *
 * **Every operation first runs [before]**, which by default does nothing and returns at once. A test
 * that needs a gap — a Goodbye landing between a Challenge and its Proof, a re-Hello while a decision
 * is in flight — passes a [before] that suspends on a gate it opens later. A test that needs the
 * provider to fail passes one that throws: the throw escapes the operation unchanged, as an
 * [IllegalStateException] from a real provider would. [before] runs outside every lock, and for a
 * [key]'s signatures too.
 *
 * A room picks the scheme up through its [DeviceKeyStore]: build the store with [fakeKeyStore], and the
 * room reads [DeviceKeyStore.keyScheme] from it. A room on a real store checks every signature with
 * ECDSA, so a fake signature sent to it is simply a bad one.
 *
 * Every fake scheme checks every other fake scheme's signatures, and [fakeDeviceKey]'s: the scheme
 * holds no secret. A store's key does not come from [generate] but from the index the store was built
 * with, so two stores with different indices are two devices with two different keys whichever
 * schemes they share.
 *
 * @param random where [generate]'s points come from. There is no default: pass a seeded `Random`, so a
 *   red that depends on which key sorts first replays the same way. Two schemes on equal seeds draw
 *   the same keys. Draws are taken under a lock, so one scheme is safe to share across threads.
 * @param before runs at the start of every operation, with the operation it is about to run.
 */
@TestOnlyKeyScheme
public class FakeKeyScheme(
    private val random: Random,
    private val before: suspend (FakeKeyOperation) -> Unit = {},
) : KeyScheme {
    private val lock = reentrantLock()

    override suspend fun generate(): DeviceKey {
        before(FakeKeyOperation.Generate)
        val x = lock.withLock { random.nextBytes(POINT_SIZE - 1) }
        return FakeDeviceKey(DidKey.ofSyntax(byteArrayOf(EVEN_Y) + x), before)
    }

    /**
     * The fixed key numbered [index], the same in every run and on every target, made without
     * suspending. Its signatures run this scheme's [before]. Its [DeviceKey.id] is [fakeDidKey]`(index)`.
     */
    public fun key(index: Int): DeviceKey = FakeDeviceKey(fakeDidKey(index), before)

    /** A store's first key: [key]`(index)`, after the [before] hook has seen [FakeKeyOperation.Generate]. */
    internal suspend fun generateFixed(index: Int): DeviceKey {
        before(FakeKeyOperation.Generate)
        return key(index)
    }

    override suspend fun parse(value: String): DidKey {
        before(FakeKeyOperation.Parse(value))
        return DidKey.ofSyntax(value)
    }

    override suspend fun verify(key: DidKey, message: ByteArray, signature: ByteArray): Boolean {
        before(FakeKeyOperation.Verify(key, message, signature))
        return signature.contentEquals(fakeSignature(key, message))
    }

    override fun toString(): String = "FakeKeyScheme"
}

/**
 * The operation a [FakeKeyScheme] is about to run, as its `before` hook sees it. The arrays are the
 * caller's own, not copies: read them, do not change them.
 */
@TestOnlyKeyScheme
public sealed interface FakeKeyOperation {
    /** [FakeKeyScheme.generate]: a store making its first key, or a test making one. */
    public data object Generate : FakeKeyOperation

    /** [FakeKeyScheme.parse] of [value]. */
    public data class Parse(public val value: String) : FakeKeyOperation

    /** A fake key [key] signing [message]. */
    public class Sign(public val key: DidKey, public val message: ByteArray) : FakeKeyOperation {
        override fun toString(): String = "Sign(key=${key.value}, ${message.size} bytes)"
    }

    /** [FakeKeyScheme.verify] of [signature] by [key] over [message]. */
    public class Verify(public val key: DidKey, public val message: ByteArray, public val signature: ByteArray) : FakeKeyOperation {
        override fun toString(): String = "Verify(key=${key.value}, ${message.size} bytes)"
    }
}

/**
 * The fake device numbered [index]: a [DeviceKeyStore] whose key is [fakeDeviceKey]`(index)`, and on
 * which a room signs and checks with [scheme]. It keeps grants by the same rules as every other store,
 * since an [InMemoryDeviceKeyStore] keeps them underneath.
 *
 * One store is one device, and the index is what tells devices apart: a test with a host and two
 * joiners builds `fakeKeyStore(0)`, `fakeKeyStore(1)` and `fakeKeyStore(2)`. Distinct indices give
 * distinct keys and the same index gives the same key, in every run and on every target, so a red that
 * depends on how the devices' keys sort replays exactly, and a test can name a device's key as
 * [fakeDidKey]`(index)` before the store has made it.
 *
 * The first [DeviceKeyStore.deviceKey] call runs [scheme]'s `before` hook with
 * [FakeKeyOperation.Generate], so a hook can hold or fail a store's key the way a slow or broken
 * provider would. The store's key never comes from [scheme]'s `Random`: that only feeds a test that
 * calls [FakeKeyScheme.generate] itself, and by default it is seeded with [index].
 *
 * @param scheme the scheme a room built on this store signs and checks with. Pass one to share a
 *   `before` hook across devices.
 */
@TestOnlyKeyScheme
public fun fakeKeyStore(index: Int, scheme: FakeKeyScheme = FakeKeyScheme(Random(index))): DeviceKeyStore =
    FakeDeviceKeyStore(index, scheme)

/**
 * [fakeKeyStore]'s store: the key is [FakeKeyScheme.generateFixed]'s, made once under [mutex] as
 * [InMemoryDeviceKeyStore] makes its own, and the grants and the scheme are [grantsKept]'s. That inner
 * store's own key is never asked for, so its `Random` is never drawn from.
 */
@OptIn(TestOnlyKeyScheme::class)
private class FakeDeviceKeyStore(
    private val index: Int,
    private val scheme: FakeKeyScheme,
    private val grantsKept: DeviceKeyStore = InMemoryDeviceKeyStore(scheme),
) : DeviceKeyStore by grantsKept {
    private val mutex = Mutex()
    private var key: DeviceKey? = null

    override suspend fun deviceKey(): DeviceKey = mutex.withLock {
        key ?: scheme.generateFixed(index).also { key = it }
    }

    override fun toString(): String = "fakeKeyStore($index)"
}

/**
 * The fixed fake key numbered [index], made without suspending: the same key in every run and on every
 * target, so a hand-built frame, a seeded roster or a golden vector can name it. It signs at once, with
 * no hook; [FakeKeyScheme.key] makes the same key with its scheme's hook. Distinct indices are distinct
 * keys.
 */
@TestOnlyKeyScheme
public fun fakeDeviceKey(index: Int): DeviceKey = FakeDeviceKey(fakeDidKey(index), before = {})

/**
 * The name of [fakeDeviceKey]`(index)`, made without suspending: a syntactically valid P-256 `did:key`
 * whose point is `0x02` followed by the first 32 bytes of SHA-512 of a fixed tag and [index]. Its point
 * need not be on the curve, so the real [KeyScheme.P256] may refuse to parse it; a room on the fake
 * scheme reads it as any other key.
 */
@TestOnlyKeyScheme
public fun fakeDidKey(index: Int): DidKey {
    val digest = SHA512().apply {
        update(KEY_TAG)
        update(ByteArray(Int.SIZE_BYTES) { (index ushr (Byte.SIZE_BITS * (Int.SIZE_BYTES - 1 - it))).toByte() })
    }.digest()
    return DidKey.ofSyntax(byteArrayOf(EVEN_Y) + digest.copyOf(POINT_SIZE - 1))
}

/** A fake key: its signature is [fakeSignature], which [FakeKeyScheme.verify] recomputes. */
@OptIn(TestOnlyKeyScheme::class)
private class FakeDeviceKey(
    override val id: DidKey,
    private val before: suspend (FakeKeyOperation) -> Unit,
) : DeviceKey {
    override suspend fun sign(message: ByteArray): ByteArray {
        before(FakeKeyOperation.Sign(id, message))
        return fakeSignature(id, message)
    }

    override fun toString(): String = "FakeDeviceKey(${id.value})"
}

/**
 * SHA-512 of [SIGNATURE_TAG], [key]'s compressed point and [message]: 64 bytes, the width a statement's
 * signature must have. The point is always 33 bytes, so no two (key, message) pairs give the same input.
 */
private fun fakeSignature(key: DidKey, message: ByteArray): ByteArray =
    SHA512().apply {
        update(SIGNATURE_TAG)
        update(key.compressedPoint())
        update(message)
    }.digest()

private val SIGNATURE_TAG = "kuilt/fake-signature/v1".encodeToByteArray()
private val KEY_TAG = "kuilt/fake-key/v1".encodeToByteArray()
private const val POINT_SIZE = 33
private const val EVEN_Y: Byte = 0x02
