@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val DETACH_SERVICE_TYPE = "_kuilt-conformance._tcp."
private const val DETACH_PORT = 19500

/**
 * Pins the delegate-detach half of #2409 against the **real** [ServiceDelegate].
 *
 * Every other iOS test on this branch drives a fake browser, so the production delegate is never
 * constructed and nothing exercises the lifetime bookkeeping that fix added. This one does: it
 * builds real [NSNetService] instances — which need no network, since `NSNetService(domain:type:
 * name:port:)` is a plain initialiser — hands them to the delegate through the browser callback, and
 * reads back `delegate` to see who still points at whom.
 *
 * **What it can and cannot show.** It shows the bookkeeping: that attaching records the service,
 * that terminal callbacks and teardown both give the pointer back, and that nothing attaches after a
 * detach. It does **not** show the half that matters most — that a delegate held only by a dead
 * session is collected, and that a live `NSNetService` calling into it would crash. That is a
 * property of Kotlin/Native's GC and Objective-C's `assign` semantics, argued in
 * [NetServiceBrowseSession]'s KDoc, and no unit test can produce it on demand. Read a green here as
 * "the reclamation happens", never as "the hazard is gone".
 *
 * `resolveWithTimeout` is genuinely called on these services. Nothing resolves — the run loop is not
 * pumped during a Kotlin/Native test — and `detachAll` stops each one, which is exactly the
 * production teardown path.
 */
class BonjourServiceDetachTest {

    @Test
    fun aResolvedServiceGivesItsDelegatePointerBackWithoutWaitingForTeardown() {
        val delegate = ServiceDelegate(ResolvingSink)
        val service = netService("peer-a")

        delegate.netServiceBrowser(NSNetServiceBrowser(), didFindService = service, moreComing = false)
        val attachedDuringResolve = service.delegate

        delegate.netServiceDidResolveAddress(service)

        assertAll(
            { assertSame(delegate, attachedDuringResolve, "the delegate must attach on found") },
            {
                assertNull(
                    service.delegate,
                    "a service that has reached a terminal callback will not call back again, so " +
                        "its pointer is given back then rather than accumulating until teardown",
                )
            },
        )
    }

    @Test
    fun teardownDetachesEveryServiceStillResolving() {
        val delegate = ServiceDelegate(ResolvingSink)
        val browser = NSNetServiceBrowser()
        val services = listOf(netService("peer-a"), netService("peer-b"), netService("peer-c"))
        services.forEach { delegate.netServiceBrowser(browser, didFindService = it, moreComing = false) }

        assertTrue(
            services.all { it.delegate != null },
            "precondition: every service must be attached before teardown, or this proves nothing",
        )

        delegate.detachAll()

        assertTrue(
            services.all { it.delegate == null },
            "no NSNetService may still point at the delegate once the session has been torn down — " +
                "that pointer is unowned, and the delegate is collectable from here",
        )
    }

    @Test
    fun aServiceArrivingAfterTeardownIsRefusedRatherThanAttached() {
        val delegate = ServiceDelegate(ResolvingSink)
        val browser = NSNetServiceBrowser()
        delegate.detachAll()

        val late = netService("peer-late")
        delegate.netServiceBrowser(browser, didFindService = late, moreComing = false)

        assertNull(
            late.delegate,
            "a didFindService already queued on the run loop at teardown must not hand out the very " +
                "pointer detachAll has just reclaimed — nothing would ever come back for it",
        )
    }

    /**
     * The sink a real feed presents: it asks for resolution, which is what makes the delegate attach
     * at all.
     *
     * A sink that never asked would attach nothing, and every assertion above would hold vacuously —
     * so [aResolvedServiceGivesItsDelegatePointerBackWithoutWaitingForTeardown] and
     * [teardownDetachesEveryServiceStillResolving] both assert the attachment happened first.
     */
    private object ResolvingSink : BonjourBrowseSink {
        override fun onFound(
            serviceName: String,
            requestResolve: () -> Unit,
        ) = requestResolve()

        override fun onResolved(record: BonjourRecord) = Unit

        override fun onLost(serviceName: String) = Unit
    }

    private fun netService(name: String): NSNetService =
        NSNetService(domain = "local.", type = DETACH_SERVICE_TYPE, name = name, port = DETACH_PORT)
}

/**
 * A peer advertising **no** TXT record must be dropped, not crash the browse.
 *
 * Found by [BonjourServiceDetachTest] rather than designed: resolving a service with no TXT threw
 * `NullPointerException` out of `netServiceDidResolveAddress`. `dictionaryFromTXTRecordData:`
 * returns `nil` for an empty or malformed record, the Kotlin/Native binding declares that return
 * non-null, and the interop not-null check turns it into an exception escaping into Objective-C —
 * killing the discovery flow of every collector on this fabric, for a condition wholly under a
 * remote peer's control.
 *
 * The rig is the *absence* of a call: the service is never given TXT data, which is the only
 * configuration in which the defect occurs. Setting a valid TXT record here — the natural way to
 * write this test — is exactly the fixture choice that would have hidden it.
 */
class BonjourEmptyTxtRecordTest {

    @Test
    fun aServiceWithNoTxtRecordResolvesToNoPeerIdInsteadOfThrowing() {
        var resolved: BonjourRecord? = null
        val delegate =
            ServiceDelegate(
                object : BonjourBrowseSink {
                    override fun onFound(
                        serviceName: String,
                        requestResolve: () -> Unit,
                    ) = requestResolve()

                    override fun onResolved(record: BonjourRecord) {
                        resolved = record
                    }

                    override fun onLost(serviceName: String) = Unit
                },
            )
        // Deliberately no setTXTRecordData: that absence IS the rig.
        val service =
            NSNetService(domain = "local.", type = DETACH_SERVICE_TYPE, name = "peer-no-txt", port = DETACH_PORT)

        delegate.netServiceDidResolveAddress(service)

        val record = resolved ?: error("a resolution must still be reported, with an empty TXT map")
        assertAll(
            { assertEquals("peer-no-txt", record.serviceName) },
            { assertTrue(record.txt.isEmpty(), "an unreadable TXT record yields no entries") },
            {
                assertNull(
                    record.txt[MDNSAdvertisement.TXT_KEY_PEER_ID],
                    "no peer id, so discoveries() drops it and departures() never learns a key — " +
                        "which is the behaviour always intended for a peer that advertises nothing",
                )
            },
        )
    }
}

/** Guards against the sink above being silently wrong about what a found service reports. */
class BonjourFoundNameTest {

    @Test
    fun theNameReportedToTheSinkIsTheServiceName() {
        var seen: String? = null
        val delegate =
            ServiceDelegate(
                object : BonjourBrowseSink {
                    override fun onFound(
                        serviceName: String,
                        requestResolve: () -> Unit,
                    ) {
                        seen = serviceName
                    }

                    override fun onResolved(record: BonjourRecord) = Unit

                    override fun onLost(serviceName: String) = Unit
                },
            )

        delegate.netServiceBrowser(
            NSNetServiceBrowser(),
            didFindService =
                NSNetService(domain = "local.", type = DETACH_SERVICE_TYPE, name = "peer-a", port = DETACH_PORT),
            moreComing = false,
        )

        assertEquals("peer-a", seen)
    }
}
