# How the next turn is chosen

When an agent frees up, Heddle starts at the top of the tree. At each fork it
looks only at children that are not ahead of their share. It picks the one that
will still be furthest back after this build. Then it does the same one level
down, until it reaches a lane. This page writes that rule down exactly.
Then it works the farm's first eight builds by hand, so you can check them with
a pencil.

It continues [Fair share in one program](heddle-local.md), and uses the same
farm: web at weight 3 and mobile at weight 1, one lane each, and a 3-minute
estimate per build.

## Progress, measured by weight

Every child of a fork carries one number, its **virtual service**, written `ev`
below: the time it has been given, divided by its weight. A team at weight 3 that has had 6 minutes
sits at 2. A team at weight 1 that has had 2 minutes also sits at 2. Level
numbers mean each has had exactly its share.

Here "given" means committed, not finished. Written out for one child edge with
weight `w`:

```text
ev = seat + (issued − issuedAtSeat) / w − returned / w
```

- `issued` is everything handed down the edge: each lease's estimate the moment
  it is acquired, each `extend`, and each overrun charged at `complete`.
- `returned` is what came back up: the unused part of an estimate at `complete`,
  and the whole lease at `cancel`.
- `seat` is where the child started. A child that joins a busy fork starts level
  with the others rather than at zero, so it is not owed the fork's whole past.
  In the farm both teams joined together, before anything ran, so both start at 0.

Spending does not move `ev`; handing out does. That is why a build that is still
running already counts against its team. The formula is `HeddlePolicy.virtualServiceWide`,
and design [§7.1](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#71-virtual-service)
gives its derivation.

## The rule at one fork

Let `r` be this slot's estimate, the `reservation` passed to `acquire`. Among the
children that lead to a lane this agent can run:

1. **The line.** Take the weighted average of their `ev`:
   `V = Σ w·ev / Σ w`. This is the `eligibleFront` that `explain` reports.
2. **Eligible.** Keep the children with `ev ≤ V`: the ones at or behind the line.
   At least one always qualifies, because no average is below its own smallest
   member.
3. **Deadline.** For each eligible child, compute `ev + r/w`: where it would sit
   after this build.
4. **Winner.** The smallest deadline wins. On a tie, the edge with the smaller
   id wins, so every run of the same calls makes the same choice.

The winner's edge is handed `r`, which raises its `ev` by `r/w`, and the pick
repeats one fork down. Every candidate shares the same `r`, so deadlines differ
only by progress and weight. A heavier child moves less per build, so it wins
more often. Progress is counted in sixteenths of a unit, as whole numbers, so
there is no floating point. Only a newcomer's starting place, or a waking
child's, is rounded, up by under one sixteenth, so it never starts ahead of the
children already waiting.

The steps are `HeddlePolicy.pick`. `FairShareAllocator.pickChild` makes one such
pick at one fork, and `LocalHeddle.acquire` walks them from the root to a lane.
Design [§7.3](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#73-selection)
states the same steps for the replicated version.

## Charging the real cost

`LocalHeddle.complete` settles a lease against its estimate. When the build took
less, the remainder goes back up the path as `returned`, and `ev` falls. When it
took more, the shortfall is handed down first, raising `ev`, and reported as the
overrun. Then the real cost is charged. `cancel` returns everything. So once a
build is settled, its team's `ev` reflects exactly the time it used.

## A team coming back from quiet

A team that had nothing queued sits still while its siblings move on. When it
has work again, it is a **waker**, and its `ev` is lifted before the pick, by
how far the others moved while it was away.

Each fork keeps a **clock** for that. Every pick, the clock moves by the time
the teams that were competing kept since the last pick, issued less handed
back, divided by their total weight. When the engine sees a team go quiet, it
notes where the clock stood. When the team comes back, its `ev` moves up by how
far the clock ran since then:

```text
offset grows by  clockNow − clockWhenItLeft
```

So a team comes back as far ahead or behind as it was when it went quiet,
except across a sibling's refund while it was away ([below](#what-it-does-not-promise)).
Its own builds, and any of them handed back while it was away, still
count, and the time it sat out counts for nothing. A team the engine never saw
go quiet, one it first saw idle say, is clamped instead:

```text
offset = max(0, front + reserved / w − ev − sleeperCredit / w)
```

`reserved` is what the team's open leases still reserve. Those builds are
running, so they count as the team's own, and their refund later is its own
too. `front` is the weighted average `ev` of the siblings that kept competing, with
every waker left out. If none of them is competing, the front is the team
furthest ahead, counting the idle ones, the wakers, and each new team at zero.
That can cost a team a turn, but never give it an extra one. A child's offset
never shrinks, so a second wake cannot hand back credit the first one took.
`sleeperCredit` is the idle credit a clamped waker may keep, set on
`LocalPolicy`, and it defaults to zero.

"Quiet" means missing from `waiting`. A team that is waiting but cannot run on
this agent is still competing, so it keeps its claim. The code is
`settleJoiners` in `FairShareAllocator`, and the clamp's formula is
`HeddlePolicy.wakeOffset`.

## The first eight builds, by hand

With `r = 3`, a web build raises web's `ev` by `3/3 = 1`, and a mobile build
raises mobile's by `3/1 = 3`. Each build finishes on its estimate, so nothing
comes back. The lanes are each a team's only child, so all the choosing happens
at the top fork.

| Build | Web `ev` | Mobile `ev` | Line `V` | Eligible | Web deadline | Mobile deadline | Goes to |
|---:|---:|---:|---:|---|---:|---:|---|
| 1 | 0 | 0 | 0 | both | 1 | 3 | web |
| 2 | 1 | 0 | 3/4 | mobile | — | 3 | mobile |
| 3 | 1 | 3 | 3/2 | web | 2 | — | web |
| 4 | 2 | 3 | 9/4 | web | 3 | — | web |
| 5 | 3 | 3 | 3 | both | 4 | 6 | web |
| 6 | 4 | 3 | 15/4 | mobile | — | 6 | mobile |
| 7 | 4 | 6 | 9/2 | web | 5 | — | web |
| 8 | 5 | 6 | 21/4 | web | 6 | — | web |
| 9 | 6 | 6 | 6 | both | 7 | 9 | web |

Rows 1 to 8 are the sequence `sampleBuildFarm` asserts, and
`LocalHeddleGuideTraceTest` checks every `ev` and every line in the table through
`explain`. Row 9 is the moment the sample stops to ask `explain(mobile)`, and the
answer is this row: mobile's `ev` is 6, `front` and `eligibleFront` are both 6,
and `nextChild` is web. Mobile was
eligible and lost on its deadline, 9 against 7. `entitlement` at the same moment
is 3 agents for web and 1 for mobile, which is 4 split by weights 3 and 1.

Carry on by hand: web's ninth build runs 9 minutes, so web's `ev` rises by `9/3`
to 9. The line becomes `(3·9 + 6)/4 = 33/4`, and web, past it, is not eligible.
That is the `BehindAtLevel(…, 9, 33/4, 33/4)` the sample asserts. Then web runs
twelve builds alone and reaches 21, while mobile waits at 6. The clock ran by
web's 36 minutes over web's weight 3, which is 12. So mobile returns at
`6 + 12 = 18`, 3 behind web, as it was when it went quiet. The line is
`(3·21 + 18)/4 = 81/4`. Mobile is eligible and takes the next build, which is
the one web's overrun owed it, and then the three-to-one pattern resumes.

## Check it on your own engine

- **Read one pick.** Call `explain` on a waiting lane. For `BehindAtLevel`,
  compare `leafSideVirtualServiceWide` with `eligibleFrontWide`. The two older
  fields stop at a ceiling on a busy engine, and these do not. Above it, the lane's side
  was past the line and not a candidate this slot. At or below it, the lane's
  side was a candidate, and `nextChild` had the earlier deadline or won the
  tie on its id. Then compare it
  with `front`: above `front`, the lane's side has had more than its share of
  everyone waiting, and at or below, less.
- **Compare shares.** Over a run in which the same lanes stay queued, add up what
  `complete` charged each lane, divide by the total, and compare with
  `entitlement(lane, waiting, totalSlots) / totalSlots`.
- **Predict, then watch.** `previewPicks` lists the lanes the next acquires would
  pick, changing nothing. Run the acquires and compare. When each lane knows its
  own next job, pass each lane's queue of estimates instead of a count. Each pick
  then reserves the default and extends to the winning lane's job, as your loop
  would.
- **Run the evidence.** From the repository root:

```shell
# The worked example on this page and the previous one.
./gradlew :kuilt-heddle-core:jvmTest --tests "*HeddleSamplesRunTest" --tests "*LocalHeddleGuideTraceTest"
# The fairness bars and pinned fingerprints; allTests runs them on every target.
./gradlew :kuilt-heddle-core:jvmTest --tests "*LocalHeddleFairnessTest" --tests "*LocalHeddleTest"
# The scale probe is off by default. -i shows the LEASE-SCALE lines it prints.
./gradlew :kuilt-heddle-core:jvmTest --tests "*LeaseScaleProbe" -Pheddle.scale.probe=true -i
```

## What it does not promise

- **Fair per fork, not exact across the tree.** Each fork holds its own children
  near their weights: while the same children compete and builds finish on their
  estimates, a pick leaves its winner at most `r/w` past the line at that fork.
  A lane's share is the product of the shares along its path, and the small gaps
  of each fork along the way add up.
- **An estimate steers order.** A lease counts at its estimate until it settles,
  so a lane whose estimates run low looks cheaper while its builds run, and can
  win picks it should not. One whose estimates run high loses picks the same way.
  `complete` corrects the charge, not the picks already made, so a lane is
  always charged what its builds really cost. The effect turns into share when
  each job waits on the one before. Over 48 probe setups with two lanes at equal
  weight, 8 slots and one lane always busy, the other lane gained about 0.01 of
  share on average by reserving a quarter of its real cost, and 0.043 at most.
  Reserving four times its cost, it lost about 0.01, and 0.034 at most. Most
  callers send `defaultReservation`, so its size picks the side: small favours
  long jobs, large penalises them. This is the bound
  [#3239](https://github.com/tractat-us/kuilt/issues/3239) accepted, and a
  benchmark measures it.
- **A cancel, and a sibling's refund across a quiet spell.** A lane that goes
  quiet with one of its own leases open comes back by the clock, which does not
  read the lane's own `ev`. So when that lease completes under its estimate, the
  refund lowers the lane's `ev` as it would with no quiet spell. On the slot
  probe, over- or under-reserving the job that empties the queue now moves share
  by nothing measurable. What is left is smaller. A `cancel` charges nothing,
  with or without a quiet spell, and cancelling that job gains the lane 0.015 to 0.044 of
  share. And a *sibling's* open lease counts in the clock at the weight of
  everyone competing, while its refund lands at the weight of whoever is still
  competing. A lane away across that refund comes back owed a little more than
  it is. That one is new with the clock: the clamp it replaced brought the lane
  back level, which got this case exactly right, and the regression was
  accepted for the bursts the clock fixes. The cancel is tracked by
  [#3238](https://github.com/tractat-us/kuilt/issues/3238). The refund is part
  of the bound [#3239](https://github.com/tractat-us/kuilt/issues/3239)
  accepted: over 54 setups with two lanes under one group, a lane that reserved
  four times the cost of the job that empties its queue cost its honest sibling
  about 0.03 of share on average. The networked
  `HeddleNode` does not take either path: its `complete` keeps an unused
  reservation at the leaf instead of returning it up the path.
- **A ceiling that only the reports meet.** Each child's progress is a count of
  sixteenths of a unit. It is stored exactly, in 128 bits, and a pick compares
  it in 128 bits too, so no fork ever stops picking because a child went far.
  Long before a busy fork's children pass the ceiling, a pick moves them all
  back down by the same amount, so nobody's turn changes and the virtual-time
  reports stay exact. A fork with one waiting child that is never served can
  still pass it, and then only the reports read the ceiling. What a long-lived
  engine meets first is its counters: about 9.2 × 10¹⁸ units of service per
  child, and as many minted per run. Pick a coarse unit anyway, minutes or seconds rather than
  nanoseconds: one reservation can be at most 2⁴⁴ units.

**Sizing estimates.** The estimate and sibling-refund effects both vanish for a
lease that settles at its estimate, a `cancel` aside. So aim `defaultReservation` at or a
little below a typical job, never the longest one: a build that runs past its estimate is
corrected by `extend` or by its overrun, and hands nothing back. Pass a `reservation` per
`acquire` when the lane's history gives a better guess. Raising `sleeperCredit` does not
help: it grants a clamped waker idle credit on purpose. The estimate numbers come from
`ReservationLeanBenchmark`, an ensemble of about 100 setups that runs on demand
rather than in every build. `ReservationLeanTest` checks, on every target, that
ten of those setups run exactly as measured.

## How fast, how fair

Every number here comes from a test or probe you can rerun with the commands
above.

**Fairness.** `LocalHeddleTest.twoLevelTreeWithConcurrentLeases` runs a two-level
tree with three lanes owed 1/4, 1/4 and 1/2, at 1, 4 and 16 leases in flight,
for 2,000 completions each. It asserts every lane's share is within **0.03** of
its ideal. `weightedLeasesFollowTheWeights` runs 400 builds over weights 3 : 1
and asserts the split lands within one build of 300 : 100.

**The fingerprints.** `LocalHeddleFairnessTest` runs 10,000 seeded steps over a
lane tree weighted 3 : 1 : 1, with estimates of 1 to 5, real costs of 0 to 8,
extends and overruns. In the churn arm, lanes come and go and some slots run
only some lanes. Each arm pins the count of every pick, extend, overrun and
wake in one string, identical on every target.

| Arm | What it measures | Bar | Measured |
|---|---|---:|---:|
| backlogged | overall share against 3 : 1 : 1 | 0.01 | 0.000222 |
| backlogged | worst 500-step window | 0.05 | 0.023810 |
| churn | overall share against the share of whoever was waiting | 0.03 | 0.007225 |
| churn | worst 500-step window | 0.15 | 0.062730 |

**Scale.** `LeaseScaleProbe` times one `acquire` when a lane has 5,000 children
and 50,000 retired ones. *Served* rows' retired children once did work, which the
books must keep subtracting. Times are printed, never asserted, because they
measure the machine as much as the code.

| Commit | Machine | Row | `acquire` mean | `complete` mean |
|---|---|---|---:|---:|
| `492cdab7d` | JVM, 16-core Mac, load average about 45 | 5,000 active, 50,000 retired, served | 12.7 ms | 1.0 ms |
| `492cdab7d` | same run | 5,000 active, 50,000 retired, unserved | 7.2 ms | 0.15 ms |
| `492cdab7d` | same run | 5,000 active, none retired | 7.6 ms | 0.16 ms |
| `492cdab7d` | same run | 50 active | 0.07 ms | 0.02 ms |
| `873e6646a` | JVM, load average 8 to 9 | 5,000 active, 50,000 retired, served | 10.9 ms | — |
| `873e6646a` | macOS debug binary, same conditions | 5,000 active, 50,000 retired, served | 161 ms | — |

The `492cdab7d` run shared a heavily loaded machine, so read it as an upper
bound. Compare rows within one run, not across runs.

## Why it works this way

Each choice above was made on purpose, and each has a written reason.

- **Cost is time.** Time on an agent is what is scarce, and you already measure
  it. Heddle takes it as a number you pass in and never reads a clock, so a
  replay is exact. See Ruling 10 in the
  [topology plan](https://github.com/tractat-us/kuilt/blob/main/docs/superpowers/plans/2026-11-12-heddle-topology.md#rulings),
  and Amendment 8 for start times.
- **One estimate per slot.** `acquire` uses one reservation at every fork, so
  deadlines differ only by progress and weight. Job lengths are rarely known in
  advance, so a length per lane would gain little. See Amendment 9 in the
  [same plan](https://github.com/tractat-us/kuilt/blob/main/docs/superpowers/plans/2026-11-12-heddle-topology.md#amendments-after-task-c-2026-11-12-settled-with-the-user).
- **The clock, and the wake clamp.** Without them, a team back from a long quiet
  spell would take every agent until it caught up. A clamp alone forgives the
  lag a short burst leaves, so a fork that empties between bursts stops holding
  its weights. Design
  [§7.2](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#72-neutral-creation-and-no-idle-credit)
  and invariant 6 of [§10](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#10-normative-invariants)
  forbid unlimited idle credit.
- **One supply record per run of the engine.** Every top-up rewrites one record
  holding the running total, so the books do not grow with every refill. A record
  per refill made each `acquire` slower as they piled up. See Amendment 10.
- **A move is a delete plus an add.** A child is known by its name and place, so a
  moved child has nothing to carry its history on, and its running leases finish
  under the old one. See Ruling 12 and Amendment 6.
- **One writer.** The engine takes no locks of its own, so it costs nothing when
  your program already serializes its scheduling. See Ruling 6.

## Running it in a real program

**One writer.** `LocalHeddle` is not thread-safe. Make every call from one place
at a time, under your scheduler's own lock, and `snapshot()` too, since taking
one reads the engine's maps. The `LocalHeddleState` it returns is immutable, so
hand that to a status page or another thread instead of the engine.

**Outcomes, not exceptions.** Nothing suspends and nothing times out. The usual
"no" is a typed answer that changes nothing: `SupplyExhausted`, `NothingEligible`,
or `NoLiveLineage` from `acquire`, and `Unknown`, `Stale`, `PathDraining` or
`SupplyExhausted` from a lease call.
`complete` and `admit` are never refused. Each call is all or nothing, with one
exception: an `acquire` refused as `NoLiveLineage` still updates the engine's
memory of who was waiting, because its pick saw them.

**The books fill up, eventually.** The engine counts service in 64-bit whole
numbers, and two lanes of the largest leases fill them after about half a
million. So `acquire` stops a little early, with `LifetimeExhausted`, and keeps
the rest for work already running: `admit`, `extend` and `complete` are never
refused for it, and never throw. A charge that still does not fit is recorded
whole or not at all, and `Done.unrecorded` says how much the books left out.
One overrun too large to be real, above `MAX_RECORDED_OVERRUN`, is left out
too, so a single wrong cost cannot stop the engine.

**Exceptions mean a mistake.** An argument out of range throws
`IllegalArgumentException`. Arithmetic past `Long` throws `ArithmeticException`
rather than wrapping, except at the lifetime limit above. `IllegalStateException` means the
engine found a state its own rules rule out: a bug in it, or a damaged ledger it
did not write.

**Limits.**

- One reservation or extension is at most `LocalHeddle.MAX_RESERVATION`, 2^44
  units. An overrun charged at `complete` is not bound by it.
- `reconcileCapacity` refuses a farm whose agents × default estimate × target
  rounds would pass `Long.MAX_VALUE / 4`.
- `SupplyPolicy` sets the top-up: a refill when supply would drop below
  `lowWatermarkSlots` rounds (default 1), back up to `targetSlots` rounds
  (default 2).
