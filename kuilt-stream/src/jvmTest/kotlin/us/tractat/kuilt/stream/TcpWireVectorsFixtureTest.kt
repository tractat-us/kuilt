package us.tractat.kuilt.stream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the checked-in golden vectors and the copy the common tests run against identical.
 *
 * `kuilt-stream/wire/tcp-wire-v1.vectors.json` is what an implementer in another language reads;
 * [TCP_WIRE_V1_VECTORS] is what `TcpWireVectorsTest` checks kuilt against on every target. If they
 * could differ, the file could drift from the code with every test still green. The file is declared
 * an input of `jvmTest` in this module's build script, so editing only the JSON re-runs this test
 * rather than serving a cached pass.
 */
class TcpWireVectorsFixtureTest {
    @Test
    fun checkedInFixtureEqualsTheEmbeddedCopy() {
        // Gradle runs JVM tests with the module directory as the working directory.
        val file = File("wire/tcp-wire-v1.vectors.json")
        assertTrue(file.isFile, "fixture not found at ${file.absolutePath}")
        assertEquals(file.readText(), TCP_WIRE_V1_VECTORS)
    }
}
