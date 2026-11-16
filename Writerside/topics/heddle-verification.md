# How Heddle is verified

A scheduler that is slightly unfair is hard to catch. Nobody notices one
team getting 23% of the runners instead of 25% until the bill arrives. So
each promise Heddle makes is pinned by a test, and this page lists them,
each next to the test that makes it.

Every test named here is in `kuilt-heddle-core/src` or `kuilt-heddle/src`,
and runs in the normal build. The two modules' test sources hold about 850
`@Test` functions across more than 90 files.

## The choice is exact

**No floating point, clock or randomness in a pick.** A pick compares whole
numbers. Each child's place in line is a 128-bit count of ticks on its own
grid, 16 ticks to a unit, and a pick orders the full 128-bit value. The
rule is in design
[§7.1](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#71-virtual-service),
and the 128-bit type, `Int128`, comes from `:kuilt-math`, which refuses
rather than wraps on overflow.

**A second implementation agrees, pick for pick.**
`HeddlePolicyOracleTest.agreesWithBigIntegerOracleOverRandomizedSchedules`
re-derives every selection from scratch with `java.math.BigInteger`
fractions: a different number type and code path, with no overflow
ceiling and no shared logic. Over seeded random runs of demand, spending
and returns, both must choose the same grant every round. The grid
constant is restated in the test, not read from production, so changing it
reds the test too. `BoundMetricsOracleTest` and
`RationalBigIntegerOracleTest` do the same for the observed fairness
deviation and for the fraction type's arithmetic.

**Every platform makes the same picks.**
`HeddlePolicyGoldenVectorTest.canonicalSequenceMatchesGoldenVectorOnEveryTarget`
runs in common test code, so it executes on JVM, Android, iOS, macOS and
the browser. It drives a seeded sequence of picks and compares the result
byte for byte with a checked-in record. Any platform-dependent arithmetic
would diverge there.

**Moving the origin changes no pick.** A busy parent's counts would
eventually reach their ceiling, so before that the engine moves every
child down by the same amount: a **rebase**.
`LocalHeddleRebasePropertyTest.aBusyParentRebasesBeforeItsCeilingAndPicksAsAnUnrebasedTwin`
runs seven lanes at the largest lease size beside a twin engine with the
rebase switched off. The twin must pass its ceiling inside the run, which
proves the run reaches the regime the rebase is for. Every pick the twin
makes must match the rebasing engine's, past the ceiling too. Halfway
through, the rebasing engine is saved, encoded, decoded and restored, and
the restored copy must keep matching. In `:kuilt-heddle`,
`HeddleAcceptanceTest.aNoRebaseTwinPassesItsCeilingAndPicksAsTheRebasingClusterCallForCall`
makes the same comparison for a three-peer governed cluster.

**A pick never throws at a reachable state.**
`LocalHeddleOverflowPropertyTest` changes the waiting set at every step,
so children keep waking up against different averages. Its weights are
mixed on purpose: 60 against 1, and fractions such as 3/7, 2/5 and 5/3 whose
denominators share no factor. Before the tick grid, such a run overflowed
`Long` within tens of steps. Each arm asserts its own loop count, and that
its rig fired.

## The split matches the weights

| Test | What it runs | Bar |
|---|---|---|
| `LocalHeddleTest.weightedLeasesFollowTheWeights` | 400 builds at weights 3 : 1 | within one build of 300 : 100 |
| `LocalHeddleTest.twoLevelTreeWithConcurrentLeases` | a two-level tree owed 1/4, 1/4 and 1/2, at 1, 4 and 16 leases in flight, 2,000 completions each | every lane within 0.03 of its share |
| `LocalHeddleFairnessTest` | 10,000 seeded steps at 3 : 1 : 1, with uneven estimates and costs, extends, overruns, and lanes that come and go | four bars, from 0.01 to 0.15 |

`LocalHeddleFairnessTest` also pins the count of every pick, extend,
overrun and wake in one string, identical on every target.
[How fast, how fair](heddle-local-turns.md#how-fast-how-fair) gives its
measured values against each bar. The largest is 0.089 against a bar of
0.15, for the worst 500-step window while lanes come and go.

## The books balance

**Nothing is created or lost.** `EntitlementLedgerConservationTest` runs
random sequences of every ledger operation, then merges copies that have
seen only part of each other's updates. Throughout, the supply minted must
equal what is held plus what is spent, and an overspend must be refused.

**Merging is safe in any order.** `EntitlementLedgerLawsTest` checks the
three properties every kuilt replicated type must have: merging is
idempotent, commutative and associative, and the same updates applied in
any order give the same value.

**Equal books encode to equal bytes.** Peers that merged in different
orders must write the same bytes, or every sync would resend the whole
ledger. `EntitlementLedgerCanonicalEncodingTest` holds one arm per
unordered field.

## Partitions and agreement

**A split network converges and conserves.**
`HeddleAcceptanceTest.twoSchedulersAcrossAPartitionConvergeAndConserve`
runs two scheduling peers of a three-peer cluster on kuilt's canonical
consensus simulator. It cuts them apart, keeps both granting, heals the
cut, and checks that the books converge, every minted unit is accounted
for, and the pieces of the fairness-error bound in design
[§8.2](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#82-the-bound)
stay ordered. With one scheduler and a converged view,
`oneGovernedSchedulerPicksAsLocalHeddleCallForCall` checks that the
cluster picks exactly as the single-program engine does.

**An unreadable decision stops the peer.** A governed peer that meets an
agreed entry it cannot decode stops spending rather than guess.
`HeddleControlLogFailClosedTest.anUnknownVersionStopsTheLogThereAndClosesTheGate`
checks that, and `HeddleControlLogCodecTest` holds checked-in byte vectors
that red on any change to the log's wire format.

## The saved form

`LocalHeddlePersistenceTest` round-trips a snapshot through JSON, CBOR and
a size-prefixed binary form, and checks that damaged input decodes as
`Corrupt` rather than throwing. `LocalHeddleSnapshotGolden` holds format 1
as checked-in text, with a rule written above it: never edit it to make a
test pass. A later release must still read it, or refuse it by name.

## Tests that can fail

A test can pass because it checks nothing. Heddle's suite carries habits
against that, and some files record the measurement.

- **Assert that the rig fired.** The overflow and rebase property tests
  each assert their loop count and that the condition they exist for was
  reached. A twin that never passes its ceiling fails the run.
- **Literals, not the fixture's own constants.**
  `EntitlementLedgerLeafGainsChildRelocationTest` records why: with
  expectations spelled from the fixture's constants, zeroing a charge
  reddened 1 test of 4. With literal expectations, the same two mutations
  red 4 of 4.
- **Run the samples, not just compile them.** `HeddleSamplesRunTest` calls
  every zero-argument sample in `:kuilt-heddle-core`, on every target. A
  check in a sample that this guide quotes is a check that runs.

## Every target, not one

A seeded test can walk a different path on the JVM than on Kotlin/Native,
if it ever iterates a hash-ordered set. Then a green JVM run measures the
wrong path. kuilt's rule is that coverage is the union of the targets, not
any one of them. Heddle's property tests walk only lists they built
themselves, in declaration order, so the same seed takes the same path
everywhere, and `allTests` runs them on every target. The root build's
`forbidHashOrderedSeededDraw` check catches the greppable half of the
mistake.

## Check it yourself

```bash
./gradlew :kuilt-heddle-core:allTests :kuilt-heddle:allTests
./gradlew :kuilt-heddle-core:jvmTest --tests "*HeddlePolicyOracleTest*"
```

[How the next turn is chosen](heddle-local-turns.md#check-it-on-your-own-engine)
shows how to check fairness on your own engine, with your own tree.
