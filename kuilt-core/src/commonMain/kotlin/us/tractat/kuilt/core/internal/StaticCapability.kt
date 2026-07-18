package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability

/**
 * The floor [us.tractat.kuilt.core.Seam.capability] value: a live-but-roleless
 * [FabricAvailability.Available]. A woven [us.tractat.kuilt.core.Seam] exists, so its fabric is at
 * least attemptable. Fabrics that know their roles override
 * [us.tractat.kuilt.core.Seam.capability] with their own static/live StateFlow.
 *
 * Exposed via [asStateFlow] — not cosmetic: without it a consumer could downcast the interface
 * default to [MutableStateFlow] and mutate the one global value shared by *every* [Seam].
 */
internal val StaticAvailableCapability: StateFlow<TransportCapability> =
    MutableStateFlow(TransportCapability(emptySet(), FabricAvailability.Available)).asStateFlow()
