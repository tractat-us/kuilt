package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The list of engines that confirm a drain goes by class name, and a name fails the safe way: a Ktor
 * rename turns every `Drained` into `Unsupported`, and nothing would say so. This says so. It builds the
 * engine and opens no socket, so it pins the name and nothing about how the engine behaves.
 */
class DarwinEngineDrainTest {
    @Test
    fun theDarwinEngineIsOneThatConfirmsADrain() {
        val client = HttpClient(Darwin)
        try {
            assertTrue(client.engine.canConfirmADrain(), "this engine is called ${client.engine::class.simpleName}")
        } finally {
            client.close()
        }
    }
}
