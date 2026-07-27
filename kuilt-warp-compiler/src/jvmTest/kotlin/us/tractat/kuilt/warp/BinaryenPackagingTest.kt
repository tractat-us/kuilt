package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the module's **packaging contract** (#1335): `wasm-opt` ships as one classified
 * companion jar per OS, and the main jar carries only the coordinates needed to name the
 * missing one.
 *
 * Nothing else in the build checks this. The resource wiring lives in `build.gradle.kts`,
 * so a rename, a dropped `resources.srcDir`, or a publication that quietly stops attaching
 * the classified jars would otherwise surface as a runtime failure in a *consumer's*
 * process — and publish tasks are not in `ci-required`'s graph, so it would land on `main`
 * first. These assertions run in `jvmTest`, which sees exactly the resources the published
 * artifacts carry.
 */
class BinaryenPackagingTest {

    private fun resource(name: String): Properties? {
        val stream = BinaryenWasmOptimizer::class.java.getResourceAsStream("binaryen/$name")
            ?: return null
        return Properties().apply { stream.use { load(it) } }
    }

    @Test
    fun mainJarCarriesTheClassifierCoordinatesSoAMissingBinaryCanNameItself() {
        val coordinates = assertNotNull(
            resource("coordinates.properties"),
            "the main jvm jar must carry binaryen/coordinates.properties",
        )
        val coordinate = coordinates.getProperty("binaryen.coordinates")
        val platforms = coordinates.getProperty("binaryen.platforms").orEmpty().split(",")
        assertAll(
            {
                assertTrue(
                    coordinate.orEmpty().startsWith("us.tractat.kuilt:kuilt-warp-compiler-jvm:"),
                    "classified jars hang off the jvm publication, got '$coordinate'",
                )
            },
            {
                assertEquals(
                    listOf("linux-aarch64", "linux-x86_64", "macos-arm64", "macos-x86_64"),
                    platforms,
                    "every supported compiler-node host must be published",
                )
            },
        )
    }

    @Test
    fun theHostsClassifiedPayloadIsOnTheTestClasspathAndSelfDescribing() {
        val manifest = assertNotNull(
            resource("manifest.properties"),
            "the build host's wasm-opt must be wired into jvmTest resources",
        )
        val published = resource("coordinates.properties")
            ?.getProperty("binaryen.platforms").orEmpty().split(",")
        val files = manifest.getProperty("binaryen.files").orEmpty().split(",").filter { it.isNotEmpty() }
        assertAll(
            {
                assertTrue(
                    manifest.getProperty("binaryen.platform") in published,
                    "manifest platform '${manifest.getProperty("binaryen.platform")}' is not a published classifier",
                )
            },
            { assertEquals("bin/wasm-opt", manifest.getProperty("binaryen.executable")) },
            { assertTrue(files.contains("bin/wasm-opt"), "the manifest must list the executable, got $files") },
            {
                val missing = files.filter {
                    BinaryenWasmOptimizer::class.java.getResource("binaryen/$it") == null
                }
                assertTrue(missing.isEmpty(), "manifest names files absent from the classpath: $missing")
            },
        )
    }
}
