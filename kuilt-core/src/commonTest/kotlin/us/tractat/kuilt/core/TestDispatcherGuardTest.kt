@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestDispatcherGuardTest {

    @Test
    fun guard_throwsIllegalStateException_whenStrictAndUnderTestDispatcher() =
        runTest(UnconfinedTestDispatcher()) {
            val ex = assertFailsWith<IllegalStateException> {
                checkNotUnderTestDispatcher(
                    scope = backgroundScope,
                    typeName = "Widget",
                    substitute = "FakeWidget",
                    strict = true,
                    expectVirtualTime = false,
                )
            }
            assertTrue(
                "TestDispatcher" in ex.message!! || "virtual time" in ex.message!!,
                "Diagnostic must mention TestDispatcher or virtual time: ${ex.message}",
            )
            assertTrue("Widget" in ex.message!!, "Diagnostic must name the type: ${ex.message}")
            assertTrue("FakeWidget" in ex.message!!, "Diagnostic must name the substitute: ${ex.message}")
        }

    @Test
    fun guard_printsWarningAndDoesNotThrow_whenNotStrictAndUnderTestDispatcher() =
        runTest(UnconfinedTestDispatcher()) {
            // Must not throw — just emits a warning
            checkNotUnderTestDispatcher(
                scope = backgroundScope,
                typeName = "Widget",
                substitute = "FakeWidget",
                strict = false,
                expectVirtualTime = false,
            )
        }

    @Test
    fun guard_doesNothing_whenExpectVirtualTimeIsTrue_evenWhenStrict() =
        runTest(UnconfinedTestDispatcher()) {
            // strict = true would normally throw; expectVirtualTime = true must take precedence
            checkNotUnderTestDispatcher(
                scope = backgroundScope,
                typeName = "Widget",
                substitute = "FakeWidget",
                strict = true,
                expectVirtualTime = true,
            )
        }

    @Test
    fun guard_doesNothing_whenScopeUsesNonTestDispatcher() {
        // Deliberately using a real dispatcher: this scope is a structural argument only — no
        // coroutines are launched in it, so there is no virtual-clock decoupling risk. The point
        // of this test is to confirm the guard does NOT fire when the dispatcher is not a TestDispatcher.
        // strict = true means if detection fired it would throw — a false-positive would be caught here.
        val realScope = CoroutineScope(Dispatchers.Unconfined)
        checkNotUnderTestDispatcher(
            scope = realScope,
            typeName = "Widget",
            substitute = "FakeWidget",
            strict = true,
            expectVirtualTime = false,
        )
    }
}
