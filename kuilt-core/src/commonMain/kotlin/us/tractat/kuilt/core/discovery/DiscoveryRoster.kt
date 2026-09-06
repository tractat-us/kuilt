package us.tractat.kuilt.core.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
 * **One feed's failure costs you that feed, and nothing else.** Each source's `discoveries()` and
 * `departures()` are isolated separately, so a transport that dies mid-session degrades to *"that
 * feed stopped reporting"* rather than taking the whole fold down with it. Without the isolation a
 * single throw cancels the merged flow, kills the one backing coroutine, and freezes this
 * [StateFlow] at its last value **permanently** — every healthy source silently stops being
 * observed too, with the roster still looking plausible (#1904). A failed feed cannot be restarted:
 * a source is asked for its flow exactly once.
 *
 * **A failed feed's peers linger — the roster is best-view, not liveness.** When a source's
 * `discoveries()` dies, the peers it already contributed stay in the set. They are not synthesised
 * away, because nothing observed them leave: dropping them would assert a departure this peer never
 * saw, and would turn one transient transport fault into a mass exodus in the UI. This is exactly
 * the **ghost caveat** below, reached by a second route — a failed feed *is* a feed that can no
 * longer report departures — so the same reading applies: those entries are what a dead feed last
 * knew, not a claim that anyone is still there.
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
 * @param onSourceFailure invoked with the source whose feed died and the throwable that killed it.
 *   `:kuilt-core` is logger-free by contract, so this callback is the **only** signal a dead feed
 *   can produce — absent it the isolation above would be silent, which would be a worse diagnosis
 *   than the freeze it replaces (a freeze at least reaches the scope's `CoroutineExceptionHandler`).
 *   The throwable is passed whole rather than summarised: it came out of the consumer's own source
 *   implementation, so its trace *is* the diagnosis — including which of the two feeds died, which
 *   is why that is not a separate parameter. Best-effort and non-suspending; whatever it throws is
 *   absorbed, since a consumer's logger must never be able to kill the fold this exists to protect.
 *   Defaulted for the same reason [us.tractat.kuilt.core.fabric.acceptPump]'s is — this is a
 *   diagnostic hook on an existing primitive, not a new obligation on its callers. Whether a failed
 *   source should be observable in the roster's own *type*, so a lobby can render "mDNS is down",
 *   is a separate design question this deliberately does not answer.
 *
 * @sample us.tractat.kuilt.core.discovery.sampleDiscoveryRoster
 * @sample us.tractat.kuilt.core.discovery.sampleDiscoveryRosterSourceFailure
 */
public fun discoveryRoster(
    sources: List<PeerDiscoverySource>,
    scope: CoroutineScope,
    onSourceFailure: (PeerDiscoverySource, Throwable) -> Unit = { _, _ -> },
): StateFlow<Set<Tag>> {
    // `discoveries()`/`departures()` are called eagerly, here, in the caller's own frame — so a
    // source that cannot even produce a flow throws out of `discoveryRoster` itself. That failure is
    // loud and immediate by construction, and is deliberately not caught: it is a broken source, not
    // a feed that died mid-session.
    val events: List<Flow<RosterEvent>> = sources.flatMap { source ->
        listOf(
            source.discoveries().map { tag -> RosterEvent.Arrived(tag) }.isolate(source, onSourceFailure),
            source.departures().map { key -> RosterEvent.Departed(key) }.isolate(source, onSourceFailure),
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

/**
 * Complete this one feed on failure instead of failing the merge, reporting the cause to [onFailure].
 *
 * Applied **after** the `map`, not before it, so nothing this per-feed branch does can escape into
 * the merge — the mapping is total today, and a guard placed where the future non-total version
 * would need it costs nothing.
 *
 * ### Why there is no `ensureActive()` here
 * This repo's standing hazard is that a `catch` swallows cancellation, and the mandated
 * discriminator is `currentCoroutineContext().ensureActive()` — because *type* cannot separate "my
 * job was cancelled" (must propagate) from "a callee minted a `CancellationException` and threw it
 * at me" (this feed's failure). [kotlinx.coroutines.flow.catch] is the one place that discriminator
 * is **already built in**: it rethrows when the throwable is this coroutine's own cancellation cause
 * and catches otherwise, which is the same runtime test rather than a type test. Adding
 * `ensureActive()` inside the handler would be dead code that reads as though `catch` alone were
 * unsafe. This is the reading [us.tractat.kuilt.core.pumpIn] records for its own upstream guard, and
 * `DiscoveryRosterSourceIsolationTest` pins both directions of it: a source-minted cancellation is
 * absorbed and reported, while cancelling the fold's scope still ends the collection.
 */
private fun Flow<RosterEvent>.isolate(
    source: PeerDiscoverySource,
    onFailure: (PeerDiscoverySource, Throwable) -> Unit,
): Flow<RosterEvent> = catch { failure ->
    try {
        onFailure(source, failure)
    } catch (_: Throwable) {
        // Deliberately total, `CancellationException` included, and for the same reason `pumpIn`'s
        // `reportPumpFailure` is: this runs *inside* the guard that keeps the fold alive, so a
        // rethrow escapes it and kills every other source — the exact defect the hook reports on.
        // Nothing suspends in the callback's contract, so there is no cancellation of ours here for
        // `ensureActive()` to find. ALLOW-ise: a consumer's logger must not be able to kill the fold.
    }
}

private sealed interface RosterEvent {
    data class Arrived(val tag: Tag) : RosterEvent

    data class Departed(val peerKey: String) : RosterEvent
}
