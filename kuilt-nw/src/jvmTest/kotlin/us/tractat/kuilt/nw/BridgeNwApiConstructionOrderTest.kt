@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher is the eager-dispatch rig

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BridgeNwApi]'s constructor must not start a coroutine that reads a field the constructor has not
 * yet initialised (#2482) — the [NwSeamConstructionOrderTest] rig applied to the JVM bridge.
 *
 * ## Why this class, when #2462 was closed twice
 * #2462's class is closed for the *property-initialiser* shape: `NwSeamConstructionOrderTest` pins
 * `NwSeam` with an eager dispatcher, and `forbidCoroutineLaunchInPropertyInitializer` (#2465) fails
 * the build on a `launch` written as a class-member property initialiser anywhere in production
 * source. Neither reaches this class. The build guard decides *property initialisers only*, so an
 * `init { }` block is out of scope regardless of where it sits; the test covers `NwSeam` alone.
 *
 * [BridgeNwApi] starts four drain coroutines from an `init { }` block that is **not** last in the
 * class body. Kotlin runs `init` blocks and property initialisers in a single declaration-order pass,
 * so those bodies may only touch state declared *above* the block — exactly the constraint #2462
 * broke, one construct over. It holds today only because everything below the block is a function or
 * the `companion object`; that is a **positional** safety, and #2464's KDoc argues explicitly why
 * positional safety is not enough:
 *
 * > Hoisting `watchdogWake` above the launch also fixes the instance, and leaves a **pairwise**
 * > constraint standing … re-checked on every future edit, with nothing to catch a violation — which
 * > is how the defect arrived in the first place.
 *
 * The hazard is not hypothetical for this class in particular: its drain coroutines run on the
 * injected [kotlin.coroutines.CoroutineContext], which is [kotlinx.coroutines.Dispatchers.Default] in
 * production, so an eager dispatch really can run a drain body before the constructor returns.
 *
 * ## Why an eager dispatcher, and why that is the whole rig
 * Every other `BridgeNwApi` test injects a [kotlinx.coroutines.test.StandardTestDispatcher], which
 * **defers** a `CoroutineStart.DEFAULT` body until the scheduler is pumped — long after the
 * constructor returned. That is why a whole suite can stay green over a broken constructor.
 * [UnconfinedTestDispatcher] reports `isDispatchNeeded == false`, so a `launch` runs its body
 * **inline, on the constructing thread, at the launch site** — the same window a real dispatcher opens
 * on another thread, but deterministic. No production dispatcher and no real threads are involved, and
 * no dylib either: [FakeNwNativeLib] is the same in-process fake that backs [BridgeNwApiTest].
 *
 * The first assertion is the **rig check**: it fails if the injected context ever stops being eager, so
 * a future edit that swaps it for a standard dispatcher reddens here instead of silently turning this
 * test vacuous. It builds its scope with the same expression [BridgeNwApi] does — `CoroutineScope(
 * SupervisorJob() + dispatcher)` — because the bridge's own scope is private, so the closest available
 * check is that *that construction* over *this context* dispatches eagerly.
 *
 * ## What it covers, and what it does not
 * It covers **every** coroutine [BridgeNwApi]'s constructor starts, not just the byte drain: any of
 * them dereferencing a not-yet-initialised field surfaces as a throwable on the handler, because the
 * bridge's scope carries a [SupervisorJob] and the failure of a launched child therefore reaches the
 * [CoroutineExceptionHandler] rather than the constructor's caller — the object is built successfully
 * with a dead drain. It does not cover a field read that happens after the first suspension point (by
 * then the constructor has returned on any dispatcher), and it cannot see the same hazard in a
 * *different* class: the general form is a build guard, and #2482 records why the existing one does not
 * yet reach an `init { }` block.
 */
class BridgeNwApiConstructionOrderTest {

    @Test
    fun constructionStartsNoCoroutineThatReadsAnUninitialisedField() = runTest {
        val escaped = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> escaped += failure }
        // No Job: BridgeNwApi supplies its own SupervisorJob, exactly as it does in production.
        val eagerContext = UnconfinedTestDispatcher(testScheduler) + handler

        // Rig check: this test is only meaningful while that context, wrapped the way BridgeNwApi wraps
        // it, runs a launched body INLINE.
        var ranInline = false
        CoroutineScope(SupervisorJob() + eagerContext).launch { ranInline = true }
        assertTrue(
            ranInline,
            "rig broken: `CoroutineScope(SupervisorJob() + dispatcher)` — BridgeNwApi's own scope " +
                "expression — must run a launched body inline at the launch site, or nothing in this " +
                "test can observe a constructor/coroutine race",
        )

        val api = BridgeNwApi(FakeNwNativeLib(), FakeNwNativeLib.HOST, eagerContext)
        api.close()

        assertEquals(
            emptyList(),
            escaped.map { "${it::class.simpleName}: ${it.message}" },
            "a coroutine started by BridgeNwApi's init block failed during construction — a field it " +
                "reads is declared BELOW that block, so the block is no longer safe in declaration " +
                "order (#2482). Move the field above the init block only if you also move the init " +
                "block last: hoisting one field fixes this instance and leaves the pairwise constraint " +
                "standing (#2464).",
        )
    }
}
