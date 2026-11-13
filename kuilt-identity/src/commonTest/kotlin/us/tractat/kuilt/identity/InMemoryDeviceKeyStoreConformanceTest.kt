package us.tractat.kuilt.identity

import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture

/**
 * [InMemoryDeviceKeyStore] against the whole [DeviceKeyStoreConformanceSuite], on every target.
 *
 * It declares [DeviceKeyStoreFixture.KeepsNothing]: it has no medium, so every open is a new process
 * and a new device. The suite asserts that it really does forget, so the declaration is checked rather
 * than taken on trust. Every property `InMemoryDeviceKeyStoreTest` held by hand — the stable key, the
 * forced concurrent first calls, the newest grant per issuer and scope with its tie — is now a
 * property of the shared contract, stated here and on every platform store alike.
 */
class InMemoryDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture =
        DeviceKeyStoreFixture.KeepsNothing { beforeKeyIsMade -> InMemoryDeviceKeyStore(beforeKeyIsMade) }
}
