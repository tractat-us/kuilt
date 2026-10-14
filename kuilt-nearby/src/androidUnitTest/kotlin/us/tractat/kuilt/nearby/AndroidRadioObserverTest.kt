package us.tractat.kuilt.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidRadioObserverTest {
    @Test
    fun closeBeforeStartIsPermanent() {
        val context = CountingRadioContext()
        val observer = AndroidRadioObserver(context)
        try {
            observer.close()
            observer.start()
            observer.close()
            observer.start()
            assertAll(
                { assertEquals(0, context.registrationHooks) },
                { assertEquals(0, context.receivers.size) },
                { assertEquals(0, context.unregistrations) },
            )
        } finally {
            observer.close()
        }
    }

    @Test
    fun repeatedStartAndCloseRegisterAndReleaseOnlyOnce() {
        val context = CountingRadioContext()
        val observer = AndroidRadioObserver(context)
        try {
            observer.start()
            observer.start()
            observer.close()
            observer.close()
            observer.start()
            assertAll(
                { assertEquals(1, context.registrationHooks) },
                { assertEquals(0, context.receivers.size) },
                { assertEquals(1, context.unregistrations) },
            )
        } finally {
            observer.close()
        }
    }

    @Test
    fun closeDuringRegistrationReleasesReceiverAfterRegistrationReturns() {
        val context = CountingRadioContext()
        val observer = AndroidRadioObserver(context)
        // Force close between the observer's decision to register and the platform's return.
        // This is the actual registration boundary, with no scheduling or timeout assumption.
        context.duringRegistration = { observer.close() }
        try {
            observer.start()
            assertAll(
                { assertEquals(1, context.registrationHooks, "registration interleaving fired") },
                { assertEquals(0, context.prematureUnregisters, "unregister preceded registration") },
                { assertEquals(0, context.receivers.size, "receiver survived close during registration") },
                { assertEquals(1, context.unregistrations, "receiver must be released exactly once") },
            )
        } finally {
            observer.close()
        }
    }
}

/** Records platform calls, including premature unregisters, so failures report assertions. */
internal class CountingRadioContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
    val receivers = mutableSetOf<BroadcastReceiver>()
    var duringRegistration: () -> Unit = {}
    var registrationHooks = 0
    var unregistrations = 0
    var prematureUnregisters = 0

    override fun getApplicationContext(): Context = this

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        registrationHooks++
        duringRegistration()
        if (receiver != null) receivers.add(receiver)
        return null
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        unregistrations++
        if (!receivers.remove(receiver)) prematureUnregisters++
    }
}
