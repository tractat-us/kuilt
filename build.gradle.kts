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
// remaining close is skipped. Note that `Seam.close` carries no "must not report failure as cancellation"
// obligation (unlike `sendTo`/`broadcast`/`Loom.weave`, see `Seam.kt:106-126`), so a consumer minting one
// there is not even a contract violation.
//
// The correct form inside the shield is a plain `try` / `catch (Throwable)` with a debug log, PER cleanup
// item so one failure cannot skip the rest. `NwLoom.discardUnreturnedSeam` is the in-tree pattern.
// `NonCancellable.isActive` is always true, so an `ensureActive()` inside the shield is dead code — note
// `CompositeSeam.discardOrphanedPly`/`detachPly` do carry one, deliberately, "for symmetry" and
// self-documented as unable to fire; this guard neither requires nor forbids it.
//
// Detection is LEXICAL: a comment/string-literal-aware scanner strips `//`, `/* */` (nesting), `"…"`,
// `"""…"""` and `'…'` — re-entering code mode inside a `$`-template hole so a literal nested in one cannot
// leak a brace — then brace-depth-walks each `withContext(NonCancellable) {` block and flags any
// `runCatchingCancellable` inside it. Three known limits:
//   - the shield's ARGUMENT LIST must be on one line (`[^;{}\n]*`); the `{` itself may wrap to the next
//     line, since `\)\s*\{` crosses newlines. A multi-line argument list is not scanned — a miss, not a
//     false alarm;
//   - a `runCatchingCancellable` reached through a HELPER called from inside the shield is invisible
//     (e.g. `CompositeSeam.raisePlyFailure`), because the call site, not the callee, is what is scanned;
//   - the template-hole handling above is what keeps the walk sound. Before it, `"A${f(x = "{")}B"` inside
//     a shield emitted a stray `{` and flagged correct code OUTSIDE the shield — a false POSITIVE, which
//     on a `check`-wired guard blocks a correct PR. The dual (`"}"`) hid a real violation. Both are fixed
//     and fixture-verified; if you touch the scanner, re-verify BOTH directions, because only one of them
//     is loud.
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
        // Replace every comment, string and char literal with equivalent blank space, preserving newlines
        // (hence line numbers) so the brace walk below cannot be fooled by a `{` in prose or in a literal —
        // the hazard #1799's citation checker hit. Block comments nest, as they do in Kotlin.
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
        // `[^;{}\n]*` bounds the argument list to one line and cannot swallow the block's own brace.
        val anchor = Regex("""withContext\s*\(\s*[^;{}\n]*\bNonCancellable\b[^;{}\n]*\)\s*\{""")
        val call = Regex("""\brunCatchingCancellable\b""")
        val offenders = sources.files.sortedBy { it.invariantSeparatorsPath }.asSequence().flatMap { file ->
            val raw = file.readText()
            val code = stripNonCode(raw)
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

// Run the guards as part of `check` (hence `build`, hence CI) in every module.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("forbidUnboundedSwatchDelivery"))
        dependsOn(rootProject.tasks.named("forbidSourcelessKmpTarget"))
        dependsOn(rootProject.tasks.named("forbidPortProbeRebind"))
        dependsOn(rootProject.tasks.named("verifyDocCitations"))
        dependsOn(rootProject.tasks.named("forbidRunCatchingCancellableUnderNonCancellable"))
    }
}
