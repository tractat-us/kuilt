package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId

/**
 * Per-origin inbound gate for a composite fabric. Collapses duplicate [PlyFrame.Data]
 * (same `(originId, originSeq)` arriving over multiple plies) and releases per-origin
 * frames in sequence order with a bounded buffer. Not thread-safe: its caller serialises
 * access — `CompositeSeam` holds its lock across every [accept], which is what restores the
 * single-collection invariant across the concurrent per-ply inbound pumps.
 *
 * ### The origin key is peer-chosen, so the table is capped (#1814)
 * `originId` is a field the **sending** peer picks, read straight off the wire by
 * `PlyFrame.decode`. [maxBuffered] bounds the buffer *within* one origin; nothing bounded
 * the **number** of origins, and no path ever removes one — so a peer varying `originId`
 * per frame grew [nextExpected] (and, once it sent a second, out-of-order frame under an
 * id, [buffers]) without limit, for as long as the ply stayed attached. Remote memory
 * exhaustion with no malformed frame required: every frame is well-formed.
 *
 * [MAX_ORIGINS] closes that. Note exactly what it bounds and what it does not:
 *  - It bounds the **count** of origins this gate holds state for, over the gate's whole
 *    lifetime — origin state is never pruned, so a departed peer keeps its slot until the
 *    composite seam that owns this gate is gone. A lifetime budget, not a live-origin one.
 *  - It does **not** bound bytes, on either axis. Each admitted origin may still hold up to
 *    [maxBuffered] payloads of whatever size its transport allows; and the retained [PeerId]
 *    **key** is itself attacker-chosen and attacker-*sized*, because `PlyFrame.decode` bounds a
 *    declared id length only by the transport's frame size — a 1 MiB `originId` decodes fine.
 *    So the floor an attacker pins permanently is `MAX_ORIGINS * idBytes`, and pinning it costs
 *    only 256 frames with no out-of-order games, on top of the `MAX_ORIGINS * maxBuffered`
 *    payload ceiling.
 *  - It does **not** bound the *refusal* rate. Every refused frame reaches `onPlyFailure` as one
 *    [PlyReconcileException], so a flood trades memory pressure for consumer-callback pressure —
 *    unbounded in rate, from well-formed input. That channel is shared with the malformed-frame
 *    path and so is pre-existing in kind, but this is a cheaper way to drive it.
 *  - It does **not** check that an origin is an admitted member. Gating on composite roster
 *    state is the stronger property and is deliberately not done here: this gate knows
 *    nothing of the roster, and a `Data` frame can legitimately arrive before the `Announce`
 *    that registers its origin. See #1874 for that follow-on, which also covers the pruning
 *    a membership gate would need.
 *
 * ### The admission budget has a per-source dimension (#1874)
 * One seam-wide pool meant a single flooder could spend all of it, so an honest peer's first
 * `Data` frame was then refused **for the life of the seam** — including on plies the flooder had
 * no presence on. Admission is therefore charged to the **source** that introduces an origin:
 * the `(plyId, transportSender)` pair the caller already holds at `CompositeSeam.onPlyFrame`, and
 * already uses as the slot key for its `idMap`. A source may introduce [MAX_ORIGINS_PER_SOURCE]
 * origins; the seam-wide [MAX_ORIGINS] still bounds the total.
 *
 * **The whole argument is which of the three ids the attacker picks**, so it is worth being exact:
 *  - `originId` — read straight off the wire by `PlyFrame.decode`. **Frame-chosen**, entirely the
 *    sender's. This is the key being budgeted, and the reason there is a budget at all.
 *  - `plyId` — named by the local consumer's `CompositeLoom` desired set, unique per composite by
 *    `CompositeLoom.weave`'s own `require`, and carried on no wire format. **Purely local**: a
 *    remote peer cannot add one, name one, or move a frame between them.
 *  - `transportSender` — `Swatch.sender`, stamped by the receiving ply's fabric from its own view
 *    of the connection. **Transport-attributed**: `learnAnnouncedIdLocked` already rests on exactly
 *    this ("the sender is the fabric's, not the frame's, so a peer cannot displace another peer's
 *    slot"), so this is a shared assumption rather than a new one. It is nullable, and a fabric
 *    that attributes no sender lands every frame on that ply in one bucket — the guarantee then
 *    degrades to per-ply, which is still strictly finer than the seam-wide pool it replaces.
 *
 * What a remote party *can* still influence is the **number of sources**: distinct transport
 * identities are cheap on a relay ply. That is exactly why [MAX_ORIGINS] is retained as a ceiling
 * over the per-source shares rather than being replaced by them — a per-source share alone would
 * multiply the table by the source count and reintroduce #1814's unbounded growth one level up.
 * So the **global worst case is unchanged**: [MAX_ORIGINS] origins, `MAX_ORIGINS * maxBuffered`
 * retained payloads. What changed is the *distribution* — spending the pool now costs
 * `MAX_ORIGINS / MAX_ORIGINS_PER_SOURCE` distinct sources instead of one.
 *
 * The one table this adds, [admittedBySource], is bounded by the same ceiling and by construction:
 * an entry is written **only** on a successful admission, so its size can never exceed the number
 * of admissions, which is [nextExpected]'s size. The retained-key floor above therefore gains a
 * second, similar term — but the source half of it is transport-attributed, so its *size* is the
 * fabric's choice rather than the frame's.
 *
 * ### What this does and does not fix, of the three properties #1874 names
 * Fixed: **one ply can no longer consume the whole budget**. Sharply bounded, not fixed: an honest
 * peer is starved only by a flood sharing its *own* source, and a churning peer now walks its own
 * bucket rather than the shared pool — so the never-pruned damage is localised, not removed.
 * Untouched: **nothing is ever pruned**, and nothing ties an origin to an admitted member. Both
 * need the roster coupling #1874's second direction describes; the two compose.
 *
 * ### Refuse the new origin; never evict an admitted one
 * Evicting (LRU or otherwise) would hand the attacker the displacement: origin ids are
 * remote-chosen and can be emitted at any rate, so a rotating-id flood would evict every
 * honest origin within one cap-sized window, and re-admitting an evicted origin takes the
 * first-sight branch — re-baselining its sequence, which re-opens the cross-ply duplicate
 * this gate exists to collapse and discards whatever it had buffered. That trades a bounded
 * memory problem for an unbounded *duplicate-delivery* one against honest peers. Refusal is
 * contained instead: an already-admitted origin is never disturbed, and the residual damage is
 * that no *new* origin is **ever** admitted once the table is full — permanent for the life of
 * the seam, not transient, because nothing prunes (#1874).
 *
 * The refusal throws rather than returning empty because an empty return already means
 * "duplicate" — a normal, expected outcome — while a refusal is an anomaly, and `kuilt-core`
 * is logger-free by contract. `CompositeSeam.onPlyFrame` calls this inside its inbound
 * guard, so the throw drops that one frame, leaves the ply live, and surfaces to the
 * consumer as `PlyReconcileException(plyId, INBOUND, …)` — precisely the semantics that
 * phase already documents.
 *
 * @param maxBuffered Maximum out-of-order frames held per origin before a gap-skip
 * is forced to preserve liveness.
 */
internal class PlyInboundGate(private val maxBuffered: Int = 16) {
    // Per origin: the next sequence we expect to deliver. Capped at MAX_ORIGINS entries.
    private val nextExpected = mutableMapOf<PeerId, Long>()
    // Per origin: out-of-order frames not yet deliverable. Only ever populated for an origin
    // already in `nextExpected` (the first-sight branch below returns before reaching it), so
    // capping that map caps this one too.
    private val buffers = mutableMapOf<PeerId, MutableMap<Long, ByteArray>>()

    // Per source: how many origins that source has introduced, charged at admission and never
    // released (nothing prunes — #1874). Written ONLY on a successful admission below, which is
    // what bounds it: its size can never exceed the admission count, i.e. `nextExpected.size`,
    // itself capped at MAX_ORIGINS. Read with `?: 0` rather than `getOrPut` for exactly that
    // reason — an insert on the refusal path would make a source-rotating flood grow this map
    // without limit, which is the very hole the seam-wide cap closed for origins.
    private val admittedBySource = mutableMapOf<Pair<PlyId, PeerId?>, Int>()

    /**
     * Origins admitted over this gate's lifetime, and source buckets held — the two sides of the
     * invariant `admittedBySource.size <= nextExpected.size` that bounds the map this fix adds.
     *
     * Test-visible because that bound is otherwise **unobservable**: an entry inserted on the
     * *refusal* path (a `getOrPut` where the code above reads `?: 0`) changes no verdict any
     * behavioural test can see, and would let a source-rotating flood grow the map without limit —
     * #1814's defect one level up, in the very fix for it. Measured: with the read written as
     * `getOrPut`, every one of the other tests in `PlyInboundGatePerSourceBudgetTest` stays green.
     * Nothing in production reads either of these.
     */
    val admittedOriginCount: Int get() = nextExpected.size

    /** @see admittedOriginCount */
    val sourceBucketCount: Int get() = admittedBySource.size

    /**
     * Returns the payloads to deliver now, in order. Empty for a duplicate.
     *
     * The per-origin state this consults is keyed by [PlyFrame.Data.originId] **alone**, across
     * every source — collapsing the same `(originId, originSeq)` arriving over several plies is the
     * job this gate exists to do, and keying that state by source instead would give one origin a
     * sequence baseline per ply and deliver every frame once per ply. Only the *admission* is
     * charged per source.
     *
     * @param plyId which ply the frame arrived on. Local, never on the wire — see the class KDoc.
     * @param transportSender the ply fabric's own view of who sent the frame (`Swatch.sender`), not
     * the frame's claim about itself. Null where a fabric attributes no sender.
     *
     * Throws [IllegalStateException] for a frame from an origin this gate has not seen when either
     * budget is spent — that source's [MAX_ORIGINS_PER_SOURCE] share, or the seam-wide
     * [MAX_ORIGINS]. The frame is refused and nothing is recorded, so the same id is refused again
     * rather than half-admitted, and no other source's share is touched.
     */
    fun accept(plyId: PlyId, transportSender: PeerId?, frame: PlyFrame.Data): List<ByteArray> {
        val origin = frame.originId
        if (origin !in nextExpected) {
            // Peer-chosen key: admit a new one only while BOTH budgets have room. See the class KDoc
            // for why this refuses rather than evicting, and for what each cap does and does not
            // bound. The per-source check runs first because it is the tighter one, so the message a
            // flooder provokes names the share it actually spent; a source with an unspent share
            // falls through to the seam-wide check and is told that instead.
            val source = plyId to transportSender
            val spent = admittedBySource[source] ?: 0
            check(spent < MAX_ORIGINS_PER_SOURCE) {
                "composite inbound gate is at its cap of $MAX_ORIGINS_PER_SOURCE origins for source " +
                    "(ply '${plyId.value}', sender '${transportSender?.value}'); " +
                    "refusing the frame from unseen origin '${origin.value}'"
            }
            check(nextExpected.size < MAX_ORIGINS) {
                "composite inbound gate is at its seam-wide cap of $MAX_ORIGINS origins; " +
                    "refusing the frame from unseen origin '${origin.value}'"
            }
            // First sight of this origin: adopt its sequence as the baseline, and charge the
            // admission to the source that introduced it. Both writes are reachable only past both
            // checks, which is what keeps `admittedBySource` bounded by `nextExpected`.
            admittedBySource[source] = spent + 1
            nextExpected[origin] = frame.originSeq + 1
            return listOf(frame.payload)
        }
        val expected = nextExpected.getValue(origin)
        if (frame.originSeq < expected) return emptyList() // duplicate / already delivered / skipped

        val buffer = buffers.getOrPut(origin) { LinkedHashMap() }
        if (frame.originSeq == expected) {
            buffer[expected] = frame.payload
        } else {
            buffer[frame.originSeq] = frame.payload
            // Overflow: buffer has reached the cap → skip the gap to the lowest buffered.
            if (buffer.size >= maxBuffered) {
                nextExpected[origin] = buffer.lowestBufferedSeq()
            }
        }
        return drain(origin, buffer)
    }

    private fun drain(origin: PeerId, buffer: MutableMap<Long, ByteArray>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var expect = nextExpected.getValue(origin)
        while (true) {
            val payload = buffer.remove(expect) ?: break
            out.add(payload)
            expect += 1
        }
        nextExpected[origin] = expect
        return out
    }

    // commonMain has no sorted map; scan for the min buffered key.
    private fun MutableMap<Long, ByteArray>.lowestBufferedSeq(): Long = keys.min()

    private companion object {
        /**
         * How many distinct origins one gate will ever hold state for, across every source.
         *
         * A `const`, not a constructor knob: this is not tuning. The honest working set is one
         * origin per remote composite peer, so a real composite holds **single-digit** origins,
         * and the only caller that could want this raised is the attacker's — a bigger number
         * just buys a bigger table. 256 leaves ample headroom over any plausible roster while
         * keeping the retained-payload ceiling (`MAX_ORIGINS * maxBuffered`, 4096 frames at the
         * default) finite, which is the whole point.
         *
         * Deliberately **unchanged** by the per-source share below, so the gate's global worst case
         * is exactly what #1814 established. A per-source share is a distribution rule, not a
         * licence to raise the total.
         */
        const val MAX_ORIGINS = 256

        /**
         * How many origins one `(plyId, transportSender)` source may introduce, out of [MAX_ORIGINS].
         *
         * The honest need is **one**: a `CompositeSeam` stamps every `Data` frame it originates with
         * its own `selfId`, so one source carries one origin. 16 is deliberate headroom over that,
         * for the two topologies where a source legitimately carries several — the relaying gateway
         * `accept`'s origin/sender split already anticipates, and a peer whose composite id churns
         * behind a transport identity the fabric keeps stable across its reconnects.
         *
         * The number it buys on the other side: spending the whole pool now costs
         * `MAX_ORIGINS / MAX_ORIGINS_PER_SOURCE` = 16 distinct transport-attributed sources, and a
         * single flooder pins at most 16 of 256 slots — leaving 240 for everyone else, where before
         * it took all 256. Lower would buy a sharper bound at the cost of refusing honest gateway
         * traffic; higher trends back toward the one-flooder-takes-all case this exists to stop.
         */
        const val MAX_ORIGINS_PER_SOURCE = 16
    }
}
