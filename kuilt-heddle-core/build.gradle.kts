import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // The module that declares @HeddleInternalApi is the one place it is not a warning sign.
    compilerOptions { optIn.add("us.tractat.kuilt.heddle.HeddleInternalApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.serialization.core)
            // The positive control for `verifyHeddleCoreDependencies` below: plant a forbidden
            // dependency and the check must go red. Run it as
            //   ./gradlew :kuilt-heddle-core:verifyHeddleCoreDependencies -Pheddle.core.plantForbiddenDependency=true
            if (providers.gradleProperty("heddle.core.plantForbiddenDependency").orNull == "true") {
                implementation(project(":kuilt-liveness"))
            }
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.serialization.json)
            // A snapshot's envelope is checked in a binary format too, not only in JSON.
            implementation(libs.kotlinx.serialization.cbor)
        }
    }
}

// ── The policy and the ledger never reach the network (#3167) ──────────────────────────────────
//
// What this module is FOR is being usable with nothing that moves bytes: no Seam, no Raft, no
// replication, no liveness, no coroutines. A dependency on any of those is a design regression,
// and it is invisible in review because it arrives transitively: add one innocent-looking project
// and the coroutines runtime comes with it.
//
// So the check reads the RESOLVED graph, not the build script: every main compilation of every
// target, compile and runtime classpath both, every component in it, direct or transitive. Test
// compilations are deliberately out of scope — the tests use `:kuilt-test` and `:kuilt-conformance`,
// which sit on `:kuilt-core`, and a consumer never sees a test classpath.
//
// Why not a stronger tier: a compiler has no notion of "this module may not depend on that one",
// and a source scan for imports misses exactly the transitive case. Why not weaker: review is what
// the issue named as insufficient.
//
// Positive control: `-Pheddle.core.plantForbiddenDependency=true` adds `:kuilt-liveness` to
// `commonMain`, which brings `:kuilt-core` and the coroutines runtime with it, and the check must
// red naming all three.
val forbiddenProjects = setOf(":kuilt-core", ":kuilt-raft", ":kuilt-quilter", ":kuilt-liveness")

// One entry per main classpath: every component it resolves, direct or transitive, as `project :x`
// or `group:module`. This map is the task's declared INPUT, not merely something it reads — a task
// with outputs and no inputs is UP-TO-DATE whenever its stamp exists, which is how the planted
// dependency first sailed through green. A changed graph now re-runs the check.
//
// Filled after evaluation: AGP creates the Android compilations late, and reading `kotlin.targets`
// eagerly would silently leave both Android classpaths out.
val resolvedGraph = objects.mapProperty<String, List<String>>()
afterEvaluate {
    kotlin.targets
        .flatMap { target -> target.compilations.filter { !it.name.contains("test", ignoreCase = true) } }
        .flatMap { c -> listOfNotNull(c.compileDependencyConfigurationName, c.runtimeDependencyConfigurationName) }
        .distinct()
        .mapNotNull { name -> configurations.findByName(name) }
        .forEach { conf ->
            resolvedGraph.put(
                conf.name,
                conf.incoming.resolutionResult.rootComponent.map { root ->
                    val seen = HashSet<ResolvedComponentResult>()
                    val queue = ArrayDeque(listOf(root))
                    val ids = sortedSetOf<String>()
                    while (queue.isNotEmpty()) {
                        val component = queue.removeFirst()
                        if (!seen.add(component)) continue
                        when (val id = component.id) {
                            is ProjectComponentIdentifier -> ids += "project ${id.projectPath}"
                            is ModuleComponentIdentifier -> ids += "${id.group}:${id.module}"
                        }
                        component.dependencies
                            .filterIsInstance<ResolvedDependencyResult>()
                            .forEach { queue.addLast(it.selected) }
                    }
                    ids.toList()
                },
            )
        }
}

val verifyHeddleCoreDependencies by tasks.registering {
    group = "verification"
    description = "Fails when :kuilt-heddle-core's main classpaths resolve Seam, Raft, replication, liveness or coroutines."
    val graph = resolvedGraph
    val forbidden = forbiddenProjects.map { "project $it" }.toSet()
    inputs.property("resolvedGraph", graph)
    val stamp = layout.buildDirectory.file("verification/verify-heddle-core-dependencies.ok")
    outputs.file(stamp)
    doLast {
        val byClasspath = graph.get()
        check(byClasspath.isNotEmpty()) { "found no main classpaths to inspect — the check would pass vacuously" }
        val offences = byClasspath.flatMap { (classpath, ids) ->
            ids.filter { it in forbidden || it.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines") }
                .map { "$classpath → $it" }
        }
        if (offences.isNotEmpty()) {
            throw GradleException(
                ":kuilt-heddle-core must not depend on ${forbidden.sorted()} or kotlinx-coroutines (#3167), " +
                    "but its main classpaths resolve:\n  " + offences.sorted().joinToString("\n  "),
            )
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "ok — ${byClasspath.size} main classpaths, none reaches a forbidden module:\n" +
                byClasspath.keys.sorted().joinToString("\n") { "  $it" } + "\n",
        )
    }
}
tasks.named("check") { dependsOn(verifyHeddleCoreDependencies) }

// ── The lease scale probe (#3173, plan Task C) is opt-in ────────────────────────────────────────
//
// `LeaseScaleProbe` builds 55,000 edges and times acquires on them: seconds on the JVM, minutes on a
// Kotlin/Native debug binary, and evidence for one decision rather than a regression gate. So it is
// excluded from every test task unless `-Pheddle.scale.probe=true`. The exclusion is at the TASK
// level, on `AbstractTestTask` so the native and wasm tasks are covered too, and an excluded probe
// is absent from the results XML rather than present and green (`forbidRuntimeSelfSkippingProbe`).
// With the flag on the exclusion is not added at all, so a `--tests "*LeaseScaleProbe"` include
// can match. `filter.excludeTestsMatching`, not `filter { … }`: see kuilt-multipeer's build for why
// the lambda form does not compile on this receiver.
val runScaleProbe = providers.gradleProperty("heddle.scale.probe").orNull == "true"
tasks.withType<AbstractTestTask>().configureEach {
    if (!runScaleProbe) {
        // Both spellings. The class pattern is what the JVM and Kotlin/Native runners match; the
        // wasmJs Karma runner matches it against `<class>.<method>` and so needs the second one —
        // measured: with the class pattern alone the probe still ran in `wasmJsBrowserTest`.
        filter.excludeTestsMatching("*LeaseScaleProbe")
        filter.excludeTestsMatching("*LeaseScaleProbe.*")
    }
}
