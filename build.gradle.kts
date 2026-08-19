plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
}

// Root aggregation for both doc/coverage tools:
//   - Kover: `koverXmlReport`/`koverHtmlReport` → one merged coverage report.
//   - Dokka: `dokkaGenerate` → ONE browsable HTML API site at build/dokka/html/
//     listing all modules (without it the root task renders only the empty root).
// Each module gets the aggregation dependency only if it actually applies the
// tool — `plugins.withId` adds it lazily per applied plugin. This excludes
// `:kuilt-bom` (a Gradle `java-platform` with no sources/coverage and neither
// plugin) and any future platform module. A plain
// `subprojects.forEach { kover(it) }` instead fails to resolve the BOM's
// non-existent `kover`/`dokka` variant.
subprojects.forEach { sub ->
    sub.plugins.withId("org.jetbrains.kotlinx.kover") {
        dependencies { kover(sub) }
    }
    sub.plugins.withId("org.jetbrains.dokka") {
        dependencies { dokka(sub) }
    }
}

val kuiltVersionLine: String = providers.gradleProperty("kuiltVersionLine").get()

allprojects {
    // CI passes -Pversion=${kuiltVersionLine}.0-dev.<run_number> (see publish.yml).
    // Local builds get a non-releasable -dev marker derived from the same line.
    group = "us.tractat.kuilt"
    version = (findProperty("version") as? String)
        ?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "$kuiltVersionLine.0-dev"
}

// Categorical test backstops — applied to every JVM test task in every subproject.
//
// 1. Timeout: kill any hung JVM test process after 15 min so CI surfaces a failure
//    at the task level (with a named stack trace) rather than waiting for the
//    30-min job cancel that produces no actionable signal. 15 min is generous for
//    any single module's test task (kuilt-crdt:jvmTest peaks at ~9 min on a cold
//    build) while still catching a true hang well before the job-level 30-min cap.
//    See #329 for the incident.
//
// 2. kotlinx.coroutines.debug: names every coroutine with its launch call-site.
//    When a runTest timeout fires, the JVM dump (from the jstack watchdog in
//    ci.yml) shows "Coroutine …, created at …" instead of anonymous threads,
//    making the hung coroutine immediately identifiable.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(15))
        systemProperty("kotlinx.coroutines.debug", "")
    }
    // 3. Elapsed-vs-ceiling on timeout-shaped failures — see TimeoutShape below (#1931).
    //    On EVERY test task, not just the JVM ones: `Test` does not cover the Kotlin/Native or
    //    Kotlin/JS test tasks (they extend `AbstractTestTask` directly), and Native is the platform
    //    whose console rendering is worst.
    tasks.withType<AbstractTestTask>().configureEach {
        addTestListener(TimeoutShapedFailureReporter(reports.junitXml.outputLocation.locationOnly.map { it.asFile.path }))
    }
}

// ─── Timeout-shaped failures print elapsed-vs-ceiling (#1931) ────────────────────────────────────
//
// Gradle renders a failed test on the console as `<exception class> at <file>:<line>` and NOTHING
// else — no message. For a `runTest` wall-clock trip on Kotlin/Native that is
//
//     kotlinx.coroutines.test.UncompletedCoroutinesError at null:-1
//
// which reads as "the stack is missing" and makes the failure look undiagnosable. It is not. The
// archived results XML already carries the full stack, the exception MESSAGE, and a `time=`
// attribute. The gap is the console, not the data — so this is a REPORTING fix and adds nothing to
// any test.
//
// Two values decide "slow box or genuinely wedged", and both are in hand at `afterTest`:
//
//   * ELAPSED — `TestResult.endTime - startTime`, millisecond granularity (the results XML's
//     `time=` to 3 decimal places is computed from the same pair).
//   * The CEILING — Gradle does not know it (it is a Kotlin-level value), but the coroutines library
//     puts it in the exception MESSAGE verbatim. Four shapes, all parsed below, all pinned to
//     kotlinx-coroutines 1.11.0 sources; re-check them on an upgrade, because a reworded message
//     silently degrades this to elapsed-only rather than failing:
//       "After waiting for 5s, …"                    `test/TestBuilders.kt:343` and `:547`
//       "Timed out waiting for 5000 ms"              `Timeout.kt:275` (real-time `withTimeout`)
//       "Timed out waiting for 5s"                   `flow/operators/Delay.kt:403` (`Flow.timeout`)
//       "Timed out after 750ms of _virtual_ …"       `test/TestDispatcher.kt:46`
//     When none matches, elapsed is printed alone rather than a guess — that is still most of the
//     value, because the reader can compare it to the constant in the test source.
//
// Read the percentage as "did this consume its whole budget?", NOT as a precise overshoot — the two
// platforms bracket 100% from opposite sides, measured here on an idle box (load ~2.4):
//
//   * JVM — elapsed runs slightly OVER (2.193 s against a 2 s ceiling, 110%).
//   * Kotlin/Native — elapsed runs a constant ~0.7 s UNDER, so it approaches 100% from below as the
//     ceiling grows: 1.333 s / 2 s (67%), 5.267 s / 6 s (88%), 11.233 s / 12 s (94%). The K/N test
//     runner's clock starts after the ceiling's does; the same understated value is what Gradle
//     writes into the results XML's `time=`, so this is a property of the data, not of this code.
//
// Honesty about what the ratio means: when a `runTest` ceiling trips, elapsed is ~always within a
// second of the ceiling, so the percentage is near-tautologically ~100%. What the line actually buys
// is (a) the ceiling is NAMED, without opening the source to chase a named backstop constant;
// (b) the message is surfaced, and its variants are the real discriminator — "the test completed,
// but only after the timeout" is a slow box, "the test body did not run to completion" is a wedge,
// "active child jobs" is a leak; and (c) elapsed separates a ceiling trip (elapsed ≈ ceiling) from a
// teardown-leak `UncompletedCoroutinesError` (elapsed ≪ ceiling), which carries no ceiling in its
// message at all. For a real-time `withTimeout` the ratio is not tautological and reads normally; for
// the VIRTUAL-time variant the percentage is suppressed outright, since comparing wall-clock elapsed
// to a virtual budget is a category error (a 750 ms virtual ceiling trips in 4 ms of wall clock).
//
// Deliberately scoped to TIMEOUT-SHAPED failures: an ordinary assertion failure already renders a
// line in the test's own source and needs no help; a `runTest` trip renders a line inside the
// coroutines library. Known and accepted over-trigger: a test asserting ON one of these message
// texts, should it fail, matches the phrase predicate and gets an extra informational line. Known
// gap: the task-level `timeout` above kills the whole task and produces no per-test result, so a
// task timeout is out of a test listener's reach by construction.
//
// Declared as a top-level object/class rather than script-level functions for the same reason
// `KotlinCodeScanner` is (see "Guard plumbing" below): a script-level `fun` referenced from a lazily
// executed body captures the `Build_gradle` script instance, which the configuration cache cannot
// serialize. A top-level class in a Kotlin script compiles to a STATIC nested class and captures
// nothing.
object TimeoutShape {
    /** Exception simple names that mean "a duration budget was exceeded". */
    private val TIMEOUT_TYPES = listOf("UncompletedCoroutinesError", "TimeoutCancellationException")

    /** One `Duration.toString()` component (`1.5s`, `500ms`) or a `<n> ms` pair. Longest unit first. */
    private const val TOKEN = """\d+(?:\.\d+)?\s*(?:ms|us|ns|d|h|m|s)"""

    /** The declared ceiling as the coroutines library writes it, e.g. `After waiting for 1m 30s,`. */
    private val CEILING =
        Regex("""(?:After waiting for|Timed out waiting for|Timed out after)\s+($TOKEN(?:\s+$TOKEN)*)""")

    private val COMPONENT = Regex("""(\d+(?:\.\d+)?)\s*(ms|us|ns|d|h|m|s)""")

    /**
     * `TestDispatcher.timeoutMessage` (1.11.0 `TestDispatcher.kt:46`) marks a budget spent in VIRTUAL
     * time. Wall-clock elapsed is not comparable to it — the budget was consumed by advancing the test
     * clock, not by the box — so the percentage is suppressed rather than printed as a category error.
     */
    private const val VIRTUAL_MARKER = "of _virtual_ (kotlinx.coroutines.test) time"

    /** True when [className] or [message] identifies a duration-budget failure. */
    fun isTimeoutShaped(className: String?, message: String?): Boolean =
        TIMEOUT_TYPES.any { className.orEmpty().contains(it) || message.orEmpty().contains(it) } ||
            CEILING.containsMatchIn(message.orEmpty())

    /** The ceiling exactly as the message spells it (`5s`, `1m 30s`, `5000 ms`), or null. */
    fun ceilingText(message: String?): String? = CEILING.find(message.orEmpty())?.groupValues?.get(1)

    /** [ceilingText] in milliseconds, or null when it does not parse. */
    fun ceilingMillis(ceiling: String): Double? {
        var total = 0.0
        var matched = false
        for (m in COMPONENT.findAll(ceiling)) {
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            total += value * when (m.groupValues[2]) {
                "d" -> 86_400_000.0
                "h" -> 3_600_000.0
                "m" -> 60_000.0
                "s" -> 1_000.0
                "ms" -> 1.0
                "us" -> 0.001
                else -> 0.000_001 // "ns"
            }
            matched = true
        }
        return if (matched) total else null
    }

    /**
     * `<class>: <message>`, without repeating the class when the message already carries it —
     * Kotlin/Native reports the message as the full `toString()` where the JVM reports it bare.
     */
    fun describe(className: String?, message: String?): String {
        val type = className.orEmpty()
        val text = message.orEmpty()
        return when {
            type.isEmpty() -> text
            text.startsWith("$type:") -> text
            else -> "$type: $text"
        }
    }

    /** The console block for one timeout-shaped failure, or null when [result] is not one. */
    fun render(qualifiedName: String, result: TestResult, resultsDir: String): String? {
        val failure = result.failures.firstOrNull { isTimeoutShaped(it.details.className, it.details.message) }
            ?: return null
        val elapsedMs = (result.endTime - result.startTime).coerceAtLeast(0)
        val elapsed = "%.3fs".format(java.util.Locale.ROOT, elapsedMs / 1000.0)
        val ceiling = ceilingText(failure.details.message)
        val ceilingMs = ceiling?.let(::ceilingMillis)
        val budget = when {
            ceiling == null ->
                "elapsed $elapsed, no ceiling in the message — compare it with the timeout in the test source"
            failure.details.message.orEmpty().contains(VIRTUAL_MARKER) ->
                "elapsed $elapsed wall-clock, ceiling $ceiling of VIRTUAL time — not comparable; " +
                    "the budget went on advancing the test clock, not on the box"
            ceilingMs == null -> "elapsed $elapsed vs ceiling $ceiling"
            else ->
                "elapsed $elapsed vs ceiling $ceiling (${Math.round(elapsedMs / ceilingMs * 100)}%)" +
                    "  ← slow box or wedge?"
        }
        return buildString {
            appendLine("TIMEOUT-SHAPED FAILURE  $qualifiedName — $budget")
            appendLine("    ${describe(failure.details.className, failure.details.message)}")
            append("    full stack + per-test timings: $resultsDir")
        }
    }
}

/** Prints [TimeoutShape.render] for every timeout-shaped failure on the task it is attached to. */
class TimeoutShapedFailureReporter(private val resultsDir: Provider<String>) : TestListener {
    override fun beforeSuite(suite: TestDescriptor): Unit = Unit
    override fun afterSuite(suite: TestDescriptor, result: TestResult): Unit = Unit
    override fun beforeTest(testDescriptor: TestDescriptor): Unit = Unit

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        if (result.resultType != TestResult.ResultType.FAILURE) return
        val qualifiedName = listOfNotNull(testDescriptor.className, testDescriptor.name).joinToString(".")
        // The DIRECTORY, never a constructed file name: the results XML is `TEST-<class>.xml` on the
        // JVM but `TEST-<task>.<class>.xml` on Kotlin/Native, and a wrong path is worse than none.
        val block = TimeoutShape.render(qualifiedName, result, resultsDir.get()) ?: return
        Logging.getLogger("kuilt.timeout-shape").lifecycle(block)
    }
}

// ─── Guard plumbing ─────────────────────────────────────────────────────────────────────────
//
// Every guard below is a `check`-wired verification task, and every one of them carries a stamp
// file as its single output. The stamp's contents are meaningless; its EXISTENCE is what lets
// Gradle do up-to-date checking at all — a task with inputs but no outputs has no basis for it
// and re-runs on every single build (#1827). Do not delete a stamp as dead weight: without it the
// guard silently becomes the reason someone deletes the guard.
//
// A stamp is only safe if the declared inputs are HONEST — a guard that caches a stale success has
// stopped guarding, which is strictly worse than one that re-runs needlessly. So each `forbid*`
// guard declares the exact Kotlin FILES it reads (not merely the directories that contain them)
// via the helper below, and then walks that same lazily-resolved set in `doLast`. Declared input
// and scanned set are the same object, so they cannot drift apart, and a newly-added violating
// file always invalidates the cached success.
//
// `verifyDocCitations` is the ONE exception and does not use the helper: it reads `.md` as well as
// `.kt`, so it declares its doc and source roots as whole DIRECTORIES (`inputs.dir` per root,
// which over-declares — more re-runs, never fewer). What keeps that honest is not the input
// declaration but its in-task `inputRoots` check, which fails loudly on a citation pointing
// outside every declared root rather than letting it be silently exempt from invalidation. Its
// third scanned surface — every `<module>/module.md` — is declared as FILES, via the shared
// `moduleDocFiles()` provider below, and does follow the helper discipline: declared input and
// walked set are the same object.
//
// One more thing the cache key depends on that is easy to move by accident: the ALLOWLISTS in
// `forbidPortProbeRebind` / `forbidUnboundedSwatchDelivery` are covered only because they are
// literals in this script, and so are folded into the task-action implementation hash. Editing one
// re-runs the guard. Moving an allowlist to `gradle.properties`, a resource file, or any other
// external source would silently drop it out of the key and reintroduce exactly the stale-green
// class these stamps were made safe against — if you externalise one, declare it as an input too.
//
// TAKING A MUTATION RECEIPT ON A GUARD WHOSE INPUT IS THE MODULE SET (#2272). Several guards here
// take the subproject set as an input — `verifyModuleTable` and `forbidUnlintedModule` among them —
// and the obvious receipt is "add a module to `settings.gradle.kts`, watch it go red". It does go
// red. It reds on something else, and the red NAMES YOUR PROBE, which is what makes it so easy to
// tick off. Two things pre-empt the guard, in this order: Gradle refuses to configure a project
// whose directory does not exist, and then `kuilt-bom/build.gradle.kts`'s completeness check fails
// at CONFIGURATION time, before any task runs at all.
//
// The shape that works is a flag, not an edit:
//
//     ./gradlew <theGuardUnderTest> -PguardProbeModule=:kuilt-zzz-probe
//
// `settings.gradle.kts` includes that path against a directory under `build/` and `kuilt-bom`
// accounts for it, so configuration succeeds and the guard under test is the only thing left that
// can fail. Nothing tracked is edited, so there is nothing to revert — which also retires the
// follow-on trap of the hand-edited version, where a `deliberatelyUnpublished` entry that outlives
// the probe trips `staleExclusions` on the NEXT run, a second red about a second unrelated thing.
// For a guard that needs the probe to CARRY something — `forbidUnlintedModule` wants Kotlin source
// with no detekt task — write it into `build/guard-probe/src/`, which is gitignored for the same
// reason the probe directory is.
//
// SCOPE THE INVOCATION to the guard under test. Task-level guards never pre-empt one another, but
// they do all report: a probe module reds `verifyModuleTable` (it has no CLAUDE.md row) whatever
// else you were proving, so `./gradlew build` hands back two reds of which one is noise.
//
// The complementary receipt needs no probe at all — REMOVE an `include`. Nothing pre-empts a module
// going away, and it exercises the same input property from the other side.
//
// DISTRUST THE GREEN AT LEAST AS HARD AS THE RED. Everything above is about a red that names the
// wrong thing, which at least makes you look. The worse verdict this probe can hand you is a GREEN,
// and it has: the probe path used to be a caller-chosen string, and `-PguardProbeModule=:zzz-probe`
// returned BUILD SUCCESSFUL from `verifyModuleTable` — because that guard filters on `:kuilt-`, so
// the probe was included, exempted, and INVISIBLE, on a cache key identical to the no-probe run.
// A receipt-shaped nothing. That is why the path is now a reserved literal rather than a shape
// (see `settings.gradle.kts`), and it generalises past this one flag: a FREE KNOB ON A FIXTURE
// DRIFTS TO THE ONE SETTING WHERE THE PROPERTY CANNOT FAIL. Before recording a 🟢, name what would
// have had to be true for it to be a 🔴, and confirm the task actually EXECUTED — `UP-TO-DATE` or
// `FROM-CACHE` on a mutation run is not a verdict, it is the previous verdict replayed. Re-take
// control arms with `--rerun-tasks` and demand the word EXECUTED, not merely the absence of
// `FROM-CACHE`: `--no-build-cache` suppresses only the cache and leaves the stamp-based
// `UP-TO-DATE` check fully in force, which is the mode a receipt-taker actually hits, because they
// re-run in a warm worktree. Measured here — two `--no-build-cache` runs in a row report
// `2 up-to-date`; `--rerun-tasks` reports `2 executed`. (Add `--no-build-cache` on top if a task
// still says `FROM-CACHE`; the local cache is shared across worktrees.)
//
// WHY THE BOM CHECK IS NOT JUST MOVED TO A TASK, since that is the reflex on reading the above:
// `publish` does not run `check`. A task-wired completeness check would let a module that forgot
// `kuilt.publish` be absent from a PUBLISHED BOM's constraints with nothing failing — the exact
// drift #1044 exists to end, traded away to make a rare receipt cheaper. WHAT NONE OF THIS COVERS,
// stated rather than implied: a NEW configuration-time failure added anywhere in the build would
// pre-empt the probe again, and the only thing that detects that is reading the red you got.
fun kotlinSourcesIn(roots: List<java.io.File>, pattern: String = "**/*.kt"): FileTree =
    files(roots).asFileTree.matching { include(pattern) }

// The same, for a guard whose scope needs more than one Ant pattern. `forbidProductionDispatcherInTests`
// wants every TEST source set, and that is two shapes, not one: the KMP layout's `src/<target>Test/` and
// the plain-JVM layout's `src/test/` (`:kuilt-scale`, `:demo-cli`, `:examples`). A single pattern can
// express either, and the one that expresses the KMP shape silently drops three modules.
fun kotlinSourcesIn(roots: List<java.io.File>, patterns: List<String>): FileTree =
    files(roots).asFileTree.matching { include(patterns) }

// Every subproject's `module.md`: the Dokka per-module doc surface, and a doc root in its own right.
//
// It is the FIRST page a reader meets a module through, it carries both of the things this build
// script checks in prose — `<!-- verbatim from … -->` citations and `@sample` tags — and it lives
// under neither `docs/` nor `Writerside/`. `verifySampleLinks` (#2259) had to reach for it on day
// one; `verifyDocCitations` did not, and so spent its whole life unable to see a citation written
// there (#2256). One provider, two guards: the set of files they cover is now the same object, and
// a reader can see at a glance that it is.
//
// A lazily-resolved `fileTree` rather than a `listOf(File)` filtered by `isFile`, and the reason is
// COST, not correctness — the tempting correctness argument is false and was checked before being
// written here. Gradle 9.4.1 records a configuration-time `isFile` probe as a configuration-cache
// filesystem input, so the filtered-list version does NOT go stale: creating a `module.md` prints
// `configuration cache cannot be reused because the file system entry '…/module.md' has been
// created`, reconfigures, and catches the citation. What it costs to get there is a full task-graph
// recalculation for the whole build; this version touches no filesystem at configuration time, so
// the same edit is an ordinary input change — `Configuration cache entry reused`, one task re-runs.
//
// The patterns are derived from the subproject list rather than globbed as `*/module.md`, so a
// module nested a level down (`demo/web` is `:demo-web`) is covered the moment it grows one.
// Adding a module edits `settings.gradle.kts`, which re-runs configuration, so the derivation
// cannot go stale either. One consequence to know about: `:spike` is a subproject only under
// `-PincludeSpike`, which CI does not pass, so a `spike/module.md` would not be in this set on the
// required gate. `verifyDocCitations`' unscanned-citation check is what keeps that from being a
// silent hole — it fails loudly on a citation in any `.md` this set does not reach.
fun moduleDocFiles(): FileTree {
    val patterns = subprojects.map {
        "${it.projectDir.relativeTo(rootDir).invariantSeparatorsPath}/module.md"
    }
    return fileTree(rootDir) { include(patterns) }
}

// Shared lexical scanner for the guards that must distinguish CODE from prose.
//
// Replace every comment, string and char literal with equivalent blank space, preserving newlines
// (hence line numbers) so a brace or an identifier in prose or in a literal cannot be mistaken for
// the real thing — the hazard #1799's citation checker hit. Block comments nest, as they do in
// Kotlin. `forbidRunCatchingCancellableUnderNonCancellable` (#1803) needs it so a `{` in a string
// cannot fool its brace walk; `forbidBareRunCatching` (#1329) needs it because `runCatching`
// appears in PROSE in several scanned files, including a `[runCatching]` KDoc link inside
// `RunCatchingCancellable.kt` itself.
//
// The `$`-template handling (re-entering code mode inside a hole) is what keeps it sound, and it
// exists because both failure directions were observed: `"A${f(x = "{")}B"` used to emit a stray
// `{` and flag correct code (a false POSITIVE, which on a `check`-wired guard blocks a correct PR),
// and its dual (`"}"`) used to hide a real violation. Both are fixed. If you touch this, re-verify
// BOTH directions with a mutation receipt on EVERY guard that calls it — only one direction is loud.
//
// Declared as an `object` rather than a top-level `fun` deliberately: the callers invoke it from
// inside `doLast`, and a script-level function reference there would capture the `Build_gradle`
// script instance, which the configuration cache (on in `gradle.properties`) cannot serialize.
// Object member access compiles to a static reference and captures nothing.
object KotlinCodeScanner {
    fun stripNonCode(text: String): String {
        val out = StringBuilder(text.length)
        // Frame kinds: 0 = outermost code, 1 = template code (a string-template hole), 2 = string,
        // 3 = raw string.
        val kinds = ArrayList<Int>().apply { add(0) }
        val depths = ArrayList<Int>().apply { add(0) }
        fun push(kind: Int, depth: Int) { kinds.add(kind); depths.add(depth) }
        fun pop() { kinds.removeAt(kinds.size - 1); depths.removeAt(depths.size - 1) }
        var i = 0
        var blockDepth = 0
        while (i < text.length) {
            val kind = kinds[kinds.size - 1]
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (kind >= 2) { // inside a string literal
                when {
                    kind == 3 && text.startsWith("\"\"\"", i) -> { pop(); i += 3 }
                    kind == 2 && c == '\\' -> i += 2
                    kind == 2 && c == '"' -> { pop(); i++ }
                    c == '$' && next == '{' -> { push(1, 1); i += 2 }
                    c == '\n' -> {
                        out.append('\n')
                        if (kind == 2) pop() // malformed single-line string; recover, don't run away
                        i++
                    }
                    else -> i++
                }
                continue
            }
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> { if (c == '\n') out.append('\n'); i++ }
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("\"\"\"", i) -> { push(3, 0); i += 3 }
                c == '"' -> { push(2, 0); i++ }
                c == '\'' -> { // char literal: no templates, bounded to its line
                    i++
                    while (i < text.length && text[i] != '\'' && text[i] != '\n') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    if (i < text.length && text[i] == '\'') i++
                }
                c == '{' -> {
                    if (kind == 1) depths[depths.size - 1] = depths[depths.size - 1] + 1
                    out.append('{'); i++
                }
                c == '}' -> {
                    if (kind == 1 && depths[depths.size - 1] - 1 == 0) {
                        pop(); i++ // the template's own closer; its opener was not emitted either
                    } else {
                        if (kind == 1) depths[depths.size - 1] = depths[depths.size - 1] - 1
                        out.append('}'); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

// The INVERSE projection of `KotlinCodeScanner.stripNonCode`, for the one guard that has to read
// PROSE rather than code: `verifySampleLinks` (#2259), whose subject — a `@sample` tag — lives only
// inside KDoc. Everything OUTSIDE a `/** … */` block is blanked to spaces with newlines preserved,
// so line N of the result is line N of the source carrying just its KDoc text.
//
// Plain `//` and non-doc `/* */` comments are blanked too, and that is a rule rather than an
// oversight: Dokka reads KDoc only, so a `@sample` written in an ordinary comment is inert, and
// reporting it would be a false alarm on a line that documents nothing.
//
// String and char literals are tracked for the same reason the sibling scanner tracks them — a
// `"/**"` inside a literal must not open a comment — and an escape at end-of-line consumes only the
// backslash, so a line number can never be swallowed. `object` for the same configuration-cache
// reason as its sibling; see that scanner's note.
object KdocScanner {
    fun kdocOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var depth = 0 // block-comment nesting depth; 0 = not inside one
        var isKdoc = false // the outermost open block comment began with `/**`
        var inRaw = false
        var inStr = false
        var inChar = false
        fun blank(n: Int) { repeat(n) { out.append(' ') } }
        // An escape sequence: blank the backslash, then its escapee unless that is the newline
        // (which must reach the output, or every later line number shifts by one).
        fun escape() {
            blank(1)
            i++
            if (i < text.length && text[i] != '\n') { blank(1); i++ }
        }
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (c == '\n') {
                out.append('\n')
                inStr = false // a malformed single-line string; recover, don't run away
                inChar = false
                i++
                continue
            }
            when {
                inRaw ->
                    if (text.startsWith("\"\"\"", i)) { inRaw = false; blank(3); i += 3 } else { blank(1); i++ }
                inStr -> if (c == '\\') escape() else { if (c == '"') inStr = false; blank(1); i++ }
                inChar -> if (c == '\\') escape() else { if (c == '\'') inChar = false; blank(1); i++ }
                depth > 0 -> when {
                    c == '/' && next == '*' -> { depth++; if (isKdoc) out.append("/*") else blank(2); i += 2 }
                    c == '*' && next == '/' -> {
                        depth--
                        if (isKdoc) out.append("*/") else blank(2)
                        if (depth == 0) isKdoc = false
                        i += 2
                    }
                    else -> { if (isKdoc) out.append(c) else blank(1); i++ }
                }
                // `/**` opens KDoc — but `/**/` is merely an empty block comment, not a doc one.
                c == '/' && next == '*' -> {
                    depth = 1
                    isKdoc = text.getOrNull(i + 2) == '*' && text.getOrNull(i + 3) != '/'
                    if (isKdoc) { out.append("/**"); i += 3 } else { blank(2); i += 2 }
                }
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') { blank(1); i++ }
                text.startsWith("\"\"\"", i) -> { inRaw = true; blank(3); i += 3 }
                c == '"' -> { inStr = true; blank(1); i++ }
                c == '\'' -> { inChar = true; blank(1); i++ }
                else -> { blank(1); i++ }
            }
        }
        return out.toString()
    }
}

// Locates `runTest(…)` calls whose `timeout =` argument is a BARE DURATION LITERAL, for
// `forbidTightRunTestTimeout` below. Same `object` rationale as `KotlinCodeScanner`: the caller
// invokes it from inside `doLast`, where a script-level function reference would capture the
// unserializable `Build_gradle` instance.
//
// The argument list is walked with a PAREN COUNTER, not a regex. `runTest\([^)]*timeout` looks
// right and is wrong: `[^)]*` cannot cross the `)` in `runTest(StandardTestDispatcher(), …)`, so it
// misses the idiomatic form — which is ~all of them. Measuring the population that way undercounted
// it 5×. Depth tracking also keeps a nested `timeout =` (an argument of an argument) from being
// read as `runTest`'s own.
//
// "Bare literal" is decided by asking whether the value expression contains a DIGIT, not by
// matching `5.seconds`. That is the whole point: a rule keyed to the number 5 is satisfied by 4.
// A named constant contains no digit and passes; `5.seconds`, `4.seconds` and `2 * SOMETHING` do
// not. Known edge, stated rather than fixed: a POSITIONAL timeout (`runTest(dispatcher, 5.seconds)`)
// is not detected — `runTest`'s parameter order makes it unwritable by accident, and zero sites in
// the tree use it.
object RunTestTimeoutScanner {
    private val call = Regex("""(?<![A-Za-z0-9_])runTest\s*\(""")
    private val named = Regex("""(?<![A-Za-z0-9_])timeout\s*=""")
    private val digit = Regex("""[0-9]""")

    /** 1-based line numbers, in source order, of every violating call in already-stripped [code]. */
    fun violations(code: String): List<Int> {
        val out = mutableListOf<Int>()
        for (m in call.findAll(code)) {
            val open = m.range.last // index of the '('
            var i = open + 1
            var depth = 1
            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                }
                i++
            }
            if (depth != 0) continue // unbalanced: malformed source, leave it to the compiler
            if (hasLiteralTimeout(code.substring(open + 1, i - 1))) {
                out += code.take(m.range.first).count { it == '\n' } + 1
            }
        }
        return out
    }

    private fun hasLiteralTimeout(args: String): Boolean =
        splitTopLevel(args).any { arg ->
            val name = named.find(arg) ?: return@any false
            depthAt(arg, name.range.first) == 0 && digit.containsMatchIn(arg.substring(name.range.last + 1))
        }

    private fun splitTopLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        args.forEachIndexed { idx, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts += args.substring(start, idx); start = idx + 1 }
            }
        }
        parts += args.substring(start)
        return parts
    }

    private fun depthAt(text: String, index: Int): Int {
        var depth = 0
        for (k in 0 until index) {
            when (text[k]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth
    }
}

// Locates the WRAPPER shape `RunTestTimeoutScanner` is structurally blind to (#1739): a function
// that feeds `runTest`'s own timeout from one of its PARAMETERS, whose DEFAULT is a bare duration
// literal. There is no literal at the `runTest(` call site — it reads `timeout = timeout` — so the
// call-site scanner cannot flag it and cannot vouch for it either. One such wrapper silently applies
// its ceiling to every call site that takes the default, which is normally all of them.
//
// The anchor that keeps this from firing on the many legitimate APIs that take a timeout
// (`HeartbeatConfig(timeout = …)`, `awaitCommit(within = 2.seconds)`, `assertAborts…(timeout =
// 5.seconds)` — a VIRTUAL `withTimeout` bound, which is the thing #1739 wants) is that the parameter
// must belong to a declaration that itself calls `runTest(`. Nothing but a `runTest` wrapper does.
//
// Ownership is resolved by taking the NEAREST PRECEDING `fun` keyword rather than by computing a
// declaration's extent: all four in-tree wrappers are expression-bodied (`): TestResult = runTest(`),
// for which no brace walk gives an extent at all. A plain `@Test fun t() = runTest(timeout = X)`
// resolves to `t`, which has no parameters, so it contributes nothing.
//
// Two rules, unioned, both keyed to "the default CONTAINS A DIGIT" for the same reason the sibling
// scanner is — a rule naming `5.seconds` is satisfied by `4.seconds`, and every sanctioned constant
// (`TEST_WEDGE_BACKSTOP`, `RAFT_SIM_WEDGE_BACKSTOP`, `WARP_SIM_WEDGE_BACKSTOP`) is digit-free:
//   (a) FORWARDING — a parameter whose name appears inside the `runTest(` call's own top-level
//       `timeout =` argument. Name-agnostic on purpose: `fun sim(wedge: Duration = 5.seconds) =
//       runTest(timeout = wedge)` is the same defect, and a rule keyed to the NAME `timeout` would be
//       evaded by renaming exactly as a rule keyed to `5` is evaded by writing `4`.
//   (b) NAME — a parameter called `timeout` in a `runTest`-calling declaration, which catches the
//       POSITIONAL forward (`runTest(dispatcher, timeout)`) that (a) cannot see because there is no
//       `timeout =` argument to read.
// Locates a cancellation RETHROW placed directly around a `withTimeout` bound, for
// `forbidCancellationRethrowAroundBound` below (#2292). Same `object` rationale as the sibling
// scanners: it is invoked from inside `doLast`, where a script-level function reference would
// capture the unserializable `Build_gradle` instance.
//
// THE DEFECT. `TimeoutCancellationException` **is a** `CancellationException`. So a
// `catch (e: CancellationException) { throw e }` sitting above a `withTimeout` intercepts the bound's
// OWN expiry — the one condition the handler beneath it was written for — and the handler becomes
// dead code. `runCatchingCancellable { withTimeout(…) }` is the same defect spelled with the helper:
// it rethrows on exactly the outcome its `getOrElse`/`onFailure` names. Both shapes shipped here.
//
// TWO RULES, and what CLEARS each.
//   A. A `try` whose block contains a bare `withTimeout(`, followed by a `catch` naming
//      `CancellationException` whose body throws. Cleared by exactly one thing: an EARLIER `catch`
//      naming `TimeoutCancellationException`, which handles the bound's own expiry by type, so the
//      later rethrow can only ever see a real cancel (`:kuilt-test`'s `MidHandshakeCollapse` is the
//      in-tree example, and disabling this clause makes it the guard's one false positive — that is
//      the receipt that the clause is load-bearing rather than decorative).
//      An `ensureActive()` in the catch body is deliberately NOT a clearance, though the tempting
//      symmetry with the sibling guard says it should be: `catch (e: CancellationException) {
//      ensureActive(); throw e }` is still the whole defect — when the exception is the minted
//      timeout, `ensureActive` falls through and the `throw e` rethrows it anyway. Honouring it
//      would clear a defective shape silently. The cost is the converse: a correct
//      `ensureActive(); throw Converted(e)` is flagged. Zero in-tree sites have it, and a false
//      positive here is loud and cleared in one line by writing `catch (e: Throwable)` — the
//      trade `forbidBareRunCatching` makes for the same reason.
//   B. `runCatchingCancellable {` whose block contains a bare `withTimeout(`. Nothing clears it —
//      the helper rethrows unconditionally. Convert to `withTimeoutOrNull` plus an explicit throw.
// `withTimeoutOrNull` is not matched by either rule and needs no exemption: `\bwithTimeout\s*[({]`
// requires a `(` or `{` immediately after the name, which `withTimeoutOrNull(` does not supply.
// That is load-bearing — a sweep that treated the two alike would false-positive on ~all of them.
object CancellationRethrowAroundBoundScanner {
    private val tryBlock = Regex("""(?<![A-Za-z0-9_])try\s*\{""")
    private val helper = Regex("""(?<![A-Za-z0-9_])runCatchingCancellable\s*\{""")
    private val clause = Regex("""^\s*(catch\s*\([^)]*\)\s*\{|finally\s*\{)""")
    private val bound = Regex("""(?<![A-Za-z0-9_])withTimeout\s*[({]""")

    /** One offending site: where the guard is written, and which rule it broke. */
    data class Violation(val line: Int, val rule: String, val detail: String)

    /** In source order, over already-stripped [code]. */
    fun violations(code: String): List<Violation> {
        val out = mutableListOf<Violation>()
        for (m in tryBlock.findAll(code)) {
            val open = code.indexOf('{', m.range.first)
            val end = closingBrace(code, open) ?: continue
            if (!bound.containsMatchIn(code.substring(open, end))) continue
            var pos = end
            var timeoutHandled = false
            while (true) {
                val next = clause.find(code.substring(pos)) ?: break
                val start = pos + next.range.first
                val brace = code.indexOf('{', start)
                val close = closingBrace(code, brace) ?: break
                val header = code.substring(start, brace)
                val body = code.substring(brace, close)
                when {
                    header.contains("TimeoutCancellationException") -> timeoutHandled = true
                    header.contains("CancellationException") && !timeoutHandled &&
                        body.contains("throw") ->
                        out += Violation(
                            lineOf(code, start),
                            "A",
                            "catch (…: CancellationException) { throw } directly over a `withTimeout`",
                        )
                }
                pos = close
            }
        }
        for (m in helper.findAll(code)) {
            val open = code.indexOf('{', m.range.first)
            val end = closingBrace(code, open) ?: continue
            if (bound.containsMatchIn(code.substring(open, end))) {
                out += Violation(lineOf(code, m.range.first), "B", "runCatchingCancellable { … withTimeout(…) … }")
            }
        }
        return out.sortedBy { it.line }
    }

    /** Index just PAST the `}` matching the `{` at [open], or null if unbalanced. */
    private fun closingBrace(code: String, open: Int): Int? {
        if (open < 0 || open >= code.length || code[open] != '{') return null
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth++ else if (code[i] == '}') depth--
            if (depth == 0) return i + 1
            i++
        }
        return null
    }

    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1
}

object RunTestWrapperTimeoutScanner {
    private val call = Regex("""(?<![A-Za-z0-9_])runTest\s*\(""")
    private val funKeyword = Regex("""(?<![A-Za-z0-9_])fun(?![A-Za-z0-9_])""")
    private val named = Regex("""(?<![A-Za-z0-9_])timeout\s*=""")
    private val digit = Regex("""[0-9]""")
    private val ident = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** One offending wrapper parameter: where it is declared, what it is called, what it defaults to. */
    data class Violation(val line: Int, val parameter: String, val default: String)

    private data class Param(val name: String, val default: String, val offset: Int)

    /** In source order, deduplicated by declaration site, over already-stripped [code]. */
    fun violations(code: String): List<Violation> {
        val funs = funKeyword.findAll(code).map { it.range.first }.toList()
        val out = linkedMapOf<Int, Violation>()
        for (m in call.findAll(code)) {
            val owner = funs.lastOrNull { it < m.range.first } ?: continue
            val literalDefaults = parameters(code, owner).filter { digit.containsMatchIn(it.default) }
            if (literalDefaults.isEmpty()) continue
            val close = matchingBracket(code, m.range.last) ?: continue
            val timeoutArg = timeoutArgument(code.substring(m.range.last + 1, close))
            val mentioned = timeoutArg?.let { arg -> ident.findAll(arg).map { it.value }.toSet() }.orEmpty()
            literalDefaults
                .filter { it.name in mentioned || it.name == "timeout" }
                .forEach { p ->
                    out[p.offset] = Violation(
                        line = code.take(p.offset).count { it == '\n' } + 1,
                        parameter = p.name,
                        default = p.default,
                    )
                }
        }
        return out.values.sortedBy { it.line }
    }

    /** The top-level `timeout = …` argument's value expression, or `null` if the call has none. */
    private fun timeoutArgument(args: String): String? =
        topLevelParts(args, 0, args.length)
            .map { args.substring(it.first, it.last + 1) }
            .firstNotNullOfOrNull { arg ->
                named.find(arg)?.takeIf { depthOf(arg, it.range.first) == 0 }?.let { arg.substring(it.range.last + 1) }
            }

    /** Parameters of the declaration whose `fun` keyword starts at [funStart], defaults included. */
    private fun parameters(code: String, funStart: Int): List<Param> {
        var i = funStart + "fun".length
        while (i < code.length && code[i] != '(') {
            if (code[i] == '{' || code[i] == '}' || code[i] == ';') return emptyList()
            i++
        }
        val close = matchingBracket(code, i) ?: return emptyList()
        return topLevelParts(code, i + 1, close).mapNotNull { param(code, it) }
    }

    // `name: Type = default`, both separators taken at depth 0. Angle brackets are NOT tracked, so a
    // generic argument list splits a parameter in two (`Map<String` / `Int> = mapOf()`); the halves
    // simply fail to parse as a parameter, and every OTHER parameter in the list still resolves —
    // degrading to a miss on that one parameter rather than to a wrong verdict on the declaration.
    private fun param(code: String, range: IntRange): Param? {
        var depth = 0
        var colon = -1
        var eq = -1
        for (i in range) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ':' -> if (depth == 0 && colon < 0) colon = i
                '=' -> if (depth == 0 && eq < 0 && code.getOrNull(i + 1) != '=' &&
                    code.getOrNull(i - 1) !in setOf('=', '!', '<', '>')
                ) {
                    eq = i
                }
            }
        }
        if (colon < 0 || eq < colon) return null // no type, or no default: not a defaulted parameter
        val name = ident.findAll(code.substring(range.first, colon)).lastOrNull() ?: return null
        return Param(
            name = name.value,
            default = code.substring(eq + 1, range.last + 1).trim(),
            offset = range.first + name.range.first,
        )
    }

    /** Comma-separated spans of `code[from until until]` at bracket depth 0, as absolute ranges. */
    private fun topLevelParts(code: String, from: Int, until: Int): List<IntRange> {
        val parts = mutableListOf<IntRange>()
        var start = from
        var depth = 0
        for (i in from until until) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts += start until i; start = i + 1 }
            }
        }
        parts += start until until
        return parts.filter { !it.isEmpty() }
    }

    /** Index of the bracket closing the one at [open], or `null` when the source is unbalanced. */
    private fun matchingBracket(code: String, open: Int): Int? {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun depthOf(text: String, index: Int): Int {
        var depth = 0
        for (k in 0 until index) {
            when (text[k]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth
    }
}

// Guard: forbid unbounded Swatch delivery channels (fabric-backpressure epic, #701/#741).
// Every in-process fabric must deliver inbound frames through the bounded `Spool` primitive;
// a raw `Channel<Swatch>(... UNLIMITED ...)` reintroduces the unbounded inbound backlog that
// caused the #655 OOM. The single sanctioned exception is `FaultySeam` (deterministic loss
// injection — a test fixture, not a capacity policy). Type-scoped to `Channel<Swatch>` so it
// catches delivery buffers without flagging legitimate non-delivery channels. Line-based
// (matches the idiomatic single-line declaration).
val forbidUnboundedSwatchDelivery by tasks.registering {
    group = "verification"
    description = "Fails if any source declares an unbounded Channel<Swatch> — use a bounded Spool<Swatch>."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a function of file NAMES (the allowlist) and file CONTENTS only, both of which a RELATIVE
    // fingerprint captures — so a cache hit genuinely means "this exact source was verified green".
    val stamp = layout.buildDirectory.file("verification/forbid-unbounded-swatch-delivery.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    val allowlist = setOf("FaultySeam.kt")
    doLast {
        val ctor = Regex("""Channel<Swatch>\s*\(""")
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.asSequence()
            .filter { it.name !in allowlist }
            .flatMap { file ->
                file.readLines().asSequence().withIndex()
                    .filter { (_, line) -> ctor.containsMatchIn(line) && "UNLIMITED" in line }
                    .map { (i, line) -> "${file.relativeTo(rootPath)}:${i + 1}  ${line.trim()}" }
            }.toList()
        if (offenders.isNotEmpty()) {
            error(
                "Unbounded Swatch delivery channel(s) found — deliver through a bounded Spool<Swatch> " +
                    "instead (FaultySeam is the only allowed exception):\n  " + offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} Kotlin sources scanned\n")
    }
}

// Guard: forbid the probe-a-free-port-then-re-bind-it TOCTOU (#1590, first seen as #1586).
//
//     val port = ServerSocket(0).use { it.localPort }   // allocates, then CLOSES
//     …later: bind that number                          // anything can have taken it by now
//
// The probe socket is released before the real bind, so any other process on the box can win the
// port inside that window. It is invisible when a module runs alone and shows up as
// `BindException: Address already in use` under concurrent builds — routine here, since several
// sessions build in parallel on one machine. It is a flake *generator*: every site eventually costs
// someone a debugging session for a defect that is not in their change.
//
// The fix is to never release the port: bind 0 and read back what you actually got —
//   Ktor:  embeddedServer(Netty, port = 0).start(wait = false)
//          val port = server.engine.resolvedConnectors().first().port
//   Ktor sockets: aSocket(selector).tcp().bind("127.0.0.1", 0)
//          val port = (serverSocket.localAddress as InetSocketAddress).port
//
// Detection is deliberately narrow — the *closing* probe only. A `ServerSocket(0)` held open for the
// life of the fixture (e.g. the loopback proxies in the half-open tests) reports a port it still
// owns and is fine; so is a `use { }` block that accepts on the socket it opened. The trip-wire is
// `ServerSocket(0)` + `.use` + a `localPort` read inside the first few lines of the block, i.e. "the
// only thing taken out of this socket is its number, and then it is closed".
//
// The allowlist is the #1590 backlog, not an escape hatch: `TcpLoom`/mDNS sites need their host to
// bind 0 itself (a per-site change, not a mechanical one) and the `*ConformanceTest` files are held
// out to avoid colliding with in-flight work. Every entry is a known site awaiting conversion — do
// NOT add a new file here; bind 0 instead.
val forbidPortProbeRebind by tasks.registering {
    group = "verification"
    description = "Fails if a source probes a free port with ServerSocket(0) and then re-binds it (#1590)."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: stamp ⇒ UP-TO-DATE (#1827). Same reasoning as the sibling guard —
    // the verdict reads only file names (the allowlist) and file contents.
    val stamp = layout.buildDirectory.file("verification/forbid-port-probe-rebind.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    // Known #1590 sites not yet converted. Shrinks to empty as they land; never grows.
    val allowlist = setOf(
        // Held out of the sweep to avoid a merge collision with in-flight conformance work.
        "WebSocketConformanceTest.kt",
        "MDNSConformanceTest.kt",
        // mDNS: the port is an *input* to the advertisement built inside the embeddedServer module
        // lambda, so it must be known before start() — needs a restructure, not a one-line change.
        "MDNSLoomCapabilityTest.kt",
        "MDNSMultiAcceptHostTest.kt",
        "MDNSRoomKeySourcingTest.kt",
        "MDNSSelfDiscoveryFilterTest.kt",
        "MDNSSelfDiscoveryMulticastTest.kt",
    )
    doLast {
        // Matches the aliased import too (`JvmServerSocket(0)` contains `ServerSocket(0)`).
        val probe = Regex("""ServerSocket\(\s*0\s*\)""")
        val lookahead = 3
        // Code only — a line of prose that *describes* the banned idiom (this comment, the
        // explanatory notes at each converted site) is not the idiom.
        fun codeOf(raw: String): String {
            val t = raw.trimStart()
            return if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else raw.substringBefore("//")
        }
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.asSequence()
            .filter { it.name !in allowlist }
            .flatMap { file ->
                val code = file.readLines().map(::codeOf)
                code.asSequence().withIndex()
                    .filter { (i, line) ->
                        probe.containsMatchIn(line) && ".use" in line &&
                            code.subList(i, minOf(i + lookahead, code.size)).any { "localPort" in it }
                    }
                    .map { (i, line) -> "${file.relativeTo(rootPath)}:${i + 1}  ${line.trim()}" }
            }.toList()
        if (offenders.isNotEmpty()) {
            error(
                "Free-port probe then re-bind (TOCTOU) found — the probe socket is closed before the " +
                    "real bind, so another process can take the port in that window (#1590). Bind port 0 " +
                    "and read back the port you actually got: Ktor " +
                    "`server.engine.resolvedConnectors().first().port` after `start(wait = false)`, or " +
                    "`(serverSocket.localAddress as InetSocketAddress).port` for a Ktor socket:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} Kotlin sources scanned\n")
    }
}

// Guard: forbid declaring a KMP target you have no source for (#1014).
//
// A module that applies `kuilt.kmp-library` gets the full target set (jvm, android,
// iosArm64, iosSimulatorArm64, macosArm64, wasmJs). If a target's MAIN compilation has
// no Kotlin source anywhere in its source-set closure, the native/wasm compilation is
// `NO-SOURCE` — a clean no-op under `./gradlew build`, so `ci-required` stays green — but
// it produces no `.klib`, and `generateMetadataFileFor<Target>Publication` (which runs
// ONLY in the post-merge publish workflow, never in `ci-required`) then throws
// `FileNotFoundException`. That broke publish for weeks (#1014): `:kuilt-otel-logback`
// put all its source in a manual `jvmAndAndroidMain` intermediate with an empty
// `commonMain`, so its native/wasm targets compiled nothing. The fix (#1017) was
// `kuilt.jvmAndroidOnly=true`, which stops declaring those targets. This guard makes the
// whole class impossible to merge by catching a source-less declared target PRE-merge,
// host-independently, under `check`.
//
// Timing: the KMP source-set hierarchy (the `jvmMain -> commonMain` dependsOn edges that
// make `commonMain` part of the jvm main compilation's closure) is only fully wired after
// ALL projects are evaluated — at each subproject's own `afterEvaluate`, a leaf
// compilation's `allKotlinSourceSets` still returns just the leaf set, missing `commonMain`
// (which would falsely flag every module). So we resolve the closure inside
// `gradle.projectsEvaluated`, not per-project `afterEvaluate`.
//
// CC-friendliness: the typed KGP extension objects aren't configuration-cache serializable,
// so we snapshot a `List<Pair<targetLabel, sourceTree>>` here at configuration time and do
// the emptiness check in `doLast`.
val srclessTargetProbes = mutableListOf<Pair<String, FileTree>>()
gradle.projectsEvaluated {
    rootProject.subprojects.forEach { sub ->
        val kmp = sub.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
            ?: return@forEach // e.g. :kuilt-bom (java-platform), :kuilt-scale (plain kotlinJvm)
        kmp.targets.forEach { target ->
            // Skip the metadata/common target — it has no published klib of its own.
            if (target is org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget) return@forEach
            if (target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.common) return@forEach
            val main = target.compilations.findByName("main") ?: return@forEach
            // DECLARED source only — anything under the module's `build/` is dropped (#2374).
            // `kuilt.warp-ops` puts `build/generated/ksp/metadata/commonMain/kotlin` in commonMain,
            // and scanning it cost two things: Gradle 9 failed a module-scoped
            // `:kuilt-warp-test:build` on an implicit dependency on `kspCommonMainKotlinMetadata`
            // (`srcDirs` is a `Set<File>` — nowhere to carry a producer), and, measured rather than
            // theorised, a genuinely source-less target went GREEN as soon as one file sat under
            // `build/`. What the filter costs, stated as a cost: a target whose only Kotlin is
            // generated now reads as source-less — a FALSE POSITIVE, since its compilation would
            // produce a klib and publish fine. Acceptable today because no target here is in that
            // position (the filtered probe going green across every declared target is the proof)
            // and it is unreachable for `kuilt.warp-ops`, whose KSP run generates from `@WarpOp`
            // declarations in hand-written `commonMain`. A judgement call — scanning generated dirs
            // and adding a `dependsOn` on the generator has no false positive, but puts the verdict
            // back at the mercy of whether the generator ran, the dishonesty #2374 exists to end.
            val buildRoot = sub.layout.buildDirectory.get().asFile.toPath()
            val srcDirs = main.allKotlinSourceSets.flatMap { it.kotlin.srcDirs }
                .filterNot { it.toPath().startsWith(buildRoot) }
            val label = "${sub.path} target '${target.targetName}' (${target.platformType})"
            srclessTargetProbes += label to kotlinSourcesIn(srcDirs)
        }
    }
    tasks.named("forbidSourcelessKmpTarget") {
        // One SEPARATELY NAMED input per target, not one merged file input. This guard is the only
        // one whose verdict depends on WHICH tree a source file sits in, so pooling the trees would
        // be a dishonest input: a file moving from a module that has only that one source into
        // another module leaves the union fingerprint unchanged while flipping the first module's
        // targets to source-less — a stale cached success over a real violation, which is precisely
        // the failure the stamp output must not enable. Per-target properties also mean that adding
        // or removing a target changes the SET of input properties, so a newly declared target can
        // never inherit the previous run's verdict.
        srclessTargetProbes.forEach { (label, tree) ->
            inputs.files(tree)
                .withPropertyName("targetSources_" + label.replace(Regex("[^A-Za-z0-9]+"), "_"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

val forbidSourcelessKmpTarget by tasks.registering {
    group = "verification"
    description = "Fails if any subproject declares a KMP target whose main compilation has no Kotlin source (see #1014)."
    val probes = srclessTargetProbes
    // See "Guard plumbing" above: stamp ⇒ UP-TO-DATE (#1827). The inputs are registered per target
    // in the `projectsEvaluated` block above, once the source-set closure is resolvable.
    val stamp = layout.buildDirectory.file("verification/forbid-sourceless-kmp-target.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    doLast {
        val offenders = probes.filter { (_, tree) -> tree.isEmpty }.map { (label, _) ->
            "$label has no Kotlin source — do not declare a target you have no source for (see #1014)."
        }
        if (offenders.isNotEmpty()) {
            error(
                "KMP target(s) declared with no Kotlin source — an empty native/wasm compilation passes " +
                    "`ci-required` but breaks the publish workflow's metadata generation. Add source, or " +
                    "opt the module out of those targets (e.g. kuilt.jvmAndroidOnly=true):\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${probes.size} declared KMP targets checked\n")
    }
}

// Guard: keep `<!-- verbatim from <path>#<symbol> -->` doc citations true (#1792).
//
// Every fenced code block in `docs/`, `Writerside/` and any `<module>/module.md` that claims to be
// copied out of a compiled source carries an HTML citation comment naming that source. Dokka `@sample`
// blocks cannot rot — `src/commonSamples/` is compiled as part of `commonTest` — but a
// block that *quotes* a sample can, and did: an audit of the first 23 found 3 drifted
// (#1791, #1792). The failure is quiet and asymmetric: a block that says "verbatim" and
// isn't is worse than no citation at all, because a reader stops checking. This closes the
// asymmetry — a drifted or dangling citation now fails `check` the way a broken sample does.
//
// Two markers, two strengths:
//   <!-- verbatim  from … -->  the block must appear in the cited source, character for
//                             character, modulo indentation (the four modes below).
//   <!-- condensed from … -->  the block is deliberately abridged or reworded; only the
//                             cited path and symbol have to resolve.
// Both are checked for a dangling reference, so a renamed or deleted symbol fails the build
// instead of quietly becoming a lie — the second, smaller win here.
//
// Match modes, tried in order, each after dropping blank edges and de-indenting:
//   full       the whole cited declaration, including its leading @annotations
//   body       the declaration's body, braces stripped
//   bodySlice  a contiguous run of body lines
//   fullSlice  a contiguous run of declaration lines — also the whole-file mode, used by
//              the citations that name no #symbol
//   elided     ONLY if every mode above failed AND the citation names a #symbol: the block is
//              split on bare `// …` lines and each part must be a contiguous run of the source,
//              in order, non-overlapping, with at least one real line elided at every marker
//              (#1825)
// The slice modes are what let one long E2E test back three separate walkthrough blocks;
// the de-indent is what lets a chunk lifted from inside a nested scope sit flush in a doc;
// the annotation mode is what lets a doc quote `@Test fun …` as written. Anything looser —
// dropping the source's own comments, trimming an assertion message, rewording a line — is
// a condensation, not a quote. Relabel such a block `condensed from` rather than widening
// this list: a check loose enough to pass a paraphrase is not checking anything.
//
// The elided mode exists for one shape the contiguous modes cannot express: a class shell
// with some members left out, which is two slices plus a synthesised `}`. Before #1825 those
// blocks had to be relabelled `condensed`, which stopped checking them entirely — the weakest
// outcome available, since the drift they are likeliest to suffer (a member renamed, the
// shell changed) is precisely what then went unenforced. The marker ADDS an assertion rather
// than removing one: it says "source was omitted HERE", and the ordering, non-overlap and
// minimum-one-line-elided rules are what stop it degrading into "match anything from here
// on". It cannot launder a reordered, reworded or invented line — see matchElided.
//
// It also REQUIRES a #symbol. A symbol-less citation is matched against the whole file, and
// ordered multi-slice over a whole file would let a block draw its parts from two unrelated
// declarations and present them as one flow — every line real, every line in order, and the
// block still misrepresenting the source. That is the single thing the `verbatim` label exists
// to rule out, so it is rejected. Naming the declaration bounds the haystack to it. Note the
// residual, deliberate limit: for a CLASS-level citation the haystack is the whole class, so an
// elision there can still cross member boundaries — bounded by, and attributed to, the
// declaration actually being cited, which is what the citation claims to be showing.
//
// `docs/superpowers/` is exempt (`citationExempt` below). Those are frozen, dated planning
// artifacts whose citations are deliberately unresolved templates
// (`<!-- verbatim from <cited path>#… -->`). It is the one exemption, and it is enforced rather
// than implied: every OTHER markdown file in the tree must either be scanned or carry no citation
// at all, which is what stops #2256 from recurring one filename over.
val verifyDocCitations by tasks.registering {
    group = "verification"
    description = "Fails if a <!-- verbatim from … --> doc citation has drifted from, or dangles off, its source (#1792)."
    val docRoots = listOf(rootDir.resolve("docs"), rootDir.resolve("Writerside"))
        .filter(java.io.File::isDirectory)
    val srcRoots = subprojects.mapNotNull { it.projectDir.resolve("src").takeIf(java.io.File::isDirectory) }
    // Every root a CITED SOURCE may live in. A citation pointing outside these is rejected at
    // execution time (see the check in doLast) rather than being silently exempt from re-running —
    // so this list and the enforcement can never drift apart.
    val inputRoots = docRoots + srcRoots
    inputRoots.forEach { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
    // The third scanned surface, and the one that is a citING file only — nothing cites a `.md`, so
    // it is an input without being an `inputRoots` entry. Declared as the same lazily-resolved set
    // `doLast` walks, per "Guard plumbing" above: without this the stamp would hold a `module.md`
    // citation UP-TO-DATE green after it had drifted, which is the half of #2256 that scanning
    // alone does not fix.
    val moduleDocs = moduleDocFiles()
    inputs.files(moduleDocs).withPropertyName("moduleDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // EVERY markdown file in the tree — not to check it, but to prove nothing was missed. See the
    // unscanned-citation check in doLast: the three surfaces above are a *choice*, and a citation
    // written outside them is read by nobody. `**/build/**` is excluded because other tasks emit
    // `.md` there (119 of them), which would both be meaningless to check and make this task's
    // input overlap another's output; `.claude/worktrees/**` because ephemeral agent worktrees are
    // whole copies of the repo, `docs/` and all.
    val allMarkdown = fileTree(rootDir) {
        include("**/*.md")
        exclude("**/build/**", "**/.git/**", "**/.gradle/**", "**/node_modules/**", "**/.claude/worktrees/**")
    }
    inputs.files(allMarkdown).withPropertyName("allMarkdown")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Prefixes whose markdown may carry citation-shaped lines while being scanned by nobody. This is
    // the ONLY legitimate way to be unscanned and hold a citation, and it is deliberately one entry:
    // `docs/superpowers/` holds frozen, dated planning artifacts whose citations are unresolved
    // templates (`<!-- verbatim from <cited path>#… -->`). Consumed by BOTH the scan filter and the
    // unscanned-citation check, so "not scanned" and "allowed to be unscanned" cannot drift apart.
    val citationExempt = listOf("docs/superpowers/")
    val rootPath = rootDir
    // A stamp file so the task can be UP-TO-DATE: with inputs but no outputs Gradle has to
    // re-run it on every build, which is how a verification task earns a reputation for
    // slowing the build down and then gets deleted.
    val stampFile = layout.buildDirectory.file("doc-citations/verified.txt").get().asFile
    outputs.file(stampFile)
    outputs.cacheIf { true }
    doLast {
        val cite = Regex("""^<!--\s*(verbatim|condensed) from\s+(.+?)\s*-->$""")
        val symRefs = Regex("""#(`[^`]+`|[^\s#]+)""")
        // The elision marker (#1825): a BARE `// …` (or `// ...`) line, nothing else on it. Bare on
        // purpose — trailing prose would make it ambiguous with a real source comment, and the
        // marker has to be unmistakable to a reader as well as to this regex.
        val elision = Regex("""^//\s*(?:…|\.\.\.)$""")

        fun trimBlankEdges(ls: List<String>): List<String> =
            ls.map(String::trimEnd).dropWhile(String::isEmpty).dropLastWhile(String::isEmpty)

        fun dedent(ls: List<String>): List<String> {
            val pad = ls.filter(String::isNotBlank)
                .minOfOrNull { it.takeWhile(Char::isWhitespace).length } ?: 0
            return ls.map { if (it.isBlank()) "" else it.drop(pad) }
        }

        // The canonical form both sides are compared in: no blank edges, no common indent.
        fun canon(ls: List<String>): List<String> = dedent(trimBlankEdges(ls))

        // A "code-only" projection of a whole file: one output line per input line, with the
        // contents of comments and literals blanked out but the line count preserved (so an
        // index into this list is the same index into the raw lines). Locating a declaration
        // and balancing its braces run over THIS list; the content comparison always runs
        // over the raw lines.
        //
        // Blanking comments is not a nicety. KDoc prose in this repo routinely carries braces
        // in inline code spans — e.g. `while (true) { delay(); ping() }` in
        // kuilt-liveness/.../AgentCookbookSamples.kt — and those sit *inside* cited regions.
        // Counting them would let an ordinary comment edit truncate a region (reporting drift
        // in a block nobody touched) or unbalance it outright.
        //
        // Handled: line comments, block comments/KDoc (multi-line, and nested, which Kotlin
        // permits), "…" strings, """…""" raw strings, '…' char literals, and `…` quoted
        // identifiers (a test named `fun \`closes the } group\`()` would otherwise miscount).
        // Known limits, none of which occurs in any cited file today: a `$`-template whose
        // braces span lines, and a declaration whose braces genuinely do not balance — the
        // latter is reported honestly, with relabelling as the way forward.
        fun codeOnly(rawLines: List<String>): List<String> {
            var blockDepth = 0
            var inRaw = false
            return rawLines.map { line ->
                val sb = StringBuilder()
                var i = 0
                var inString = false
                var inChar = false
                var inTick = false
                var escaped = false
                while (i < line.length) {
                    val c = line[i]
                    val two = if (i + 1 < line.length) line.substring(i, i + 2) else ""
                    when {
                        inRaw -> {
                            if (line.startsWith("\"\"\"", i)) {
                                inRaw = false
                                i += 3
                                continue
                            }
                        }
                        blockDepth > 0 -> when {
                            two == "/*" -> { blockDepth++; i += 2; continue }
                            two == "*/" -> { blockDepth--; i += 2; continue }
                            else -> {}
                        }
                        inString -> when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> inString = false
                        }
                        inChar -> when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '\'' -> inChar = false
                        }
                        // A quoted identifier's text is preserved so declStart can still match
                        // it; only braces within are neutralised, so they cannot skew the count.
                        inTick -> {
                            if (c == '`') inTick = false
                            sb.append(if (c == '{' || c == '}') '_' else c)
                        }
                        line.startsWith("\"\"\"", i) -> { inRaw = true; i += 3; continue }
                        two == "//" -> return@map sb.toString()
                        two == "/*" -> { blockDepth++; i += 2; continue }
                        c == '"' -> inString = true
                        c == '\'' -> inChar = true
                        c == '`' -> { inTick = true; sb.append(c) }
                        else -> sb.append(c)
                    }
                    i++
                }
                sb.toString()
            }
        }

        // `codeLines` is always the codeOnly() projection — never the raw lines.
        fun declStart(codeLines: List<String>, symbol: String): Int {
            // Tolerates a type-parameter list and an extension receiver, and the backticks
            // a test-function name carries (`fun \`leader wins\`()`).
            val pat = Regex(
                """(?:^|\s)(?:fun|class|object|interface|val|var)\s+""" +
                    """(?:<[^>]*>\s*)?(?:[\w.<>?, ]*\.)?`?""" +
                    Regex.escape(symbol.trim('`')) + """`?\s*[(<:={]""",
            )
            return codeLines.indexOfFirst { pat.containsMatchIn(it) }
        }

        // Last line of the declaration; -1 if a brace opened and never closed.
        fun declEnd(codeLines: List<String>, start: Int): Int {
            var depth = 0
            var opened = false
            for (i in start until codeLines.size) {
                for (c in codeLines[i]) {
                    if (c == '{') {
                        depth++
                        opened = true
                    } else if (c == '}') {
                        depth--
                    }
                }
                if (opened && depth <= 0) return i
            }
            return if (opened) -1 else start
        }

        fun annotationStart(codeLines: List<String>, start: Int): Int {
            var i = start
            while (i > 0 && codeLines[i - 1].trimStart().startsWith("@")) i--
            return i
        }

        // First line index of the body (exclusive of the opening-brace line), or -1 if the
        // declaration has no body.
        fun bodyStart(codeLines: List<String>, start: Int, end: Int): Int {
            val open = (start..end).firstOrNull { codeLines[it].contains('{') } ?: return -1
            return if (open < end) open + 1 else -1
        }

        // Is `block` a contiguous run of `haystack`? Each candidate window is canonicalised
        // in its own right, so a chunk lifted from a nested scope matches when de-indented.
        fun isSliceOf(block: List<String>, haystack: List<String>): Boolean {
            if (block.isEmpty() || block.size > haystack.size) return false
            return (0..haystack.size - block.size).any { s ->
                canon(haystack.subList(s, s + block.size)) == block
            }
        }

        // The earliest index at or after `from` where `part` sits as a contiguous, canonicalised
        // run of `haystack`; -1 if there is none. Earliest-first is optimal for the ordered walk
        // below: placing a part as early as possible leaves the most room for the parts after it.
        fun sliceIndexOf(part: List<String>, haystack: List<String>, from: Int): Int {
            if (part.isEmpty() || part.size > haystack.size) return -1
            for (s in from..haystack.size - part.size) {
                if (canon(haystack.subList(s, s + part.size)) == part) return s
            }
            return -1
        }

        // Does this part consist of nothing but closing brackets? Such a part is the doc closing a
        // brace that the elided region also closes, and gets anchored to the region's own end (see
        // matchElided) instead of being allowed to match any stray `}` in between.
        fun isCloserOnly(part: List<String>): Boolean =
            part.isNotEmpty() && part.all { line -> line.isBlank() || line.trim().all { it in "}])" } }

        // Ordered, non-overlapping multi-slice match — what an elision marker buys, and the ONLY
        // thing it buys. Returns -1 on success, else the index of the part that could not be placed.
        //
        // Every part must still appear in the source contiguously and character-for-character; the
        // parts must appear in the same ORDER as the doc gives them; and consecutive parts may not
        // overlap or even abut — the search for part k+1 starts one line past the end of part k, so
        // the marker has to elide at least one real line. That last rule is what stops `// …` being
        // free slack: a marker between two adjacent regions is a lie (the block would have matched
        // contiguously without it) and is rejected rather than waved through.
        //
        // What a careless or malicious citation still CANNOT do: reorder lines, invent a line,
        // silently drop a line from inside a quoted run, or paraphrase anything. What it CAN do:
        // hide an arbitrary amount of source at each marker, and — because each part is
        // canonicalised in its own right, as everywhere else here — re-indent one part relative to
        // another. Hiding source is the whole point; if what is hidden matters to the reader, that
        // is a docs-review question, not something the checker can decide.
        fun matchElided(parts: List<List<String>>, haystack: List<String>): Int {
            var cursor = 0
            parts.forEachIndexed { k, part ->
                if (k == parts.lastIndex && k > 0 && isCloserOnly(part)) {
                    // Anchor a trailing all-closers part to the last non-blank lines of the region,
                    // so `}` proves the region's own closer rather than some inner one.
                    val end = haystack.indexOfLast(String::isNotBlank)
                    val s = end - part.size + 1
                    if (s < cursor || s < 0 || canon(haystack.subList(s, end + 1)) != part) return k
                    return -1
                }
                val s = sliceIndexOf(part, haystack, cursor)
                if (s < 0) return k
                cursor = s + part.size + 1 // +1 more so the marker after this part elides a real line
            }
            return -1
        }

        // Length-preserving normalisation, used only for the failure diff: the same de-indent
        // as `canon` but WITHOUT dropping blank edges, so a window's index maps 1:1 onto a raw
        // source line and the two sides never slip out of step (which is what produced
        // spurious "(past end of source)" rows).
        fun align(ls: List<String>): List<String> = dedent(ls.map(String::trimEnd))

        // Name the lines that moved, with context. Someone fixing a citation should not have
        // to re-derive what changed.
        fun showDiff(source: List<String>, doc: List<String>, atLine: Int): String {
            val span = maxOf(source.size, doc.size)
            val differing = (0 until span).filter { source.getOrNull(it) != doc.getOrNull(it) }
            if (differing.isEmpty()) return ""
            val show = (differing.first() - 2)..(differing.last() + 2)
            val sb = StringBuilder(
                "      closest match starts at source line $atLine; " +
                    "${differing.size} of $span lines differ:\n",
            )
            var elided = 0
            for (i in show.first.coerceAtLeast(0) until minOf(span, show.last + 1)) {
                val s = source.getOrNull(i)
                val d = doc.getOrNull(i)
                if (s == d) {
                    // Keep the report to one screen; long runs of agreement carry no signal.
                    if (differing.none { kotlin.math.abs(it - i) <= 2 }) {
                        elided++
                        continue
                    }
                    sb.append("          |  $s\n")
                } else {
                    if (elided > 0) {
                        sb.append("          … $elided identical line(s) …\n")
                        elided = 0
                    }
                    sb.append("        - source: ${s ?: "(past end of source)"}\n")
                    sb.append("        + doc:    ${d ?: "(past end of block)"}\n")
                }
            }
            return sb.toString()
        }

        // Diff the block against whichever stretch of source it most nearly matches, searching
        // EVERY candidate — each region's whole declaration and its body. Searching only one
        // shape is what made this useless for `full`/`fullSlice` citations: a block that quotes
        // the declaration (`@Test fun … { … }`) diffed against the body is offset by the
        // signature, so every line reads as changed and the one line that actually moved never
        // appears. `candidates` are (raw lines, 0-based absolute start line) pairs.
        fun bestDiff(block: List<String>, candidates: List<Pair<List<String>, Int>>): String {
            var bestScore = Int.MAX_VALUE
            var bestWindow: List<String> = emptyList()
            var bestLine = 0
            candidates.forEach { (raw, base) ->
                val hay = align(raw)
                val starts = if (block.size >= hay.size) listOf(0) else (0..hay.size - block.size).toList()
                starts.forEach { s ->
                    val window = hay.subList(s, minOf(s + block.size, hay.size))
                    val score = (0 until maxOf(window.size, block.size))
                        .count { window.getOrNull(it) != block.getOrNull(it) }
                    if (score < bestScore) {
                        bestScore = score
                        bestWindow = window
                        bestLine = base + s + 1
                    }
                }
            }
            return showDiff(bestWindow, block, bestLine)
        }

        fun exempt(f: java.io.File): Boolean {
            val rel = f.relativeTo(rootPath).invariantSeparatorsPath
            return citationExempt.any(rel::startsWith)
        }

        val mdFiles = (
            docRoots.flatMap { root ->
                root.walkTopDown().filter { f -> f.isFile && f.extension == "md" && !exempt(f) }
            } + moduleDocs.files
            ).sortedBy { it.invariantSeparatorsPath }

        // ── The citING side of the `inputRoots` check below ─────────────────────────────────────
        // That check refuses a citation whose cited SOURCE no declared input reaches, on the grounds
        // that a gate nothing invalidates goes green over drift. This is its twin, and it guards the
        // strictly worse case: a citation in a markdown file that is not in `mdFiles` is never read
        // at all. That was #2256 — and fixing it for `module.md` alone would have left the identical
        // failure one filename over, since `kuilt-test/README.md`, `examples/README.md` and
        // `.claude/skills/kuilt-primitives/SKILL.md` are no more scanned than a `module.md` was.
        //
        // The check is deliberately cheap and structural: it does not verify these blocks, it
        // refuses to let one exist unverified. The remedy is to move the block onto a scanned
        // surface or widen the scan — exemption is the last resort, and named as such.
        val scannedPaths = mdFiles.mapTo(mutableSetOf()) { it.invariantSeparatorsPath }
        val stowaways = allMarkdown.files
            .asSequence()
            .filterNot { it.invariantSeparatorsPath in scannedPaths || exempt(it) }
            .sortedBy { it.invariantSeparatorsPath }
            .mapNotNull { md ->
                md.readLines().asSequence().withIndex()
                    .firstOrNull { (_, line) -> cite.matchEntire(line.trim()) != null }
                    ?.let { (n, line) ->
                        "${md.relativeTo(rootPath).invariantSeparatorsPath}:${n + 1}\n      ${line.trim()}"
                    }
            }
            .toList()
        if (stowaways.isNotEmpty()) {
            error(
                "Doc citation(s) in markdown this task does not scan (#2256). An unscanned citation " +
                    "is not a weaker check — it is no check at all, and it passes by never being " +
                    "looked at:\n\n" + stowaways.joinToString("\n\n") + "\n\n" +
                    "Scanned: docs/, Writerside/, and every <subproject>/module.md. Move the block " +
                    "onto one of those surfaces, or widen the scanned set in build.gradle.kts. Add " +
                    "the file to `citationExempt` there ONLY if the line is a deliberately " +
                    "unresolved template — that exempts it from checking for good.\n",
            )
        }

        // Cited source files, parsed once each — several citations share one file.
        val codeCache = mutableMapOf<java.io.File, Pair<List<String>, List<String>>>()
        fun loadSource(f: java.io.File): Pair<List<String>, List<String>> =
            codeCache.getOrPut(f) { f.readLines().let { raw -> raw to codeOnly(raw) } }

        val failures = mutableListOf<String>()
        var checked = 0

        mdFiles.forEach { md ->
            val lines = md.readLines()
            var i = 0
            while (i < lines.size) {
                val m = cite.matchEntire(lines[i].trim())
                if (m == null) {
                    i++
                    continue
                }
                val kind = m.groupValues[1]
                val payload = m.groupValues[2]
                val where = "${md.relativeTo(rootPath).invariantSeparatorsPath}:${i + 1}"
                val label = "<!-- $kind from $payload -->"
                val path = payload.substringBefore('#').trim()
                val symbols = symRefs.findAll(payload).map { it.groupValues[1] }.toList()

                // The block this citation is attached to: the next fenced region.
                var open = i + 1
                while (open < lines.size && lines[open].isBlank()) open++
                val fenced = open < lines.size && lines[open].trimStart().startsWith("```")
                var blockRaw = emptyList<String>()
                if (fenced) {
                    var close = open + 1
                    while (close < lines.size && lines[close].trim() != "```") close++
                    blockRaw = lines.subList(open + 1, minOf(close, lines.size))
                    i = close + 1
                } else {
                    i++
                }
                checked++

                if (!fenced) {
                    failures += "$where\n      $label\n      citation is not followed by a fenced code " +
                        "block, so it cites nothing. Attach it to the block it describes, or delete it."
                    continue
                }
                val srcFile = rootPath.resolve(path)
                if (!srcFile.isFile) {
                    failures += "$where\n      $label\n      cited file does not exist: $path"
                    continue
                }
                // A cited file outside every declared input root would resolve here at execution
                // time but never invalidate the task — leaving a REQUIRED gate green over a
                // drifted citation. That silent pass is the worst failure this task could have,
                // so it is a loud failure instead. `build-logic/` is the live example: an
                // included build, not a subproject, so its sources are not an input. The markdown
                // inputs count too, or this message would state something false about a cited
                // `module.md` — which IS an input, just not via a root.
                if (inputRoots.none { srcFile.startsWith(it) } && srcFile !in allMarkdown.files) {
                    failures += "$where\n      $label\n      cited file is outside every declared task " +
                        "input root, so editing it would NOT re-run this check — a drifted citation " +
                        "would stay green: $path\n      Add its root to verifyDocCitations' inputs " +
                        "(see build.gradle.kts) before citing it."
                    continue
                }
                val (srcLines, srcCode) = loadSource(srcFile)
                val dangling = symbols.filter { declStart(srcCode, it) < 0 }
                if (dangling.isNotEmpty()) {
                    failures += "$where\n      $label\n      cited symbol not found in $path: " +
                        dangling.joinToString(", ") +
                        "\n      Renamed or deleted? Point the citation at the new name, or restore the symbol."
                    continue
                }
                // A condensed block is checked for existence only — the whole point of the
                // weaker marker is that its content is deliberately not a quote. Placed BEFORE
                // brace balancing so that relabelling is always an available way forward: if the
                // region cannot be delimited, `condensed from` still gets the author unstuck.
                if (kind == "condensed") continue

                val block = canon(blockRaw)
                if (block.isEmpty()) {
                    failures += "$where\n      $label\n      the fenced block is empty"
                    continue
                }

                // Per cited symbol: the declaration region and (if any) its body, each with the
                // 0-based absolute line where it starts, so the failure diff can name a real line.
                var unbalanced: String? = null
                val declRegions = mutableListOf<Pair<List<String>, Int>>()
                val bodyRegions = mutableListOf<Pair<List<String>, Int>>()
                if (symbols.isEmpty()) {
                    declRegions += srcLines to 0
                } else {
                    for (symbol in symbols) {
                        val s = declStart(srcCode, symbol)
                        val e = declEnd(srcCode, s)
                        if (e < 0) {
                            unbalanced = symbol
                            break
                        }
                        val a = annotationStart(srcCode, s)
                        declRegions += srcLines.subList(a, e + 1) to a
                        val b = bodyStart(srcCode, s, e)
                        if (b >= 0) bodyRegions += srcLines.subList(b, e) to b
                    }
                }
                if (unbalanced != null) {
                    failures += "$where\n      $label\n      could not delimit `$unbalanced` in $path: " +
                        "its braces do not balance, so the citation cannot be verified. This is a limit " +
                        "of the checker, not necessarily a defect in the source.\n      Relabel the " +
                        "citation `<!-- condensed from $payload -->` to record it as unverified, and " +
                        "please report the declaration that broke it."
                    continue
                }

                val matched = declRegions.indices.any { r ->
                    val decl = declRegions[r].first
                    val body = bodyRegions.getOrNull(r)?.first ?: emptyList()
                    canon(decl) == block ||
                        (body.isNotEmpty() && canon(body) == block) ||
                        (body.isNotEmpty() && isSliceOf(block, body)) ||
                        isSliceOf(block, decl)
                }
                val candidates = declRegions + bodyRegions
                // Elision fallback (#1825). Reached ONLY after every contiguous mode above has
                // failed, which makes it strictly additive: no citation that passes today can start
                // failing because of it, and a source that genuinely contains a `// …` line is still
                // matched literally first.
                val parts = if (matched) emptyList() else block
                    .fold(mutableListOf(mutableListOf<String>())) { acc, line ->
                        if (elision.matches(line.trim())) acc.add(mutableListOf()) else acc.last().add(line)
                        acc
                    }.map { canon(it) }
                val elided = !matched && parts.size > 1
                val elidedResults = if (elided && symbols.isNotEmpty() && parts.none(List<String>::isEmpty)) {
                    candidates.map { matchElided(parts, it.first) }
                } else {
                    emptyList()
                }
                if (elided && parts.any(List<String>::isEmpty)) {
                    failures += "$where\n      $label\n      an elision marker (`// …`) must sit " +
                        "BETWEEN two quoted regions — one at the start or end of the block, or two " +
                        "in a row, elides everything on one side and asserts nothing.\n      Quote " +
                        "the lines around it, or drop the marker and let the block match as a " +
                        "contiguous slice."
                } else if (elided && symbols.isEmpty()) {
                    // Without a #symbol the haystack is the WHOLE FILE, and ordered multi-slice over a
                    // whole file lets a block present lines from two unrelated declarations as one
                    // flow — e.g. a `require(...)` from one function above a field assignment from
                    // another, reading as if the first guarded the second. Every line would be real
                    // and in order, and the block would still misrepresent the source, which is the
                    // one thing `verbatim` exists to rule out. Naming the declaration bounds the
                    // haystack to it, so a cross-declaration splice is unrepresentable.
                    failures += "$where\n      $label\n      this block uses an elision marker " +
                        "(`// …`) but the citation names no `#symbol`, so it would be matched against " +
                        "the whole file — which would let the block splice lines from two unrelated " +
                        "declarations into one apparent flow.\n      Name the declaration the block " +
                        "comes from (`<!-- verbatim from $path#<symbol> -->`), or drop the marker and " +
                        "quote a contiguous run."
                } else if (elidedResults.isNotEmpty() && elidedResults.none { it < 0 }) {
                    // Report against the candidate that got FURTHEST before failing — the one the
                    // author most likely meant.
                    val failedPart = elidedResults.max()
                    // The likeliest authoring mistake is not drift but ORDER — parts quoted out of
                    // source order, or a marker between two adjacent lines that elides nothing. In
                    // both the part is present and a line-by-line diff is noise, so say what is
                    // actually wrong instead.
                    val strayAt = candidates.firstNotNullOfOrNull { (raw, base) ->
                        sliceIndexOf(parts[failedPart], raw, 0).takeIf { it >= 0 }?.let { base + it + 1 }
                    }
                    failures += "$where\n      $label\n      the block does not appear in the cited " +
                        "source: part ${failedPart + 1} of ${parts.size} (the lines " +
                        (if (failedPart == 0) "before the first `// …`" else "after `// …` #$failedPart") +
                        ") is not there, in order, after the part before it.\n" +
                        if (strayAt != null) {
                            "      That part IS in $path, at line $strayAt — just not after the part " +
                                "it is quoted below. Parts must appear in source order, and each " +
                                "`// …` must elide at least one real line (a marker between two " +
                                "adjacent lines asserts an omission that did not happen). Reorder " +
                                "the block to match the source, or drop the marker."
                        } else {
                            bestDiff(parts[failedPart], candidates) +
                                "      Each part must still be a contiguous, character-for-character " +
                                "run of $path, and `// …` must elide at least one real line. Re-copy " +
                                "the part that moved."
                        }
                } else if (!matched && elidedResults.isEmpty()) {
                    val widest = candidates.maxOf { canon(it.first).size }
                    failures += "$where\n      $label\n      the block (${block.size} lines) does not " +
                        "appear in the cited source (up to $widest lines) in any accepted form.\n" +
                        bestDiff(block, candidates) +
                        "      Re-copy the block from $path, or mark an omitted region with a bare " +
                        "`// …` line. Relabel the citation " +
                        "`<!-- condensed from $payload -->` only if the block cannot be a literal " +
                        "quote — that exempts it from content checking for good."
                }
            }
        }

        if (failures.isNotEmpty()) {
            error(
                "Doc citation(s) out of sync with the source they name (#1792). A block labelled " +
                    "`verbatim from` must still appear in that source:\n\n" +
                    failures.joinToString("\n\n") + "\n",
            )
        }
        stampFile.parentFile.mkdirs()
        stampFile.writeText("verified $checked citations across ${mdFiles.size} markdown files\n")
        logger.info("verifyDocCitations: $checked citations across ${mdFiles.size} markdown files")
    }
}

// Guard: every `@sample` KDoc tag must name a sample Dokka can actually resolve (#2259).
//
// A sample's BODY cannot rot — `src/commonSamples/kotlin` is compiled into `commonTest`, so a broken
// sample breaks the build. The one part that could rot silently was the LINK: nothing read the tag.
// Rename the sample, move it to another module, or typo the package, and Dokka emits a warning that
// `failOnWarning` (unset repo-wide) drops on the floor, while the API page renders the raw FQN text
// where the example should be. Green build, broken docs, and the only way to notice was to open
// `build/dokka/html/` by hand.
//
// ── The resolution rule, established by PROBING Dokka rather than assuming ──────────────────────
// Each claim below was tested by planting the spelling in `:kuilt-heddle` and reading both the
// generator's warnings and the emitted HTML. Absence of a warning proves nothing on its own — an
// unparsed tag is silent too — so every probe was confirmed against whether the page rendered code.
// `@sample X` resolves if and only if:
//
//   1. the tag STARTS a KDoc line (after the optional leading `*`). Mid-line — `/** Blah. @sample
//      pkg.f */` — Dokka does not parse it as a tag AT ALL: no warning, no sample, nothing;
//   2. `X` is FULLY QUALIFIED. A bare `sampleFoo`, or a tail like `heddle.sampleFoo`, does NOT
//      resolve even when the sample sits in the citing file's own package. Surrounding KDoc-link
//      brackets (`@sample [pkg.f]`) are accepted and stripped;
//   3. `X` names a FUNCTION. A class or object is refused outright ("Only function links allowed");
//   4. that function is declared at top level OR as a member of a type (`pkg.Holder.sampleFoo`
//      resolves — `:kuilt-cluster` relies on it). A LOCAL function nested in another function's body
//      does not;
//   5. of ANY visibility — `private` resolves. Dokka's own message says "top-level" for both this
//      and (4); the message is wrong, and matching the message rather than the behaviour would have
//      false-red two shipped modules;
//   6. it lives under the CITING MODULE's `src/commonSamples/kotlin`. That is the second, quieter
//      edge of this bug: `samples.from(samplesDir)` in `kuilt.kmp-library` registers one samples root
//      per module, so a tag naming a real function in a SIBLING module's samples compiles, reads
//      correctly to a human, and still renders nothing. A plain `commonTest` function does not
//      resolve either — being on the compile path is not the same as being in the samples root.
//
// An extension receiver is absent from the FQN (`fun CoroutineScope.sampleX` ⇒ `pkg.sampleX`), which
// is why the name this guard indexes is the trailing identifier before the parameter list rather
// than anything parsed out of a receiver.
//
// ── Why it cannot be quietly bypassed ───────────────────────────────────────────────────────────
// Rule 6 is a MODEL of a wiring that lives somewhere else, and `verifyDocCitations` already learned
// what happens when a guard's model can drift from the thing it models: it goes green over exactly
// the case it exists to catch. So the model is asserted, not assumed. This task fails loudly if the
// `samples.from(samplesDir)` wiring is no longer in `kuilt.kmp-library`, and if any module's own
// build script registers a samples root of its own — either would mean a resolvable link this guard
// calls dangling (loud but wrong) or, far worse, a dangling one it calls fine.
//
// ── Known limits, all of which fail LOUD ────────────────────────────────────────────────────────
// The declaration scan is lexical, over `KotlinCodeScanner.stripNonCode` so prose and literals are
// invisible. It reads a function's name as the trailing identifier between `fun` and the first `(`
// after it, which a receiver that is itself a function type (`fun ((Int) -> Unit).f()`) would defeat;
// none exists in tree. A missed declaration makes a good link read as dangling — a false RED, which
// someone sees, rather than a false green, which nobody does. That asymmetry is the design.
val verifySampleLinks by tasks.registering {
    group = "verification"
    description = "Fails if an @sample KDoc tag names a sample Dokka cannot resolve (#2259)."
    // A module is keyed by its directory RELATIVE TO THE ROOT, not by its Gradle name, so every path
    // this task prints is one a reader can paste (`:demo-web` lives at `demo/web`). Longest-path
    // first, so mapping a file to its module is a first-match on a prefix even when one module's
    // directory sits inside another's.
    val moduleDirs = subprojects
        .map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath to it.projectDir }
        .filter { it.second.resolve("src").isDirectory }
        .sortedByDescending { it.second.invariantSeparatorsPath.length }
    // One tree covers both halves of the question: the tags (KDoc anywhere under `src/`) and the
    // samples they name (`src/commonSamples/kotlin`). Declared as the same lazily-resolved set that
    // `doLast` walks, per "Guard plumbing" above, so the two cannot drift.
    val sources = kotlinSourcesIn(moduleDirs.map { it.second.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `module.md` carries `@sample` tags too, and a module.md-only PR is docs-only — which is why
    // this task is also run by CI's `doc-citations` job, exactly as its sibling is. Shared with
    // `verifyDocCitations`, which reads the same files for citations (#2256).
    val moduleDocs = moduleDocFiles()
    inputs.files(moduleDocs).withPropertyName("moduleDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The two files that define the wiring this guard models; see the bypass note above.
    val samplesWiring = rootDir.resolve("build-logic/src/main/kotlin/kuilt.kmp-library.gradle.kts")
    inputs.file(samplesWiring).withPropertyName("samplesWiring")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val buildScripts = fileTree(rootDir) { include("*/build.gradle.kts") }
    inputs.files(buildScripts).withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a pure function of the scanned files' contents, which a RELATIVE fingerprint captures.
    val stamp = layout.buildDirectory.file("verification/verify-sample-links.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val funToken = Regex("""(?<![A-Za-z0-9_])fun(?![A-Za-z0-9_])""")
        val typeDecl = Regex("""(?<![A-Za-z0-9_])(?:class|interface|object)\s+([A-Za-z_]\w*)""")
        val companionDecl = Regex("""(?<![A-Za-z0-9_])companion\s+object(?:\s+([A-Za-z_]\w*))?""")
        val packageDecl = Regex("""(?m)^\s*package\s+([\w.]+)""")
        val namedDecl = Regex(
            """(?<![A-Za-z0-9_])(?:class|interface|object|fun|val|var)\s+""" +
                """(?:<[^>]*>\s*)?(?:[\w.<>?, ]*\.)?(`[^`]+`|[A-Za-z_]\w*)""",
        )
        // A `@sample` written somewhere it will never be read. Two narrowings keep it off prose,
        // because this is the one check whose subject is text a doc author may legitimately write:
        // the argument must be DOTTED (so "the @sample tag is…" is not a candidate), and a leading
        // backtick disqualifies it (so KDoc *about* a tag — `@sample pkg.f` — is quoting, not
        // tagging). Both forms occur in tree; neither is a defect.
        val strayTag = Regex("""(?<!`)@sample\s+\[?[A-Za-z_]\w*(?:\.\w+)+""")

        // ── The model check. A guard whose model of the world can silently go stale has stopped
        // being a guard; `verifyDocCitations` carries the same check for the same reason. ──
        // The call is read from CODE, so wiring that has been commented out registers as gone. The
        // PATH is a string literal, which `stripNonCode` necessarily blanks, so it is read from the
        // raw text — the one place here where prose and code cannot be told apart, and the reason
        // both halves are required rather than either alone.
        val wiringRaw = samplesWiring.readText()
        val wiringCode = KotlinCodeScanner.stripNonCode(wiringRaw)
        if (!wiringCode.contains("samples.from(samplesDir)") || !wiringRaw.contains("src/commonSamples/kotlin")) {
            error(
                "verifySampleLinks resolves every @sample against `<module>/src/commonSamples/kotlin`, " +
                    "because that is the ONLY samples root `kuilt.kmp-library` hands Dokka. That wiring " +
                    "is no longer there, so this guard's model of what Dokka can see is stale and its " +
                    "verdicts cannot be trusted in either direction.\n  " +
                    samplesWiring.relativeTo(rootPath).invariantSeparatorsPath + "\n" +
                    "Update this task alongside the wiring (build.gradle.kts, #2259).",
            )
        }
        val strayRoots = buildScripts.files.sortedBy { it.invariantSeparatorsPath }
            .filter { KotlinCodeScanner.stripNonCode(it.readText()).contains("samples.from(") }
            .map { it.relativeTo(rootPath).invariantSeparatorsPath }
        if (strayRoots.isNotEmpty()) {
            error(
                "A module registers a Dokka samples root of its own, so `<module>/src/commonSamples/" +
                    "kotlin` is no longer the whole story and this guard would call a RESOLVABLE " +
                    "@sample dangling — or miss a dangling one:\n  " + strayRoots.joinToString("\n  ") +
                    "\nTeach verifySampleLinks about the extra root before adding it (#2259).",
            )
        }

        fun moduleOf(f: java.io.File): String? =
            moduleDirs.firstOrNull { (_, dir) -> f.startsWith(dir) }?.first

        // A function's own name: the trailing identifier of the text between `fun` and its parameter
        // list. Reading it from the END is what makes a type-parameter list and an extension receiver
        // both irrelevant — `fun <T> CoroutineScope.sampleX(` is just `sampleX`, which is exactly the
        // name Dokka resolves.
        fun trailingName(between: String): String? {
            var e = between.length
            while (e > 0 && between[e - 1].isWhitespace()) e--
            if (e == 0) return null
            if (between[e - 1] == '`') {
                val s = between.lastIndexOf('`', e - 2)
                return if (s < 0) null else between.substring(s + 1, e - 1)
            }
            var s = e
            while (s > 0 && (between[s - 1].isLetterOrDigit() || between[s - 1] == '_')) s--
            return if (s == e) null else between.substring(s, e)
        }

        // ── Index every function (and type, for the diagnosis) each module's samples root declares.
        val sampleFuns = mutableMapOf<String, MutableSet<String>>()
        val sampleTypes = mutableMapOf<String, MutableSet<String>>()

        fun indexSamples(module: String, code: String) {
            val pkg = packageDecl.find(code)?.groupValues?.get(1).orEmpty()
            val funs = sampleFuns.getOrPut(module) { mutableSetOf() }
            val types = sampleTypes.getOrPut(module) { mutableSetOf() }
            fun qualify(chain: List<String>, name: String): String =
                (listOfNotNull(pkg.takeIf(String::isNotEmpty)) + chain + name).joinToString(".")
            // One frame per `{`. A type body contributes its name to the qualification chain;
            // anything else — a function body, an `init`, a lambda, a `when` — contributes null, and
            // a null anywhere on the stack means nothing declared below it is addressable by FQN.
            val stack = mutableListOf<String?>()
            val funEnds = funToken.findAll(code).map { it.range.last + 1 }.toList()
            var nextFun = 0
            var headerStart = 0
            var i = 0
            while (i < code.length) {
                if (nextFun < funEnds.size && funEnds[nextFun] == i) {
                    if (stack.all { it != null }) {
                        val paren = code.indexOf('(', i)
                        if (paren > i) {
                            trailingName(code.substring(i, paren))
                                ?.let { funs += qualify(stack.filterNotNull(), it) }
                        }
                    }
                    nextFun++
                }
                when (code[i]) {
                    '{' -> {
                        val header = code.substring(headerStart, i)
                        val decl = listOfNotNull(
                            companionDecl.findAll(header).lastOrNull(),
                            typeDecl.findAll(header).lastOrNull(),
                        ).maxByOrNull { it.range.first }
                        // A `fun` after the type keyword means the brace belongs to the function, not
                        // to a body-less `class A` further up the unreset header.
                        val name = decl
                            ?.takeIf { funToken.find(header, it.range.last) == null }
                            ?.let { it.groupValues[1].ifEmpty { "Companion" } }
                        val chain = stack.filterNotNull()
                        val addressable = name != null && chain.size == stack.size
                        if (name != null && addressable) types += qualify(chain, name)
                        stack.add(if (addressable) name else null)
                        headerStart = i + 1
                    }
                    '}' -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        headerStart = i + 1
                    }
                    ';' -> headerStart = i + 1
                }
                i++
            }
        }

        val kotlinFiles = sources.files.sortedBy { it.invariantSeparatorsPath }
        val samplesMarker = "/src/commonSamples/kotlin/"
        kotlinFiles.filter { it.invariantSeparatorsPath.contains(samplesMarker) }.forEach { f ->
            moduleOf(f)?.let { indexSamples(it, KotlinCodeScanner.stripNonCode(f.readText())) }
        }
        val bySimpleName = sampleFuns.asSequence()
            .flatMap { (module, fqns) -> fqns.asSequence().map { it.substringAfterLast('.') to (module to it) } }
            .groupBy({ it.first }, { it.second })

        // ── Collect and check every tag. ────────────────────────────────────────────────────────
        // The KDoc line stripped of its frame: leading `/**`, the per-line `*`, and a trailing `*/`.
        fun kdocContent(line: String): String {
            var s = line.trim()
            if (s.startsWith("/**")) s = s.removePrefix("/**").trimStart()
            if (s.startsWith("*") && !s.startsWith("*/")) s = s.removePrefix("*").trimStart()
            if (s.endsWith("*/")) s = s.dropLast(2).trimEnd()
            return s
        }

        // A block tag, not merely a line that starts with those seven characters: `@sampleFoo` is a
        // different tag (or a typo), never this one.
        fun isSampleTag(content: String): Boolean =
            content == "@sample" || content.startsWith("@sample ") || content.startsWith("@sample\t")

        val failures = mutableListOf<String>()
        var checked = 0

        // Name the samples that DO exist under a near-miss name. A dangling link is nearly always a
        // rename, so the fix is usually sitting in this list.
        fun List<Pair<String, String>>.hint(prefix: String): String =
            if (isEmpty()) "" else joinToString(", ", prefix, ".") { (m, fqn) -> "`$fqn` (in $m)" }

        fun verify(where: String, module: String, declaredIn: String, raw: String) {
            checked++
            val target = raw.trim().removeSurrounding("[", "]").trim()
            val head = "$where\n      $declaredIn\n      @sample $raw\n      "
            val funs = sampleFuns[module].orEmpty()
            if (target in funs) return
            val samplesRoot = "$module/src/commonSamples/kotlin"
            val elsewhere = sampleFuns.filterKeys { it != module }.filterValues { target in it }.keys
            val alike = bySimpleName[target.substringAfterLast('.')].orEmpty()
                .filterNot { it.second == target }
            failures += head + when {
                target.isEmpty() ->
                    "the tag names nothing. Give it the fully-qualified name of a sample function, " +
                        "or delete the tag."
                // Deliberately strict: the whole remainder of the line is the name. Guessing that
                // Dokka takes only the first token would make this guard pass a tag it might well
                // reject, and a guard is worth having only in the direction that fails LOUD.
                target.any(Char::isWhitespace) ->
                    "a @sample takes one fully-qualified name and nothing else — the rest of the " +
                        "line is read as part of the name. Move any commentary to its own line."
                target in sampleTypes[module].orEmpty() ->
                    "that names a class or object. Dokka accepts only a FUNCTION here (\"Only " +
                        "function links allowed\") — name the function inside it, " +
                        "`$target.<function>`."
                elsewhere.isNotEmpty() ->
                    "that function exists, but in ${elsewhere.joinToString(", ")} — not in " +
                        "$samplesRoot.\n      `kuilt.kmp-library` gives each module ONLY its own " +
                        "samples root, so a sibling module's sample renders nothing here. Copy the " +
                        "sample into $samplesRoot, or point the tag at one that already lives there."
                !target.contains('.') ->
                    "@sample resolves by FULLY-QUALIFIED name only — a bare name never resolves, " +
                        "even when the sample is in this file's own package." +
                        alike.hint("      Did you mean ")
                else ->
                    "no function with that fully-qualified name is declared in $samplesRoot.\n" +
                        "      Renamed, deleted, or never written? Point the tag at the sample's " +
                        "current name, or add the sample." + alike.hint("\n      Nearest match: ")
            }
        }

        kotlinFiles.forEach { file ->
            val module = moduleOf(file) ?: return@forEach
            val text = file.readText()
            val doc = KdocScanner.kdocOnly(text).lines()
            if (doc.none { it.contains("@sample") }) return@forEach
            val code = KotlinCodeScanner.stripNonCode(text).lines()
            val rel = file.relativeTo(rootPath).invariantSeparatorsPath
            // The declaration a tag documents: the first named one at or below the KDoc's close.
            fun declaredAt(line: Int): String {
                var i = line
                while (i < doc.size && !doc[i].contains("*/")) i++
                while (++i < code.size) {
                    namedDecl.find(code[i])?.let { return "in `${it.groupValues[1].trim('`')}`" }
                }
                return "at top of file"
            }
            doc.forEachIndexed { i, line ->
                val content = kdocContent(line)
                if (isSampleTag(content)) {
                    verify("$rel:${i + 1}", module, declaredAt(i), content.removePrefix("@sample").trim())
                } else if (strayTag.containsMatchIn(line)) {
                    checked++
                    failures += "$rel:${i + 1}\n      ${declaredAt(i)}\n      ${line.trim()}\n      " +
                        "a `@sample` is a KDoc BLOCK TAG and must START its line. Written mid-line it " +
                        "is not parsed as a tag at all — Dokka renders no sample and warns about " +
                        "nothing.\n      Move it onto its own line."
                }
            }
        }

        moduleDocs.files.sortedBy { it.invariantSeparatorsPath }.forEach { md ->
            val module = moduleOf(md) ?: return@forEach
            val rel = md.relativeTo(rootPath).invariantSeparatorsPath
            md.readLines().forEachIndexed { i, line ->
                val content = kdocContent(line)
                if (isSampleTag(content)) {
                    verify("$rel:${i + 1}", module, "in the module doc", content.removePrefix("@sample").trim())
                }
            }
        }

        if (failures.isNotEmpty()) {
            error(
                "Dangling @sample link(s) (#2259). A sample's body is compiled, but its LINK is not " +
                    "— a tag Dokka cannot resolve renders the raw name where the example should be, " +
                    "and warns into a build nobody fails:\n\n" + failures.joinToString("\n\n") + "\n",
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — $checked @sample links across ${sampleFuns.size} modules\n")
        logger.info("verifySampleLinks: $checked @sample links across ${sampleFuns.size} modules")
    }
}

// Guard: every callable `@sample` must actually be CALLED by a test (#2116).
//
// Its sibling `verifySampleLinks` asks whether a `@sample` tag resolves. `verifyDocCitations` asks
// whether a quoted block still matches its source. Neither asks the only question that catches a
// sample which is simply WRONG, and this one does: **is anything running it?**
//
// `src/commonSamples/kotlin` is compiled into `commonTest`, so a stale API breaks the build — which
// is why this repo calls samples load-bearing. But compiling is not running. Nothing ever CALLED
// one, so every `check(…)` and `assertEquals(…)` inside a sample was dead code, and a sample whose
// claim had quietly become false stayed green forever. That is not hypothetical: #2110 found two
// `:kuilt-crdt` samples asserting things that were false — one built an `ORSet` re-add reusing a dot
// the remover had already witnessed, so the element it claimed survived was in fact dropped. Both
// compiled. Both were quoted VERBATIM into `docs/agent-cookbook.md` and a Writerside topic. And
// `verifyDocCitations` faithfully proved the quotes matched their source: the gate did its job
// perfectly and certified a lie, because its job is quote fidelity, not truth.
//
// #2110 fixed those two and added `CrdtSamplesRunTest`. That fix decayed within weeks — by the time
// this guard was written, `sampleRgaHeadWindow` and `sampleOpLogCrdt` had been added to
// `CrdtSamples.kt` and nobody had added them to the hand-maintained list, so they were back to being
// unexecuted. A hand-maintained inventory beside a machine-readable one always loses. Hence a guard
// rather than a convention: this is the repo's "survey the category, then make it impossible".
//
// ── THE SUBJECT, and why it is arity and not assertions ─────────────────────────────────────────
// A top-level, non-`private`, ZERO-PARAMETER function declared under a module's
// `src/commonSamples/kotlin`. It must be named by that module's own test sources (`src/*Test/`).
//
// The tempting alternative — "a sample that CONTAINS an assertion must be run", which names the
// actual defect rather than a proxy — was tried first and rejected. It needs a lexical
// assertion-detector, and a fuzzy predicate inside a `check`-wired guard fails in the SILENT
// direction: a sample the detector does not recognise is exempted with no output. Worse, it hands
// out an evasion that looks like a cleanup — delete the `check(…)` and the sample leaves the
// subject set entirely. Arity is crisp, has no false negatives, and its only dodge (add a
// parameter) changes the documentation the sample exists to be, in a diff a reviewer reads.
//
// TWO EXCLUSIONS, both load-bearing:
//   * `private` — every `private` function in a samples file is a stand-in helper for a
//     parameterised sample (`ship`, `retryFrom`, `trimTheLiveReplicaWindow` in `:kuilt-bolt`). It is
//     invisible outside its file, so no test COULD call it; requiring one would be a false red.
//   * PARAMETERISED — `sampleBoltReplayVerdict(bolt)`, `:kuilt-session`'s whole cookbook file,
//     `:kuilt-otel-tap`'s seven. Calling one needs a fixture, and a fixture invented to satisfy a
//     guard is precisely how a runner ends up executing a sample while proving nothing. Note the
//     shape of what this exempts: `:kuilt-session`'s samples take a live `Room`, assert nothing (every
//     branch is `Unit`), and several `collect` forever. Forcing those into a runner would manufacture
//     a green that means "it did not throw before we cancelled it" and nothing else.
//
// ── WHAT A GREEN HERE DOES NOT MEAN ─────────────────────────────────────────────────────────────
// That a sample RAN, only. A sample with no assertions — `:kuilt-otel`'s `sampleOtlpEdge` has a body
// of pure comments — satisfies this guard and proves nothing. The runners say so at their call
// sites rather than leaving a reader to assume coverage. Adding assertions to such a sample to make
// it meaningful would mean inventing claims about documentation, which is the defect #2116 exists to
// catch, so the honest move is to name them and stop.
//
// ── THE BASELINE, and its one honest limit ──────────────────────────────────────────────────────
// Three samples FAIL when executed (#2289 owns them), so they are recorded here rather than
// red-lighting the build. Each entry must carry a reason naming an issue — the
// `forbidProductionDispatcherInTests` rule that a reasonless marker is itself a violation, one turn
// tighter. And the baseline is checked in BOTH directions: an entry for a sample that no longer
// exists, or that a test now DOES call, fails the build, so it cannot rot into a silent exemption.
//
// The limit, stated rather than implied: this baseline **cannot mechanically refuse to grow.** It is
// a literal in the same build script as the guard, so anyone adding an entry is already editing the
// file that would have to forbid it — no scan can tell "burning down" from "giving up". A baseline
// anyone can append to is decoration unless a reviewer reads it, and that is the honest description
// of this one. What it buys is that appending is a deliberate, self-documenting act with an issue
// number attached, and that a stale entry is loud. What actually keeps a NEW sample from reverting
// to unexecuted is the guard itself, which has no allowlist of its own: a fresh sample is in the
// subject set the moment it is written, and the correct count of new baseline entries is zero.
//
// ── KNOWN LIMITS, all of which fail LOUD ────────────────────────────────────────────────────────
// The scan is lexical, over `KotlinCodeScanner.stripNonCode`, so prose and literals are invisible.
// A reference is a whole-word occurrence of the name in a test source — `::sampleFoo`, `sampleFoo()`
// and a mention in a KDoc-stripped identifier position all count, so a test naming a sample and NOT
// calling it would satisfy this guard. That is deliberate: distinguishing them needs call-graph
// resolution, which is not something a lexical scanner should be asked to do, and the failure mode
// is a reviewer seeing a name with no call — visible in the diff — rather than a silent green.
val unrunSampleBaseline: Map<String, String> = mapOf()

val verifySamplesAreRun by tasks.registering {
    group = "verification"
    description = "Fails if a callable @sample is never called by a test — compiling is not running (#2116)."
    // Longest-path first, so mapping a file to its module is a first-match on a prefix even when one
    // module's directory sits inside another's. Same derivation as `verifySampleLinks`.
    val moduleDirs = subprojects
        .map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath to it.projectDir }
        .filter { it.second.resolve("src").isDirectory }
        .sortedByDescending { it.second.invariantSeparatorsPath.length }
    // Both halves of the question in one lazily-resolved set that `doLast` walks, per "Guard
    // plumbing" above: the declarations (`src/commonSamples/`) and the references to them
    // (`src/*Test/`). `commonSamples` does not end in `Test`, so the two patterns cannot overlap —
    // which is what keeps a sample from vouching for itself by being referenced by a sibling sample.
    val sources = kotlinSourcesIn(
        moduleDirs.map { it.second.resolve("src") },
        listOf("commonSamples/**/*.kt", "*Test/**/*.kt"),
    )
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The wiring this guard models; see the model check below.
    val samplesWiring = rootDir.resolve("build-logic/src/main/kotlin/kuilt.kmp-library.gradle.kts")
    inputs.file(samplesWiring).withPropertyName("samplesWiring")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a pure function of the scanned files' contents plus the baseline, and the baseline is a
    // literal in this script, so it is folded into the task-action implementation hash.
    val stamp = layout.buildDirectory.file("verification/verify-samples-are-run.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val baseline = unrunSampleBaseline
    // Captured out here, not read inside `doLast`: a script-level reference in the task action
    // captures the `Build_gradle` instance, which the configuration cache cannot serialize. The
    // same reason `KotlinCodeScanner` is an `object` — see its note above.
    val rootPath = rootDir
    doLast {
        // ── The model check. A guard whose model of the world can silently go stale has stopped
        // being a guard; `verifySampleLinks` and `verifyDocCitations` carry the same check. ──
        // This guard's whole premise is that a sample is ON THE TEST COMPILE PATH, so a test can
        // call it by name. That is one `srcDir` line in the convention plugin. Without it a sample
        // is not compiled at all, every reference stops resolving, and requiring one would be
        // nonsense. Read from CODE, so wiring that has been commented out registers as gone; the
        // PATH is a string literal, which `stripNonCode` necessarily blanks, so it is read raw.
        val wiringRaw = samplesWiring.readText()
        val wiringCode = KotlinCodeScanner.stripNonCode(wiringRaw)
        if (!wiringCode.contains("kotlin.srcDir(samplesDir)") ||
            !wiringRaw.contains("src/commonSamples/kotlin")
        ) {
            error(
                "verifySamplesAreRun requires every sample to be reachable from its module's tests, " +
                    "which is true only because `kuilt.kmp-library` adds `src/commonSamples/kotlin` " +
                    "to `commonTest`'s source roots. That wiring is no longer there, so a sample is " +
                    "not on the test compile path and this guard's demand is unmeetable:\n  " +
                    samplesWiring.relativeTo(rootPath).invariantSeparatorsPath + "\n" +
                    "Update this task alongside the wiring (build.gradle.kts, #2116).",
            )
        }

        fun moduleOf(f: java.io.File): String? =
            moduleDirs.firstOrNull { (_, dir) -> f.startsWith(dir) }?.first

        // Every modifier Kotlin allows in front of `fun`. Reading BACKWARDS over these is what makes
        // the visibility test independent of how the modifiers are ordered or line-wrapped, and it
        // stops cleanly at an annotation: `@Suppress("unused")` strips to `@Suppress(        )`, and
        // a `)` is not an identifier character, so the walk ends there rather than reading into it.
        val funModifiers = setOf(
            "public", "internal", "private", "protected", "suspend", "inline", "noinline",
            "crossinline", "expect", "actual", "external", "infix", "operator", "tailrec",
            "open", "override", "final", "abstract", "annotation",
        )
        fun modifiersBefore(code: String, funStart: Int): Set<String> {
            val mods = mutableSetOf<String>()
            var i = funStart
            while (true) {
                while (i > 0 && code[i - 1].isWhitespace()) i--
                var s = i
                while (s > 0 && (code[s - 1].isLetterOrDigit() || code[s - 1] == '_')) s--
                if (s == i) break
                val word = code.substring(s, i)
                if (word !in funModifiers) break
                mods += word
                i = s
            }
            return mods
        }

        // A function's own name: the trailing identifier of the text between `fun` and its parameter
        // list. Reading from the END makes a type-parameter list and an extension receiver both
        // irrelevant — `fun <T> CoroutineScope.sampleX(` is just `sampleX`. Same rule as
        // `verifySampleLinks`, for the same reason.
        fun trailingName(between: String): String? {
            var e = between.length
            while (e > 0 && between[e - 1].isWhitespace()) e--
            if (e == 0) return null
            if (between[e - 1] == '`') {
                val s = between.lastIndexOf('`', e - 2)
                return if (s < 0) null else between.substring(s + 1, e - 1)
            }
            var s = e
            while (s > 0 && (between[s - 1].isLetterOrDigit() || between[s - 1] == '_')) s--
            return if (s == e) null else between.substring(s, e)
        }

        val funToken = Regex("""(?<![A-Za-z0-9_])fun(?![A-Za-z0-9_])""")
        // A declaration is in the subject set iff it is at brace depth 0 (top level — a member or a
        // local function is not addressable the same way and is never a sample), not `private`, and
        // takes no parameters. Held as a `Triple(module, name, "file:line")` rather than a local
        // data class: a class declared in the task action captures the enclosing script object,
        // which the configuration cache refuses to serialize.
        val declared = mutableListOf<Triple<String, String, String>>()
        val samplesMarker = "/src/commonSamples/"
        val allFiles = sources.files.sortedBy { it.invariantSeparatorsPath }
        allFiles.filter { it.invariantSeparatorsPath.contains(samplesMarker) }.forEach { f ->
            val module = moduleOf(f) ?: return@forEach
            val code = KotlinCodeScanner.stripNonCode(f.readText())
            val rel = f.relativeTo(rootPath).invariantSeparatorsPath
            var depth = 0
            var i = 0
            val funStarts = funToken.findAll(code).associateBy({ it.range.first }, { it.range.last + 1 })
            while (i < code.length) {
                when (code[i]) {
                    '{' -> depth++
                    '}' -> if (depth > 0) depth--
                }
                val end = funStarts[i]
                if (end != null && depth == 0) {
                    val open = code.indexOf('(', end)
                    if (open > end) {
                        var d = 0
                        var j = open
                        while (j < code.length) {
                            if (code[j] == '(') d++
                            if (code[j] == ')') { d--; if (d == 0) break }
                            j++
                        }
                        val name = trailingName(code.substring(end, open))
                        val zeroArg = j < code.length && code.substring(open + 1, j).isBlank()
                        val mods = modifiersBefore(code, i)
                        if (name != null && zeroArg && "private" !in mods) {
                            val line = code.take(i).count { c -> c == '\n' } + 1
                            declared += Triple(module, name, "$rel:$line")
                        }
                    }
                }
                i++
            }
        }

        // Every identifier a module's own tests mention, in CODE — a name in a comment is not a call.
        val referenced = mutableMapOf<String, MutableSet<String>>()
        val identifier = Regex("""[A-Za-z_]\w*""")
        allFiles.filterNot { it.invariantSeparatorsPath.contains(samplesMarker) }.forEach { f ->
            val module = moduleOf(f) ?: return@forEach
            val code = KotlinCodeScanner.stripNonCode(f.readText())
            referenced.getOrPut(module) { mutableSetOf() } += identifier.findAll(code).map { it.value }
        }

        fun key(s: Triple<String, String, String>) = "${s.first}/${s.second}"
        fun isRun(s: Triple<String, String, String>) = s.second in referenced[s.first].orEmpty()

        // ── Direction 1: a sample nothing calls. ────────────────────────────────────────────────
        val unrun = declared.filterNot { isRun(it) }.filterNot { key(it) in baseline }
        if (unrun.isNotEmpty()) {
            error(
                "Sample(s) are compiled but never executed (#2116). A sample's body is compiled into " +
                    "`commonTest`, so a stale API breaks the build — but its `check(…)` and " +
                    "`assertEquals(…)` calls only run if something CALLS the function, and nothing " +
                    "does. #2110 is the receipt: two `:kuilt-crdt` samples asserted things that were " +
                    "false, compiled, and were quoted verbatim into the cookbook and the guide, while " +
                    "`verifyDocCitations` faithfully proved the quotes matched. That gate answers " +
                    "\"does the quote match the source?\"; only running the sample answers \"is the " +
                    "source true?\":\n\n  " +
                    unrun.sortedBy { key(it) }.joinToString("\n  ") { "${key(it)}\n      ${it.third}" } +
                    "\n\nTHE FIX is one line in the module's `<Module>SamplesRunTest` — see " +
                    "`CrdtSamplesRunTest` (a list of `::references`) or `QuilterSamplesRunTest` (one " +
                    "`@Test` per sample, returning the `TestResult`, which a `runTest`-based sample " +
                    "needs so JS and wasm await the promise rather than passing without running).\n" +
                    "If the sample CANNOT be called un-parameterised, give it the parameter it really " +
                    "needs and this guard exempts it — but a fixture invented only to satisfy this " +
                    "guard executes the sample while proving nothing, which is worse than no runner.",
            )
        }

        // ── Direction 2: the baseline must not rot. ─────────────────────────────────────────────
        // Both stale shapes are failures, for the reason `forbidUnlintedModule` checks its own
        // allowlist: an exemption nobody can see expiring is an exemption that never expires.
        val declaredKeys = declared.map { key(it) }.toSet()
        val runNow = declared.filter { isRun(it) }.map { key(it) }.toSet()
        val vanished = baseline.keys.filterNot { it in declaredKeys }.sorted()
        val fixed = baseline.keys.filter { it in runNow }.sorted()
        val reasonless = baseline.filterValues { !it.contains('#') }.keys.sorted()
        if (vanished.isNotEmpty() || fixed.isNotEmpty() || reasonless.isNotEmpty()) {
            val detail = buildString {
                if (fixed.isNotEmpty()) {
                    append("\n\n  A test now CALLS these, so their baseline entries are spent — delete ")
                    append("them, or the next sample to break here is silently exempt:\n  ")
                    append(fixed.joinToString("\n  "))
                }
                if (vanished.isNotEmpty()) {
                    append("\n\n  These name no sample any more (renamed, deleted, or given a ")
                    append("parameter). Delete the entry:\n  ")
                    append(vanished.joinToString("\n  "))
                }
                if (reasonless.isNotEmpty()) {
                    append("\n\n  A baseline entry must say WHY and name the issue that owns the fix. ")
                    append("Without one it is indistinguishable from giving up, and nothing ever ")
                    append("expires it:\n  ")
                    append(reasonless.joinToString("\n  "))
                }
            }
            error("`unrunSampleBaseline` is stale (#2116)." + detail)
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${declared.size} callable sample(s), ${baseline.size} baselined\n")
        logger.info(
            "verifySamplesAreRun: ${declared.size - baseline.size} of ${declared.size} " +
                "callable samples are executed",
        )
    }
}

// Guard: forbid `runCatchingCancellable` lexically inside a `withContext(NonCancellable)` block (#1803).
//
// `runCatchingCancellable` rethrows every `CancellationException`, which is right almost everywhere — it
// stops a structured-concurrency cancel being swallowed into a `Result`. But it discriminates on TYPE, and
// type cannot separate "my job was cancelled" from "a callee minted one" — most often `withTimeout`, which
// throws `TimeoutCancellationException` *to its caller* without cancelling that caller's job.
//
// Inside a `NonCancellable` shield that distinction collapses. The shield exists precisely so best-effort
// cleanup completes despite outer cancellation, so our own job is never cancelled there — which makes EVERY
// `CancellationException` reachable inside it necessarily callee-minted, and rethrowing it aborts the very
// cleanup the shield was written to guarantee. One `withTimeout` inside a consumer's `Seam.close` and every
// remaining close is skipped. `Seam.close` now carries the same "must not report failure as cancellation"
// obligation `sendTo`/`broadcast`/`Loom.weave` do (#1826), so a consumer minting one there IS a contract
// violation — but a library cannot trust a consumer, and this guard is what keeps the cleanup correct
// against one that violates it anyway.
//
// The correct form inside the shield is a plain `try` / `catch (Throwable)` with a debug log, PER cleanup
// item so one failure cannot skip the rest. `NwLoom.discardUnreturnedSeam` is the in-tree pattern.
// `NonCancellable.isActive` is always true, so an `ensureActive()` inside the shield is dead code — note
// `CompositeSeam.discardOrphanedPly`/`detachPly` do carry one, deliberately, "for symmetry" and
// self-documented as unable to fire; this guard neither requires nor forbids it.
//
// Detection is LEXICAL: `KotlinCodeScanner.stripNonCode` (hoisted to the "Guard plumbing" section, shared
// with `forbidBareRunCatching`) blanks `//`, `/* */` (nesting), `"…"`, `"""…"""` and `'…'` — re-entering
// code mode inside a `$`-template hole so a literal nested in one cannot leak a brace — and this guard then
// brace-depth-walks each `withContext(NonCancellable) {` block in the blanked text and flags any
// `runCatchingCancellable` inside it. Three known limits:
//   - the shield's ARGUMENT LIST must be on one line (`[^;{}\n]*`); the `{` itself may wrap to the next
//     line, since `\)\s*\{` crosses newlines. A multi-line argument list is not scanned — a miss, not a
//     false alarm;
//   - a `runCatchingCancellable` reached through a HELPER called from inside the shield is invisible
//     (e.g. `CompositeSeam.raisePlyFailure`), because the call site, not the callee, is what is scanned;
//   - the scanner's template-hole handling is what keeps the walk sound, and it has bitten in BOTH
//     directions — see its own doc comment. Re-verify both if you touch it.
// It also flags a `runCatchingCancellable` nested under an intervening `withTimeoutOrNull` inside the
// shield. That is not a false positive: the shape only survives its OWN timeout — a callee-minted
// cancellation escapes the `withTimeoutOrNull` (`e.coroutine !== coroutine` ⇒ rethrow) and leaves the
// shield entirely, skipping the rest of the cleanup. Hoist the `try`/`catch` OUTSIDE the inner bound.
//
// Production sources only (`*Main` source sets). Test-support modules' `commonMain` (`:kuilt-conformance`,
// `:kuilt-*-test`) IS in scope: it ships as a published artifact consumers subclass, so an aborted teardown
// there is a shipped defect, not a local test failure. Every `*Test`/`*Samples` set is out.
val forbidRunCatchingCancellableUnderNonCancellable by tasks.registering {
    group = "verification"
    description = "Fails if runCatchingCancellable appears inside a withContext(NonCancellable) block (#1803)."
    // `*Main/**/*.kt` under each module's `src` — the production source sets, spelled as a pattern
    // rather than a configuration-time directory listing, so a source set added later (an `appleMain`
    // intermediate, say) is scanned without depending on the configuration cache noticing the new
    // directory. The source-set name is part of the RELATIVE fingerprint, so moving a file out of a
    // `*Main` set invalidates too.
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") }, "*Main/**/*.kt")
    inputs.files(sources).withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val rootPath = rootDir
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827).
    val stamp = layout.buildDirectory.file("verification/forbid-runcatching-under-noncancellable.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    doLast {
        // `[^;{}\n]*` bounds the argument list to one line and cannot swallow the block's own brace.
        val anchor = Regex("""withContext\s*\(\s*[^;{}\n]*\bNonCancellable\b[^;{}\n]*\)\s*\{""")
        val call = Regex("""\brunCatchingCancellable\b""")
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.asSequence().flatMap { file ->
            val raw = file.readText()
            val code = KotlinCodeScanner.stripNonCode(raw)
            // Character interval of every shielded block: from its `{` to the matching `}`.
            val shielded = anchor.findAll(code).mapNotNull { match ->
                var depth = 0
                var j = match.range.last // the `{` itself
                while (j < code.length) {
                    if (code[j] == '{') depth++ else if (code[j] == '}') depth--
                    if (depth == 0) break
                    j++
                }
                (match.range.last..minOf(j, code.length - 1)).takeIf { depth == 0 }
            }.toList()
            if (shielded.isEmpty()) {
                emptySequence()
            } else {
                val rawLines = raw.lines()
                call.findAll(code)
                    .filter { hit -> shielded.any { hit.range.first in it } }
                    .map { hit ->
                        val line = code.take(hit.range.first).count { it == '\n' } + 1
                        "${file.relativeTo(rootPath)}:$line  ${rawLines.getOrElse(line - 1) { "" }.trim()}"
                    }
            }
        }.toList()
        if (offenders.isNotEmpty()) {
            error(
                "`runCatchingCancellable` inside a `withContext(NonCancellable)` block (#1803). Inside the " +
                    "shield your own job is never cancelled, so every `CancellationException` reachable there " +
                    "is callee-minted (a `withTimeout` in the callee) — rethrowing it ABORTS the cleanup the " +
                    "shield exists to guarantee, skipping every remaining close. Replace it with a plain " +
                    "`try` / `catch (failure: Throwable)` + debug log, one guard PER cleanup item. An " +
                    "`ensureActive()` there is dead (`NonCancellable.isActive` is always true) but harmless " +
                    "— `CompositeSeam.discardOrphanedPly`/`detachPly` keep one for symmetry. Settled " +
                    "patterns: `NwLoom.discardUnreturnedSeam`, `CompositeSeam.discardOrphanedPly`. If an " +
                    "inner `withTimeoutOrNull` is in the way, hoist the `try`/`catch` outside it:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok\n")
    }
}

// Guard: forbid a cancellation RETHROW placed directly around a `withTimeout` bound (#2292).
//
// The sibling above bans `runCatchingCancellable` inside a `NonCancellable` shield. This one bans
// the unshielded dual, and it is the more common shape: a bound is written *because* the caller has
// something to do when it expires, and then a rethrow above the handler makes that handler dead for
// exactly the expiry it was written for. Three sites shipped this way — `ServerCluster.admitLearner`
// (the room was never left, so the accepted connection leaked), `MultipeerCrossProcessProbe`'s two
// probe bounds (the probe died on an escaping cancellation instead of reporting `passed = false`),
// and `:spike`'s scenario harness (a timing-out scenario stopped the whole suite with no report).
// All three were silent, because the escaping throwable IS a cancellation: the coroutine is
// **cancelled rather than failed**, so no handler runs and no stack trace is printed.
//
// The predicate, and what clears each rule, is documented on `CancellationRethrowAroundBoundScanner`
// above. `withTimeoutOrNull` is not matched and needs no exemption.
//
// SCOPE is production `*Main` source sets, matching the sibling. Every one of the three real
// instances was in a `*Main` set, and confining it there is what lets the guard have NO allowlist:
// the one remaining in-tree occurrence of the shape is `RunCatchingCancellableTest`, which exhibits
// it deliberately to pin the trap on the primitive. An exemption for that would be an exemption
// nobody could see expiring — the failure mode this build script names elsewhere.
//
// KNOWN BLIND SPOTS, stated rather than implied. **A lexical scan is a backstop, not a proof.**
//   * A rethrow reached through a HELPER is invisible in both directions — a `withTimeout` inside a
//     function called from the `try`, or a `try` in a caller of a function that bounds itself. That
//     is not hypothetical: `:spike`'s `runSuite` guard sits two hops above the `withTimeout` that
//     defeated it, and nothing here would have flagged it. The scanner sees ONE lexical block.
//   * A `withTimeout` inside a nested `launch {}`/`async {}` within the `try` does not propagate to
//     that `try` at all, so flagging it would be a false positive. Zero in-tree sites have the shape;
//     if one appears, narrow the rule rather than exempting the file.
//   * `catch (e: Exception)` / `catch (e: Throwable)` with a hand-written
//     `if (e is CancellationException) throw e` is the same defect and is NOT matched — rule A keys
//     on the catch HEADER. The token is greppable; this paragraph is the guard on it.
//   * `:spike` is a subproject only under `-PincludeSpike`, so its sources are not in `subprojects`
//     on the required gate — same limit the sibling guards carry. The `:spike` fix in #2292 is
//     therefore held by review, not by this task.
val forbidCancellationRethrowAroundBound by tasks.registering {
    group = "verification"
    description = "Fails if a CancellationException rethrow wraps a withTimeout, making the handler under it dead code (#2292)."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") }, "*Main/**/*.kt")
    inputs.files(sources).withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val rootPath = rootDir
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827).
    val stamp = layout.buildDirectory.file("verification/forbid-cancellation-rethrow-around-bound.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    doLast {
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.flatMap { file ->
            val raw = file.readText()
            if ("withTimeout" !in raw) return@flatMap emptyList<String>()
            val rawLines = raw.lines()
            CancellationRethrowAroundBoundScanner.violations(KotlinCodeScanner.stripNonCode(raw)).map {
                "${file.relativeTo(rootPath).invariantSeparatorsPath}:${it.line}  [${it.rule}] ${it.detail}\n" +
                    "      ${rawLines.getOrElse(it.line - 1) { "" }.trim()}"
            }
        }
        if (offenders.isNotEmpty()) {
            error(
                "A cancellation rethrow wraps a `withTimeout` (#2292). `TimeoutCancellationException` IS a " +
                    "`CancellationException`, so the bound's OWN expiry — the one case the handler beneath " +
                    "was written for — is rethrown instead, and that handler is dead code. Worse, the " +
                    "escaping throwable is a cancellation, so the coroutine reads as CANCELLED rather than " +
                    "FAILED: no handler, no stack trace.\n" +
                    "  Rule A (try/catch): open a single `catch (e: Throwable)` with " +
                    "`currentCoroutineContext().ensureActive()` as its first statement — it rethrows only " +
                    "when THIS job is genuinely cancelled and lets a callee-minted timeout fall through " +
                    "(`ServerCluster.admitLearner` is the pattern). Handling the timeout by type in an " +
                    "EARLIER `catch (…: TimeoutCancellationException)` also clears it " +
                    "(`NwLoom.weave`, `MidHandshakeCollapse`).\n" +
                    "  Rule B (runCatchingCancellable): the helper rethrows unconditionally, so nothing " +
                    "clears it — use `withTimeoutOrNull` plus an explicit non-cancellation throw " +
                    "(`MultipeerCrossProcessProbe` is the pattern).\n" +
                    "  A lexical scan cannot see either construct through a HELPER, so this is a backstop, " +
                    "not a proof:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok\n")
    }
}

// Guard: forbid bare `kotlin.runCatching` anywhere in the tree (#1329).
//
// WHAT IS BANNED. `runCatching` catches `Throwable`, and `CancellationException` is a `Throwable`.
// In any suspend or coroutine context that turns a structured-concurrency cancel into an ordinary
// `Result.failure` — the coroutine keeps running inside a scope that has been told to stop, the
// cancel is never observed, and nothing anywhere reports an error. It is the quietest bug shape in
// the codebase. The fix is `runCatchingCancellable` (`:kuilt-core`), which rethrows every
// `CancellationException` and captures the rest.
//
// WHY A GRADLE GUARD AND NOT DETEKT. This is the whole point of #1329. The ban was supposed to be
// `ForbiddenMethodCall: kotlin.runCatching` in `detekt.yml`; #1086 specified it, the PR that closed
// #1086 (#1133) shipped the conversions and dropped the gate, and when the gate was finally tried it
// turned out not to work at all: `ForbiddenMethodCall` resolves NO kotlin-stdlib callee in this KMP
// setup — reproduced with caches off on real `jvmMain`/`jvmTest` sources, where
// `UnsafeCallOnNullableType` fires (so type resolution IS active) and `ForbiddenComment` fires (so
// the style ruleset IS applied), yet `ForbiddenMethodCall` matches neither `kotlin.runCatching` nor
// `kotlin.io.println`. It silently no-ops on stdlib callees. A detekt rule would also miss `:spike`,
// which has no detekt task at all (#1796). So: a source scan, which covers the tree by construction.
// Corollary — every `@Suppress("ForbiddenMethodCall")` in the tree is INERT. The rule is configured
// in neither `config/detekt/detekt.yml` nor `config/detekt/detekt-test.yml` (the latter bans
// dispatchers via `ForbiddenImport`, a different rule that does fire). Do not add one expecting it
// to do anything.
//
// SCOPE IS BLANKET — production AND test, unlike the `*Main`-only NonCancellable guard above. The
// real rule is narrower ("in any suspend or coroutine context"), but deciding lexically whether a
// given line sits in one is exactly the problem the sibling guard's scanner shows to be hard, and
// its failure mode is a SILENT false negative. A blanket ban fails the other way — loud, and cleared
// in one line. It also makes the marker sweep worth having: before it, a reader could not tell a
// deliberate bare `runCatching` from an oversight, and that indistinguishability is how the class
// regenerates.
//
// THE ESCAPE HATCH is a line-scoped marker with a MANDATORY reason:
//
//     // ALLOW-runCatching: raw daemon Thread, not a coroutine — no cancellation to swallow.
//     thread(isDaemon = true) { runCatching { pump() } }
//
// It must sit trailing on the offending line or on the line immediately above — line-tight, so it
// cannot silently cover a `runCatching` added to the same function later, which is precisely what
// `@Suppress` (declaration-scoped) would do. A marker with an EMPTY reason is itself a violation:
// the reason is the entire value of the sweep. Markers are comments, so the marker is matched
// against the RAW text while the call is found in the STRIPPED text; `stripNonCode` preserves
// newlines, so the two line-index spaces coincide.
//
// DETECTION. `(?<![A-Za-z0-9_])runCatching\s*[({]` over `KotlinCodeScanner.stripNonCode` output.
// Stripping is not optional here: `runCatching` appears in PROSE in several scanned files,
// including a `[runCatching]` KDoc link inside `RunCatchingCancellable.kt` itself. The right-hand
// `\s*[({]` is what keeps the ban off the longer names that merely share the prefix —
// `runCatchingCancellable` and the in-tree helpers `runCatchingBroadcast` / `runCatchingClosed` /
// `runCatchingAddLink` / `runCatchingAdmit` all continue with a letter, not `(` or `{`.
//
// KNOWN COVERAGE EDGES, stated rather than fixed: `:spike` is only a subproject under
// `-PincludeSpike`, so it is absent from `subprojects` on a normal build; and `build-logic/` is a
// separate included build with its own `subprojects`. Neither is scanned. Both are out of the
// runtime library, which is where a swallowed cancel actually costs something.
val forbidBareRunCatching by tasks.registering {
    group = "verification"
    description = "Fails if any source calls bare runCatching without an ALLOW-runCatching marker — use runCatchingCancellable (#1329)."
    // ALL source sets, production and test: `**/*.kt` under each module's `src`, which also picks up
    // the plain-JVM layout (`:kuilt-scale`'s `src/test`) that a `*Main`/`*Test` pattern would miss.
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict is
    // a pure function of file contents — there is no allowlist, the markers live in the sources
    // themselves — so a RELATIVE fingerprint hit genuinely means "this exact source was verified".
    val stamp = layout.buildDirectory.file("verification/forbid-bare-runcatching.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val call = Regex("""(?<![A-Za-z0-9_])runCatching\s*[({]""")
        // Group 1 is everything after the colon; blank ⇒ a reasonless marker, itself a violation.
        val marker = Regex("""//\s*ALLOW-runCatching:(.*)""")
        val unmarked = mutableListOf<String>()
        val reasonless = mutableListOf<String>()
        sources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val raw = file.readText()
            val code = KotlinCodeScanner.stripNonCode(raw)
            if (!call.containsMatchIn(code)) return@forEach
            val rawLines = raw.lines()
            call.findAll(code).forEach { hit ->
                val line = code.take(hit.range.first).count { it == '\n' } + 1
                // Trailing on the same line, or the line immediately above. Deliberately NOT a
                // multi-line lookback window — line-tight is the point.
                val candidates = listOfNotNull(rawLines.getOrNull(line - 1), rawLines.getOrNull(line - 2))
                val reasons = candidates.mapNotNull { marker.find(it)?.groupValues?.get(1)?.trim() }
                val where = "${file.relativeTo(rootPath)}:$line  ${rawLines.getOrElse(line - 1) { "" }.trim()}"
                when {
                    reasons.any { it.isNotEmpty() } -> Unit // exempt
                    reasons.isNotEmpty() -> reasonless += where
                    else -> unmarked += where
                }
            }
        }
        if (unmarked.isNotEmpty() || reasonless.isNotEmpty()) {
            val detail = buildString {
                if (unmarked.isNotEmpty()) {
                    append("\n  ").append(unmarked.joinToString("\n  "))
                }
                if (reasonless.isNotEmpty()) {
                    append("\n\n  An `// ALLOW-runCatching:` marker with an EMPTY reason is itself a violation — ")
                    append("the reason is the whole point. Say why this site is not a coroutine context, or why ")
                    append("catching the cancellation is deliberate:\n  ")
                    append(reasonless.joinToString("\n  "))
                }
            }
            error(
                "Bare `runCatching` found (#1329). It catches `Throwable`, and `CancellationException` " +
                    "is one — in a suspend or coroutine context it converts a structured-concurrency " +
                    "cancel into an ordinary `Result.failure`, so the cancel is never observed and " +
                    "nothing reports an error. Use `runCatchingCancellable` (`us.tractat.kuilt.core`), " +
                    "which rethrows cancellation and captures the rest. If this site genuinely is not a " +
                    "coroutine context (a raw `Thread`/`thread {}` body, a non-suspend `close()`), or the " +
                    "cancellation really must be caught (a test asserting on it), keep it and say so in a " +
                    "marker on this line or the line above:\n" +
                    "      // ALLOW-runCatching: <why this is safe>\n" +
                    "  `@Suppress(\"ForbiddenMethodCall\")` is NOT the escape hatch — that detekt rule is " +
                    "configured nowhere and never fires, which is what #1329 exists to fix." +
                    detail,
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} Kotlin sources scanned\n")
    }
}

// Guard: forbid `kotlin.assert` anywhere in the tree (#2119).
//
// WHAT IS BANNED. `kotlin.assert` is not an assertion primitive — whether it checks anything is a
// property of the LAUNCHER, not of the code. On the JVM it is `-ea`-gated and works here only
// because Gradle's `Test` task happens to default `enableAssertions = true`; outside Gradle, or
// under a runner that does not, every one of these lines silently evaluates to nothing. Off the JVM
// the gating is per-platform and different again. The fix is any UNCONDITIONAL throw: `check(…)` /
// `require(…)` for a precondition, `assertTrue(…)` / `assertEquals(…)` from `kotlin.test` for a
// test assertion.
//
// WHY A GUARD AND NOT A REVIEW HABIT. #2112 migrated `:kuilt-raft`'s pure Raft model check to
// `commonTest` and found ALL FIVE Raft safety invariants — Election Safety, Log Matching, State
// Machine Safety, Leader Completeness, Compaction Completeness — written with `kotlin.assert`.
// They had passed for their whole life by launcher grace, and the move to `commonTest` would have
// made that live on precisely the platforms it added. Nothing had stopped it, so it accumulated.
// Fixing the last three call sites was ten minutes; this task is the actual deliverable. Same
// reason `forbidBareRunCatching` is a source scan and not a detekt rule: `ForbiddenMethodCall`
// resolves no kotlin-stdlib callee in this KMP setup (see #1329's note above), so a detekt ban on
// `kotlin.assert` would silently no-op — the exact failure shape this guard exists to end.
//
// SCOPE IS BLANKET — production AND test, which is the point rather than an over-reach: this
// idiom's home is test sources, so a `*Main`-only scoping (the one
// `forbidRunCatchingCancellableUnderNonCancellable` uses, for its own reasons) would cover none of
// the population. `**/*.kt` under each module's `src` also picks up `commonSamples` (compiled into
// `commonTest`) and the plain-JVM `src/test` layout that a `*Main`/`*Test` pattern would miss.
//
// A hit under `src/common*` is the worst case and is TAGGED `[commonSource]` in the report: it
// compiles for every target the module declares, so it is green on JVM and inert on Native/wasm at
// the same time. That is why the tag exists — the whole list is banned either way.
//
// THERE IS NO ESCAPE HATCH, deliberately, and the contrast with `forbidBareRunCatching` above is
// the reason. That guard's real rule is NARROWER than what it can see lexically ("bare
// `runCatching` in a coroutine context"), so a blanket lexical ban necessarily over-fires and needs
// an `// ALLOW-runCatching:` marker to clear the legitimate remainder. Here the real rule and the
// lexical rule COINCIDE: there is no context in this codebase in which a launcher-gated check is
// the right primitive, so there is nothing for a marker to except. Surveyed before choosing —
// the tree contains zero user-defined `fun assert(` and zero receiver-qualified `.assert(`, so the
// false-positive population a hatch would serve is empty. And a hatch on a SILENCE bug is how the
// idiom survives: `// ALLOW-assert: debug-only sanity check` is exactly what all five Raft
// invariants would have carried. If an unforeseen legitimate site ever appears, the cheap clear is
// to rename the function (shadowing `kotlin.assert` is confusing on its own terms); adding a hatch
// later is a small PR, removing one after it has become idiom is not.
//
// DETECTION. `(?<![A-Za-z0-9_.])(kotlin\.)?assert\s*\(` over `KotlinCodeScanner.stripNonCode`
// output. The lookbehind is the whole guard: without it the pattern eats `assertEquals(`,
// `assertTrue(`, `assertAll(`, `assertContentEquals(`, `assertFailsWith(` — ~9,000 call sites
// across ~1,400 files, i.e. it would fail on everything. Excluding `.` keeps a hypothetical
// `receiver.assert(…)` out; the explicit `(kotlin\.)?` alternative puts the fully-qualified form of
// the banned function itself back IN, which the bare `[^A-Za-z0-9_.]` boundary would have missed.
// Stripping is defensive rather than load-bearing today (no prose hit exists), but this issue's own
// text is quotable into a KDoc, and the sibling guards all strip.
//
// KNOWN COVERAGE EDGES, stated rather than fixed, and the same set `forbidBareRunCatching` carries:
// `:spike` is a subproject only under `-PincludeSpike` so it is absent from `subprojects` on a
// normal build; `build-logic/` is a separate included build; `*.kts` is not scanned. Also
// undetected: an import alias (`import kotlin.assert as sanityCheck`). All are currently empty of
// the idiom, and none is the runtime library, which is where a check that evaporates costs
// something.
val forbidKotlinAssert by tasks.registering {
    group = "verification"
    description = "Fails if any source calls kotlin.assert — it is launcher-gated, so it may check nothing (#2119)."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a pure function of file contents — there is no allowlist and no in-source marker — so a
    // RELATIVE fingerprint hit genuinely means "this exact source was verified".
    val stamp = layout.buildDirectory.file("verification/forbid-kotlin-assert.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val call = Regex("""(?<![A-Za-z0-9_.])(kotlin\.)?assert\s*\(""")
        val offenders = mutableListOf<String>()
        sources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val raw = file.readText()
            val code = KotlinCodeScanner.stripNonCode(raw)
            if (!call.containsMatchIn(code)) return@forEach
            val rawLines = raw.lines()
            // Compiles for every target the module declares — green on JVM, inert on Native/wasm.
            val shared = "/src/common" in file.invariantSeparatorsPath
            call.findAll(code).forEach { hit ->
                val line = code.take(hit.range.first).count { it == '\n' } + 1
                offenders += (if (shared) "[commonSource] " else "") +
                    "${file.relativeTo(rootPath)}:$line  ${rawLines.getOrElse(line - 1) { "" }.trim()}"
            }
        }
        if (offenders.isNotEmpty()) {
            error(
                "`kotlin.assert` found (#2119). It is `-ea`-gated on the JVM and gated differently " +
                    "per platform elsewhere, so whether it checks anything is a property of the " +
                    "LAUNCHER, not of this code — it passes today only because Gradle's `Test` task " +
                    "defaults `enableAssertions = true`. Use an UNCONDITIONAL throw instead: " +
                    "`check(cond) { \"…\" }` or `require(cond) { \"…\" }` for a precondition, " +
                    "`assertTrue(cond, \"…\")` / `assertEquals(…)` from `kotlin.test` for a test " +
                    "assertion. There is no marker to opt out with, deliberately — see this guard's " +
                    "note in the root `build.gradle.kts`; a site tagged `[commonSource]` is the worst " +
                    "case, green on JVM and inert on Native/wasm at once:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} Kotlin sources scanned\n")
    }
}

// Guard: forbid a production dispatcher or `GlobalScope` in TEST sources (#1934; the bug class is #340).
//
// WHAT IS BANNED. `Dispatchers.Default` / `.IO` / `.Main` / `.Unconfined` and `GlobalScope`, in any
// test source set. A real dispatcher under `runTest` decouples the work from the virtual clock: the
// test's assertions run on the test scheduler while the subject runs on real threads, so what the
// test observes depends on how fast the box is. The fix is `StandardTestDispatcher(testScheduler)`
// (FIFO at each virtual instant) — or `UnconfinedTestDispatcher(testScheduler)` where eager-inline
// ordering cannot affect the outcome. See `docs/testing-coroutine-determinism.md`.
//
// WHY A GUARD AND NOT DETEKT — and this rule already HAD a detekt half, so the answer is specific.
// `config/detekt/detekt-test.yml` configures `ForbiddenImport` on `kotlinx.coroutines.Dispatchers`
// and `…GlobalScope`. That rule is real and does fire, but it is blind in two directions at once:
//
//   * it sees only the `import` FORM, so a fully-qualified `kotlinx.coroutines.Dispatchers.Default`
//     — which needs no import — is invisible to it by construction; and
//   * it runs only where `kuilt.detekt-kmp` wires the test config, i.e. `detektJvmTest` +
//     the two `androidUnitTest` tasks. detekt generates NO task for `commonTest` at all, and none
//     for `appleTest`/`iosTest`/`macosArm64Test`/`wasmJsTest` or the plain-JVM `src/test` layout —
//     so most of kuilt's tests were never scanned by it (#1960).
//
// The config header used to name `@Suppress("ForbiddenMethodCall")` as the sanctioned escape hatch
// for the deliberate harnesses, immediately above that live `ForbiddenImport` stanza, so the two
// read as equivalent while only one did anything: `ForbiddenMethodCall` is configured in NEITHER
// detekt config, and #1329 established it could not work here anyway (it resolves no kotlin-stdlib
// callee in this KMP setup and silently no-ops). Twenty-one of those annotations had accumulated.
// A source scan has neither blind spot — it reads files, so every test source set is covered by
// construction, and it can enforce what an `@Suppress` structurally cannot: that a REASON exists.
//
// WHY THE IMPORT IS A VIOLATION AND NOT JUST THE USE. This is the load-bearing design decision, and
// it is what makes a lexical scan complete rather than approximate. There are exactly two ways to
// reach `Dispatchers` in Kotlin — import it, or fully qualify it — so those two spellings are the
// whole language surface, and the import is a per-file choke point that every bare `Dispatchers.IO`
// in that file passes through. Scanning USES instead would need a marker on each of ~76 call sites
// (ten in one file) to buy the same property; scanning the import buys it once per file, on the line
// a reader already looks at, and is the granularity the `@file:Suppress("ForbiddenImport")`
// convention it replaces already used.
//
// The residual, stated rather than hidden: a file already marked as a real-threading harness can
// gain a new careless `Dispatchers.Default` without the guard noticing. That is accepted — by then
// the file is a harness whose every test runs on real threads, which is a far smaller blast radius
// than a virtual-time test file quietly acquiring its first real dispatcher, which IS caught.
//
// THE ESCAPE HATCH is a line-scoped marker with a MANDATORY reason:
//
//     import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-socket example — blocking reads need a real IO dispatcher
//
// Trailing on the offending line or on the line immediately above — line-tight, like #1329's
// `// ALLOW-runCatching:`. A marker with an EMPTY reason is itself a violation: the reason is the
// entire value of the sweep, and it is the one thing the `@Suppress` this replaces could not require.
// Markers are comments, so they are matched against the RAW text while the offence is found in the
// STRIPPED text; `stripNonCode` preserves newlines, so the two line-index spaces coincide.
//
// DETECTION — two regexes over `KotlinCodeScanner.stripNonCode` output.
//
// The import form enumerates every spelling that brings the object into scope, because an exclusion
// is where a lexical guard loses a true positive: plain (`import kotlinx.coroutines.Dispatchers`),
// MEMBER (`…Dispatchers.IO`, then bare `IO`), STAR (`…Dispatchers.*`), ALIASED (`… as D`, then
// `D.IO`), and the PACKAGE WILDCARD (`import kotlinx.coroutines.*`, then bare `Dispatchers.IO`).
// The wildcard over-fires slightly — a file importing the package only for `launch` is asked for a
// marker — and is in anyway: it is the one spelling that would otherwise be a silent hole, and the
// tree contains zero of them. Whitespace is legal around every dot in an import, hence the `\s*`.
//
// The fully-qualified form is the same identifier chain outside an import. Stripping is load-bearing
// for BOTH, not decorative: `WebSocketPingHalfOpenTest.kt`'s KDoc says the looms do "not import
// `kotlinx.coroutines.Dispatchers`", which is prose that names the banned chain exactly.
//
// KNOWN COVERAGE EDGES, stated rather than fixed:
//   * `src/commonSamples/` is NOT scanned. Samples compile into `commonTest` but demonstrate the
//     PRODUCTION API, where a real dispatcher is often the correct thing to show.
//   * A test-support module's `commonMain` (`:kuilt-test`, `:kuilt-raft-test`, `:kuilt-deal-test`)
//     is not a test source set and is not scanned, so a helper published from one could hand a real
//     dispatcher to a test that itself looks clean. Same edge `detekt-test.yml` always had.
//   * An EXPLICITLY CONSTRUCTED real dispatcher evades this entirely — `newFixedThreadPoolContext`,
//     `newSingleThreadContext`, `Executors.…asCoroutineDispatcher()`. That is not an oversight but
//     it is not nothing either: five `:kuilt-otel*` / `:kuilt-otel-logging` harnesses get their real
//     threads that way, and each carries a `@file:Suppress("ForbiddenImport")` that has never
//     applied to anything, because they import no `Dispatchers`. The line drawn here is that those
//     spellings are explicit, local and named at the site, where `Dispatchers.Default` is ambient —
//     and the rule this guard enforces, in `CLAUDE.md` and in `detekt-test.yml`, names the four
//     `Dispatchers` members and `GlobalScope`. Widening it is a separate decision, not a bug fix.
//   * `:spike` (a subproject only under `-PincludeSpike`), `build-logic/` (a separate included
//     build) and `*.kts` are unscanned, as with every sibling guard.
val forbidProductionDispatcherInTests by tasks.registering {
    group = "verification"
    description = "Fails if a test source reaches a production dispatcher or GlobalScope without an ALLOW-realDispatcher marker (#1934)."
    // Both test-source layouts: the KMP `src/<target>Test/` and the plain-JVM `src/test/`.
    val sources = kotlinSourcesIn(
        subprojects.map { it.projectDir.resolve("src") },
        listOf("*Test/**/*.kt", "test/**/*.kt"),
    )
    inputs.files(sources).withPropertyName("kotlinTestSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict is
    // a pure function of file contents — there is no allowlist, the markers live in the sources
    // themselves — so a RELATIVE fingerprint hit genuinely means "this exact source was verified".
    val stamp = layout.buildDirectory.file("verification/forbid-production-dispatcher-in-tests.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val importForm = Regex(
            """^[ \t]*import\s+kotlinx\s*\.\s*coroutines\s*\.\s*(?:(?:Dispatchers|GlobalScope)(?![A-Za-z0-9_])|\*)""",
            RegexOption.MULTILINE,
        )
        val qualifiedForm = Regex(
            """kotlinx\s*\.\s*coroutines\s*\.\s*(?:Dispatchers|GlobalScope)(?![A-Za-z0-9_])""",
        )
        // Group 1 is everything after the colon; blank ⇒ a reasonless marker, itself a violation.
        val marker = Regex("""//\s*ALLOW-realDispatcher:(.*)""")
        val unmarked = mutableListOf<String>()
        val reasonless = mutableListOf<String>()
        sources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val raw = file.readText()
            val code = KotlinCodeScanner.stripNonCode(raw)
            if (!qualifiedForm.containsMatchIn(code) && !importForm.containsMatchIn(code)) return@forEach
            val rawLines = raw.lines()
            fun linesOf(hits: Sequence<MatchResult>): Set<Int> =
                hits.map { code.take(it.range.first).count { c -> c == '\n' } + 1 }.toSet()
            val imports = linesOf(importForm.findAll(code))
            // An import line matches the qualified form too — report it once, as the import it is.
            val qualified = linesOf(qualifiedForm.findAll(code)) - imports
            (imports + qualified).sorted().forEach { line ->
                // Trailing on the same line, or the line immediately above. Deliberately NOT a
                // multi-line lookback window — line-tight is the point.
                val candidates = listOfNotNull(rawLines.getOrNull(line - 1), rawLines.getOrNull(line - 2))
                val reasons = candidates.mapNotNull { marker.find(it)?.groupValues?.get(1)?.trim() }
                val kind = if (line in imports) "import" else "fully-qualified"
                val where = "${file.relativeTo(rootPath)}:$line  [$kind]  " +
                    rawLines.getOrElse(line - 1) { "" }.trim()
                when {
                    reasons.any { it.isNotEmpty() } -> Unit // exempt
                    reasons.isNotEmpty() -> reasonless += where
                    else -> unmarked += where
                }
            }
        }
        if (unmarked.isNotEmpty() || reasonless.isNotEmpty()) {
            val detail = buildString {
                if (unmarked.isNotEmpty()) {
                    append("\n  ").append(unmarked.joinToString("\n  "))
                }
                if (reasonless.isNotEmpty()) {
                    append("\n\n  An `// ALLOW-realDispatcher:` marker with an EMPTY reason is itself a ")
                    append("violation — the reason is the whole point, and is the one thing the ")
                    append("`@Suppress` this replaces could not require. Say why this harness needs ")
                    append("real threads:\n  ")
                    append(reasonless.joinToString("\n  "))
                }
            }
            error(
                "A production dispatcher or `GlobalScope` is reachable from a test source (#1934). " +
                    "Under `runTest` a real dispatcher decouples the subject from the virtual clock, " +
                    "so what the test observes depends on how fast the box is — the #340 bug class. " +
                    "Use `StandardTestDispatcher(testScheduler)`, or " +
                    "`UnconfinedTestDispatcher(testScheduler)` where eager-inline ordering cannot " +
                    "matter; prefer the published harnesses (`raftSimTest` / `warpSimTest`), which " +
                    "wire one in. A `[fully-qualified]` hit needs no import, which is exactly why " +
                    "detekt's `ForbiddenImport` cannot see it. If this really is a deliberate " +
                    "real-threading harness — a true-parallelism stress probe, a callback-thread " +
                    "regression test, a `runBlocking` benchmark, a real socket — keep it and say so " +
                    "in a marker on this line or the line above:\n" +
                    "      // ALLOW-realDispatcher: <why this harness needs real threads>\n" +
                    "  `@Suppress(\"ForbiddenMethodCall\")` is NOT the escape hatch — that detekt " +
                    "rule is configured nowhere and never fires, which is what #1934 exists to fix." +
                    detail,
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} Kotlin test sources scanned\n")
    }
}

// Guard: forbid a bare duration literal as a `runTest(…)` timeout — at a CALL SITE (pass 1, a
// per-file count ratchet) or in a WRAPPER's parameter default (pass 2, zero tolerance). #1739.
//
// WHAT IS BANNED. `runTest(StandardTestDispatcher(), timeout = 5.seconds)`. Under a test dispatcher
// with seeded RNG and in-memory transports a test has no real-clock input anywhere on its execution
// path, so its virtual trajectory — and therefore the total quantity of real work it performs — is
// identical on every run. A wall-clock cap over a fixed quantity of work asserts nothing about the
// code; it asserts "this host can retire N units of work in T seconds", which is false exactly when
// the box is busy. Measured on the worst site: 2.65× contention degradation against 1.8× headroom,
// i.e. a DETERMINISTIC red on a busy runner, not a flake (#1891, mutation-verified 5 s → 4/4 FAIL,
// 30 s → 4/4 PASS). It also produces the worst diagnostic in the tree — a bare
// `UncompletedCoroutinesError` — by pre-empting the bounded `await*`/`settle()` assertions inside
// the test, which are the fast, load-INDEPENDENT detector and dump state when they fire.
//
// THE FIX IS A NAMED CONSTANT, NOT A DIFFERENT NUMBER: `TEST_WEDGE_BACKSTOP` (`:kuilt-test`), or a
// harness's own (`RAFT_SIM_WEDGE_BACKSTOP`, `WARP_SIM_WEDGE_BACKSTOP`). A `runTest(…)` with NO
// `timeout` argument is also fine — the 60 s library default is a perfectly good backstop.
//
// WHY THE RULE IS "ANY LITERAL" AND NOT "5.seconds". #1918 corrected the prose (`CLAUDE.md` used to
// instruct "a tight timeout, never the 60 s default"); a new 5 s ceiling was written HOURS later, in
// a module that correction had just red-lit, by a worker branching off a commit that contained it.
// Local convention beat the document. A rule keyed to the number 5 is satisfied by 4; a rule
// requiring a NAME is not, and a name is also the correct thing for the next contributor to copy.
//
// WHY A PER-FILE COUNT RATCHET AND NOT A FILE ALLOWLIST. This is the load-bearing design decision.
// The instance that escalated #1739 was an 11th ceiling written into `HeddleFenceTest.kt`, which
// already had ten — the ten are WHY the eleventh was written. Under the `forbidPortProbeRebind`-shape
// file allowlist that file is exempt, so the new violation lands green and the guard is decorative
// against the exact event it exists to stop (the #1086/#1133 failure). So the baseline records a
// COUNT per file and the guard fails when a count INCREASES. Sweeping a file drives it to zero and
// deletes its entry; the baseline only moves down.
//
// THE WRAPPER SHAPE, AND WHY IT NEEDS ITS OWN PASS. A harness that feeds `runTest`'s timeout from a
// PARAMETER DEFAULT (`fun simTest(timeout: Duration = 5.seconds, …) = runTest(timeout = timeout)`) is
// a DECLARATION, not a call site: the literal never appears in an argument list, so the call-site
// rule above can neither flag it nor vouch for it, while every caller taking the default silently
// inherits the ceiling. That is how `warpSimTest`'s 5 s hid on a published `commonMain` harness. This
// comment used to say the blind spot was open and enumerate the four in-tree wrappers by hand, with
// `voterMeshSimTest` marked LIVE; both the enumeration and the defect are now gone — pass 2
// (`RunTestWrapperTimeoutScanner`) decides it mechanically, with NO baseline, so the correct count is
// zero and a fifth wrapper fails on arrival rather than needing a reviewer to notice.
//
// Its boundary is exact, and stated here rather than implied. It fires on a parameter whose default
// CONTAINS A DIGIT (same test as pass 1 — `4.seconds` must be the same defect as `5.seconds`, and
// every sanctioned constant is digit-free) when the parameter either (a) is named in the enclosing
// declaration's `runTest(… timeout = …)` argument, or (b) is itself called `timeout`. Rule (a) is
// name-agnostic on purpose, so renaming the parameter evades nothing; rule (b) is what catches a
// POSITIONAL forward, where there is no `timeout =` argument to read. The anchor keeping it off the
// many legitimate timeout-taking APIs — `HeartbeatConfig(timeout = …)`, `awaitCommit(within =
// 2.seconds)`, `assertAbortsOnMidHandshakeCollapse(timeout = 5.seconds)`, all VIRTUAL bounds, which
// are the detectors #1739 wants — is that the declaration must itself call `runTest(`. Nothing but a
// `runTest` wrapper does.
//
// That anchor is why pass 2 depends on `KotlinCodeScanner.stripNonCode` for its PRECISION and not
// merely for tidiness, and there is a live receipt in the tree: `:kuilt-test`'s
// `assertAbortsOnMidHandshakeCollapse` takes `timeout: Duration = 5.seconds` — a virtual `withTimeout`
// bound, entirely correct — and its KDoc says "pair with `runTest(timeout = TEST_WEDGE_BACKSTOP)`".
// On raw text that is a `timeout` parameter with a literal default in a declaration containing
// `runTest(`, i.e. a false positive on a published `commonMain` helper. Stripped, the KDoc is gone
// and it does not fire. Anything that weakens the stripper re-arms it.
//
// It reads the SAME whole-tree source set as pass 1 rather than a curated list of test source sets
// plus the test-support modules' `commonMain` (where two of the four wrappers live — `raftSimTest`
// and `warpSimTest` ship in a published `commonMain`, so a `*Test`-only scan would miss exactly the
// two with the widest blast radius). A curated list would itself be the next blind spot, since the
// module that needs adding to it is by definition the one nobody thought of; the `runTest(`-in-
// declaration anchor already does all the filtering, at no cost, on files pass 1 has open anyway.
//
// KNOWN LIMITS, stated rather than papered over:
//   * The ratchet does not auto-tighten. Sweeping a file without deleting its entry leaves the
//     baseline loose for that path until someone notices. Failing on a DECREASE would fix it, and is
//     deliberately not done here: it would red-light every in-flight branch that merely deletes a
//     test, for a diff this PR is explicitly trying not to be a bad neighbour to.
//   * Pass 2 sees the literal, not the value. A wrapper that COMPUTES its default from something
//     digit-free — `timeout: Duration = tightBudget()`, `= SOMETHING / 6` — reads as a named
//     constant and passes, exactly as `timeout = tightBudget()` does at a call site. Both passes buy
//     "the number lives in one reviewable named place", not "the number is generous"; nothing
//     mechanical can assert the latter, which is why `TEST_WEDGE_BACKSTOP`'s KDoc argues it instead.
//   * Pass 2 resolves a parameter one hop. A wrapper wrapping a wrapper (`fun a(t: Duration =
//     5.seconds) = b(timeout = t)`) has no `runTest(` in its own declaration and is invisible; the
//     literal is caught only if it reaches a declaration that calls `runTest` directly. Zero in-tree
//     sites are two hops deep, and the honest reason not to chase it is that call-graph resolution is
//     not something a lexical scanner should be asked to do.
//   * A wrapper that hard-codes the ceiling in its BODY (`= runTest(timeout = 5.seconds)`) is not a
//     pass-2 shape at all — it is an ordinary call site, and pass 1 has it.
//   * Declaration ownership is the nearest preceding `fun` keyword, not a computed extent (all four
//     in-tree wrappers are expression-bodied, for which no brace walk yields one). A `runTest(` that
//     is not inside any function body would therefore borrow the parameters of whatever `fun`
//     precedes it in the file. It would also have to mention one of them by name to fire.
//   * `:spike` (only present under `-PincludeSpike`) and `build-logic/` are not scanned, same as the
//     sibling guards.
val forbidTightRunTestTimeout by tasks.registering {
    group = "verification"
    description = "Fails on a runTest timeout that is a bare duration literal — at a call site beyond its baseline, or in a wrapper's parameter default. Use a named backstop constant (#1739)."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") })
    inputs.files(sources).withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict is
    // a function of file PATHS (the baseline keys) and file CONTENTS, both captured by a RELATIVE
    // fingerprint — so a cache hit genuinely means "this exact source was verified green".
    val stamp = layout.buildDirectory.file("verification/forbid-tight-runtest-timeout.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    // The baseline MUST stay a literal here. Per "Guard plumbing" above it is covered by the cache
    // key only because it is folded into the task-action implementation hash; moving it to
    // `gradle.properties` or a resource would silently drop it out and reintroduce the stale-green
    // class the stamps were made safe against. Regenerate after a sweep with the same scanner rather
    // than by hand. Entries are paths relative to the root, violation counts as of #1739's first PR.
    val baseline = mapOf(
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/AttachmentDirectoryTest.kt" to 2,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/ClusterClientFailoverTest.kt" to 7,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/ClusterClientTest.kt" to 8,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/EndpointRotationTest.kt" to 5,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/FederatedCoreAdmissionTest.kt" to 5,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/OverlayServerFailoverTest.kt" to 3,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RaftRelayHubTest.kt" to 8,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RelayDialectOriginPreservationTest.kt" to 1,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RoutedRaftTransportTest.kt" to 16,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RoutedUnicastRouterTest.kt" to 4,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/ServerClusterDirectoryConvergenceTest.kt" to 1,
        "kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/VoterReconnectionSupervisorTest.kt" to 4,
        "kuilt-cluster/src/jvmTest/kotlin/us/tractat/kuilt/cluster/RoutedRaftTransportMisWiredRelayTest.kt" to 1,
        "kuilt-warp-compiler/src/jvmTest/kotlin/us/tractat/kuilt/warp/RealVariantTieringTest.kt" to 1,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/ChicoryWasmRuntimeTest.kt" to 1,
    )
    doLast {
        val found = sortedMapOf<String, List<Int>>()
        val wrappers = sortedMapOf<String, List<RunTestWrapperTimeoutScanner.Violation>>()
        sources.files.forEach { file ->
            val raw = file.readText()
            if ("runTest" !in raw) return@forEach
            val code = KotlinCodeScanner.stripNonCode(raw)
            val path = file.relativeTo(rootPath).invariantSeparatorsPath
            val hits = RunTestTimeoutScanner.violations(code)
            if (hits.isNotEmpty()) found[path] = hits
            val wrapped = RunTestWrapperTimeoutScanner.violations(code)
            if (wrapped.isNotEmpty()) wrappers[path] = wrapped
        }
        // Pass 2: the wrapper defaults. NO baseline and NO ratchet — unlike the call sites there is
        // no grandfathered population to burn down, so the only correct count is zero. A wrapper is
        // also strictly worse than a call site: it is one edit that retimes every test that takes
        // the default, which is normally all of them.
        if (wrappers.isNotEmpty()) {
            val detail = wrappers.entries.joinToString("\n") { (path, hits) ->
                "  $path\n" + hits.joinToString("\n") { "    :${it.line}  ${it.parameter} = ${it.default}" }
            }
            error(
                "A `runTest(…)` wrapper defaults its timeout to a bare duration literal (#1739).\n" +
                    "The literal is in the PARAMETER DEFAULT, so the call site reads `timeout = timeout` and " +
                    "the call-site rule can neither flag it nor vouch for it — while every caller that takes " +
                    "the default silently inherits the ceiling. That is how a 5 s cap rode a published " +
                    "`commonMain` harness into every test using it.\n" +
                    "  THE FIX IS A NAMED CONSTANT, NOT A DIFFERENT NUMBER — `4.seconds` is the same defect " +
                    "as `5.seconds`:\n" +
                    "      timeout: Duration = TEST_WEDGE_BACKSTOP        // us.tractat.kuilt.test, :kuilt-test\n" +
                    "      timeout: Duration = RAFT_SIM_WEDGE_BACKSTOP    // :kuilt-raft-test\n" +
                    "      timeout: Duration = WARP_SIM_WEDGE_BACKSTOP    // :kuilt-warp-test\n" +
                    "  A harness far enough from those to want its own declares one beside itself, with KDoc " +
                    "saying it is a generous wall-clock WEDGE BACKSTOP and not a performance assertion — fast " +
                    "failure is the job of the bounded virtual-time `await*`/`settle()` calls inside the test, " +
                    "which are immune to host load.\n" +
                    "  If this parameter is a VIRTUAL bound (a `withTimeout` inside the test body) rather than " +
                    "`runTest`'s wall-clock ceiling, the repo's name for it is `within` — see " +
                    "`RaftTestFixtures.awaitCommit`.\n" + detail,
            )
        }
        val regressions = found.filter { (path, hits) -> hits.size > (baseline[path] ?: 0) }
        if (regressions.isNotEmpty()) {
            val detail = regressions.entries.joinToString("\n") { (path, hits) ->
                val was = baseline[path]
                val from = if (was == null) "no baseline (this file is new to the ratchet)" else "baseline $was"
                "  $path — $from, now ${hits.size}\n    line(s): ${hits.joinToString(", ")}"
            }
            error(
                "A `runTest(…)` gained a bare duration-literal timeout (#1739).\n" +
                    "A wall-clock ceiling over a VIRTUAL-time trajectory asserts nothing about the code — it " +
                    "asserts \"this host can retire N units of work in T seconds\", which is false exactly when " +
                    "the box is busy, and it pre-empts the bounded `await*`/`settle()` assertions that are the " +
                    "fast, load-independent detector.\n" +
                    "  THE FIX IS A NAMED CONSTANT, NOT A DIFFERENT NUMBER — `4.seconds` is the same defect as " +
                    "`5.seconds`:\n" +
                    "      timeout = TEST_WEDGE_BACKSTOP        // us.tractat.kuilt.test, :kuilt-test\n" +
                    "      timeout = RAFT_SIM_WEDGE_BACKSTOP    // :kuilt-raft-test\n" +
                    "      timeout = WARP_SIM_WEDGE_BACKSTOP    // :kuilt-warp-test\n" +
                    "  Dropping the `timeout` argument entirely is also fine (the 60 s library default).\n" +
                    "  This is a per-file COUNT ratchet: a file already over baseline is not an excuse to add " +
                    "one more. Do NOT raise the baseline in `build.gradle.kts` — sweep the file (all-or-none, " +
                    "so the next contributor copies the constant rather than a neighbour) and delete its " +
                    "entry.\n" + detail,
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "ok — ${sources.files.size} Kotlin sources scanned, " +
                "${found.size} file(s) at or below baseline (${found.values.sumOf { it.size }} sites), " +
                "0 wrapper timeout defaults\n",
        )
    }
}

// Locates a structured EVENT NAME inside a logging call and reports the LEVEL that call was made at,
// for `forbidDemotedFieldTrail` below (#2420). Same `object` rationale as the sibling scanners: it is
// invoked from inside `doLast`, where a script-level function reference would capture the
// unserializable `Build_gradle` instance.
//
// It needs the INVERSE stripping of every other scanner here. Its subject lives inside a STRING
// LITERAL (`log.info { "nw.seam.settled self=…" }`), which `KotlinCodeScanner.stripNonCode` blanks by
// design, and `KdocScanner` keeps only KDoc. So this one blanks COMMENTS and keeps strings — while
// still tracking string state, because a `//` inside a literal must not open a comment and a `"` inside
// a comment must not open a string. Blanking comments is not tidiness: the event names appear in
// PROSE all over this repo (KDoc on the very functions that log them, and the field-trail argument in
// `NwSeam`'s class doc), and every one of those would otherwise be attributed to whatever log call
// happened to precede it.
//
// OWNERSHIP is the nearest PRECEDING log call, with no extent computed. That is sound for the shape it
// reads — an event name is the first thing in its own log call's message — and its failure mode is a
// loud false positive (an event name written outside any log call is attributed to the previous one, or
// reported as `[not inside a logging call]` when there is no previous one) rather than a silent miss.
// Both spellings of the logger receiver in this tree are matched (`log` and `logger`); a third would
// read as "not inside a logging call", which fails rather than passing quietly.
object FieldTrailScanner {
    private val logCall = Regex("""(?<![A-Za-z0-9_])(?:log|logger)\s*\.\s*(trace|debug|info|warn|error)\s*[({]""")

    /** One occurrence of an event name: its 1-based line, and the level of the call it sits inside. */
    data class Sighting(val line: Int, val level: String?)

    /** Blank comments to nothing, preserving newlines (hence line numbers) and every string literal. */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        var inStr = false
        var inRaw = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                blockDepth > 0 -> when { // block comments nest, as they do in Kotlin
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> { if (c == '\n') out.append('\n'); i++ }
                }
                inRaw ->
                    if (text.startsWith("\"\"\"", i)) { inRaw = false; out.append("\"\"\""); i += 3 } else { out.append(c); i++ }
                // An escape consumes its escapee, so `\"` cannot close the literal — but a `\` before a
                // newline still emits the newline, or every later line number shifts.
                inStr -> when {
                    c == '\\' -> { out.append(c); i++; if (i < text.length) { out.append(text[i]); i++ } }
                    c == '"' -> { inStr = false; out.append(c); i++ }
                    c == '\n' -> { inStr = false; out.append(c); i++ } // malformed; recover, don't run away
                    else -> { out.append(c); i++ }
                }
                inChar -> when {
                    c == '\\' -> { out.append(c); i++; if (i < text.length) { out.append(text[i]); i++ } }
                    c == '\'' || c == '\n' -> { inChar = false; out.append(c); i++ }
                    else -> { out.append(c); i++ }
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("\"\"\"", i) -> { inRaw = true; out.append("\"\"\""); i += 3 }
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Every occurrence of [event] in already-comment-stripped [code], each with the level of the nearest
     * preceding logging call. The boundary check on both sides is what keeps `nw.seam.closed` from
     * matching `nw.seam.closed-state` and `nw.seam.closed.ignored` — three distinct events, two of which
     * are legitimately DEBUG.
     */
    fun sightings(code: String, event: String): List<Sighting> {
        fun tokenChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'
        val starts = logCall.findAll(code).map { it.range.first to it.groupValues[1] }.toList()
        val out = mutableListOf<Sighting>()
        var idx = code.indexOf(event)
        while (idx >= 0) {
            val before = code.getOrNull(idx - 1)
            val after = code.getOrNull(idx + event.length)
            if ((before == null || !tokenChar(before)) && (after == null || !tokenChar(after))) {
                out += Sighting(
                    line = code.take(idx).count { it == '\n' } + 1,
                    level = starts.lastOrNull { it.first < idx }?.second,
                )
            }
            idx = code.indexOf(event, idx + 1)
        }
        return out
    }
}

// Guard: forbid a CURATED field-trail log line from being emitted below INFO — or from silently going
// away. #2420.
//
// WHAT THIS IS FOR. `:kuilt-nw`'s characteristic failure is SILENCE: a seam that has resolved a peer
// and cannot move a byte over it emits nothing for its whole lifetime, so a wedged device and an idle
// one produce identical logs. Diagnosing one therefore rests entirely on a handful of lines being
// PRESENT in a capture taken off a real phone — and DEBUG is not present there. Measured from a field
// store during the #2425 wedge: 664 INFO / 7 WARN / 1 ERROR and **zero** DEBUG records, in a store that
// had not wrapped (its oldest record was 36 days older than the session). So DEBUG was never CAPTURED,
// not evicted, and the level of these specific lines is the difference between a one-line read and a
// two-device forensic exercise. Two of them — `nw.seam.dedup.replace` / `dedup.keep` — were at DEBUG
// during that incident, which is why the deciding fact was unavailable.
//
// WHY A CURATED LIST AND NOT A HEURISTIC. This is the load-bearing design decision, and the honest
// answer is that no heuristic is available. "Every `nw.*` event must be INFO" is simply false: most of
// them are correctly DEBUG (`nw.seam.opened`, `nw.send`, `nw.api.state` on the quiet transitions), and
// promoting them would raise a device's noise floor enough to evict the very trail this protects — the
// store is bounded. Nor can the criterion be derived from the code, because it is a judgement about
// what a DIAGNOSTICIAN needs, made once per line. So the list IS the assertion: an entry means "a human
// decided this line has to survive to a field capture", and adding or removing one is an edit a
// reviewer sees. The alternative shape considered and rejected was a `// FIELD-TRAIL:` marker comment
// on each call site — cheaper to add, but it puts the claim where a mechanical level change tends to
// sweep past it, and it cannot detect the line being DELETED at all.
//
// WHAT IT CHECKS, per curated event:
//   1. every occurrence is at `info`, `warn` or `error` — the three levels a release device records;
//   2. every occurrence sits inside a logging call, so the level is knowable at all;
//   3. the event still EXISTS somewhere in production source. A rename or deletion fails, for the same
//      reason `verifyModuleTable` and `forbidUnlintedModule` check their own stale direction: the trail
//      is a claim about what a capture will contain, and an entry naming a line nobody emits is a lie
//      that no other check can catch.
// It deliberately does NOT check the CONTENT of a line — whether it carries identities rather than
// sizes is a human assertion, exactly like `verifySeamHarnessCoverage`'s row-to-harness mapping.
//
// ONE ATTRIBUTION EDGE worth knowing about, because it is invisible in a green: an event name that is
// not lexically inside its own logging call — `nw.seam.inbound-silent` is built into a string in
// `sweepInboundSilence` and logged by a `log.warn { line }` further down — is vouched for via the
// NEAREST PRECEDING log call, which happens to be that `log.warn`. The verdict is correct today, but a
// function reorder could silently retarget it to some other call. What actually pins that line's level
// is a test asserting `Level.WARN` on the emitted event (`NwSeamWedgeDiagnosticsTest`), not this guard;
// the guard's contribution there is the staleness check. Keeping the trail's lines lexically inside
// their own `log.*` call is the cheap way to stay clear of this.
//
// The LEVEL SPLIT among the curated lines is a design decision this guard preserves but cannot verify:
// ERROR for a contract violation (`nw.seam.registry.orphan` — a state `NwSeam`'s own model forbids),
// WARN for a condition (`nw.seam.inbound-silent` — a quiet link may be a wedge or an idle app). All
// three levels satisfy the guard; flattening them would pass here and lose the distinction, so it is
// stated in the code that emits them, not enforced here.
//
// SCOPE: production `*Main/**/*.kt` across every module, the same source set as the sibling guards —
// which notably INCLUDES `appleMain`, where `detektAll` reaches nothing at all (its tasks are
// parse-only, #2039). `:spike`, `build-logic/` and `*.kts` are unscanned, as with every sibling.
val forbidDemotedFieldTrail by tasks.registering {
    group = "verification"
    description = "Fails if a curated field-trail log line is emitted below INFO, or has gone missing (#2420)."
    val sources = kotlinSourcesIn(subprojects.map { it.projectDir.resolve("src") }, "*Main/**/*.kt")
    inputs.files(sources).withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict is a
    // pure function of the scanned file contents and of the curated list, and the list is a literal here
    // precisely so it is folded into the task-action implementation hash — externalising it would drop it
    // out of the cache key and reintroduce the stale-green class.
    val stamp = layout.buildDirectory.file("verification/forbid-demoted-field-trail.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    // THE TRAIL a stuck `:kuilt-nw` formation is diagnosed from. Grouped by the question each answers.
    // Adding a line here is a promise that a field capture will carry it; removing one is a decision that
    // nobody needs it. Both belong in a diff.
    val fieldTrail = setOf(
        // Who did this device see, and what did it decide to do about it?
        "nw.loom.discovered",
        "nw.loom.self-skip",
        "nw.loom.name-collision",
        "nw.loom.redial-parked",
        "nw.loom.redial-resumed",
        "nw.loom.weave-timeout",
        // Which link resolved to whom — and, when a peer was double-dialled, which link SURVIVED. The two
        // `dedup` lines each report the direction that end kept, so two devices' captures can be compared
        // directly. In #2425 that comparison had to be reconstructed from both phones' Apple unified logs
        // instead, because these lines were at DEBUG and so were absent from the kuilt capture entirely.
        "nw.seam.resolved.first",
        "nw.seam.dedup.replace",
        "nw.seam.dedup.keep",
        "nw.seam.self-connection",
        "nw.seam.dialled-mismatch",
        "nw.seam.settled",
        // Did the link then die, and did this seam agree that it had?
        "nw.seam.closed",
        "nw.seam.closed-state",
        "nw.seam.removeByConn",
        "nw.seam.grace.expired",
        "nw.seam.viability.lost",
        "nw.seam.viability.recovered",
        "nw.seam.corrupt-inbound",
        "nw.seam.TORN",
        // The #2420 wedge diagnostics — one contract violation and two conditions.
        "nw.seam.registry.orphan",
        "nw.seam.inbound-silent",
        "nw.seam.publish-swap",
        // The transport's own account, which is the only place a browse result or a path change appears.
        "nw.api.browse-result",
        "nw.path.update",
        // A frame that went nowhere (#2455). Both were at DEBUG, which is the whole reason "no send error
        // in the log" was evidence of NOTHING on this fabric during the #2425 analysis: 182 bytes were
        // written onto a destroyed connection and the capture recorded neither line. They are pinned here
        // rather than left to the throw and the escalation alone, because those two reach a CALLER and a
        // teardown path — not a capture. This is what the fix itself now rests on for field diagnosis.
        "nw.send.no-connection",
        "nw.send.failed",
    )
    doLast {
        val captured = setOf("info", "warn", "error") // what a release device's store actually retains
        val demoted = mutableListOf<String>()
        val unlogged = mutableListOf<String>()
        val seen = mutableMapOf<String, Int>()
        sources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val raw = file.readText()
            if (fieldTrail.none { it in raw }) return@forEach
            val code = FieldTrailScanner.stripComments(raw)
            val path = file.relativeTo(rootPath).invariantSeparatorsPath
            for (event in fieldTrail) {
                for (sighting in FieldTrailScanner.sightings(code, event)) {
                    seen[event] = (seen[event] ?: 0) + 1
                    val where = "$path:${sighting.line}  $event"
                    when {
                        sighting.level == null -> unlogged += "$where  [not inside a logging call]"
                        sighting.level !in captured -> demoted += "$where  [log.${sighting.level}]"
                    }
                }
            }
        }
        val missing = fieldTrail.filter { it !in seen }.sorted()
        if (demoted.isNotEmpty() || unlogged.isNotEmpty() || missing.isNotEmpty()) {
            val detail = buildString {
                if (demoted.isNotEmpty()) {
                    append("\n  BELOW INFO — a release device records nothing of these:\n  ")
                    append(demoted.joinToString("\n  "))
                }
                if (unlogged.isNotEmpty()) {
                    append("\n\n  NOT IN A LOGGING CALL — the guard cannot vouch for a level it cannot see. ")
                    append("If this is prose, it belongs in a comment (which is stripped); if it is a log ")
                    append("line on a receiver other than `log`/`logger`, rename the receiver:\n  ")
                    append(unlogged.joinToString("\n  "))
                }
                if (missing.isNotEmpty()) {
                    append("\n\n  NO LONGER EMITTED ANYWHERE — renamed or deleted:\n  ")
                    append(missing.joinToString("\n  "))
                    append("\n  A curated entry naming a line nobody emits is a lie about what a capture ")
                    append("will contain. Update the event name here in the same commit that renames it, ")
                    append("or delete the entry if the line is genuinely gone — and then say so in the ")
                    append("issue, because something that was on the trail no longer is.")
                }
            }
            error(
                "A curated FIELD-TRAIL log line is not reachable in a field capture (#2420).\n" +
                    "These lines are the diagnosis of a stuck `:kuilt-nw` formation, whose failure mode is " +
                    "SILENCE — a wedged device and an idle one otherwise produce identical logs. DEBUG does " +
                    "NOT reach a release device's store: a capture taken during the #2425 wedge held 664 " +
                    "INFO / 7 WARN / 1 ERROR and ZERO debug records, in a store that had not wrapped. So a " +
                    "line demoted to `log.debug` is not quieter, it is ABSENT.\n" +
                    "  THE FIX IS THE LEVEL, NOT THE LIST. Restore the call to `log.info` (or `warn`/`error` " +
                    "if it reports a condition or a contract violation). Do NOT drop the entry from " +
                    "`fieldTrail` in `build.gradle.kts` to get past this — that is the edit this guard " +
                    "exists to make visible, and it is the one that costs a two-device forensic exercise " +
                    "the next time a pair of phones 30 cm apart will not form a session.\n" +
                    "  If the line genuinely no longer belongs on the trail, removing its entry is a " +
                    "reviewable decision — make it deliberately, in its own commit, with the reason." +
                    detail,
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "ok — ${fieldTrail.size} curated field-trail event(s), " +
                "${seen.values.sum()} emission site(s), all at INFO or above\n",
        )
    }
}

// Guard: forbid a module that contributes Kotlin source but no detekt task (#2005).
//
// Detekt is registered as a SIDE EFFECT of applying `kuilt.kmp-library`. Nothing else applies it,
// and nothing notices when a module doesn't: the module compiles, `./gradlew build` is green, and
// `detektAll` simply schedules no task for it — silent zero lint coverage, indistinguishable from
// "clean". That has now been found three times independently, each time by someone tripping over it
// while doing something else: `:spike` (#1863), `commonTest` across the whole repo (#1960), and
// `:kuilt-scale` (#2005, found because its new measurement suites had to be held to the repo's
// conventions BY HAND). Fixing instances leaves the fourth one to be found the same way. This makes
// "compiled but unlinted" a build failure instead.
//
// The assertion, per subproject with at least one `**/*.kt` under `src/`:
//   1. the detekt plugin is applied — the module is analysable at all, and
//   2. a `detektAll` task exists — so `./gradlew detektAll`, which is what CI's lint job runs,
//      actually reaches it. (1) without (2) is the shape that produced #2005's evidence: a module
//      can carry detekt tasks that the repo's own entry point never schedules.
//
// Both checks are by NAME/plugin-id rather than by task type: `io.gitlab.arturbosch.detekt.Detekt`
// is on `build-logic`'s classpath, not this script's, so a typed reference wouldn't compile here.
//
// WHAT THIS DOES NOT CATCH, stated rather than implied: it asserts the module is linted, not that
// every source set is covered. `detektAll` deliberately wires only the type-resolved tasks, and
// `commonTest` has no such task at all — that gap is #1960 and needs a different mechanism. Nor
// does it see a module whose sources live outside `src/`; the definition matches the sibling
// guards above, and widening it to "any Kotlin file anywhere" would drag in `build/` output.
//
// The allowlist is the known backlog, not an escape hatch — and since #2025 the citation rule on it
// is enforced rather than requested (see the allowlist's own comment). No module needs to be there
// on grounds of shape any more: `kuilt.detekt-jvm` covers a plain Kotlin/JVM module and
// `kuilt.detekt-kmp` a plain KMP one, each in one line.
val unlintedModuleProbes = mutableListOf<Triple<String, Boolean, FileTree>>()
gradle.projectsEvaluated {
    rootProject.subprojects.forEach { sub ->
        val linted = sub.plugins.hasPlugin("io.gitlab.arturbosch.detekt") &&
            sub.tasks.findByName("detektAll") != null
        unlintedModuleProbes += Triple(sub.path, linted, kotlinSourcesIn(listOf(sub.projectDir.resolve("src"))))
    }
    tasks.named("forbidUnlintedModule") {
        // Two independent things can flip the verdict, so both are declared. The per-module source
        // trees (separately named, for the same reason as `forbidSourcelessKmpTarget`: the verdict
        // depends on WHICH module a file sits in, so a pooled fingerprint would let a file move
        // between modules invisibly) cover "this module gained its first Kotlin source". The
        // registration map covers "this module gained or lost detekt", which is a change in a
        // SUBPROJECT's build script — invisible to the root script's own action-implementation hash,
        // so without this property a module could quietly drop detekt onto a cached green.
        unlintedModuleProbes.forEach { (path, _, tree) ->
            inputs.files(tree)
                .withPropertyName("moduleSources_" + path.replace(Regex("[^A-Za-z0-9]+"), "_"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        inputs.property("detektRegistration", unlintedModuleProbes.associate { (path, linted, _) -> path to linted })
    }
}

// Known-unlinted modules, each with the issue that tracks wiring it up. An entry MUST cite an
// issue — a bare exclusion turns this guard back into the silence it exists to break. Shrinks to
// empty; do NOT add a plain-JVM module here, apply `kuilt.detekt-jvm` instead, and do NOT add a
// plain KMP module here either, apply `kuilt.detekt-kmp` (#2016).
//
// The citation rule is CHECKED, not asked for (#2025). It used to be this comment plus a line in
// the failure message, with the value a free-form `String` — so `":foo" to ""` or `":foo" to
// "TODO"` was accepted in silence, which is the same shape as the defect the guard exists to end.
// A guard rots at its escape hatch, so both directions are mechanical:
//   - every value must CONTAIN an issue reference (`#<n>`, n ≥ 1) — validated eagerly below, at
//     root-script configuration time rather than inside the task, so no invocation can skip it; and
//   - every entry for a module that IS in this build must still be genuinely unlinted (in `doLast`,
//     where the probes are) — so wiring a module up and forgetting to delete its entry fails
//     instead of leaving a permanent hole. `:spike` is `-PincludeSpike`-gated and simply absent
//     from most builds, which is why that half is scoped to modules actually present.
val unlintedModuleAllowlist = mapOf(
    ":spike" to "#1863", // plain KMP, appleMain-only; -PincludeSpike-gated
)
unlintedModuleAllowlist.forEach { (path, citation) ->
    if (!citation.contains(Regex("#[1-9]\\d*"))) {
        error(
            "forbidUnlintedModule's allowlist entry for $path cites \"$citation\", which contains no " +
                "issue reference. Every entry MUST cite the issue that tracks wiring the module up " +
                "(e.g. \"#1863\") — a bare exclusion turns this guard back into the silence it exists " +
                "to break (#2025). Fix the entry in the root `build.gradle.kts`, or delete it and " +
                "apply `kuilt.detekt-jvm` / `kuilt.detekt-kmp` to $path instead.",
        )
    }
}

val forbidUnlintedModule by tasks.registering {
    group = "verification"
    description = "Fails if a subproject has Kotlin source but no detekt task — compiled but unlinted (#2005)."
    val probes = unlintedModuleProbes
    // See "Guard plumbing" above: stamp ⇒ UP-TO-DATE (#1827). Inputs are registered in the
    // `projectsEvaluated` block above, once every module's plugins and tasks exist.
    val stamp = layout.buildDirectory.file("verification/forbid-unlinted-module.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val allowlist = unlintedModuleAllowlist
    inputs.property("allowlist", allowlist)
    doLast {
        val stale = probes
            .filter { (path, linted, _) -> linted && path in allowlist }
            .map { (path, _, _) -> "$path (allowlisted for ${allowlist.getValue(path)})" }
        if (stale.isNotEmpty()) {
            error(
                "forbidUnlintedModule's allowlist names module(s) that ARE linted — a stale entry is a " +
                    "standing hole in the guard, since it would also swallow a future regression " +
                    "(#2025):\n  " + stale.joinToString("\n  ") +
                    "\n  Delete the entry from `allowlist` in the root `build.gradle.kts`.",
            )
        }
        val offenders = probes
            .filter { (path, linted, tree) -> !linted && path !in allowlist && !tree.isEmpty }
            .map { (path, _, tree) -> "$path (${tree.files.size} Kotlin file(s) under src/)" }
        if (offenders.isNotEmpty()) {
            error(
                "Module(s) contribute Kotlin source but register no detekt task — they compile, " +
                    "`./gradlew build` is green, and `detektAll` schedules NOTHING for them, which is " +
                    "indistinguishable from being clean (#2005):\n  " + offenders.joinToString("\n  ") +
                    "\n  THE FIX is one line in the module's `plugins { }` block — `id(\"kuilt.detekt-jvm\")` " +
                    "for a plain Kotlin/JVM module, `id(\"kuilt.detekt-kmp\")` (declared LAST) for a plain " +
                    "KMP one.\n" +
                    "  A KMP library should apply `kuilt.kmp-library`, which registers detekt already.\n" +
                    "  If none fits, add the module to this guard's `allowlist` in the root " +
                    "`build.gradle.kts` WITH the issue number tracking it — an entry without one is " +
                    "REJECTED, because a silent exclusion is the exact failure this guard ends.",
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "ok — ${probes.count { (_, _, tree) -> !tree.isEmpty }} module(s) with Kotlin source checked, " +
                "${allowlist.size} allowlisted\n",
        )
    }
}

// Guard: forbid Android PRODUCTION source that `detektAll` does not analyse (#2334).
//
// `forbidUnlintedModule` above asserts a module is linted; this asserts one SOURCE SET is, and it
// exists because the module-level assertion was green throughout the whole life of the bug. Every
// module with `src/androidMain` had detekt applied and a `detektAll` to schedule — and `detektAll`
// analysed none of that directory. `detektJvmMain` folds in `commonMain` and any jvmAndAndroid
// intermediate, then explicitly drops the `androidMain` leaf; the only other Android tasks wired in
// were the two `androidUnitTest` ones. So Android TEST code was linted and Android PRODUCTION code
// was not — the inversion nobody would choose, sitting under a green module-level guard. A
// `BroadcastReceiver` registration defect shipped through it (#2395), caught by a human reviewer.
//
// The assertion is EMPIRICAL and per file, not a task-name checklist: every `**/*.kt` under an
// Android production source root must appear in the `source` of some task `detektAll` actually
// depends on. That shape is what makes it hard to fool. A name-based version would pass the moment
// `detektAndroidRelease` was named anywhere, whether or not it reached the files — which is exactly
// the "wired but unreachable" false green #2005 and #2039 are both about, and the one a reader of a
// green `detektAll` cannot distinguish from clean. Reading `source` needs no detekt classes on this
// script's classpath (they are `build-logic`'s, per `forbidUnlintedModule`'s note): every detekt
// task is a plain Gradle `SourceTask`.
//
// It is also what PINS THE ONE CHOICE `kuilt.detekt-kmp` MAKES — release variant only, not both.
// That choice rests on `androidMain` being the sole Android production source set, so debug and
// release analyse identical files (measured: identical file counts, identical findings). Add
// `src/androidDebug` and only the release task's source is in the union, so the new files are
// uncovered and this reds — with a message naming the second task to wire. The premise is therefore
// checked rather than described, which is the point: a comment saying "there is no debug-only
// source today" is true until it isn't, and nothing tells you when.
//
// DELIBERATELY ANDROID-ONLY, and the restraint is the design. The general form — "fail on any
// production source no detekt task covers" — would red the whole repo on gaps that are open on
// purpose and already tracked: `appleMain` and every native/wasm source set have tasks, but
// PARSE-ONLY ones in which none of the four type-resolution rules can fire (#2039), and `commonTest`
// has no task at all (#1960). A guard that fails on known, filed, accepted debt gets an allowlist
// covering most of the repo on day one, and an allowlist that large is indistinguishable from no
// guard. Android is the case where the gap was neither known nor accepted.
val androidCoverageProbes = mutableListOf<Triple<String, FileTree, FileCollection>>()
gradle.projectsEvaluated {
    // Android PRODUCTION roots only. `androidUnitTest`/`androidInstrumentedTest` are covered by the
    // test tasks `kuilt.detekt-kmp` has always wired in, and holding them to this guard would
    // re-import #1960's `commonTest` gap through the side door.
    val androidProductionPatterns = listOf(
        "androidMain/**/*.kt",
        "androidDebug/**/*.kt",
        "androidRelease/**/*.kt",
    )
    rootProject.subprojects.forEach { sub ->
        val androidProduction = kotlinSourcesIn(listOf(sub.projectDir.resolve("src")), androidProductionPatterns)
        val detektAll = sub.tasks.findByName("detektAll")
        // The union of what `detektAll` actually schedules — resolved from the task graph, so a task
        // that exists but is not a dependency contributes nothing, exactly as at build time.
        val analysed = sub.files(
            detektAll?.taskDependencies?.getDependencies(detektAll)
                ?.filterIsInstance<SourceTask>()
                ?.map { it.source }
                .orEmpty(),
        )
        androidCoverageProbes += Triple(sub.path, androidProduction, analysed)
    }
    tasks.named("forbidUnlintedAndroidMain") {
        androidCoverageProbes.forEach { (path, androidProduction, analysed) ->
            val slug = path.replace(Regex("[^A-Za-z0-9]+"), "_")
            // Per-module and separately named for the same reason as `forbidUnlintedModule`'s: the
            // verdict depends on WHICH module a file sits in, so a pooled fingerprint would let a
            // file move between modules invisibly.
            inputs.files(androidProduction)
                .withPropertyName("androidProductionSources_$slug")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            // Only the Android-rooted slice of what `detektAll` analyses. The whole union would be
            // every module's commonMain and jvmMain — an enormous fingerprint whose contents cannot
            // change this verdict. The slice can: it empties out the moment `detektAll` stops
            // depending on the Android task, which is the regression this guard exists to catch.
            inputs.files(analysed.filter { file -> file.invariantSeparatorsPath.contains("/src/android") })
                .withPropertyName("androidSourcesAnalysedByDetektAll_$slug")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

val forbidUnlintedAndroidMain by tasks.registering {
    group = "verification"
    description = "Fails if a module has Android production Kotlin source that detektAll does not analyse (#2334)."
    val probes = androidCoverageProbes
    // See "Guard plumbing" above: stamp ⇒ UP-TO-DATE (#1827). Inputs are registered in the
    // `projectsEvaluated` block above, once every module's detekt tasks exist and carry their source.
    val stamp = layout.buildDirectory.file("verification/forbid-unlinted-android-main.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        var covered = 0
        val offenders = probes.flatMap { (path, androidProduction, analysed) ->
            val analysedFiles = analysed.files
            val declared = androidProduction.files
            covered += declared.count { it in analysedFiles }
            (declared - analysedFiles).sorted().map { "$path — ${it.relativeTo(rootPath).invariantSeparatorsPath}" }
        }
        if (offenders.isNotEmpty()) {
            error(
                "Android production Kotlin source is not analysed by ANY task `detektAll` depends on. " +
                    "It compiles, `./gradlew build` is green, and the lint job reports nothing about " +
                    "it — indistinguishable from clean, which is how a BroadcastReceiver defect " +
                    "reached review unlinted (#2334):\n  " + offenders.joinToString("\n  ") +
                    "\n  THE FIX is in `build-logic/src/main/kotlin/kuilt.detekt-kmp.gradle.kts`: add " +
                    "the detekt task that covers this source set to `detektAllTaskNames`. " +
                    "`androidMain` is covered by `detektAndroidRelease`; a build-type-scoped source " +
                    "set needs its own variant task (`src/androidDebug` ⇒ `detektAndroidDebug`), " +
                    "which is the case that single-variant wiring deliberately does not cover.\n" +
                    "  Do NOT fold Android source into `detektJvmMain` instead — it carries the JVM " +
                    "compile classpath, where `android.*` does not resolve, and all four rules in " +
                    "`config/detekt/detekt.yml` require type resolution. That is a false green, not " +
                    "a fix.",
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — $covered Android production Kotlin file(s) confirmed analysed by detektAll\n")
    }
}

// Locates the not-null assertion operator `!!` in Kotlin CODE, for
// `forbidNotNullAssertionInUnresolvedSource` below (#2039). Same `object` rationale as the sibling
// scanners: the caller invokes it from inside `doLast`, where a script-level function reference
// would capture the unserializable `Build_gradle` instance.
//
// It expects text already put through `KotlinCodeScanner.stripNonCode`, and that dependency is what
// keeps a `!!` written in PROSE from being reported as code. Not a hypothetical: `!!` appears in
// prose ten times in this tree today — `Assertions.kt`, `MappedBolt.kt`, `CardState.kt`,
// `EntitlementLedger.kt`, `MetricTapClient.kt`, `ServerClusterReconnect.kt`, `MultiNodeRaftSim.kt`
// — every one of them inside KDoc or a `//` comment, several of them saying "never write `!!`".
// A naive grep reports all ten. The stripper also RE-ENTERS code mode inside a string-template
// hole, so `"len=${value!!}"` is a real violation and is still reported.
//
// `selfTestFailures()` pins both directions on every execution of the guard, rather than in a
// review comment. `KotlinCodeScanner`'s own note asks whoever edits it to "re-verify BOTH
// directions with a mutation receipt on EVERY guard that calls it" — only one of those directions
// is loud, so this turns that standing obligation into a mechanism: weaken the stripper and this
// guard names the shape that broke, at the cost of a few microseconds per build.
object NotNullAssertionScanner {
    /** 1-based line numbers of every `!!` in already-stripped Kotlin code. */
    fun violations(code: String): List<Int> {
        val hits = ArrayList<Int>()
        var line = 1
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\n' -> { line++; i++ }
                // Non-overlapping, so `!!!` is one assertion plus a stray negation rather than two.
                c == '!' && i + 1 < code.length && code[i + 1] == '!' -> { hits += line; i += 2 }
                else -> i++
            }
        }
        return hits
    }

    // name → (source, expected line numbers). Written with escapes rather than raw strings so the
    // fixture can contain a raw-string case without fighting its own delimiters.
    private val cases: List<Triple<String, String, List<Int>>> = listOf(
        Triple("a bare assertion", "val n = value!!.length\n", listOf(1)),
        Triple("two on one line", "val n = a!!.b!!.c\n", listOf(1, 1)),
        Triple("a string literal", "val s = \"never write !! here\"\n", emptyList()),
        Triple("a line comment", "// never write !! here\nval n = 1\n", emptyList()),
        Triple("a block comment", "/* never write !! here */\nval n = 1\n", emptyList()),
        Triple("a nested block comment", "/* /* !! */ !! */\nval n = 1\n", emptyList()),
        Triple("KDoc", "/**\n * Never reach for `!!` here.\n */\nval n = 1\n", emptyList()),
        Triple("a raw string", "val s = \"\"\"\nnot !! code\n\"\"\"\n", emptyList()),
        Triple("a char literal", "val c = '!'\nval d = !flag\n", emptyList()),
        // The two template directions. A hole is CODE — an assertion there is real and must fire;
        // the literal text around it is not, and must not.
        Triple("a string-template hole", "val s = \"len=\${value!!.length}\"\n", listOf(1)),
        Triple("literal text beside a hole", "val s = \"\${a}!! literal\"\n", emptyList()),
        // Line numbers must survive stripping, or every report points at the wrong place.
        Triple("line numbers survive", "// !!\n\n/* !!\n !! */\nval n = value!!\n", listOf(5)),
    )

    fun selfTestFailures(): List<String> = cases.mapNotNull { (name, source, expected) ->
        val actual = violations(KotlinCodeScanner.stripNonCode(source))
        if (actual == expected) null else "$name — expected line(s) $expected, got $actual"
    }
}

// Guard: forbid `!!` in source sets that NO type-resolved detekt task covers (#2039).
//
// THE FACT THIS EXISTS FOR. Every rule `config/detekt/detekt.yml` enables — `UnsafeCallOnNullableType`,
// `UnnecessaryNotNullOperator`, `MapGetWithNotNullAssertionOperator`, `CastNullableToNonNullableType`
// — requires TYPE RESOLUTION, and detekt resolves types only against a JVM classpath. A detekt task
// for a wasm or native source set therefore parses the files and fires nothing at all. The receipt
// is in #2039: an `internal fun probe(v: String?): Int = v!!.length` appended to
// `demo/web/src/wasmJsMain` leaves `:demo-web:detektWasmJsMain --rerun-tasks` at
// "0 number of total code smells, BUILD SUCCESSFUL", while the same line fails
// `:demo-shared:detektJvmMain` immediately. `:spike` (#1863) has no detekt plugin at all.
//
// So `appleMain`, `iosMain`, `macosMain`, `wasmJsMain` and their test siblings are where all
// non-JVM platform code sits, and a green there means "nobody looked", which a reader cannot tell
// from "clean". This is the same false-green class as #2005 and #2334, one tier further out.
//
// WHAT IT IS AND IS NOT. It is a LEXICAL guard on one operator, and the limits are inherent, not
// oversights — say them out loud rather than let the next reader find them:
//   * It cannot see through a TYPEALIAS or a HELPER. `fun <T> T?.orDie(): T = this ?: error("…")`
//     is invisible to it, and so is any `!!` that has been moved behind a function. So is
//     `checkNotNull`-shaped code that is actually wrong. It buys the OBVIOUS case, not the class.
//   * It is a TOKEN match, not a semantic one. It knows nothing about nullability, so it cannot
//     distinguish a genuinely unsafe `!!` from one a human has proved safe — and it cannot fire on
//     the other three rules at all (`UnnecessaryNotNullOperator`, `CastNullableToNonNullableType`
//     and `MapGetWithNotNullAssertionOperator` all need types; the last is a strict subset of `!!`
//     and so is covered incidentally).
//   * `!!` reached by a `!` immediately followed by a unary `!` (`if (!!flag)`) reads as an
//     assertion here. It is one line in the whole tree's worth of risk and the shape is worth a
//     second look anyway; no attempt is made to tell them apart.
// Its value is exactly this: in source sets that today get NOTHING, the one rule that matters most
// now costs something to break. A rule set that does not need type resolution (option 1 in #2039)
// would cover more; it also costs a second config file and a repo-wide sweep, and was not chosen.
//
// HOW SCOPE IS DERIVED, and why it is not a list of source-set names. An include-list of
// `appleMain`/`wasmJsMain`/… is exactly the blind spot it is trying to close: the source set that
// needs adding to it is by definition the one nobody thought of, and a `linuxX64Main` or a
// `watchosMain` would land uncovered and silent. So the scope is subtractive and EMPIRICAL —
// every `**/*.kt` under a module's `src/`, MINUS every file in the `source` of a type-resolved
// detekt task. A new target is in scope on arrival; a source set that GAINS type-resolved coverage
// leaves scope automatically, so this can never double-report against detekt.
//
// The type-resolved set is named by TASK, and that name list is the one thing here that can rot,
// so it is checked: every name below must resolve in at least one module, or the guard fails
// rather than silently widening its own scope to the whole repo. The names mirror the two
// convention plugins' own `detektAllTaskNames` — `kuilt.detekt-kmp`'s five (`detektMetadataCommonMain`
// is deliberately NOT among them: it is the parse-only task, and `commonMain` earns its coverage by
// being FOLDED into `detektJvmMain`) plus `kuilt.detekt-jvm`'s two.
//
// WHAT IS DELIBERATELY OUT OF SCOPE: `commonTest`, `commonSamples` and the `jvmAndAndroidTest`
// intermediate. detekt generates no type-resolved task for any of them either, so the subtraction
// above leaves them in — and they are carved back out by name, because they are #1960's gap, not
// #2039's. The distinction is not cosmetic: theirs is JVM-path code whose FIX is to fold it into
// `detektJvmTest` and get all four real rules, and pre-empting that with a lexical ban on one
// operator would trade a fixable gap for a permanent approximation. It is also 433 sites across 83
// files against this guard's whole population of 7 — a baseline that size is indistinguishable
// from no guard, which is the objection `forbidUnlintedAndroidMain` records about allowlists.
// The carve-out checks its own stale direction: an entry matching nothing left in scope fails,
// so closing #1960 red-lights the entry that has become a lie instead of leaving it as decoration.
//
// `spike/src` is added by PATH, not through `subprojects`. `:spike` is a subproject only under
// `-PincludeSpike`, which CI does not pass, so a scope derived from the module set would cover it
// on nobody's build. Its `appleMain` is the module #1863 tracks and it is in scope on every build.
//
// THE BASELINE IS A PER-FILE COUNT RATCHET, for `forbidTightRunTestTimeout`'s reason and not a
// weaker one: under a file ALLOWLIST the file with the most `!!` — the one whose local convention
// is teaching the next contributor — is the one that becomes exempt. A count only ever moves down.
// Sweep a file all-or-none and delete its entry. It does NOT auto-tighten: sweeping a file without
// deleting its entry leaves the baseline loose for that path until someone notices, and failing on
// a DECREASE would red-light the branch doing the sweeping. What IS checked is the entry that has
// stopped meaning anything — a key naming a file no longer in scope fails, so a deleted or renamed
// path cannot sit there grandfathering whatever lands on it next.
val typeResolvedDetektTaskNames = listOf(
    // kuilt.detekt-kmp — the tier that carries a JVM/Android compile classpath. `detektJvmMain`
    // also carries commonMain and any jvmAndAndroid* MAIN intermediate, folded in by that plugin.
    "detektJvmMain",
    "detektAndroidRelease",
    "detektJvmTest",
    "detektAndroidDebugUnitTest",
    "detektAndroidReleaseUnitTest",
    // kuilt.detekt-jvm — plain Kotlin/JVM modules (`:kuilt-scale`, `:examples`, `:kuilt-warp-ksp`,
    // the demo apps). Both tasks carry the compile classpath.
    "detektMain",
    "detektTest",
)

// The #1960 tier — see the carve-out paragraph above.
val commonTierSourceSets = listOf("commonTest", "commonSamples", "jvmAndAndroidTest")

// Everything under a module's `src/` that no type-resolved detekt task analyses, and the subset of
// it this guard actually scans (the same set minus the #1960 tier). Filled in `projectsEvaluated`,
// once every module's detekt tasks exist and carry their folded source.
val notTypeResolvedSources = objects.fileCollection()
val notTypeResolvedScannedSources = objects.fileCollection()
val typeResolvedDetektTasksFound = objects.setProperty(String::class.java)

gradle.projectsEvaluated {
    val found = LinkedHashSet<String>()
    val analysed = rootProject.subprojects.flatMap { sub ->
        typeResolvedDetektTaskNames.mapNotNull { name ->
            (sub.tasks.findByName(name) as? SourceTask)?.also { found += name }?.source
        }
    }
    typeResolvedDetektTasksFound.set(found)
    // `spike/src` by path — see the note above; `files()` de-duplicates it under `-PincludeSpike`.
    val roots = rootProject.subprojects.map { it.projectDir.resolve("src") } + rootDir.resolve("spike/src")
    val uncovered = kotlinSourcesIn(roots).minus(files(analysed))
    notTypeResolvedSources.from(uncovered)
    // A local copy, not the script-level `val`: a lambda that closed over the property directly
    // would capture the `Build_gradle` instance, which the configuration cache cannot serialize.
    val carveOut = commonTierSourceSets
    notTypeResolvedScannedSources.from(
        uncovered.filter { file ->
            carveOut.none { "/src/$it/" in file.invariantSeparatorsPath }
        },
    )
}

val forbidNotNullAssertionInUnresolvedSource by tasks.registering {
    group = "verification"
    description = "Fails on a `!!` in a source set no type-resolved detekt task covers — apple/native/wasm and :spike, where all four nullability rules are silently inert (#2039)."
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a function of file PATHS (the baseline keys, and the carve-out's staleness check) and file
    // CONTENTS, both captured by a RELATIVE fingerprint. The declared set is the UNFILTERED one, so
    // a file entering or leaving the carve-out invalidates the cached success too.
    inputs.files(notTypeResolvedSources).withPropertyName("sourcesWithoutTypeResolvedDetekt")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("typeResolvedDetektTasksFound", typeResolvedDetektTasksFound)
    val stamp = layout.buildDirectory.file("verification/forbid-notnull-assertion-in-unresolved-source.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    val scanned = notTypeResolvedScannedSources
    val uncovered = notTypeResolvedSources
    val carveOut = commonTierSourceSets
    val expectedTaskNames = typeResolvedDetektTaskNames
    val tasksFound = typeResolvedDetektTasksFound
    // The baseline MUST stay a literal here. Per "Guard plumbing" above it is covered by the cache
    // key only because it is folded into the task-action implementation hash; moving it to
    // `gradle.properties` or a resource would silently drop it out and reintroduce the stale-green
    // class the stamps were made safe against. Entries are paths relative to the root, violation
    // counts as of #2039's first PR — 4 files, 7 sites, the whole grandfathered population.
    // Regenerate after a sweep with this scanner, not by hand.
    val baseline = mapOf(
        "kuilt-nw/src/appleMain/kotlin/us/tractat/kuilt/nw/RealNwApi.kt" to 1,
        "kuilt-otel/src/appleTest/kotlin/us/tractat/kuilt/otel/NSFileManagerDurableStoreTest.kt" to 4,
        "kuilt-otel/src/wasmJsTest/kotlin/us/tractat/kuilt/otel/IndexedDbDurableStoreTest.kt" to 1,
        "spike/src/appleMain/kotlin/spike/nw/SpikeNw.kt" to 1,
    )
    doLast {
        val selfTest = NotNullAssertionScanner.selfTestFailures()
        if (selfTest.isNotEmpty()) {
            error(
                "The `!!` scanner does not agree with its own fixture, so this guard's verdict — in " +
                    "EITHER direction — means nothing. Almost always the cause is an edit to " +
                    "`KotlinCodeScanner.stripNonCode`, which every one of these cases depends on:\n  " +
                    selfTest.joinToString("\n  "),
            )
        }
        val missingTasks = expectedTaskNames - tasksFound.get()
        if (missingTasks.isNotEmpty()) {
            error(
                "This guard derives its scope by SUBTRACTING the source of the type-resolved detekt " +
                    "tasks, and these names now resolve in no module: ${missingTasks.joinToString(", ")}.\n" +
                    "  A name that stops matching does not narrow the guard, it WIDENS it — every file " +
                    "that task covered silently becomes \"uncovered\", and the next `!!` written in " +
                    "ordinary jvm/common code is reported here instead of by detekt, which is the rule " +
                    "that should have caught it.\n" +
                    "  THE FIX is to re-sync `typeResolvedDetektTaskNames` in `build.gradle.kts` with " +
                    "`detektAllTaskNames` in `build-logic/src/main/kotlin/kuilt.detekt-kmp.gradle.kts` " +
                    "and `kuilt.detekt-jvm.gradle.kts` — minus `detektMetadataCommonMain`, which is the " +
                    "PARSE-ONLY task and must never be counted as coverage.",
            )
        }
        val uncoveredPaths = uncovered.files.map { it.invariantSeparatorsPath }
        val staleCarveOut = carveOut.filter { name -> uncoveredPaths.none { "/src/$name/" in it } }
        if (staleCarveOut.isNotEmpty()) {
            error(
                "`commonTierSourceSets` carves ${staleCarveOut.joinToString(", ")} out of this guard " +
                    "because #1960 leaves it with no type-resolved detekt task — and it now has one, " +
                    "or has gone away. Either way the entry has become a lie about why that source is " +
                    "unscanned.\n" +
                    "  THE FIX is to delete the entry, then re-run and burn down whatever it was hiding " +
                    "(if the source set is now type-resolved, it will be subtracted anyway and nothing " +
                    "moves).",
            )
        }
        val found = sortedMapOf<String, List<Int>>()
        val bySourceSet = sortedMapOf<String, Int>()
        scanned.files.forEach { file ->
            val path = file.relativeTo(rootPath).invariantSeparatorsPath
            bySourceSet.merge(path.substringAfter("/src/").substringBefore('/'), 1, Int::plus)
            val raw = file.readText()
            if ("!!" !in raw) return@forEach
            val hits = NotNullAssertionScanner.violations(KotlinCodeScanner.stripNonCode(raw))
            if (hits.isNotEmpty()) found[path] = hits
        }
        // The baseline's own stale direction — a DANGLING key, i.e. a path that is no longer in
        // scope at all (deleted, renamed, or moved into a source set detekt now type-resolves). That
        // entry has stopped grandfathering anything and become a claim about a file that is not
        // there, which is the shape `forbidUnlintedModule` and the carve-out above both refuse.
        // A mere DECREASE is deliberately tolerated, for `forbidTightRunTestTimeout`'s reason: a
        // half-swept file would otherwise red-light the branch doing the sweeping.
        val dangling = baseline.keys.filterNot { key ->
            scanned.files.any { it.relativeTo(rootPath).invariantSeparatorsPath == key }
        }
        if (dangling.isNotEmpty()) {
            error(
                "The baseline grandfathers file(s) this guard no longer scans:\n    " +
                    dangling.sorted().joinToString("\n    ") +
                    "\n  Deleted, renamed, or moved into a source set a type-resolved detekt task now " +
                    "covers — either way the entry is a claim about a file that is not there, and it " +
                    "would silently grandfather a NEW file that later lands on the same path.\n" +
                    "  THE FIX is to delete the entry (or re-key it to the new path with the count this " +
                    "scanner reports).",
            )
        }
        val regressions = found.filter { (path, hits) -> hits.size > (baseline[path] ?: 0) }
        if (regressions.isNotEmpty()) {
            val detail = regressions.entries.joinToString("\n") { (path, hits) ->
                val was = baseline[path]
                val from = if (was == null) "no baseline (this file is new to the ratchet)" else "baseline $was"
                "  $path — $from, now ${hits.size}\n    line(s): ${hits.joinToString(", ")}"
            }
            error(
                "A `!!` was written in a source set NO type-resolved detekt task covers (#2039).\n" +
                    "detekt resolves types only against a JVM classpath, so every rule in " +
                    "`config/detekt/detekt.yml` — `UnsafeCallOnNullableType` among them — parses these " +
                    "files and fires nothing. `./gradlew detektAll` is green on them whatever they " +
                    "contain, which is indistinguishable from clean.\n" +
                    "  THE FIX is to remove the assertion, not to suppress it — there is no hatch:\n" +
                    "      requireNotNull(x) { \"…\" } / checkNotNull(x) { \"…\" }   // fail fast, with a diagnostic\n" +
                    "      x ?: error(\"…\")                                       // same, at an expression\n" +
                    "      x?.let { … } / x ?: default                           // when absence is legal\n" +
                    "  This is a per-file COUNT ratchet: a file already over baseline is not an excuse to " +
                    "add one more. Do NOT raise the baseline in `build.gradle.kts` — sweep the file " +
                    "(all-or-none, so the next contributor copies the shape rather than a neighbour) and " +
                    "delete its entry.\n" + detail,
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "ok — ${scanned.files.size} Kotlin source(s) with no type-resolved detekt coverage scanned " +
                "(${bySourceSet.entries.joinToString(", ") { "${it.key} ${it.value}" }}), " +
                "${found.size} file(s) at or below baseline (${found.values.sumOf { it.size }} sites)\n",
        )
    }
}

// Guard: forbid `:kuilt-bolt` rejoining the CRDT lattice (#2212, epic #2210).
//
// A bolt is a WRITE-ONLY archive. Its whole reason to exist is that it consumes operations, never
// states, and never joins the lattice — which is what lets a server's archive retain a year beside
// a phone that retains an hour. Suppression is monotone and contagious through `Quilted.piece`
// (`Rga.piece` takes the elementwise MAX of the compaction floors and the UNION of the compacted
// ids, then purges under both), so the instant a bolt could be `piece`d with anything, its
// retention would become the group's business again and the module would be pointless.
//
// The invariant is therefore enforced by ABSENCE, and absence is exactly what no runtime test can
// observe: there is no value to assert about a method that is not there. A source scan can, and a
// compile-level absence is worth more than a test — a consumer who cannot NAME `piece` on a bolt
// cannot call it, in any version, from any module.
//
// Scope is `:kuilt-bolt`'s MAIN sources only. Test sources legitimately build `Rga` fixtures and
// merge them, and the module's own KDoc must be free to explain what it does not do — hence the
// shared `KotlinCodeScanner`, which blanks comments and string literals so PROSE about `piece` and
// `Quilted` (there is a good deal of it, deliberately) is invisible here while code is not.
//
// `Patch` is deliberately NOT scanned. It is the state-fragment type a bolt must never absorb, but
// banning the token would guard a hypothetical: absorbing one requires `piece`, which this guard
// already forbids, and the extra rule would only fire on a KDoc-adjacent identifier some future
// backend legitimately needed. Two tokens, both load-bearing, is the whole rule.
val forbidBoltRejoiningTheLattice by tasks.registering {
    group = "verification"
    description = "Fails if :kuilt-bolt's main sources name Quilted or piece — a bolt must never merge back (#2212)."
    val sources = kotlinSourcesIn(listOf(project(":kuilt-bolt").projectDir.resolve("src")), "**/*Main/**/*.kt")
    inputs.files(sources).withPropertyName("boltMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict
    // is a pure function of the scanned files' contents, which a RELATIVE fingerprint captures.
    val stamp = layout.buildDirectory.file("verification/forbid-bolt-rejoining-the-lattice.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val banned = Regex("""(?<![A-Za-z0-9_])(Quilted|piece)(?![A-Za-z0-9_])""")
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.asSequence()
            .flatMap { file ->
                KotlinCodeScanner.stripNonCode(file.readText()).lineSequence().withIndex()
                    .mapNotNull { (i, line) ->
                        banned.find(line)?.let { "${file.relativeTo(rootPath)}:${i + 1}  ${it.value}" }
                    }
            }.toList()
        if (offenders.isNotEmpty()) {
            error(
                "`:kuilt-bolt` main source names `Quilted` or `piece`. A bolt is a write-only archive: " +
                    "it consumes operations, never states, and must never merge back into the lattice — " +
                    "`piece` would make its source's forgetting contagious again and defeat the whole " +
                    "module (#2212). If a bolt genuinely needs to READ a CRDT, take an `OpLogCrdt`, not a " +
                    "`Quilted`:\n  " + offenders.joinToString("\n  "),
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${sources.files.size} :kuilt-bolt main sources scanned\n")
    }
}

// Guard: every `:kuilt-*` module has a row in CLAUDE.md's module table (#2257).
//
// CLAUDE.md's "Module structure & dependency direction" section is the first thing an agent reads
// in this repo, so a module missing from it is invisible to exactly the reader it exists for — an
// agent asked to keep a longer history than the live replica will not find the module that does it.
// That is not hypothetical: `:kuilt-bolt` shipped seven PRs before anyone noticed it had no row,
// and the sweep that found it found twenty-one more (the whole `:kuilt-otel` and `:kuilt-warp`
// families, plus `:kuilt-heddle`, `:kuilt-nw`, `:kuilt-gossip-test`, `:kuilt-scale`). A
// hand-maintained inventory beside a machine-maintained one drifts in one direction and does so in
// silence. `:kuilt-bom` does not drift, because it derives its constraints from the `kuilt.publish`
// marker (#1044) rather than from a list somebody must remember to edit; this is the same idea one
// level down, for the list that cannot be derived.
//
// There is deliberately NO allowlist. The table was brought to complete (#2261) BEFORE this guard
// landed, so the baseline is empty — and an allowlist over an empty baseline is nothing but a place
// to put the next omission.
//
// ── Why CLAUDE.md and not README.md ─────────────────────────────────────────────────────────────
// README's module list is a CURATED consumer surface, not an inventory. It omits internal plumbing
// (`:kuilt-liveness`, `:kuilt-quilter`, `:kuilt-cluster`, `:kuilt-stream`, `:kuilt-tcp`,
// `:kuilt-bom`), omits every `*-test` module, and presents the otel and warp families as one
// umbrella row apiece pointing at a guide. Every one of those absences is a choice. Enforcing
// one-row-per-module there would need an allowlist naming most of the repo — and an allowlist that
// large IS the escape hatch, so the guard would encode nothing and rot at it.
//
// ── The model, asserted rather than assumed ─────────────────────────────────────────────────────
// The scan is scoped to the section under `## Module structure & dependency direction`, ending at
// the next `## ` heading, because a row only helps where a reader looking for the module map will
// find it — a mention in some later paragraph is prose, not an entry. That scoping is this guard's
// model of the document, so a missing heading fails LOUDLY rather than silently narrowing the
// search to nothing and passing.
//
// A row is a line beginning ``| `:kuilt-x` |``, and FENCED BLOCKS ARE SKIPPED. The fence tracking is
// not incidental tidiness: without it a ```` ```markdown ```` block illustrating the row format
// satisfied the guard, so deleting a real row and leaving an illustrative one built SUCCESSFULLY
// while the rendered table had no entry. `verifyDocCitations` tracks fences for the same reason, and
// `KotlinCodeScanner`/`KdocScanner` exist to stop exactly this — illustrative text read as content.
// (An HTML-commented row is the same mechanism and is NOT handled; a `<!-- | `:kuilt-x` | -->` line
// does not begin with `|`, so it is already invisible, but a multi-line HTML comment wrapping real
// rows would still satisfy this guard. Nothing in tree does that.)
//
// Reformatting the table the OTHER way — changing the row syntax so nothing matches — fails in the
// safe direction: every module reads as missing, which is a red somebody sees. That half of the
// asymmetry does hold; the fenced case above is why it cannot be claimed for the check as a whole,
// and a stated asymmetry with a live counterexample is worse than no statement, because it tells the
// next reader not to look.
//
// The stale direction is checked too, for the same reason `forbidUnlintedModule` checks its own
// allowlist: a row for a module that has since been renamed or deleted describes a repo that no
// longer exists, and nothing else in the build would ever notice.
val verifyModuleTable by tasks.registering {
    group = "verification"
    description = "Fails if a :kuilt-* module has no row in CLAUDE.md's module table (#2257)."
    val claudeMd = rootDir.resolve("CLAUDE.md")
    inputs.file(claudeMd).withPropertyName("claudeMd")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The other half of the verdict is the module SET, which lives in `settings.gradle.kts` and is
    // not a file this task reads. Declared as a property for the same reason `forbidUnlintedModule`
    // declares its `detektRegistration` map: without it, adding a module would land on a cached
    // green (see "Guard plumbing" above — a stamp is only safe if the inputs are honest).
    //
    // RECEIPT ORDERING, for whoever re-proves that: adding a module to `settings.gradle.kts` by hand
    // does NOT reach this task — it is pre-empted at configuration time and the red is about
    // something else. Use `-PguardProbeModule=:kuilt-zzz-probe`, or prove the property from the
    // other side by removing an `include`. See "Guard plumbing" above for why, and for what the
    // probe flag does not cover.
    val modulePaths = subprojects.map { it.path }.filter { it.startsWith(":kuilt-") }.sorted()
    inputs.property("modulePaths", modulePaths)
    val stamp = layout.buildDirectory.file("verification/verify-module-table.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    doLast {
        val heading = "## Module structure & dependency direction"
        val lines = claudeMd.readLines()
        val start = lines.indexOfFirst { it.trimEnd() == heading }
        if (start < 0) {
            error(
                "CLAUDE.md has no \"$heading\" section, so this guard cannot tell a listed module " +
                    "from an unlisted one and every verdict it could give would be meaningless " +
                    "(#2257). If the section was renamed, rename it here too; if the module map " +
                    "moved elsewhere, point this task at its new home.",
            )
        }
        val relativeEnd = lines.subList(start + 1, lines.size).indexOfFirst { it.startsWith("## ") }
        val end = if (relativeEnd < 0) lines.size else start + 1 + relativeEnd
        val rowName = Regex("""^\|\s*`(:kuilt-[a-z0-9-]+)`\s*\|""")
        val section = lines.subList(start, end)
        // Fenced blocks are illustration, not table. See the fence note above for the false green
        // this closes.
        var fenced = false
        val listed = section.mapNotNull { line ->
            when {
                line.trimStart().startsWith("```") -> { fenced = !fenced; null }
                fenced -> null
                else -> rowName.find(line)?.groupValues?.get(1)
            }
        }.toSet()

        val phantom = (listed - modulePaths.toSet()).sorted()
        if (phantom.isNotEmpty()) {
            error(
                "CLAUDE.md's module table has a row for module(s) that are not in this build — a " +
                    "row describing a repo that no longer exists is worse than no row, because a " +
                    "reader trusts it (#2257):\n  " + phantom.joinToString("\n  ") +
                    "\n  Delete the row, or fix the name if the module was renamed.",
            )
        }
        val missing = modulePaths.filterNot { it in listed }
        if (missing.isNotEmpty()) {
            // The subsection names are READ from the slice, never listed here. A hand-maintained
            // inventory beside a machine-maintained one is the exact defect this guard exists to
            // end, and a hardcoded copy inside the guard is the same defect one level in — the
            // first version of this message had already lost "Contract & core".
            val sections = section.mapNotNull { Regex("""^\*\*(.+?)\*\*""").find(it)?.groupValues?.get(1) }
            error(
                "Module(s) are in `settings.gradle.kts` but have no row in CLAUDE.md's \"$heading\" " +
                    "table. That table is the first thing an agent reads here, so an unlisted " +
                    "module is invisible to exactly the reader it exists for (#2257):\n  " +
                    missing.joinToString("\n  ") +
                    "\n  THE FIX is one table row, in the subsection the module belongs to (" +
                    sections.joinToString(" / ") + "), following the shape of its neighbours: " +
                    "targets, what it does, what it depends on. There is no allowlist here, " +
                    "deliberately — the table was complete when this guard landed.",
            )
        }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText("ok — ${modulePaths.size} module(s) checked against CLAUDE.md's table\n")
    }
}

// Walks Kotlin type declarations and their SUPERTYPE LISTS, for `verifySeamHarnessCoverage` below.
// Same `object` rationale as `KotlinCodeScanner`, whose output it consumes: the caller invokes it
// from inside `doLast`, where a script-level function reference would capture the unserializable
// `Build_gradle` instance.
//
// WHY A WALK AND NOT A REGEX. A class *implements* an interface through its supertype list, and the
// interface's name also appears in imports, KDoc, parameter types, property types and return types
// — so `git grep "Seam"` over-reports by an order of magnitude, and `git grep ": Seam"` under-reports
// (it misses `: Seam by inner`, a trailing-comma constructor whose `) : Seam {` sits on its own line,
// a qualified `: us.tractat.kuilt.core.Seam`, and every INDIRECT implementor). The supertype list is
// a bracket-structured region — from the type-name to the body brace, minus the primary constructor
// and the type parameters — so it is walked with depth counters, the same choice
// `RunTestTimeoutScanner` makes for an argument list and for the same reason.
//
// EVERY keyword occurrence produces a record, including object EXPRESSIONS (`object : Seam { … }`,
// name `null`). That is deliberate rather than thorough: an anonymous implementation is exactly the
// shape that would slip past a name-keyed registry, so the caller has to see it and decide, not
// silently not-see it.
//
// WHAT IT DOES NOT RESOLVE, since the guard's honesty rests on this being stated: names are matched
// as SIMPLE names, with no import/package resolution and no typealias expansion. So an `import … as`
// alias, or a `typealias` in the supertype position, reads as an unrelated type and its implementor
// is MISSED; and two same-named types in different packages are conflated, which can over-report. The
// tree today contains neither shape for `Seam` (checked when this landed), the conflation direction
// fails safe (a spurious row is visible and deletable), and the alias direction is the residual
// false-negative this guard cannot close by construction — closing it needs a compiler front end,
// not a source scan.
object KotlinTypeGraph {

    /**
     * One `class`/`interface`/`object` occurrence: its [name] (`null` for an object *expression*),
     * its 1-based [line], and the SIMPLE names in its supertype list.
     */
    data class Decl(val name: String?, val line: Int, val supertypes: List<String>)

    // `:` is in the lookbehind so `Foo::class` is not read as a declaration. `.` for the same reason.
    private val KEYWORD = Regex("""(?<![A-Za-z0-9_.:])(?:class|interface|object)(?![A-Za-z0-9_])""")

    /** Every declaration in already-[KotlinCodeScanner.stripNonCode]-ed [code], in source order. */
    fun declarations(code: String): List<Decl> {
        val out = mutableListOf<Decl>()
        for (m in KEYWORD.findAll(code)) {
            var p = m.range.last + 1
            while (p < code.length && code[p].isWhitespace()) p++
            val nameEnd = identifierEnd(code, p)
            val named = nameEnd > p
            // `companion object : Foo` has no written name but is not anonymous — it is `Companion`.
            val name = if (named) code.substring(p, nameEnd) else companionName(code, m.range.first)
            val headerFrom = if (named) nameEnd else p
            out += Decl(name, lineOf(code, m.range.first), supertypesFrom(code, headerFrom))
        }
        return out
    }

    /** Exclusive end of the identifier starting at [start], or [start] when there is none. */
    private fun identifierEnd(code: String, start: Int): Int {
        if (start >= code.length) return start
        if (!(code[start].isLetter() || code[start] == '_')) return start
        var i = start + 1
        while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
        return i
    }

    /** `"Companion"` when the keyword at [at] is preceded by the `companion` soft keyword. */
    private fun companionName(code: String, at: Int): String? {
        var i = at - 1
        while (i >= 0 && code[i].isWhitespace()) i--
        val end = i + 1
        while (i >= 0 && (code[i].isLetterOrDigit() || code[i] == '_')) i--
        return if (end > i + 1 && code.substring(i + 1, end) == "companion") "Companion" else null
    }

    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    private fun supertypesFrom(code: String, from: Int): List<String> {
        val colon = supertypeColon(code, from) ?: return emptyList()
        val raw = code.substring(colon + 1, supertypeListEnd(code, colon + 1))
        // A `where` clause constrains type PARAMETERS; it is not part of the supertype list.
        val list = WHERE.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw
        return splitTopLevel(list).mapNotNull(::headType)
    }

    private val WHERE = Regex("""(?<![A-Za-z0-9_])where(?![A-Za-z0-9_])""")

    /**
     * Index of the `:` opening the supertype list, or `null` when the declaration has none.
     *
     * Depth-tracked so a constructor parameter's own `:` (inside parens) and a type-parameter bound's
     * (inside angle brackets) are both skipped — those are the two `:`s that precede the one we want.
     */
    private fun supertypeColon(code: String, from: Int): Int? {
        var i = from
        var paren = 0
        var angle = 0
        var brack = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '[' -> brack++
                ']' -> brack--
                '<' -> if (paren == 0 && brack == 0) angle++
                '>' -> if (paren == 0 && brack == 0 && angle > 0) angle--
                '{' -> if (paren == 0 && brack == 0 && angle == 0) return null
                ':' -> if (paren == 0 && brack == 0 && angle == 0) return i
                '\n' -> if (paren == 0 && brack == 0 && angle == 0) {
                    // A header ends at a newline unless the next line plainly continues it. `class
                    // Foo(val a: Int)` with no body and no supertypes is the common case here.
                    val j = nextNonSpace(code, i + 1) ?: return null
                    if (code[j] != ':' && code[j] != '(' && code[j] != '{' && code[j] != '<') return null
                    i = j
                    continue
                }
                else -> Unit
            }
            i++
        }
        return null
    }

    /** Exclusive end of the supertype list that starts at [from] (just after its `:`). */
    private fun supertypeListEnd(code: String, from: Int): Int {
        var i = from
        var paren = 0
        var angle = 0
        var brack = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '[' -> brack++
                ']' -> brack--
                '<' -> if (paren == 0 && brack == 0) angle++
                '>' -> if (paren == 0 && brack == 0 && angle > 0) angle--
                '{' -> if (paren == 0 && brack == 0 && angle == 0) return i
                '\n' -> if (paren == 0 && brack == 0 && angle == 0 && !continues(code, from, i)) return i
                else -> Unit
            }
            i++
        }
        return code.length
    }

    /** True when the supertype list started at [from] carries on past the newline at [at]. */
    private fun continues(code: String, from: Int, at: Int): Boolean {
        if (code.substring(from, at).isBlank()) return true // `: \n    Seam` — nothing consumed yet
        var k = at - 1
        while (k >= from && (code[k] == ' ' || code[k] == '\t')) k--
        if (k >= from && (code[k] == ',' || code[k] == ':')) return true
        val j = nextNonSpace(code, at + 1) ?: return false
        if (code[j] == ',' || code[j] == '{') return true
        return code.startsWith("by ", j) || code.startsWith("where ", j)
    }

    private fun nextNonSpace(code: String, from: Int): Int? {
        var j = from
        while (j < code.length && code[j].isWhitespace()) j++
        return if (j < code.length) j else null
    }

    private fun splitTopLevel(list: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in list) {
            when (c) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> depth--
                else -> Unit
            }
            if (c == ',' && depth == 0) {
                out += cur.toString()
                cur.clear()
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    /** `Seam` from `us.tractat.kuilt.core.Seam by inner`, `Foo` from `Foo<T>(arg)`. */
    private fun headType(entry: String): String? {
        val t = entry.trim()
        var i = 0
        while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_' || t[i] == '.')) i++
        val qualified = t.substring(0, i)
        return qualified.substringAfterLast('.').ifEmpty { null }
    }

    /** Every name in [seeds]' transitive subtype closure over [supertypes], seeds included. */
    fun closureOf(seeds: Set<String>, supertypes: Map<String, Set<String>>): Set<String> {
        val reached = seeds.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            supertypes.forEach { (name, parents) ->
                if (name !in reached && parents.any { it in reached }) {
                    reached += name
                    grew = true
                }
            }
        }
        return reached
    }
}

// Guard: every production `Seam` implementation is ENUMERATED in `docs/seam-harness-coverage.md`,
// against either the conformance harness that drives it or an opt-out carrying a reason (#1871).
//
// ── WHAT THIS PROVES, AND — READ THIS FIRST — WHAT IT DOES NOT ──────────────────────────────────
// It proves ENUMERATION COMPLETENESS: no production `Seam` implementation is absent from the
// registry, and no registry row names a seam or a harness that no longer exists.
//
// It does NOT prove COVERAGE. Nothing here checks that the harness a row names actually exercises
// the seam it is written beside. A row mapping `NearbySeam` to `TcpConformanceTest` is a lie this
// guard passes without comment. The mapping is a human assertion, reviewed like any other prose.
//
// That boundary is the whole design, not a shortcoming to be fixed later. "Is seam X covered by some
// harness?" is not statically decidable here: `SeamConformanceSuite` drives seams through a `Loom`,
// so nothing in the source says `InMemoryLoomConformanceTest` exercises `InMemorySeam` — the harness
// builds a `Loom`, and which `Seam` implementation falls out is a runtime fact of `weave`. A guard
// that CLAIMED to detect coverage would be a stronger reassurance than it could pay for, and #1871
// is precisely a report of a reassuring artifact (a 6/6-correct blast-radius matrix) that was blind.
// Overstating this one would repeat that mistake with more ceremony.
//
// ── WHY THE GAP IS WORTH A GUARD AT ALL ────────────────────────────────────────────────────────
// #1859 added a new `Seam` obligation and measured its blast radius across every harness. The
// measurement was correct — and still missed two production `commonMain` seams (`TieredSeam`,
// `RoomHubSeam`) that violated both halves of the obligation, because enumerating the HARNESSES
// answers "which seams are reachable from a `Loom` that has a bound conformance subclass", not
// "which seams exist". Those two sets differ, and nothing reported the difference. #1937 bound the
// two missing harnesses; it did not make the difference visible. This does: the untested set is now
// a list somebody has to look at, and adding to it costs a written reason.
//
// ── WHY A MARKDOWN TABLE AND NOT A `mapOf` IN THIS SCRIPT ──────────────────────────────────────
// The registry's PAYLOAD is its opt-out list — the first honest count of how many production seams
// no conformance harness touches. That number only does its job if a person reads it, so it lives
// where `verifyModuleTable`'s inventory lives: in a document, as a table, diffable in a PR. A
// `mapOf` two thousand lines into a build script is an allowlist wearing a registry's clothes.
//
// The reason string is MANDATORY for an opt-out, and long enough that `n/a` cannot pass. Same
// mechanism as `forbidProductionDispatcherInTests`'s `// ALLOW-realDispatcher: <reason>`, whose
// group-1-blank case is itself a violation: a bare name on an allowlist re-hides exactly what the
// list exists to surface.
//
// ── SCOPE ──────────────────────────────────────────────────────────────────────────────────────
// Production source only, in both KMP and plain-JVM layouts (`src/*Main/`, `src/main/`) — the mirror
// of `forbidProductionDispatcherInTests`'s two test shapes. That deliberately INCLUDES the
// test-support modules' `commonMain` (`:kuilt-test`, `:kuilt-session-test`, `:kuilt-conformance`):
// `FakeSeam` and `FlakyLifecycleSeam` were fixed for a real contract violation in #1854, so a fixture
// seam is a seam. `spike/src` is added by hand because `:spike` is only in the build under
// `-PincludeSpike`, and a guard whose reach depends on a flag is a guard with a hole in it.
//
// The harness side scans TEST source for the transitive subclasses of `SeamConformanceSuite`; the
// ANONYMOUS harnesses (`object : SeamConformanceSuite()` inside the suite's own meta-tests) have no
// name to cite and are correctly absent from it. `git grep ": SeamConformanceSuite"` reports 21 hits
// and the count that matters is 17 — the other four are three object expressions and a return type.
//
// The transitive closure there is deliberate but has one known friction, stated before it bites: an
// ABSTRACT harness base (`abstract class BaseFooConformanceTest : SeamConformanceSuite()`, subclassed
// twice) lands in the set alongside its subclasses, so the cited-by-no-row check would want all three
// named. Nothing in the tree has that shape today; when something does, cite them all — the extra
// names are true, and narrowing the closure to leaf classes would let a base that drives a seam its
// subclasses do not go unattributed.
val verifySeamHarnessCoverage by tasks.registering {
    group = "verification"
    description = "Fails if a production Seam implementation has no row in docs/seam-harness-coverage.md (#1871)."
    val srcRoots = (subprojects.map { it.projectDir.resolve("src") } + rootDir.resolve("spike/src")).distinct()
    val productionSources = kotlinSourcesIn(srcRoots, listOf("*Main/**/*.kt", "main/**/*.kt"))
    val harnessSources = kotlinSourcesIn(srcRoots, listOf("*Test/**/*.kt", "test/**/*.kt"))
    val registryFile = rootDir.resolve("docs/seam-harness-coverage.md")
    inputs.files(productionSources).withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(harnessSources).withPropertyName("harnessSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(registryFile).withPropertyName("registry")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // See "Guard plumbing" above: the stamp is what makes UP-TO-DATE possible (#1827). The verdict is
    // a function of file NAMES (registry rows are keyed by path) and file CONTENTS only, both of which
    // a RELATIVE fingerprint captures — so a cache hit genuinely means "this exact tree was verified".
    val stamp = layout.buildDirectory.file("verification/verify-seam-harness-coverage.ok")
    outputs.file(stamp)
    outputs.cacheIf { true }
    val rootPath = rootDir
    doLast {
        val heading = "## Registry"
        // A row: | `TypeName` | `path/to/Decl.kt` | `Harness`[, `Harness`…] or none | reason |
        val row = Regex(
            """^\|\s*`([A-Za-z_][A-Za-z0-9_]*)`\s*\|\s*`([^`|]+)`\s*\|\s*([^|]*?)\s*\|\s*(.*?)\s*\|\s*$""",
        )
        val harnessCell = Regex("""`([A-Za-z_][A-Za-z0-9_]*)`""")
        // Long enough that `n/a`, `TODO`, `later` and a bare `-` cannot pass for a reason.
        val minReason = 20

        // ── The tree ────────────────────────────────────────────────────────────────────────────
        val supertypes = mutableMapOf<String, MutableSet<String>>()
        val sites = linkedMapOf<String, MutableList<Int>>() // "path#Name" -> declaration line(s)
        val siteName = mutableMapOf<String, String>()
        val anonymous = mutableListOf<Triple<String, Int, List<String>>>()
        productionSources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val rel = file.relativeTo(rootPath).invariantSeparatorsPath
            KotlinTypeGraph.declarations(KotlinCodeScanner.stripNonCode(file.readText())).forEach { d ->
                if (d.name == null) {
                    anonymous += Triple(rel, d.line, d.supertypes)
                    return@forEach
                }
                supertypes.getOrPut(d.name) { mutableSetOf() } += d.supertypes
                val key = "$rel#${d.name}"
                sites.getOrPut(key) { mutableListOf() } += d.line
                siteName[key] = d.name
            }
        }
        val harnessSupertypes = supertypes.mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
        val harnesses = mutableSetOf<String>()
        harnessSources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            KotlinTypeGraph.declarations(KotlinCodeScanner.stripNonCode(file.readText())).forEach { d ->
                if (d.name != null) harnessSupertypes.getOrPut(d.name) { mutableSetOf() } += d.supertypes
            }
        }
        val harnessNames = KotlinTypeGraph.closureOf(setOf("SeamConformanceSuite"), harnessSupertypes)
        harnessSources.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            KotlinTypeGraph.declarations(KotlinCodeScanner.stripNonCode(file.readText())).forEach { d ->
                if (d.name != null && d.name != "SeamConformanceSuite" && d.name in harnessNames) {
                    harnesses += d.name
                }
            }
        }

        val seamNames = KotlinTypeGraph.closureOf(setOf("Seam"), supertypes)
        // `Seam` itself is the contract, not an implementation of it.
        val scanned = sites.keys.filter { siteName.getValue(it) != "Seam" && siteName.getValue(it) in seamNames }
            .sorted()
        val failures = mutableListOf<String>()

        // ── An unnameable implementation cannot carry a row ─────────────────────────────────────
        val anonymousSeams = anonymous.filter { (_, _, sup) -> sup.any { it in seamNames } }
        if (anonymousSeams.isNotEmpty()) {
            failures += "Anonymous `object : Seam` in PRODUCTION source. The registry is keyed by " +
                "`<path>#<TypeName>`, so an unnamed implementation cannot carry a row — and being " +
                "unregisterable is exactly how it would slip past this guard (#1871). Give it a name:" +
                anonymousSeams.joinToString("") { (path, line, sup) -> "\n    $path:$line  : ${sup.joinToString(", ")}" }
        }
        val ambiguous = scanned.filter { sites.getValue(it).size > 1 }
        if (ambiguous.isNotEmpty()) {
            failures += "Two `Seam` implementations share a simple name in ONE file, so `<path>#<Type>` " +
                "cannot identify either (#1871). Rename one:" +
                ambiguous.joinToString("") { "\n    $it  lines ${sites.getValue(it).joinToString(", ")}" }
        }

        // ── The registry ────────────────────────────────────────────────────────────────────────
        if (!registryFile.isFile) {
            error(
                "$registryFile is missing. It is the registry of every production `Seam` implementation " +
                    "and the conformance harness (or written opt-out) each one has — see #1871.",
            )
        }
        val lines = registryFile.readLines()
        val start = lines.indexOfFirst { it.trimEnd() == heading }
        if (start < 0) {
            error(
                "${registryFile.relativeTo(rootPath)} has no \"$heading\" section, so this guard cannot " +
                    "tell a registry row from an example in the prose and every verdict it could give " +
                    "would be meaningless (#1871). If the section was renamed, rename it here too.",
            )
        }
        val relativeEnd = lines.subList(start + 1, lines.size).indexOfFirst { it.startsWith("## ") }
        val end = if (relativeEnd < 0) lines.size else start + 1 + relativeEnd
        // Fenced blocks are illustration, not registry — the same false green `verifyModuleTable`
        // closes: a ```markdown block showing the row format would otherwise satisfy the guard.
        var fenced = false
        val rows = mutableListOf<List<String>>() // type, path, harnessCell, reason
        lines.subList(start, end).forEach { line ->
            when {
                line.trimStart().startsWith("```") -> fenced = !fenced
                fenced -> Unit
                else -> row.find(line)?.let { rows += it.groupValues.drop(1) }
            }
        }
        val listed = rows.associateBy { "${it[1]}#${it[0]}" }
        val duplicated = rows.groupBy { "${it[1]}#${it[0]}" }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicated.isNotEmpty()) {
            failures += "Duplicate registry row(s) — one seam, one row:\n    " + duplicated.joinToString("\n    ")
        }

        val missing = scanned.filterNot { it in listed }
        if (missing.isNotEmpty()) {
            failures += "Production `Seam` implementation(s) with NO row in " +
                "${registryFile.relativeTo(rootPath)} (#1871). Nothing else in this build would ever " +
                "report that they are untested — that silence is the defect this guard exists to end:" +
                missing.joinToString("") { key ->
                    "\n    ${key.substringBefore('#')}:${sites.getValue(key).first()}  " +
                        key.substringAfter('#')
                } +
                "\n  THE FIX is one row apiece under \"$heading\", naming EITHER the " +
                "`SeamConformanceSuite` subclass that drives it — via the `Loom` its `newLoomPair` " +
                "returns — OR `none` plus a written reason of at least $minReason characters saying " +
                "why no harness reaches it. A reason is mandatory: a bare allowlist re-hides the very " +
                "thing this list exists to show."
        }
        val dangling = listed.keys.filterNot { it in scanned }.sorted()
        if (dangling.isNotEmpty()) {
            failures += "Registry row(s) naming a seam this build does not have — a row describing " +
                "code that no longer exists is worse than no row, because a reader trusts it. Delete " +
                "it, or fix the path/name if it moved:\n    " + dangling.joinToString("\n    ")
        }

        rows.forEach { (type, path, cell, reason) ->
            val named = harnessCell.findAll(cell).map { it.groupValues[1] }.toList()
            // Required on EVERY row, not just an opt-out. On an opt-out it is the deliverable —
            // the untested set is only useful if each entry says why. On a mapped row it is the
            // only thing a reviewer can falsify: this guard cannot tell whether the named harness
            // really weaves that seam, so the written path is what makes the claim checkable at all.
            if (reason.length < minReason) {
                failures += "`$path#$type` has no usable reason (\"$reason\"). Write at least " +
                    "$minReason characters saying how the named harness reaches this seam — or, for " +
                    "`none`, why nothing does. A bare name on a list re-hides what the list is for."
            }
            when {
                cell.trim() == "none" -> Unit
                named.isEmpty() ->
                    failures += "`$path#$type` has an unreadable harness cell (\"$cell\"): write " +
                        "`` `HarnessClassName` `` (comma-separated if several) or the bare word `none`."
                else -> named.filterNot { it in harnesses }.forEach {
                    failures += "`$path#$type` names harness `$it`, which is not a " +
                        "`SeamConformanceSuite` subclass in this tree. Renamed, deleted, or a typo — " +
                        "either way the row now asserts coverage nothing provides."
                }
            }
        }
        // A harness cited by no row means the mapping has drifted: every named subclass drives SOME
        // seam through the suite, so one attributed nowhere is a row that was never updated. This is
        // the closest thing here to a coverage check, and it is still only about the NAMES.
        val uncited = (harnesses - rows.flatMap { harnessCell.findAll(it[2]).map { m -> m.groupValues[1] } }.toSet())
            .sorted()
        if (uncited.isNotEmpty()) {
            failures += "`SeamConformanceSuite` subclass(es) cited by no registry row. Each one drives " +
                "some seam through the suite, so a harness attributed to nothing means the mapping has " +
                "drifted — find the seam it weaves and put it in that seam's row:\n    " +
                uncited.joinToString("\n    ")
        }

        if (failures.isNotEmpty()) {
            error(
                failures.joinToString("\n\n") + "\n\nNOTE — what a GREEN here does and does not mean: it " +
                    "means every production `Seam` implementation is ENUMERATED, and that no row names " +
                    "a seam or harness that has gone away. It does NOT mean the harness each row names " +
                    "actually exercises that seam; that mapping is a human assertion and is not checked.",
            )
        }
        val optedOut = rows.count { it[2].trim() == "none" }
        val out = stamp.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "ok — ${scanned.size} production Seam implementation(s) enumerated; " +
                "${scanned.size - optedOut} mapped to a harness, $optedOut opted out with a reason; " +
                "${harnesses.size} named SeamConformanceSuite subclass(es)\n",
        )
    }
}

// Run the guards as part of `check` (hence `build`, hence CI) in every module.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("forbidUnboundedSwatchDelivery"))
        dependsOn(rootProject.tasks.named("forbidBoltRejoiningTheLattice"))
        dependsOn(rootProject.tasks.named("forbidUnlintedModule"))
        dependsOn(rootProject.tasks.named("forbidUnlintedAndroidMain"))
        dependsOn(rootProject.tasks.named("forbidNotNullAssertionInUnresolvedSource"))
        dependsOn(rootProject.tasks.named("forbidSourcelessKmpTarget"))
        dependsOn(rootProject.tasks.named("forbidPortProbeRebind"))
        dependsOn(rootProject.tasks.named("verifyDocCitations"))
        dependsOn(rootProject.tasks.named("verifySampleLinks"))
        dependsOn(rootProject.tasks.named("verifySamplesAreRun"))
        dependsOn(rootProject.tasks.named("verifyModuleTable"))
        dependsOn(rootProject.tasks.named("verifySeamHarnessCoverage"))
        dependsOn(rootProject.tasks.named("forbidRunCatchingCancellableUnderNonCancellable"))
        dependsOn(rootProject.tasks.named("forbidCancellationRethrowAroundBound"))
        dependsOn(rootProject.tasks.named("forbidBareRunCatching"))
        dependsOn(rootProject.tasks.named("forbidKotlinAssert"))
        dependsOn(rootProject.tasks.named("forbidProductionDispatcherInTests"))
        dependsOn(rootProject.tasks.named("forbidTightRunTestTimeout"))
        dependsOn(rootProject.tasks.named("forbidDemotedFieldTrail"))
    }
}
