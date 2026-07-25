# Module kuilt-warp-heddle

Share one pool of machines fairly between several kinds of work.

Warp already spreads a pile of tasks across whoever is connected, with nobody doing the same
job twice and no central boss. What it can't do on its own is say *"the interactive work gets
three times the grid the batch work does."* Every task looks equal to warp. This small add-on
supplies the missing idea — **fair share** — without changing how warp picks who runs what.

## How it works, in one breath

You put a **lane** label on a task — a plain string like `"acme/interactive"` or `"batch"`.
Warp carries the label but ignores it. This module reads the label and, before a task runs,
checks a shared, weighted budget: is there room in that lane's share right now? If yes, the task
runs and its use is charged. If not, the task simply waits — it is never thrown away — and runs
later when the lane's share refills. Two lanes weighted three-to-one end up completing work
three-to-one.

An **untagged** task (the default) skips all of this and behaves exactly as warp always has.

## The pieces

- **`Lane`** (in `:kuilt-warp`) — the opaque label a task carries. Warp core assigns it no
  meaning; only this module interprets it.
- **`HeddleAdmissionControl`** — the adapter. It plugs into warp's `AdmissionControl` gate,
  maps a task's `Lane` to a fair-share **leaf** in `:kuilt-heddle`, reserves that leaf's
  entitlement before the task runs, and charges it once the task finishes. Out of entitlement
  ⇒ the task defers. Untagged ⇒ admitted for free.
- **`TaskDescriptor.inLane(...)`** — the producer-side tagging step: copy a descriptor onto a
  lane before you enqueue it.

## Deeper: why this is cheap

The budget itself is a `:kuilt-heddle` `EntitlementLedger` — a coordination-free replicated
structure. Reserving and charging are local reads and writes of already-agreed holdings, so
admitting a task adds **no** consensus round; warp's free path stays exactly as cheap as before.
Entitlement flows down the weighted tree the ordinary heddle way — advertise demand, call
`HeddleNode.schedule(parent)` — and warp's consistent-hash placement is untouched: this module
answers *how much*, never *where*.

See `docs/heddle-design.md` §14 (warp as the first customer) for the full design.
