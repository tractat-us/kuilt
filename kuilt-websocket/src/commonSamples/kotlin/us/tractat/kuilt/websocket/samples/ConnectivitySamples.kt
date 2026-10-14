package us.tractat.kuilt.websocket.samples

import io.ktor.client.HttpClient
import us.tractat.kuilt.websocket.ConnectivityObserver
import us.tractat.kuilt.websocket.KtorClientLoom

/** Supply a platform observer and keep it alive until every loom using it is done. */
public fun websocketWithConnectivity(
    httpClient: HttpClient,
    connectivity: ConnectivityObserver,
): KtorClientLoom = KtorClientLoom(httpClient, connectivity = connectivity)
