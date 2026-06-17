@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuilterTestDispatcherGuardTest {

    private val ser = QuiltMessage.serializer(GCounter.serializer())

    /**
     * When strictTestGuard=true, constructing Quilter under a TestDispatcher
     * must throw with an actionable message mentioning TestDispatcher and Quilter.
     */
    @Test
    fun seamReplicatorUnderTestDispatcher_throwsWithActionableMessage_whenStrictGuardEnabled() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("guard-strict"))
            val config = QuilterConfig(strictTestGuard = true)

            val ex = assertFailsWith<IllegalStateException> {
                Quilter(
                    replica = ReplicaId(seam.selfId.value),
                    seam = seam,
                    initial = GCounter.ZERO,
                    messageSerializer = ser,
                    scope = backgroundScope,
                    config = config,
                )
            }

            assertTrue(
                "TestDispatcher" in ex.message!! || "virtual time" in ex.message!!,
                "Expected diagnostic to mention TestDispatcher or virtual time, got: ${ex.message}",
            )
            assertTrue(
                "Quilter" in ex.message!!,
                "Expected diagnostic to mention Quilter, got: ${ex.message}",
            )
        }

    /**
     * Default strictTestGuard=false must not throw — even under a TestDispatcher.
     * Existing tests remain unaffected.
     */
    @Test
    fun seamReplicatorUnderTestDispatcher_doesNotThrow_whenStrictGuardDisabled() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("guard-default"))
            val config = QuilterConfig() // strictTestGuard defaults to false

            // Must not throw — just emits a warning log
            Quilter(
                replica = ReplicaId(seam.selfId.value),
                seam = seam,
                initial = GCounter.ZERO,
                messageSerializer = ser,
                scope = backgroundScope,
                config = config,
            )
        }

    /**
     * `expectVirtualTime = true` must suppress the guard — no throw even when
     * `strictTestGuard = true`. The opt-out takes precedence.
     */
    @Test
    fun seamReplicatorUnderTestDispatcher_doesNotWarnOrThrow_whenExpectVirtualTimeIsTrue() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("guard-suppress"))
            val config = QuilterConfig(
                strictTestGuard = true,    // would normally throw
                expectVirtualTime = true,  // opt-out must take precedence
            )

            // Must not throw — expectVirtualTime = true short-circuits before the check.
            val replicator = Quilter(
                replica = ReplicaId(seam.selfId.value),
                seam = seam,
                initial = GCounter.ZERO,
                messageSerializer = ser,
                scope = backgroundScope,
                config = config,
            )

            assertNotNull(replicator)
        }
}
