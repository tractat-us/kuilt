import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

// Detekt for Kotlin Multiplatform modules — the source-set folding and the `detektAll`
// entry point that `kuilt.kmp-library` used to carry inline. Extracted so a plain KMP
// module — one that deliberately does NOT apply `kuilt.kmp-library` (no explicitApi, no
// publishing, a reduced target set) — gets the same lint wiring from one line in its
// `plugins { }` block instead of being silently unlinted (#2016). `kuilt.kmp-library`
// applies it; so do `:demo-shared` and `:demo-web`.
//
// Apply it AFTER the Kotlin Multiplatform plugin. detekt registers its per-source-set
// tasks from a callback keyed on the Kotlin plugin, so applying this first would order
// this script's `afterEvaluate` ahead of detekt's and every `findByName` below would miss.
// In a `plugins { }` block that means declaring it last.
//
// The companion for a plain Kotlin/JVM module is `kuilt.detekt-jvm`. `forbidUnlintedModule`
// in the root build fails the build on a module that has Kotlin source and neither.

apply(plugin = "io.gitlab.arturbosch.detekt")

configure<DetektExtension> {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
    allRules = false
}

// In KMP projects detekt generates per-sourceset tasks (detektMetadataCommonMain,
// detektJvmMain, …); the plain `detekt` lifecycle task is NO-SOURCE (no default
// JVM source dirs). The detekt plugin wires `check -> detekt`, so we must NOT
// hang the heavy type-resolution sourceset tasks off `detekt` — that would drag
// them into `./gradlew build`, where running them concurrently with the wasmJs-
// browser + test tasks OOMs the CI runner (same constraint behind --max-workers=4
// in ci.yml). Instead expose them via a dedicated `detektAll` task that CI runs
// as its own parallel job, isolated from the build's memory footprint.
afterEvaluate {
    val kmpExtension = extensions.getByType(KotlinMultiplatformExtension::class.java)

    // Test-source detekt tasks use an extended config that also bans production
    // dispatchers (Dispatchers.Default/IO/Main/Unconfined) and GlobalScope.
    // Deliberate real-threading sites suppress with @Suppress("ForbiddenMethodCall").
    val testDetektConfig = rootProject.files(
        "config/detekt/detekt.yml",
        "config/detekt/detekt-test.yml",
    )
    val testSourceSetTaskNames = listOf(
        "detektJvmTest",
        "detektAndroidDebugUnitTest",
        "detektAndroidReleaseUnitTest",
    )
    testSourceSetTaskNames.mapNotNull { tasks.findByName(it) }.forEach { task ->
        (task as Detekt).config.setFrom(testDetektConfig)
    }

    // #1021: JVM/Android-only modules keep their production code in a manual
    // intermediate source set (e.g. `jvmAndAndroidMain`, created to disable KMP
    // hierarchy auto-wiring). detekt only generates a *metadata* task for such an
    // intermediate, and metadata tasks run WITHOUT type resolution — so the
    // nullability rules this repo enables (UnsafeCallOnNullableType, … — all of which
    // REQUIRE type resolution) silently never fire on it. Meanwhile the type-resolved
    // `detektJvmMain` is NO-SOURCE, because the leaf jvm source set is empty (the code
    // lives in the intermediate). Net effect: the intermediate's production code is
    // unlinted, yet detektAll reports "0 smells".
    //
    // Fix: fold each intermediate's source dirs into the type-resolved `detektJvmMain`
    // task. That task already carries the jvm compile classpath — full type resolution,
    // and the intermediate's compileOnly deps are on it since `jvmMain` dependsOn the
    // intermediate — and is already a detektAll dependency, so the intermediate is now
    // analyzed with exactly the same rules as any other jvm source. Discover the
    // intermediate(s) generically by walking the dependsOn closure UP from the
    // jvm/android leaves: that reaches only JVM/Android-path ancestors, never the
    // apple/ios/native intermediates (which are ancestors of the native leaves), so
    // standard all-target modules — whose jvm/android closure is just commonMain — are
    // untouched and no module names are hard-coded.
    fun KotlinSourceSet.dependsOnClosure(): Set<KotlinSourceSet> =
        dependsOn + dependsOn.flatMap { it.dependsOnClosure() }
    val jvmAndroidIntermediates = kmpExtension.sourceSets
        .matching { it.name == "jvmMain" || it.name == "androidMain" }
        .flatMap { it.dependsOnClosure() }
        .filterNot { it.name == "commonMain" || it.name == "jvmMain" || it.name == "androidMain" }
        .toSet()
    // Also fold commonMain into the type-resolved detektJvmMain. detekt's own
    // detektMetadataCommonMain analyses commonMain WITHOUT type resolution, so this
    // repo's rules — all four (UnsafeCallOnNullableType, …) require type resolution —
    // never fire on commonMain-only code, and (until wired below) detektAll skipped
    // that task entirely. detektJvmMain carries the jvm compile classpath and its
    // dependsOn-closure already pulls in commonMain via jvmMain, so this analyses
    // commonMain with exactly the rules applied to jvm code. (#1416)
    val commonMainSourceSets = kmpExtension.sourceSets.matching { it.name == "commonMain" }
    val jvmMainDetekt = tasks.findByName("detektJvmMain") as? Detekt
    jvmMainDetekt?.let { jvmDetekt ->
        jvmAndroidIntermediates.forEach { intermediate -> jvmDetekt.source(intermediate.kotlin.srcDirs) }
        commonMainSourceSets.forEach { commonMain -> jvmDetekt.source(commonMain.kotlin.srcDirs) }
    }

    // Two coverage tiers, because detekt's type resolution is JVM-only.
    //
    // TYPE-RESOLVED (the normal case, any module with a jvm target): detektAll runs the
    // tasks that carry a JVM compile classpath — detektJvmMain (with commonMain and any
    // jvmAndAndroid intermediate folded in, above) plus the jvm/android test tasks. All
    // four rules in config/detekt/detekt.yml require type resolution, so this is the only
    // tier where they can fire, and it is why detektAll deliberately ignores the
    // per-target native/wasm tasks: they would cost CI time and find nothing.
    //
    // PARSE-ONLY (a module with NO jvm target, e.g. `:demo-web` — wasmJs only): there is
    // no type-resolved task to fold anything into. Falling through with the name set
    // above would leave detektAll depending on NOTHING, which is precisely the
    // "wired but unreachable" false green #2005 exists to end — so instead depend on
    // every detekt analysis task the module has. Be honest about what that buys: detekt
    // parses and reports on those sources, but with no type resolution the four
    // nullability rules cannot fire (verified — an injected `!!` in
    // `demo/web/src/wasmJsMain` leaves `detektWasmJsMain` at "0 code smells"). It is the
    // same tier every module's own wasm/native source sets sit in today; making it
    // reachable at all is the improvement. Real coverage here needs a rule set that does
    // not depend on type resolution — that gap, shared with `:spike` (#1863), is #2039.
    // The discriminator is the existence of `detektJvmMain` itself, not a target-type
    // predicate: it is exactly the task the folding above needs and exactly the task the
    // name set below names, so the two cannot drift apart.
    val typeResolved = jvmMainDetekt != null
    if (!typeResolved) {
        tasks.withType(Detekt::class.java)
            .matching { it.name.endsWith("Test") }
            .configureEach { config.setFrom(testDetektConfig) }
    }

    // Wire the dependency by a live, name-matched task collection rather than an
    // eager findByName snapshot. The KMP detekt plugin registers the commonMain
    // *metadata* task (detektMetadataCommonMain) in a LATER afterEvaluate than this
    // block, so an eager findByName here misses it — leaving commonMain silently
    // unlinted by detektAll while detektJvmMain (registered earlier) is found. A
    // `tasks.matching { }` collection is resolved at task-graph time, after every
    // detekt task exists, and is robust to new source sets/modules.
    val detektAllTaskNames = setOf("detektMetadataCommonMain", "detektJvmMain") + testSourceSetTaskNames
    tasks.register("detektAll") {
        group = "verification"
        description = if (typeResolved) {
            "Runs detekt on main sources (commonMain + jvmMain, incl. any jvmAndAndroidMain intermediate folded into the jvm task) and test sources (jvmTest, androidUnitTest) with type resolution. Not wired into check — CI runs it as a separate job to avoid OOM."
        } else {
            "Runs detekt on every source set of this jvm-less module WITHOUT type resolution (detekt resolves types only against a JVM classpath). Not wired into check — CI runs it as a separate job to avoid OOM."
        }
        dependsOn(
            if (typeResolved) {
                tasks.matching { it.name in detektAllTaskNames }
            } else {
                tasks.matching { it is Detekt && it.name != "detekt" }
            },
        )
    }
    val detektBaselineLifecycle = tasks.findByName("detektBaseline") ?: return@afterEvaluate
    listOf("detektBaselineMetadataCommonMain", "detektBaselineJvmMain").forEach { name ->
        tasks.findByName(name)?.let { detektBaselineLifecycle.dependsOn(it) }
    }
}
