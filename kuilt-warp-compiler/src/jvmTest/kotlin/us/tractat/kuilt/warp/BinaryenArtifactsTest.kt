package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consumer-facing half of #1335: when the OS-classified `wasm-opt` jar is missing or
 * wrong, the failure must hand back the exact `runtimeOnly(…)` line to paste — not an
 * opaque "no bundled binary".
 *
 * These are the only assertions on that wording. A compiler-node operator hits the message
 * on their very first run, on a host this repo's CI never builds on, so it cannot be left
 * to be discovered in production.
 */
class BinaryenArtifactsTest {

    private val published = listOf("linux-aarch64", "linux-x86_64", "macos-arm64", "macos-x86_64")
    private val coordinates = "us.tractat.kuilt:kuilt-warp-compiler-jvm:9.9.9"

    @Test
    fun hostPlatformMapsEveryPublishedHostAndRefusesTheRest() = assertAll(
        { assertEquals("macos-arm64", BinaryenArtifacts.hostPlatform("Mac OS X", "aarch64")) },
        { assertEquals("macos-arm64", BinaryenArtifacts.hostPlatform("Mac OS X", "arm64")) },
        { assertEquals("macos-x86_64", BinaryenArtifacts.hostPlatform("Mac OS X", "x86_64")) },
        { assertEquals("linux-x86_64", BinaryenArtifacts.hostPlatform("Linux", "amd64")) },
        { assertEquals("linux-aarch64", BinaryenArtifacts.hostPlatform("Linux", "aarch64")) },
        { assertEquals(null, BinaryenArtifacts.hostPlatform("Windows 11", "amd64")) },
        { assertEquals(null, BinaryenArtifacts.hostPlatform("Linux", "riscv64")) },
        { assertEquals(null, BinaryenArtifacts.hostPlatform(null, null)) },
    )

    @Test
    fun everyMappedHostIsActuallyPublished() {
        val mapped = listOf(
            "Mac OS X" to "aarch64",
            "Mac OS X" to "x86_64",
            "Linux" to "amd64",
            "Linux" to "aarch64",
        ).mapNotNull { (os, arch) -> BinaryenArtifacts.hostPlatform(os, arch) }
        assertEquals(published, mapped.sorted(), "host detection and the published set must not drift")
    }

    @Test
    fun aMissingArtifactOnASupportedHostNamesTheExactCoordinateToAdd() {
        val message = BinaryenArtifacts.missingArtifact(
            host = "macos-arm64",
            osName = "Mac OS X",
            osArch = "aarch64",
            coordinates = coordinates,
            published = published,
        )
        assertAll(
            {
                assertTrue(
                    message.contains("runtimeOnly(\"us.tractat.kuilt:kuilt-warp-compiler-jvm:9.9.9:macos-arm64\")"),
                    "message must be copy-pasteable, got:\n$message",
                )
            },
            { assertTrue(message.contains("published: linux-aarch64, linux-x86_64, macos-arm64, macos-x86_64")) },
        )
    }

    @Test
    fun anUnsupportedHostIsToldWhyRatherThanGivenAnUnbuildableCoordinate() {
        val message = BinaryenArtifacts.missingArtifact(
            host = null,
            osName = "Windows 11",
            osArch = "amd64",
            coordinates = coordinates,
            published = published,
        )
        assertAll(
            { assertFalse(message.contains("runtimeOnly("), "there is nothing to add, got:\n$message") },
            { assertTrue(message.contains("os.name=Windows 11")) },
            { assertTrue(message.contains("macOS or Linux")) },
        )
    }

    @Test
    fun aWrongOsArtifactNamesBothPlatformsAndTheReplacement() {
        val message = BinaryenArtifacts.wrongPlatform(
            bundled = "linux-x86_64",
            host = "macos-arm64",
            coordinates = coordinates,
        )
        assertAll(
            { assertTrue(message.contains("'linux-x86_64'"), message) },
            { assertTrue(message.contains("'macos-arm64'"), message) },
            {
                assertTrue(
                    message.contains("runtimeOnly(\"us.tractat.kuilt:kuilt-warp-compiler-jvm:9.9.9:macos-arm64\")"),
                    message,
                )
            },
        )
    }
}
