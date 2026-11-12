package us.tractat.kuilt.heddle

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The guard that makes [LEDGER_COMPONENTS] and [SLOT_FINALS_COMPONENTS] **unforgettable**: each
 * component set is derived from the class's declared fields rather than trusted to a reader, so
 * adding a constructor parameter without extending the list fails here immediately, naming it.
 *
 * Without this, the walks in `EntitlementLedgerComponentTest` / `SlotFinalsComponentTest` prove only
 * that the components *someone listed* survive `equals` / `piece` / `join` — which is precisely the
 * assurance that was already available, and already false, when `transferRelocIn`/`transferRelocOut`
 * were added without extending `equals`/`hashCode`/`toString` (#2366).
 *
 * **Both classes, because this PR is itself the sibling-class case.** The `EntitlementLedger` guard
 * alone would not have noticed `SlotFinals` gaining `transfers`, whose hand-written
 * [SlotFinals.join] must enumerate every field and was guarded by nothing.
 *
 * ## What it still cannot see
 *
 * Stated rather than implied, because a guard read as total is worse than a guard read as partial.
 * It compares **declared field names**, so it misses a component folded into an existing field's
 * value type (widening `Gauge`, say — the field set is unchanged), and a field inherited from a
 * supertype (`declaredFields` is not `fields`; neither class has one today).
 *
 * JVM-only because reflection is: the property is a statement about the one shared `commonMain`
 * declaration, so proving it on a single target proves it everywhere.
 */
class EntitlementLedgerComponentCoverageTest {

    @Test
    fun everyDeclaredLatticeComponentIsWalkedByTheComponentSuite() {
        assertComponentsCover(
            owner = "EntitlementLedger",
            declared = declaredFieldsOf(EntitlementLedger::class.java),
            walked = LEDGER_COMPONENTS.map { it.name }.toSet(),
            listName = "LEDGER_COMPONENTS",
            consequence = "A new component must be added to LEDGER_COMPONENTS *and* to " +
                "equals/hashCode/toString/piece — a component `equals` cannot see is a component " +
                "whose patches Quilter silently discards (#2366).",
        )
    }

    @Test
    fun everyDeclaredSlotFinalsFieldIsWalkedByTheComponentSuite() {
        assertComponentsCover(
            owner = "SlotFinals",
            declared = declaredFieldsOf(SlotFinals::class.java),
            walked = SLOT_FINALS_COMPONENTS.map { it.name }.toSet(),
            listName = "SLOT_FINALS_COMPONENTS",
            consequence = "A new field must be added to SLOT_FINALS_COMPONENTS *and* to the " +
                "hand-written SlotFinals.join — every field there is optional at the constructor, " +
                "so a join that omits one compiles and silently lowers a recorded ack final.",
        )
    }

    /**
     * The opt-out is **exhausted**: every name declared not-a-component must still be a declared
     * field. Without this the map is a place to park a stale name, and a real component could later
     * be silenced by a leftover entry that happens to match it.
     *
     * ⚠ **Vacuous today, by construction and deliberately.** [NOT_A_COMPONENT] is empty, so this
     * loop runs zero iterations and passes trivially — it asserts nothing about the current build
     * and must not be read as evidence for one. It exists so that the *first* opt-out entry arrives
     * already guarded, rather than as a hole someone has to notice and close afterwards. Its
     * positive control is adding a bogus entry (`"nosuchfield" to "why"`) and watching it red.
     */
    @Test
    fun everyOptOutStillNamesADeclaredField() {
        for ((owner, cls) in mapOf(
            "EntitlementLedger" to EntitlementLedger::class.java,
            "SlotFinals" to SlotFinals::class.java,
        )) {
            val raw = cls.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
            for ((name, reason) in NOT_A_COMPONENT[owner].orEmpty()) {
                assertTrue(
                    name in raw,
                    "`$owner.$name` is opted out of the component walk (\"$reason\") but is no longer " +
                        "a declared field — drop the entry rather than leaving it to shadow a future one",
                )
            }
        }
    }

    private companion object {

        /**
         * Fields that are declared but are **not** lattice components, each with a mandatory reason.
         *
         * Empty today, and deliberately present anyway. "Every declared field is a component" is
         * true of both classes right now and will stop being true the first time someone writes a
         * `by lazy` cache: the delegate materialises as a declared field named `x${'$'}delegate`,
         * which `isSynthetic` does **not** cover. Without a place to say "not a component, because
         * it is a cache", the next author's cheapest way to a green build is to invent a
         * `LedgerComponent("x${'$'}delegate", …)` fixture — a bogus registration that makes the walk
         * assert something meaningless and, worse, teaches the file that the list is a formality.
         *
         * A reason is structurally required (it is the map's value), and
         * [everyOptOutStillNamesADeclaredField] keeps entries from going stale. This is an escape
         * hatch from a *coverage* guard, not from `equals`/`join`: nothing here may name a field
         * that participates in either.
         */
        val NOT_A_COMPONENT: Map<String, Map<String, String>> = mapOf(
            "EntitlementLedger" to emptyMap(),
            "SlotFinals" to emptyMap(),
        )

        fun declaredFieldsOf(cls: Class<*>): Set<String> {
            val owner = cls.simpleName
            return cls.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .filterNot { it in NOT_A_COMPONENT[owner].orEmpty() }
                .toSet()
        }

        /**
         * Assert the two sets agree, and — the part that makes a red *readable* — name the drift in
         * both directions. A bare `assertEquals` over two sixteen-element sets leaves the reader to
         * diff them by eye, which is how a positive control comes to look like a pass.
         */
        fun assertComponentsCover(
            owner: String,
            declared: Set<String>,
            walked: Set<String>,
            listName: String,
            consequence: String,
        ) {
            val unwalked = (declared - walked).sorted()
            val phantom = (walked - declared).sorted()
            if (unwalked.isEmpty() && phantom.isEmpty()) return
            fail(
                buildString {
                    append("$owner and $listName have diverged. ")
                    if (unwalked.isNotEmpty()) {
                        append("DECLARED BUT NOT WALKED: $unwalked — add ")
                        append(unwalked.joinToString { "`$it`" })
                        append(" to $listName. ")
                    }
                    if (phantom.isNotEmpty()) {
                        append("WALKED BUT NOT DECLARED: $phantom — $listName names ")
                        append(phantom.joinToString { "`$it`" })
                        append(", which $owner no longer declares. ")
                    }
                    append(consequence)
                },
            )
        }
    }
}
