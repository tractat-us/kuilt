package us.tractat.kuilt.conformance

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM meta-test that pins the single source of truth for the [SeamCapabilities]
 * flag list to the data class itself.
 *
 * `commonMain` has no reflection, so the flag list ([SeamCapabilities.FLAGS]) is
 * hand-maintained — and `falseFlags()` plus the capability-matrix render columns
 * both derive from it. That collapses the two parallel lists that used to exist,
 * but leaves one residual silent failure mode: adding a **further** boolean property
 * to the data class (and to `FULL`, which the compiler forces) while forgetting to
 * extend `FLAGS`. The new flag would then be invisible to `falseFlags()`, so a
 * `false` value for it would be **undeclarable** — it would slip past
 * `SeamConformanceSuite.everyFalseCapabilityDeclaresAGap`, which only iterates
 * `falseFlags()`.
 *
 * JVM reflection can enumerate the data class's declared boolean properties even
 * though common reflection cannot, so this test makes that omission fail loudly:
 * `FLAGS` (and therefore `falseFlags()`) must cover exactly the declared boolean
 * properties.
 */
class SeamCapabilitiesReflectionTest {

    /** Every boolean property the data class declares, by name — the ground truth. */
    private val declaredBooleanProperties: Set<String> =
        SeamCapabilities::class.java.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .map { it.name }
            .toSet()

    @Test
    fun flagListCoversEveryDeclaredBooleanProperty() {
        val allFalse = SeamCapabilities(
            ordersDelivery = false,
            reportsPeerLoss = false,
            terminatesIncomingOnClose = false,
            staysTornAfterClose = false,
            throwsOnSendToTorn = false,
            supportsSendTo = false,
            securesTransport = false,
            meshDelivery = false,
            reportsLiveCapability = false,
            collapsesPeersOnTear = false,
        )

        assertAll(
            {
                assertEquals(
                    declaredBooleanProperties,
                    SeamCapabilities.FLAGS.map { it.first }.toSet(),
                    "SeamCapabilities.FLAGS (the single source for falseFlags() and the " +
                        "capability-matrix columns) must list exactly the data class's declared " +
                        "boolean properties — a new flag added to the data class but omitted here " +
                        "would be undeclarable and escape everyFalseCapabilityDeclaresAGap",
                )
            },
            {
                assertEquals(
                    declaredBooleanProperties,
                    allFalse.falseFlags(),
                    "falseFlags() on an all-false value must enumerate every declared boolean property",
                )
            },
        )
    }
}
