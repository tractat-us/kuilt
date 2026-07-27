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
    val srcDirs = subprojects.mapNotNull { it.projectDir.resolve("src").takeIf(java.io.File::exists) }
    srcDirs.forEach { inputs.dir(it) }
    val rootPath = rootDir
    val allowlist = setOf("FaultySeam.kt")
    doLast {
        val ctor = Regex("""Channel<Swatch>\s*\(""")
        val offenders = srcDirs.asSequence().flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
        }.flatMap { file ->
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
    val srcDirs = subprojects.mapNotNull { it.projectDir.resolve("src").takeIf(java.io.File::exists) }
    srcDirs.forEach { inputs.dir(it) }
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
        val offenders = srcDirs.asSequence().flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
        }.flatMap { file ->
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
// so we snapshot a `List<Pair<targetLabel, List<srcDir>>>` here at configuration time and do
// the file-existence walk in `doLast`.
val srclessTargetProbes = mutableListOf<Pair<String, List<java.io.File>>>()
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
            srclessTargetProbes += label to srcDirs
        }
    }
    tasks.named("forbidSourcelessKmpTarget") {
        srclessTargetProbes.forEach { (_, dirs) ->
            dirs.filter(java.io.File::exists).forEach { inputs.dir(it) }
        }
    }
}

val forbidSourcelessKmpTarget by tasks.registering {
    group = "verification"
    description = "Fails if any subproject declares a KMP target whose main compilation has no Kotlin source (see #1014)."
    val probes = srclessTargetProbes
    doLast {
        val offenders = probes.filter { (_, dirs) ->
            dirs.none { dir -> dir.walkTopDown().any { it.isFile && it.extension == "kt" } }
        }.map { (label, _) ->
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
    }
}

// Run the guards as part of `check` (hence `build`, hence CI) in every module.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("forbidUnboundedSwatchDelivery"))
        dependsOn(rootProject.tasks.named("forbidSourcelessKmpTarget"))
        dependsOn(rootProject.tasks.named("forbidPortProbeRebind"))
    }
}
