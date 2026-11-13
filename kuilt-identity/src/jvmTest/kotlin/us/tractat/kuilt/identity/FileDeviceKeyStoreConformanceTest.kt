package us.tractat.kuilt.identity

import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.store.FileChannelDurableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * [FileDeviceKeyStore] against the whole [DeviceKeyStoreConformanceSuite], as a
 * [DeviceKeyStoreFixture.Durable] store over a fresh temp directory per fixture. Every reopen is a
 * brand-new instance over that directory, sharing no memory with the last, so what it answers came off
 * the disk.
 *
 * Each damage hook writes the file the way that failure would leave it:
 *
 * - **corrupt key:** the key file truncated to half its length, so it is no longer JSON;
 * - **mismatched key:** a well-formed JWK whose `x` and `y` are one fresh key's and whose `d` is
 *   another's, so it decodes and fails only the self-test;
 * - **cleared key:** the key file deleted;
 * - **interrupted write:** half a JWK left in `device-key.jwk.tmp`, the one file a crash mid-write
 *   leaves, beside whatever key file is there;
 * - **corrupt grants:** three bytes that are not a grants record, written through the grant store
 *   itself, so the hook reaches the record the store reads and no other file.
 */
class FileDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val directory = createTempDirectory("kuilt-device-key")
        val keyFile = directory.resolve(FileDeviceKeyStore.KEY_FILE)
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade -> FileDeviceKeyStore(directory, beforeKeyIsMade) },
            corruptKey = { keyFile.writeBytes(keyFile.readBytes().let { it.copyOf(it.size / 2) }) },
            plantMismatchedKey = { plantMismatchedKey(keyFile) },
            clearKey = { check(keyFile.deleteIfExists()) { "precondition: there was a key file to clear" } },
            interruptKeyWrite = { interruptKeyWrite(directory) },
            corruptGrants = {
                FileChannelDurableStore(directory.toFile()).write(FileDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9))
            },
        )
    }

    private suspend fun plantMismatchedKey(keyFile: Path): DidKey {
        val named = SoftwareDeviceKey.generate()
        val signer = SoftwareDeviceKey.generate()
        val spliced = withPrivatePart(named.encodeJwk().decodeToString(), of = signer.encodeJwk().decodeToString())
        keyFile.writeBytes(spliced.encodeToByteArray())
        return named.id
    }

    private suspend fun interruptKeyWrite(directory: Path) {
        val half = SoftwareDeviceKey.generate().encodeJwk().let { it.copyOf(it.size / 2) }
        Files.write(directory.resolve("${FileDeviceKeyStore.KEY_FILE}.tmp"), half)
    }
}

/** [jwk] with its `d` replaced by the `d` in [of]: a pair whose halves belong to different keys. */
internal fun withPrivatePart(jwk: String, of: String): String {
    val d = Regex("\"d\"\\s*:\\s*\"([^\"]+)\"")
    val signerD = checkNotNull(d.find(of)) { "no d in the signer's JWK" }.groupValues[1]
    val namedD = checkNotNull(d.find(jwk)) { "no d in the named JWK" }.groupValues[1]
    check(signerD != namedD) { "precondition: two different keys" }
    return jwk.replace(namedD, signerD)
}
