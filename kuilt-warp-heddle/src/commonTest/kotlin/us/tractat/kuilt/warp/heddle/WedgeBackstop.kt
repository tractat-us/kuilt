package us.tractat.kuilt.warp.heddle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The wall-clock ceiling for this module's virtual-time tests — a **wedge detector, not a budget**.
 *
 * ## Why it is not tight
 *
 * Every test in this module runs on `StandardTestDispatcher` with a seeded RNG over an
 * [us.tractat.kuilt.core.InMemoryLoom], and advances time only through
 * [us.tractat.kuilt.test.drainAntiEntropy]. There is no real-clock input anywhere in the execution
 * path, so the virtual trajectory — and therefore the exact quantity of real work performed — is
 * identical on every run. A *tight* wall-clock `timeout` on such a test therefore asserts nothing
 * whatsoever about the code under test. It asserts only "this box can retire N units of work in
 * T seconds", which is a claim about machine load, and it is false exactly when the box is busy.
 *
 * The fast, load-independent detectors are the ones that already exist: [drainAntiEntropy] is
 * bounded by construction (it cannot spin), and the assertions carry their own messages. Those
 * report a real defect in ~1.4 s regardless of this value. Nothing is slower to fail because this
 * ceiling is generous; the only thing it governs is how long a *genuine* wedge — a coroutine that
 * hot-loops without ever suspending, which no virtual-time bound can catch — is allowed to hang
 * before the run is abandoned.
 *
 * ## The measurement that set this value (kuilt #1891)
 *
 * `EligibilityLedgerOrthogonalityTest` failed the 2026-07-29 `apple-nightly` run on `macosArm64`
 * with `UncompletedCoroutinesError` against a 5 s ceiling, having taken 5.368 s. The four preceding
 * nightlies ran the identical trajectory in 2.17 s / 2.33 s / 2.73 s / 2.21 s, and no commit in the
 * range touched the test's dependency closure. So the 5 s ceiling left the repo's heaviest such
 * test only **1.8×** headroom.
 *
 * Measured on the same unchanged binary, macosArm64, 30 runs (kuilt #1891): median 1.42 s at load
 * average 7–10, median 3.76 s at load average 21–36 — a **2.65×** contention degradation, same work,
 * slower box. The degradation exceeds the headroom, so that ceiling was not a rare flake: it was
 * guaranteed to fire under routine CI contention. (The same nightly showed the identical signature
 * more weakly across the other coordination-heavy tests — `SraSchemeConformanceTest` 1.71×,
 * `HostedHubReplicationTest` 1.45× — while the median across all 3428 cases moved 0.99×, which is
 * what "contended box" looks like and what "hot loop" does not.)
 *
 * 30 s is ~11× the worst observed idle cost and ~4× the worst observed *loaded* cost, and is an
 * order of magnitude tighter than the lane's 60-minute job budget, so a genuine wedge still fails
 * fast. It is also an established value in this repo, not a novel one.
 *
 * ## Do not tighten this back
 *
 * The repo's coroutine-test guidance asks for "a *tight* timeout, never the 60 s default", and that
 * is what produced this failure — it is sound advice about *virtual* bounds and unsound when applied
 * to the wall clock. Virtual bounds are the detector; this is only the wedge backstop, and it must
 * stay generous. See kuilt #1739 for the repo-wide class (461 sites still carry a copy-pasted 5 s
 * literal) and #1891 for this instance.
 */
internal val WEDGE_BACKSTOP: Duration = 30.seconds
