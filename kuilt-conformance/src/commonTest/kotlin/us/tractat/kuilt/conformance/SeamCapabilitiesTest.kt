package us.tractat.kuilt.conformance

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue

class SeamCapabilitiesTest {

    @Test
    fun fullHasEveryFlagTrue() {
        val capabilities = SeamCapabilities.FULL

        assertAll(
            { assertTrue(capabilities.ordersDelivery, "ordersDelivery") },
            { assertTrue(capabilities.reportsPeerLoss, "reportsPeerLoss") },
            { assertTrue(capabilities.terminatesIncomingOnClose, "terminatesIncomingOnClose") },
            { assertTrue(capabilities.staysTornAfterClose, "staysTornAfterClose") },
            { assertTrue(capabilities.throwsOnSendToTorn, "throwsOnSendToTorn") },
            { assertTrue(capabilities.supportsSendTo, "supportsSendTo") },
            { assertTrue(capabilities.securesTransport, "securesTransport") },
            { assertTrue(capabilities.meshDelivery, "meshDelivery") },
        )
    }
}
