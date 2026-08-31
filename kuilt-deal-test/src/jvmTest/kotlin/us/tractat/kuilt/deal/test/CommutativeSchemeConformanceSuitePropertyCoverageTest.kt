package us.tractat.kuilt.deal.test

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps [COMMUTATIVE_SCHEME_SUITE_PROPERTIES] honest by comparing it against the `@Test` methods
 * [CommutativeSchemeConformanceSuite] actually declares.
 *
 * `CommutativeSchemeConformanceSuiteEmptyDomainRigTest` drives that table to prove no property can
 * be reached with an empty domain, and the table is hand-written because neither Kotlin/Native nor
 * wasmJs can enumerate annotated methods. A hand-written table is exactly the thing that goes stale:
 * a tenth property added to the suite would simply not be exercised by the rig, and every test in
 * this module would stay green. Nothing about the rig itself can notice that — it can only run the
 * rows it was given, so its coverage has to be asserted from outside.
 *
 * **JVM-only, and that is a limitation of the platform rather than a choice.** The suite is
 * `commonMain` and runs everywhere; only this coverage check needs reflection, and one target
 * catching the drift is enough — the table it protects is shared by all of them.
 *
 * The comparison is set-equality in **both** directions on purpose: a missing row means an
 * unexercised property, and a stale row means the rig is asserting something about a method that no
 * longer exists. Matched on the annotation's simple name rather than on `org.junit.Test`, so a
 * future move to JUnit 5 changes nothing here.
 */
class CommutativeSchemeConformanceSuitePropertyCoverageTest {

    @Test
    fun theEmptyDomainRigCoversEverySuiteProperty() {
        val declared = CommutativeSchemeConformanceSuite::class.java.declaredMethods
            .filter { method -> method.annotations.any { it.annotationClass.simpleName == "Test" } }
            .map { it.name }
            .toSet()
        assertEquals(
            declared,
            COMMUTATIVE_SCHEME_SUITE_PROPERTIES.map { it.first }.toSet(),
            "COMMUTATIVE_SCHEME_SUITE_PROPERTIES has drifted from CommutativeSchemeConformanceSuite's " +
                "@Test surface. A property missing from the table is one the empty-domain rig never " +
                "exercises — give it a non-empty check and add its row, rather than editing this test.",
        )
    }
}
