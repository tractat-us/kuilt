package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability

/**
 * The floor [us.tractat.kuilt.core.Seam.capability] value: roleless and
 * [FabricAvailability.Unknown].
 *
 * A woven [us.tractat.kuilt.core.Seam] proves the fabric was *attemptable*; it does not prove the
 * device's path is up **right now**, and those are different questions once `Room.localFabric`
 * surfaces this to consumers (#1712). A fabric with no live path observer must say "I cannot tell"
 * rather than assert `Available` — an authoritative false negative is strictly worse than silence.
 * Fabrics with a real observer override [us.tractat.kuilt.core.Seam.capability] (see `NwSeam`, #1541).
 *
 * Exposed via [asStateFlow] — not cosmetic: without it a consumer could downcast the interface
 * default to [MutableStateFlow] and mutate the one global value shared by *every* [Seam].
 */
internal val StaticUnknownCapability: StateFlow<TransportCapability> =
    MutableStateFlow(
        TransportCapability(emptySet(), FabricAvailability.Unknown("no live path observer on this fabric")),
    ).asStateFlow()
