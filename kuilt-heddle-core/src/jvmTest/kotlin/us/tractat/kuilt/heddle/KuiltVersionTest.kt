package us.tractat.kuilt.heddle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This module's [KuiltVersion.VERSION] is the version the build publishes it under (#3323).
 *
 * `:kuilt-heddle-core` carries its own copy because it may not depend on `:kuilt-core`, which
 * carries the other; this is the same test as `:kuilt-core`'s, against this module's POM. The
 * reference is the POM the `kotlinMultiplatform` publication writes, handed over by the build as
 * `kuilt.publishedPom`, rather than `project.version` — so a build that ever sets the publication's
 * coordinates somewhere other than the project version reds here instead of shipping a value that
 * names a different release. Run with `-Pversion=<anything>` and both sides move together; a
 * generator that ignored the override would red.
 *
 * What CI proves: the PR build runs this only at the default `<line>.0-dev`. The publish jobs pass
 * `-Pversion` (a snapshot's `<line>.0-dev.<run>`, a tag's `x.y.z`) but run no tests, so the override
 * path is proven by a local `-Pversion=…` run, not by CI.
 *
 * JVM only: the value is one generated `commonMain` file every target compiles, so there is no
 * per-target value to disagree, and the POM is a file only a JVM test can read.
 */
class KuiltVersionTest {

    @Test
    fun `the value is the version the published POM declares`() {
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
