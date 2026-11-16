# Causal links — re-discovering which step led to which

Your app's pieces run in many places: a phone taps a button, a browser tab loads,
a desktop process charges a card, a background sync wakes up hours later. Normally,
to see how one step led to another you have to *hand-carry a trace id* from each step
into the next — and the moment a step forgets to pass it along, or the next step
happens on a different device or after an offline gap, the connection is lost. You
end up with a pile of disconnected events and no way to tell which one caused which.

**Causal links re-discover those connections for you.** kuilt already knows the
happens-before order of events across every device — even offline, even across
trace boundaries — because that ordering is baked into how its data syncs. This
feature reads that ordering and emits ordinary OpenTelemetry *span links*: "this step
could have been caused by that earlier step." You get back the connections that
manual trace-passing dropped, with no extra plumbing in your app.

## Honest by construction: "could have caused," never "did"

The one promise this feature makes carefully is the one it refuses to overstate.
A link means the earlier step *could have* caused the later one — it happened before
it and was visible to it. It does **not** mean it actually did. Happens-before is a
strict superset of true cause-and-effect, so every link kuilt emits is tagged
`kuilt.causality=potential`. That honesty is the whole point: an overclaiming
correlation tool is worse than none, because you stop trusting it. A `potential`
link is a strong, cheap hint a human or a query can follow — not a verdict.

## What you get, and what you don't

- **Cross-trace and cross-device edges** that explicit context propagation missed —
  the edges that are actually worth surfacing.
- **No links from causality that never flowed through kuilt.** If a step was triggered
  by something kuilt never saw (a side channel, an external webhook), there is no
  happens-before record, so there is no link. Absence of a link is not absence of cause.
- **Span-to-span only, for now.** A log or a metric happening-before a span is real
  causality but out of scope here — the clock advances on span events only.
- **Late edges during sync.** Until a far-off device's data arrives, a predecessor it
  refers to may not be present yet; that edge is simply skipped (and debug-logged),
  never errored, and appears once sync catches up.
- **No clock skew worries.** Inference is pure event-order, never wall-clock time, so
  a device whose clock is hours off doesn't distort anything.

---

## How it works (the deeper layer)

Everything below is the mechanism; you don't need it to use the feature.

### Dots and the frontier

Every span event gets a **dot** — a clock-free `(replica, seq)` pair from kuilt's CRDT
core. A `replica` namespaces the dot to one device; `seq` is that device's own
monotonic counter, so no two events anywhere can collide and no coordination is needed.

A [`WarpCausalClock`](https://tractat-us.github.io/kuilt/api/) mints these. You call
`tick()` once per span, in creation order. Each `tick()`:

1. records the current **frontier** — the set of dots seen so far — as that span's
   *predecessors*, then
2. allocates the span's own new dot, and
3. collapses the frontier to just that new dot.

So the next span's predecessors point back at this one, chaining happens-before
forward. A [`CausalStamp(dot, predecessors)`](https://tractat-us.github.io/kuilt/api/)
is what each span carries. When data arrives from another device, `observe(remoteFrontier)`
folds its dots into the frontier, so the next local span records a cross-device
predecessor — that is how links cross machines.

The clock **must** be recovered from durable storage on restart: a restart that reset
`seq` to 0 would re-mint dots already used and silently corrupt causality. `tick()`
stays pure; the caller `persist()`s after a batch is durably exported, and `recover()`s
at startup. A clock mints and persists **only after that read has landed**: until then
`tick()` answers `ClockTick.Unrecovered` and `persist()` answers `ClockPersist.Unrecovered`
rather than guess at `seq`. `recover()` says what it found: `Recovered`, `Undecodable` (the
clock starts fresh) or `Refused`. While the read is refused, the span exporter saves spans
unstamped and retries the read on each export and clear, and the first read that lands arms
the clock. Those retries back off on the same doubling window as the exporter's span read
(#3436): after `k` refused reads in a row the next `2^k − 1` turns skip the clock's read, with
`k` capped at 6. `recover()` always reads. A clock whose read has landed keeps going through a later refusal, since this
device wrote every `seq` its store can hold.

Starting fresh is not starting unused: the stored spans still carry this device's old dots.
So when the span exporter's read of its stored spans lands, at startup or on the first export
after the store heals, it raises the clock above the highest dot of this device's it found,
before that export stamps anything. It counts a span's own dot and the earlier dots the span
names. A merge raises the clock the same way, from the spans a peer sends, and so does a span
the caller stamped with one of this device's dots.

The stored spans cannot name a dot whose span has left: evicted from the buffer, or cleared. So
every span write also saves this device's high-water mark: every dot of this device's the spans
have held and, once the clock has read its own entry, the clock's number when the write was made.
The floor is the larger of that mark and the highest dot the stored spans carry. The mark is read
from the running clock as the write is made, so it covers every dot minted before that write.

Three gaps remain. If both saved entries are corrupt, the floor starts at zero. A span can reach
a peer through `snapshot()` before its export has written the clock; if the device crashes then,
no entry need be corrupt: the restart reads a clock below that dot, and a new span can take the
dot until the peer sends it back. That is the export's crash window plus gossip, tracked in
#3517; the log exporter's twin was closed by a claim written ahead of the mint. And a corrupt span entry starts fresh and loses its mark. A
sound clock entry still covers every dot an export or clear wrote, since those write the clock
first, but not a dot a merge wrote before any clock write: a merge never writes the clock.

The clock also trusts every dot it reads, merges or is handed, so a buggy peer's dot at the
largest possible number stops it stamping at all: later spans are saved without a stamp.

### The cross-boundary rule

[`inferCausalLinks(spans)`](https://tractat-us.github.io/kuilt/api/) is a pure function
over a collection of span records. It indexes the stamped spans by dot, then for each
span `e2` and each predecessor dot it resolves the predecessor span `e1` and emits an
edge **iff `e1.spanId != e2.parentSpanId`**.

That single condition is the whole filter. An edge whose predecessor *is* the explicit
parent merely restates the ordinary trace tree you already have — so it's dropped. Every
other predecessor edge — a different trace, or the same trace but not the direct parent —
is exactly the connection manual propagation would have lost, so it's kept. Predecessor
dots that don't resolve (late sync) are skipped, and the result is sorted by
`(fromSpanId, linkedSpanId)` so the output is deterministic regardless of input order.

The emitted [`SpanLink`](https://tractat-us.github.io/kuilt/api/) maps directly onto an
OTLP `Span.Link`, carrying the predecessor's trace and span ids plus the
`kuilt.causality=potential` attribute.
