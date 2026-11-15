package us.tractat.kuilt.identity

import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.store.FileChannelDurableStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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
 *   while the key itself waits aside, untouched (`Rigged`);
 * - **unreadable grants:** every permission taken off the grants record, so opening it fails with
 *   `EACCES` while the directory still takes a new file — see [unreadableGrantsRecord].
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
            makeGrantsUnreadable = {
                // `grants` needs no escaping, so the record's filename is the key's name.
                unreadableGrantsRecord(directory.resolve(FileDeviceKeyStore.GRANTS.name)) {
                    FileChannelDurableStore(directory.toFile()).read(FileDeviceKeyStore.GRANTS)
                }
            },
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

/**
 * Every permission taken off [record], so opening it fails with `EACCES` — a file under Data Protection
 * on a locked phone fails the same way — while its directory still takes a new file, so a store that
 * read the failure as "no grants" could still write a new record over it.
 *
 * The rig checks that it took: run as root, a mode of `000` is not enforced. Restoring answers whether
 * the record was written while it was unreadable: a write renames a new, readable file into place,
 * and a delete leaves none, while the faulted file stays unreadable until the restore.
 *
 * @param readDirectly the record read straight off the medium through the store's own backend.
 */
private fun unreadableGrantsRecord(
    record: Path,
    readDirectly: suspend () -> ByteArray?,
): DeviceKeyStoreFixture.UnreadableGrants {
    check(Files.exists(record, LinkOption.NOFOLLOW_LINKS)) { "precondition: a grants record at $record to fault" }
    Files.setPosixFilePermissions(record, emptySet())
    check(!Files.isReadable(record)) { "rig: $record is still readable after chmod 000 — running as root? The fault did not take" }
    return DeviceKeyStoreFixture.UnreadableGrants(
        probe = { readDirectly() },
        restore = {
            val rewritten = !Files.exists(record, LinkOption.NOFOLLOW_LINKS) || Files.isReadable(record)
            if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(record, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
            rewritten
        },
    )
}
