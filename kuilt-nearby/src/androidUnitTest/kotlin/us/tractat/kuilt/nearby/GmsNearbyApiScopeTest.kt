package us.tractat.kuilt.nearby

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [ReadyGoogleApiAvailability::class])
class GmsNearbyApiScopeTest {
    @Test
    fun cancelledOwnerBeforeConstructionRegistersNothing() = runTest {
        val owner = Job().apply { cancel() }
        val context = CountingRadioContext()
        val api = GmsNearbyApi(context, CoroutineScope(coroutineContext + owner))
        api.close()
        assertAll(
            { assertEquals(0, context.registrationHooks) },
            { assertEquals(0, context.unregistrations) },
            { assertNull(api.radioState.value) },
        )
    }

    @Test
    fun factoryForwardsOwnerAndReleasesItsHiddenObserver() = runTest {
        val owner = Job()
        val context = CountingRadioContext()
        try {
            nearbyLoom(context, CoroutineScope(coroutineContext + owner))
            assertEquals(1, context.receivers.size, "factory observes eagerly without collectors")
            owner.cancel()
            assertAll(
                { assertEquals(0, context.receivers.size) },
                { assertEquals(1, context.unregistrations) },
                { assertEquals(0, context.prematureUnregisters) },
            )
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun cancelledOwnerBeforeFactoryRegistersNothing() = runTest {
        val owner = Job().apply { cancel() }
        val context = CountingRadioContext()
        nearbyLoom(context, CoroutineScope(coroutineContext + owner))
        assertAll(
            { assertEquals(0, context.registrationHooks) },
            { assertEquals(0, context.receivers.size) },
        )
    }

    @Test
    fun cancellationInsideRegistrationDefersUnregisterUntilReturn() = runTest {
        val owner = Job()
        val context = CountingRadioContext().apply { duringRegistration = { owner.cancel() } }
        val api = GmsNearbyApi(context, CoroutineScope(coroutineContext + owner))
        try {
            assertAll(
                { assertEquals(1, context.registrationHooks, "cancellation interleaving fired") },
                { assertEquals(0, context.prematureUnregisters) },
                { assertEquals(0, context.receivers.size) },
                { assertEquals(1, context.unregistrations) },
                { assertNull(api.radioState.value, "cancelled registration must not publish") },
            )
            api.close()
            api.close()
            assertEquals(1, context.unregistrations)
        } finally {
            owner.cancel()
            api.close()
        }
    }

    @Test
    fun explicitCloseThenCancellationUnregistersOnceAndDetachesChild() = runTest {
        val owner = Job()
        val context = CountingRadioContext()
        val api = GmsNearbyApi(context, CoroutineScope(coroutineContext + owner))
        try {
            api.close()
            api.close()
            assertAll(
                { assertTrue(owner.isActive, "closing observation does not cancel its owner") },
                { assertFalse(owner.children.any(), "close detaches the observer from its owner") },
            )
            owner.cancel()
            assertAll(
                { assertEquals(0, context.receivers.size) },
                { assertEquals(1, context.unregistrations) },
                { assertEquals(0, context.prematureUnregisters) },
            )
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun cancellationDoesNotWaitForOtherChildrenAndCloseRemainsIdempotent() = runTest {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val release = CompletableDeferred<Unit>()
        val sibling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
        }
        val context = CountingRadioContext()
        val api = GmsNearbyApi(context, scope)
        try {
            owner.cancel()
            assertAll(
                { assertFalse(sibling.isCompleted, "sibling rig is still holding the parent open") },
                { assertFalse(owner.isCompleted, "parent completion cannot explain cleanup") },
                { assertEquals(0, context.receivers.size) },
                { assertEquals(1, context.unregistrations) },
            )
            api.close()
            api.close()
            assertEquals(1, context.unregistrations)
        } finally {
            release.complete(Unit)
            api.close()
            owner.cancel()
            sibling.join()
        }
    }

    @Test
    fun eagerStateAndLiveBroadcastsReachSeamCapability() = runTest {
        val owner = Job()
        val context = CountingRadioContext()
        val bluetooth = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(bluetooth).setState(BluetoothAdapter.STATE_ON)
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        val api = GmsNearbyApi(context, CoroutineScope(coroutineContext + owner))
        // Real Android radio observation, fake connection operations: no Nearby hardware or RPC.
        val seamApi = object : NearbyApi by FakeNearbyApi(FakeNearbyRadio()) {
            override val radioState = api.radioState
        }
        val seam = NearbySeam(
            selfId = PeerId("self"),
            endpointPeers = mutableMapOf(),
            endpointPeersMutex = Mutex(),
            registry = registryOver(PeerId("self"), emptyMap()),
            api = seamApi,
            weavePeers = MutableStateFlow(emptySet()),
            scope = CoroutineScope(backgroundScope.coroutineContext + Job()),
            msgIdCounter = MsgIdCounter(),
        )
        try {
            assertAll(
                { assertEquals(1, context.receivers.size) },
                { assertEquals(NearbyRadioState(NearbyRadioStatus.On, NearbyRadioStatus.On), api.radioState.value) },
                { assertIs<FabricAvailability.Available>(seam.capability.value.availability) },
            )
            val receiver = context.receivers.single()
            shadowOf(bluetooth).setState(BluetoothAdapter.STATE_OFF)
            shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
            receiver.onReceive(context, Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
            testScheduler.runCurrent()
            assertIs<FabricAvailability.Unavailable>(seam.capability.value.availability)
            shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED)
            receiver.onReceive(context, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION))
            testScheduler.runCurrent()
            assertIs<FabricAvailability.Available>(seam.capability.value.availability)
            // The seam outlives its owner scope here, deliberately: observation ends, the seam does not.
            owner.cancel()
            shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
            receiver.onReceive(context, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION))
            testScheduler.runCurrent()
            assertAll(
                { assertEquals(0, context.receivers.size) },
                { assertNull(api.radioState.value, "close withdraws the reading; a queued callback cannot republish") },
                {
                    assertIs<FabricAvailability.Unknown>(
                        seam.capability.value.availability,
                        "a seam outliving observation must not keep the last Available",
                    )
                },
            )
        } finally {
            seam.close()
            api.close()
            owner.cancel()
        }
    }
}

/** Supplies GMS availability alongside Robolectric services to exercise the production observer. */
@Implements(GoogleApiAvailability::class)
class ReadyGoogleApiAvailability {
    @Implementation
    fun isGooglePlayServicesAvailable(context: Context): Int = 0
}
