package us.tractat.kuilt.mdns

import kotlin.jvm.JvmInline

/**
 * Platform-neutral mDNS service-type wrapper.
 *
 * Holds the **canonical base form** — e.g. `"_myapp._tcp"` — and normalizes to
 * each mDNS API's required format internally. Callers never need to know which
 * suffix a given platform expects.
 *
 * ## Canonical form
 *
 * Pass the service type without any trailing dot or `.local.` suffix:
 * ```
 * MDNSServiceType("_myapp._tcp")
 * ```
 *
 * ## Per-platform normalization
 *
 * | Platform | API | Required format | Method |
 * |----------|-----|-----------------|--------|
 * | JVM | JmDNS | `"_myapp._tcp.local."` | [forJmDns] |
 * | Android | NsdManager | `"_myapp._tcp."` | [forNsd] |
 * | iOS / macOS | NSNetServiceBrowser | `"_myapp._tcp."` | [forNsNetServiceBrowser] |
 *
 * The normalization strips any existing suffix before appending the expected
 * one, so callers that accidentally pass a pre-suffixed string still get a
 * correct result.
 *
 * @property value The canonical base form (e.g. `"_myapp._tcp"`).
 */
@JvmInline
public value class MDNSServiceType(public val value: String) {
    /**
     * Returns the service type in JmDNS format: `"_myapp._tcp.local."`.
     *
     * JmDNS requires the `.local.` domain suffix appended to the service type.
     */
    public fun forJmDns(): String = "${canonicalBase()}.local."

    /**
     * Returns the service type in NsdManager format: `"_myapp._tcp."`.
     *
     * Android's NsdManager expects a trailing `.` but no `.local.` domain.
     */
    public fun forNsd(): String = "${canonicalBase()}."

    /**
     * Returns the service type in NSNetServiceBrowser format: `"_myapp._tcp."`.
     *
     * [NSNetServiceBrowser.searchForServicesOfType] takes the type without the
     * domain (the domain `"local."` is passed separately). This is the same
     * format as [forNsd].
     */
    public fun forNsNetServiceBrowser(): String = forNsd()

    /** Strips any existing platform suffix to produce the clean base form. */
    private fun canonicalBase(): String =
        value.removeSuffix(".local.").removeSuffix(".")
}
