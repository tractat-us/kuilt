package us.tractat.kuilt.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [KuiltVersion.VERSION] is the version the build publishes this module under (#3323).
 *
 * The reference is the POM the `kotlinMultiplatform` publication writes, handed over by the build
 * as `kuilt.publishedPom`, rather than `project.version` — so a build that ever sets the
 * publication's coordinates somewhere other than the project version reds here instead of
 * shipping a constant that names a different release. Run with `-Pversion=<anything>` and both
 * sides move together; a generator that ignored the override would red.
 *
 * JVM only: the constant is one generated `commonMain` file every target compiles, so there is no
 * per-target value to disagree, and the POM is a file only a JVM test can read.
 *
 * What CI proves: the PR build runs this only at the default `<line>.0-dev`. The publish jobs pass
 * `-Pversion` (a snapshot's `<line>.0-dev.<run>`, a tag's `x.y.z`) but run no tests, so the override
 * path is proven by a local `-Pversion=…` run, not by CI. `:kuilt-heddle-core` has the same test
 * for its own copy.
 */
class KuiltVersionTest {

    @Test
    fun `the constant is the version the published POM declares`() {
        val pomPath = assertNotNull(
            // ALLOW-selfSkipGate: the build's handover of the POM path; a missing one fails, never skips
            System.getProperty("kuilt.publishedPom"),
            "kuilt.publishedPom is unset — the jvmTest wiring in kuilt.version-constant is missing",
        )
        val pom = File(pomPath)
        assertTrue(pom.isFile, "the publication's POM was not generated at $pomPath")

        val published = projectVersionIn(pom)

        // The precondition: a real version came out of the POM, so the equality below cannot pass
        // by comparing two empty or placeholder strings.
        assertTrue(
            published.isNotBlank() && published != "unspecified",
            "the POM at $pomPath declares no usable version: '$published'",
        )
        assertEquals(published, KuiltVersion.VERSION)
    }

    /** The project's own `<version>`, a direct child of `<project>`, not a dependency's. */
    private fun projectVersionIn(pom: File): String {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
        val children = root.childNodes
        val version = (0 until children.length).map { children.item(it) }.single { it.nodeName == "version" }
        return version.textContent.trim()
    }
}
