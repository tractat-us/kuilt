package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import us.tractat.kuilt.core.Tag

/**
 * Merge several discovery feeds into one live roster of who this peer can currently see.
 *
 * A phone browsing for nearby games over both Bonjour (mDNS) and Apple Multipeer has two
 * separate feeds of "someone just appeared" / "someone just left". This folds all of them —
 * [sources] — into a single set that a lobby UI can render directly, so you don't hand-write
 * the merge each time. Every [PeerDiscoverySource.discoveries] event adds a peer, every
 * [PeerDiscoverySource.departures] event removes one, keyed on [Tag.peerKey].
 *
 * The returned [StateFlow] claims only **this peer's current best view** — nothing more. It is
 * not an agreement, a vote, or a decision about who hosts. Two peers folding the same feeds can
 * hold different rosters at the same instant (a feed lags, a departure hasn't propagated, the
 * same physical peer carries different [Tag.peerKey]s across transports), so this view is not a
 * safe election input. Pick a host from [us.tractat.kuilt.core.Seam.peers] once connected — never
 * from a discovery roster. See `docs/discovery-bootstrap.md`.
 *
 * **Ghost caveat — the roster is add-only over a source that returns `emptyFlow()`.**
 * [PeerDiscoverySource.departures] has no default: a source with no leave signal (a fixed-roster
 * test fake, a platform stub, a browse API that only reports arrivals) must return `emptyFlow()`
 * explicitly. Over such a source a discovered peer is **never removed** — it lingers as a *ghost*
 * long after it is gone, and the set only grows. This is a real limitation of what that feed can
 * tell you, not a bug in the fold. The caveat applies to **exactly** those sources: read a
 * source's `departures()` body, and if it is `emptyFlow()`, expect a stale roster from it.
 *
 * The fold runs on [scope]: its backing coroutine is launched eagerly there and is cancelled when
 * [scope] is cancelled. [scope] is required — pass the caller's scope (in a test, a
 * `backgroundScope` bound to the test clock); this function never spins up a dispatcher of its own.
 *
 * @sample us.tractat.kuilt.core.discovery.sampleDiscoveryRoster
 */
public fun discoveryRoster(
    sources: List<PeerDiscoverySource>,
    scope: CoroutineScope,
): StateFlow<Set<Tag>> {
    val events: List<Flow<RosterEvent>> = sources.flatMap { source ->
        listOf(
            source.discoveries().map { tag -> RosterEvent.Arrived(tag) },
            source.departures().map { peerKey -> RosterEvent.Departed(peerKey) },
        )
    }
    return events.merge()
        // Immutable accumulator keyed on peerKey — thread-safe by construction, no shared
        // mutable state and no single-thread-dispatcher confinement.
        .scan(emptyMap<String, Tag>()) { roster, event ->
            when (event) {
                is RosterEvent.Arrived -> roster + (event.tag.peerKey to event.tag)
                is RosterEvent.Departed -> roster - event.peerKey
            }
        }
        .map { roster -> roster.values.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
}

private sealed interface RosterEvent {
    data class Arrived(val tag: Tag) : RosterEvent

    data class Departed(val peerKey: String) : RosterEvent
}
