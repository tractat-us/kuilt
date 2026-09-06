package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.TransportCapability
import kotlin.test.assertTrue

/**
 * Shared machinery for the #2601 positive-control rigs — [SymmetricLifecycleObligationRigTest],
 * [SymmetricDeliveryObligationRigTest], [SymmetricRefusalObligationRigTest] and
 * [SymmetricSelfDialObligationRigTest].
 *
 * All four rigs make the same move: break the **joiner** end of a reference pair, drive one
 * [SeamConformanceSuite] obligation body against it, and assert the red lands on the joiner arm that
 * names the defect rather than anywhere else in the same obligation. The mechanism is identical in
 * each, so it lives here once — a second private copy would be the thing this file exists to avoid,
 * and a drift between the copies would silently weaken whichever rig kept the weaker one.
 */

/**
 * Applies [decorate] to the seam handed back by `weave(Rendezvous.Existing)` — the joiner — and
 * nothing else.
 *
 * Implemented rather than delegated (`Loom by inner`) on purpose, the reason
 * [JoinerRosterObligationRigTest] gives: [Loom.host] and [Loom.join] are *default* members, so
 * delegation would forward them to the inner loom's own `weave` and route straight past this
 * override.
 */
internal class DecoratingJoinerLoom(
    private val inner: Loom,
    private val decorate: (Seam) -> Seam,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val seam = inner.weave(rendezvous)
        return if (rendezvous is Rendezvous.Existing) decorate(seam) else seam
    }

    override fun capability(): TransportCapability = inner.capability()
}

/**
 * A joiner whose `state` never leaves [SeamState.Woven] — the shape `MuxBase.ChannelView` had before
 * #2372, where `state` delegated to a base connection that is still alive.
 *
 * The real close still runs underneath, so the joiner genuinely tears; only what it *reports* is
 * wrong, which is the defect a consumer meets. Two rigs need it — it is the stimulus for the
 * **precondition** arm of every row that asks a question about a `Torn` joiner — so it lives here
 * rather than once per rig.
 */
internal class SeamWhoseStateNeverTears(delegate: Seam) : Seam by delegate {
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
}

/**
 * Assert the red carries [fragment] — the message of the specific joiner arm under test. A rig that
 * reddened on the host arm, or on a precondition, would tick a "did it go red?" box while proving
 * nothing, so the *shape* of the red is asserted rather than merely its presence.
 */
internal fun assertRedOn(fragment: String, failure: Throwable) {
    assertTrue(
        fragment in failure.message.orEmpty(),
        "the red must come from the JOINER arm specifically (looking for \"$fragment\"); " +
            "got: ${failure.message}",
    )
}

/**
 * Assert exactly [expected] arms of an [us.tractat.kuilt.test.assertAll] batch reddened. A broader
 * red means the rig broke the pair rather than the one end it names — which would make every
 * message-shape assertion above it true for the wrong reason.
 */
internal fun assertArmCount(expected: Int, failure: Throwable) {
    assertTrue(
        "$expected assertion(s) failed" in failure.message.orEmpty(),
        "exactly $expected arm(s) may red — a broader red means the rig broke the pair rather " +
            "than the one end it names; got: ${failure.message}",
    )
}
