@file:OptIn(ExperimentalForeignApi::class)

package spike.nw

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.NW_PARAMETERS_DISABLE_PROTOCOL
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_start
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_start
import platform.Network.nw_listener_create
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_include_peer_to_peer
import platform.darwin.dispatch_queue_create

/**
 * Phase-0 cinterop compile probe. NOT behaviourally complete — its only job is
 * to prove the `platform.Network` C surface, block-as-lambda handler bridging,
 * and `dispatch_queue` interop all COMPILE for iosArm64. TLS-PSK is added in a
 * second iteration once this baseline links.
 */
public class SpikeNw {
    private val queue = dispatch_queue_create("us.tractat.spike.nw", null)

    public fun startHost(serviceType: String) {
        val params = nw_parameters_create_secure_tcp(
            NW_PARAMETERS_DISABLE_PROTOCOL, // no TLS for the first probe
            NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        // includePeerToPeer place 1 of 3: the listener params.
        nw_parameters_set_include_peer_to_peer(params, true)

        val listener = nw_listener_create(params)
        nw_listener_set_queue(listener, queue)
        nw_listener_set_advertise_descriptor(
            listener,
            nw_advertise_descriptor_create_bonjour_service("spike", serviceType, null),
        )
        // Block handler bridged as a plain Kotlin lambda (the key K/N claim to prove).
        nw_listener_set_state_changed_handler(listener) { _, _ -> }
        nw_listener_set_new_connection_handler(listener) { connection ->
            nw_connection_set_queue(connection, queue)
            nw_connection_start(connection)
        }
        nw_listener_start(listener)
    }

    public fun startBrowse(serviceType: String) {
        val params = nw_parameters_create_secure_tcp(
            NW_PARAMETERS_DISABLE_PROTOCOL,
            NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        // includePeerToPeer place 2 of 3: the browser params.
        nw_parameters_set_include_peer_to_peer(params, true)

        val descriptor = nw_browse_descriptor_create_bonjour_service(serviceType, null)
        val browser = nw_browser_create(descriptor, params)
        nw_browser_set_queue(browser, queue)
        nw_browser_set_browse_results_changed_handler(browser) { _, _, _ -> }
        nw_browser_start(browser)
    }
}
