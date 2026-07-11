@file:OptIn(ExperimentalForeignApi::class)

package spike.nw

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.NW_PARAMETERS_DISABLE_PROTOCOL
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_parameters_t
import platform.Network.nw_protocol_options_t
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_protocol_options_add_pre_shared_key
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
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

    /**
     * TLS-PSK parameters. Proves the fiddliest C-API path: the `configure_tls`
     * block copies the sec-protocol options off the TLS options and installs a
     * pre-shared key. PSK + identity become `dispatch_data_t` (null destructor →
     * dispatch copies the bytes, so the pinned buffer needn't outlive the call).
     */
    public fun secureParams(psk: ByteArray, pskIdentity: ByteArray): nw_parameters_t? {
        val params = nw_parameters_create_secure_tcp(
            configure_tls = { options: nw_protocol_options_t? ->
                val sec = nw_tls_copy_sec_protocol_options(options)
                sec_protocol_options_add_pre_shared_key(
                    sec,
                    toDispatchData(psk),
                    toDispatchData(pskIdentity),
                )
            },
            configure_tcp = NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        // includePeerToPeer place 3 of 3: the (secure) connection/listener params.
        nw_parameters_set_include_peer_to_peer(params, true)
        return params
    }

    private fun toDispatchData(bytes: ByteArray): dispatch_data_t =
        bytes.usePinned { pinned ->
            dispatch_data_create(pinned.addressOf(0), bytes.size.convert(), null, null)
        }
}
