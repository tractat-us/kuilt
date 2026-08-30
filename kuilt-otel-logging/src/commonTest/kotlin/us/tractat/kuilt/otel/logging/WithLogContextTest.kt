package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The slot contract behind [withLogContext], mirroring `WithActiveTraceTest` for the
 * trace slot: what the synchronous capture edge reads, and when.
 *
 * The end-to-end consequences — which record gets which attributes — are pinned by
 * `LogContextPerScopeTest`; this file pins the mechanism those rest on, so a slot
 * regression names itself instead of surfacing as a mis-stamped record.
 */
class WithLogContextTest {
    @Test
    fun scopeSetsSlotInsideAndRestoresOutside() = runTest {
        assertTrue(currentLogContext().isEmpty())
        withLogContext(mapOf("session.id" to "A")) {
            // The synchronous read the capture edge performs must see the binding here.
            assertEquals(mapOf("session.id" to "A"), currentLogContext())
        }
        assertTrue(currentLogContext().isEmpty(), "the binding must not outlive its block")
    }

    @Test
    fun nestedScopesMergeAndRestore() = runTest {
        withLogContext(mapOf("session.id" to "outer", "device.role" to "host")) {
            withLogContext(mapOf("session.id" to "inner")) {
                assertEquals(mapOf("session.id" to "inner", "device.role" to "host"), currentLogContext())
            }
            assertEquals(mapOf("session.id" to "outer", "device.role" to "host"), currentLogContext())
        }
        assertTrue(currentLogContext().isEmpty())
    }

    @Test
    fun theVarargFormIsTheSameBinding() = runTest {
        withLogContext("session.id" to "A", "device.role" to "host") {
            assertEquals(mapOf("session.id" to "A", "device.role" to "host"), currentLogContext())
        }
    }
}
