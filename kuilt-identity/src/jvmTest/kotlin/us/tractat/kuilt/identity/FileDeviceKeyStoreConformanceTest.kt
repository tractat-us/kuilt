package us.tractat.kuilt.identity

import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.store.FileChannelDurableStore
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * - **interrupted write:** half a JWK left in a temporary file named the way the store names its
 *   own, the one file a crash mid-write leaves, beside whatever key file is there (`LeftResidue`);
 * - **corrupt grants:** three bytes that are not a grants record, written through the grant store
 *   itself, so the hook reaches the record the store reads and no other file;
 * - **unreadable key:** the key file a symbolic link to itself, so reading it fails with `ELOOP`
 *   while the key itself waits aside, untouched (`Rigged`).
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
            makeKeyUnreadable = { loopKeyFile(keyFile) },
        )
    }

    /**
     * The key file replaced by a symbolic link to itself: present, and unreadable with `ELOOP`, the
     * way an exotic or failing file system answers. The key is kept aside, and `restore` puts it back.
     */
    private fun loopKeyFile(keyFile: Path): DeviceKeyStoreFixture.UnreadableKey {
        val aside = keyFile.resolveSibling("${FileDeviceKeyStore.KEY_FILE}.held-by-test")
        Files.move(keyFile, aside)
        Files.createSymbolicLink(keyFile, keyFile.fileName)
        return DeviceKeyStoreFixture.UnreadableKey.Rigged {
            Files.delete(keyFile)
            Files.move(aside, keyFile)
        }
    }

    private suspend fun plantMismatchedKey(keyFile: Path): DidKey {
        val named = SoftwareDeviceKey.generate()
        val signer = SoftwareDeviceKey.generate()
        val spliced = withPrivatePart(named.encodeJwk().decodeToString(), of = signer.encodeJwk().decodeToString())
        keyFile.writeBytes(spliced.encodeToByteArray())
        return named.id
    }

    /**
     * Half a JWK in a temporary file beside the key, which is what a crash between the temporary
     * file's write and its rename leaves. The name matches the store's temporary files.
     */
    private suspend fun interruptKeyWrite(directory: Path): DeviceKeyStoreFixture.KeyWriteInterruption {
        val residue = directory.resolve("${FileDeviceKeyStore.KEY_FILE}.tmp")
        val half = SoftwareDeviceKey.generate().encodeJwk().let { it.copyOf(it.size / 2) }
        Files.write(residue, half)
        return DeviceKeyStoreFixture.KeyWriteInterruption.LeftResidue {
            if (Files.exists(residue, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(residue) else null
        }
    }
}
