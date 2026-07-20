package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.Tag

/** STUB — replaced by the real fold in the next commit. */
@Suppress("UNUSED_PARAMETER")
public fun discoveryRoster(
    sources: List<PeerDiscoverySource>,
    scope: CoroutineScope,
): StateFlow<Set<Tag>> = MutableStateFlow<Set<Tag>>(emptySet()).asStateFlow()
