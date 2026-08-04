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
// outside every declared root rather than letting it be silently exempt from invalidation.
//
// One more thing the cache key depends on that is easy to move by accident: the ALLOWLISTS in
// `forbidPortProbeRebind` / `forbidUnboundedSwatchDelivery` are covered only because they are
// literals in this script, and so are folded into the task-action implementation hash. Editing one
// re-runs the guard. Moving an allowlist to `gradle.properties`, a resource file, or any other
// external source would silently drop it out of the key and reintroduce exactly the stale-green
// class these stamps were made safe against — if you externalise one, declare it as an input too.
fun kotlinSourcesIn(roots: List<java.io.File>, pattern: String = "**/*.kt"): FileTree =
    files(roots).asFileTree.matching { include(pattern) }

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
        "TcpConformanceTest.kt",
        "WebSocketConformanceTest.kt",
        "MDNSConformanceTest.kt",
        // mDNS: the port is an *input* to the advertisement built inside the embeddedServer module
        // lambda, so it must be known before start() — needs a restructure, not a one-line change.
        "MDNSLoomCapabilityTest.kt",
        "MDNSMultiAcceptHostTest.kt",
        "MDNSRoomKeySourcingTest.kt",
        "MDNSSelfDiscoveryFilterTest.kt",
        "MDNSSelfDiscoveryMulticastTest.kt",
        // TcpLoom sites: bind the Ktor ServerSocket to 0 and read localAddress instead.
        "TcpClusterExampleTest.kt",
        "TcpLoomCapabilityTest.kt",
        "TcpRoundTripTest.kt",
        "TcpMeshBuilder.kt",
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
            val srcDirs = main.allKotlinSourceSets.flatMap { it.kotlin.srcDirs }
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
// Every fenced code block in `docs/` and `Writerside/` that claims to be copied out of a
// compiled source carries an HTML citation comment naming that source. Dokka `@sample`
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
// `docs/superpowers/` is excluded. Those are frozen, dated planning artifacts whose
// citations are deliberately unresolved templates (`<!-- verbatim from <cited path>#… -->`).
val verifyDocCitations by tasks.registering {
    group = "verification"
    description = "Fails if a <!-- verbatim from … --> doc citation has drifted from, or dangles off, its source (#1792)."
    val docRoots = listOf(rootDir.resolve("docs"), rootDir.resolve("Writerside"))
        .filter(java.io.File::isDirectory)
    val srcRoots = subprojects.mapNotNull { it.projectDir.resolve("src").takeIf(java.io.File::isDirectory) }
    // Every root whose contents invalidate this task. A citation pointing outside these is
    // rejected at execution time (see the check in doLast) rather than being silently exempt
    // from re-running — so this list and the enforcement can never drift apart.
    val inputRoots = docRoots + srcRoots
    inputRoots.forEach { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE) }
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

        val mdFiles = docRoots.flatMap { root ->
            root.walkTopDown().filter { f ->
                f.isFile && f.extension == "md" &&
                    !f.relativeTo(rootPath).invariantSeparatorsPath.startsWith("docs/superpowers/")
            }
        }.sortedBy { it.invariantSeparatorsPath }

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
                // included build, not a subproject, so its sources are not an input.
                if (inputRoots.none { srcFile.startsWith(it) }) {
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

// Guard: forbid a bare duration literal in a `runTest(…)` timeout argument (#1739).
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
// KNOWN LIMITS, stated rather than papered over:
//   * The ratchet does not auto-tighten. Sweeping a file without deleting its entry leaves the
//     baseline loose for that path until someone notices. Failing on a DECREASE would fix it, and is
//     deliberately not done here: it would red-light every in-flight branch that merely deletes a
//     test, for a diff this PR is explicitly trying not to be a bad neighbour to.
//   * A wrapper harness's own default (`fun simTest(timeout: Duration = 5.seconds)`) is invisible to
//     a rule scoped to `runTest(` call sites — the wrapper passes `timeout = timeout`, which has no
//     literal. That is how `warpSimTest`'s 5 s default hid on a published `commonMain` harness while
//     applying to every call site. THIS BLIND SPOT IS NOT CLOSED, and the comment here previously
//     said "both in-tree wrappers now use named constants", which was a count and was wrong. There
//     are FOUR wrappers that feed `runTest`'s own timeout from a parameter default:
//       - `raftSimTest`      (`:kuilt-raft-test`, commonMain)  → `RAFT_SIM_WEDGE_BACKSTOP`   OK
//       - `warpSimTest`      (`:kuilt-warp-test`, commonMain)  → `WARP_SIM_WEDGE_BACKSTOP`   OK
//       - `raftRunTest`      (`:kuilt-raft`,      commonTest)  → `TEST_WEDGE_BACKSTOP`       OK (#1739 slice)
//       - `voterMeshSimTest` (`:kuilt-cluster`,   commonTest)  → bare `5.seconds`            LIVE
//     `voterMeshSimTest` (`kuilt-cluster/src/commonTest/.../VoterMeshSim.kt`) is a known live
//     instance: 4 call sites, none overriding, and its KDoc still argues for the defect
//     ("default 5 s — keep it tight"). Tracked separately; do not read this guard as green for it.
//     A FIFTH wrapper would be caught only by review — the scanner cannot see any of them.
//   * `:spike` (only present under `-PincludeSpike`) and `build-logic/` are not scanned, same as the
//     sibling guards.
val forbidTightRunTestTimeout by tasks.registering {
    group = "verification"
    description = "Fails if a file gains a runTest(timeout = <literal>) beyond its baseline — use a named backstop constant (#1739)."
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
        "demo/cli/src/test/kotlin/us/tractat/kuilt/demo/cli/PatchworkCliTest.kt" to 2,
        "demo/shared/src/commonTest/kotlin/us/tractat/kuilt/demo/PatchworkSessionSeamTearTest.kt" to 2,
        "demo/shared/src/commonTest/kotlin/us/tractat/kuilt/demo/PatchworkSessionTest.kt" to 4,
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
        "kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/PrincipalAttestationConformanceSuite.kt" to 6,
        "kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomFanoutIsolationConformanceSuite.kt" to 4,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/MuxServerLoomLifecycleTest.kt" to 7,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RoomHubSeamCloseTest.kt" to 2,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RoomHubSeamMembershipTest.kt" to 1,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RoomHubSeamSelfIdTest.kt" to 1,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/StarTopologyPeerRoutingTest.kt" to 2,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/TieredSeamTest.kt" to 12,
        "kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/AcceptPumpTest.kt" to 1,
        "kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/GossipSimulationTest.kt" to 1,
        "kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/GossipViewTest.kt" to 1,
        "kuilt-liveness/src/commonTest/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetectorTransportCloseTest.kt" to 2,
        "kuilt-liveness/src/commonTest/kotlin/us/tractat/kuilt/liveness/SoloDeadlineDetectorTest.kt" to 9,
        "kuilt-nearby/src/commonTest/kotlin/us/tractat/kuilt/nearby/NearbyIdentityTest.kt" to 1,
        "kuilt-nw/src/commonTest/kotlin/us/tractat/kuilt/nw/NwMeshRoomPartitionTest.kt" to 1,
        "kuilt-quilter/src/commonSamples/kotlin/us/tractat/kuilt/quilter/QuilterSamples.kt" to 6,
        "kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/BoundedCounterEqualizerTest.kt" to 5,
        "kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterCustomReplicaIdTest.kt" to 2,
        "kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterFullStateResyncTest.kt" to 1,
        "kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterFullStateRetryTest.kt" to 2,
        "kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterResendRetryTest.kt" to 3,
        "kuilt-quilter/src/jvmTest/kotlin/us/tractat/kuilt/quilter/QuilterConcurrencyTest.kt" to 3,
        "kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/InMemoryMeshBuilderTest.kt" to 11,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/AdoptTearTerminalTest.kt" to 3,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ConcurrentResumeHangTest.kt" to 2,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/FastReconnectRaceTest.kt" to 3,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/GracefulLeaveTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/HostAuthoritativeLeaveTest.kt" to 2,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/HostReconnectControllerInjectionTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinerHostTimeoutRecoveryTest.kt" to 2,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinerReconnectTest.kt" to 9,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/LivenessRouteGateTest.kt" to 3,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/LocalFabricTest.kt" to 13,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/MembershipEventDropContractTest.kt" to 2,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/MeshRoomRecoveryTest.kt" to 3,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/MultiRoomIsolationTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/MuxHubPrincipalTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomAttestedPrincipalsTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomResumeTimeoutTest.kt" to 2,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/SeamRoomCloseResurrectionTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/SeamRoomDetectorTeardownTest.kt" to 1,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarTopologyPresenceFanoutTest.kt" to 4,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/TransportCloseWindowTest.kt" to 5,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/WindowLevelTest.kt" to 10,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt" to 3,
        "kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt" to 3,
        "kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/FakeSeamTest.kt" to 5,
        "kuilt-warp-compiler/src/jvmTest/kotlin/us/tractat/kuilt/warp/RealVariantTieringTest.kt" to 1,
        "kuilt-warp-otel/src/commonTest/kotlin/us/tractat/kuilt/warp/otel/WarpMetricsTest.kt" to 3,
        "kuilt-warp-runtime/src/appleTest/kotlin/us/tractat/kuilt/warp/Wasm3RuntimeDispatchTest.kt" to 1,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/ChicoryRuntimeDispatchTest.kt" to 1,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/ChicoryWasmRuntimeTest.kt" to 1,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/ChicoryWasmRuntimeTimingTest.kt" to 1,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/LazyFetchAndRunTest.kt" to 4,
        "kuilt-warp-runtime/src/jvmTest/kotlin/us/tractat/kuilt/warp/SettleUntilRealIoTest.kt" to 1,
        "kuilt-warp-runtime/src/wasmJsTest/kotlin/us/tractat/kuilt/warp/BrowserWasmRuntimeDispatchTest.kt" to 1,
        "kuilt-warp-test/src/commonMain/kotlin/us/tractat/kuilt/warp/test/WasmRuntimeConformanceSuite.kt" to 10,
    )
    doLast {
        val found = sortedMapOf<String, List<Int>>()
        sources.files.forEach { file ->
            val raw = file.readText()
            if ("runTest" !in raw) return@forEach
            val hits = RunTestTimeoutScanner.violations(KotlinCodeScanner.stripNonCode(raw))
            if (hits.isNotEmpty()) found[file.relativeTo(rootPath).invariantSeparatorsPath] = hits
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
                "${found.size} file(s) at or below baseline (${found.values.sumOf { it.size }} sites)\n",
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

// Run the guards as part of `check` (hence `build`, hence CI) in every module.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("forbidUnboundedSwatchDelivery"))
        dependsOn(rootProject.tasks.named("forbidUnlintedModule"))
        dependsOn(rootProject.tasks.named("forbidSourcelessKmpTarget"))
        dependsOn(rootProject.tasks.named("forbidPortProbeRebind"))
        dependsOn(rootProject.tasks.named("verifyDocCitations"))
        dependsOn(rootProject.tasks.named("forbidRunCatchingCancellableUnderNonCancellable"))
        dependsOn(rootProject.tasks.named("forbidBareRunCatching"))
        dependsOn(rootProject.tasks.named("forbidTightRunTestTimeout"))
    }
}
