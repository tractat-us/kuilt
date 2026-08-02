# Debugging process rules — lessons from the #1466 fiasco

These are hard-won rules for debugging bugs that a **local test suite cannot see** —
ones that only reproduce on real hardware, over a real network, or under real
contention. They come out of #1466 (an iPhone↔iPhone host-election hang that took
~4.5 hours and five PRs, with two premature issue closes and three blind hardware
round-trips). Every one of those wasted cycles was avoidable. Follow these.

## 1. Don't `closes #N` a hardware/integration-reproduced bug until the fix is validated against the reproducer

If a bug was found on real hardware or a real integration (not by a local unit
test), a PR that *looks* like the fix has not earned a closing keyword. Use
`part of #N` / `see #N`, land the change, validate it against the **same
reproducer that found the bug**, then close the issue by hand.

The trap that bit #1466 twice: a `FakeSeam`-injected-signal test proves the
**consumer's reaction** to a signal — it never proves the **transport actually
emits** that signal. Two fixes shipped with `closes #1466` and green
fake-injected tests; the real transport never emitted the signal they reacted
to, so the bug survived and the issue auto-closed anyway. **Say this in the PR**:
"this test injects the signal via `FakeSeam`; it verifies the reaction, not the
emission — the emission is validated on-device before close."

## 2. Instrument before hypothesizing; after one failed fix, ship evidence, not a second guess

On a system you cannot directly observe, the first shipped change should be
**evidence capture**, not a fix. Co-log the decisive facts from a single
vantage point — for a membership/liveness bug that means **identities, not
sizes**: log the actual `selfId` / `peers` / `host` / `state`, not
`peers.size`. A count tells you *that* something changed; the identities tell
you *what*, which is the whole question.

The circuit-breaker: **after one fix round fails, the next change you ship must
be instrumentation, not a second hypothesis.** #1466's decision tree hinged on
"is `state` `Woven` or `Torn` when `peers` drops?" — a question the consumer was
structurally unable to answer for three fix rounds because the bit was never
logged. (This is why `ElectionLobby.state` is now exposed, #1491, and why the
`#1466` diagnostic logging co-logged identities+state from one collector.)

## 3. A contract-impossible value is a fork — probe both branches

When a metric or invariant takes a value the contract says is **impossible**,
you have exactly two mutually exclusive bugs and must probe both:

- a **measurement bug** — the value is being computed/read wrong; or
- a **contract-violation bug** — the value is real and some code path violated
  the contract.

Don't assume it's the measurement (the comfortable option). When hardware
contradicts a formal proof, the proof's *model is missing an input*: enumerate
every site that mutates the violated state and check which one the model didn't
account for. In #1466 the "impossible" roster inversion was a real
contract-violation (a node registered itself as its own remote), not a
measurement artifact — and the missing mutation site was the self-connection the
model never considered.

## Pulling device telemetry

Rule 2 needs evidence off the phone. **The logs pull automatically — run a script,
don't hand-roll `devicectl`.** Both pullers discover every tethered iPhone
themselves; you never look up a UDID. Two scripts, no overlap:

- **[`spike/collect-logs.sh`](../spike/collect-logs.sh)** — after a spike
  connectivity-suite run. Pulls each phone's `suite-*.log` + `nw.log` and merges
  them into one causally-ordered cross-device timeline, which is the artifact: a
  suite verdict is an asymmetry *between* the two phones.
- **[`.claude/scripts/pull-device-telemetry.sh`](../.claude/scripts/pull-device-telemetry.sh)**
  — an arbitrary app's durable telemetry store, by bundle id. Decodes it and ranks
  every candidate container by record count and first/last record time, so you take
  the one that spans the incident. Use it when you don't know the bundle id: an
  Xcode-built install carries a second, generated id, and that is often the one
  holding the session.

**The record timestamps in a durable store are write-times, not event-times** —
the store flushes on a fixed cadence, so record order can never establish that one
event preceded another; read the `at=` / `expiresAt=` fields in the event bodies
instead. Assuming otherwise produced a wrong root-cause diagnosis on #1637.

---

*See also [`testing-coroutine-determinism.md`](testing-coroutine-determinism.md)
for the virtual-time/dispatcher discipline, and the "never a flake" stance: a
hanging or intermittently-failing test is a real defect with poor signal, never
noise to be retried away.*
