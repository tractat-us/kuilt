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

    // Test-source detekt tasks use an extended config that also bans importing
    // Dispatchers or GlobalScope. Note what that does and does not reach: only these
    // three tasks, and only the `import` form. Every other test source set — commonTest
    // above all, for which detekt generates no task at all (#1960) — and the
    // fully-qualified `kotlinx.coroutines.Dispatchers.Default` spelling are covered
    // instead by `forbidProductionDispatcherInTests` in the root build (#1934), whose
    // hatch is a line-tight `// ALLOW-realDispatcher: <reason>`. A deliberate
    // real-threading harness silences THIS rule with @file:Suppress("ForbiddenImport");
    // @Suppress("ForbiddenMethodCall") silences nothing anywhere (#1329).
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

    // #2334: `androidMain` PRODUCTION source. Until this task was wired in, it was analysed by
    // NOTHING — the two androidUnitTest tasks above cover Android TEST source, and the fold below
    // walks the dependsOn closure UP from the jvm/android leaves and then explicitly drops the
    // leaves themselves, so `androidMain`'s own srcDirs land in no type-resolved task. The result
    // was the exact inversion nobody would choose deliberately: Android test code linted, Android
    // production code — whole public entry points, everything that touches the Android SDK — not.
    // It is not hypothetical; a `BroadcastReceiver` registration defect shipped into the blind spot
    // in `kuilt-nearby/src/androidMain` and was caught by a human reviewer, detekt having analysed
    // none of it.
    //
    // The fix is a REAL Android variant task, not a fold into `detektJvmMain`. Folding would analyse
    // Android code against the JVM compile classpath, where `android.*` does not resolve — and since
    // all four rules in `config/detekt/detekt.yml` need type resolution, unresolved receivers mean
    // false negatives on precisely the Android-SDK-touching code this exists to cover.
    //
    // RELEASE, not debug, and not both. `androidMain` is a single source set compiled verbatim into
    // both variants — measured, both tasks analyse the identical file set and report the identical
    // findings — so wiring both would double Android compilation across every module in a job with
    // a documented OOM history (see the comment above `afterEvaluate`) to buy nothing. Release is
    // the principled half of the pair because it is the one that SHIPS: `kuilt.kmp-library` declares
    // `publishLibraryVariants("release")`, so release is the variant consumers resolve. The repo has
    // no `buildTypes`/`productFlavors` block and no build-type-scoped source dir or dependency
    // configuration, so nothing debug-only escapes today — and that premise is not left to prose:
    // `forbidUnlintedAndroidMain` in the root build asserts, empirically and per file, that every
    // Kotlin file under an Android production source root is in the source set of a task `detektAll`
    // actually schedules. Add `src/androidDebug` and it reds.
    val androidMainTaskName = "detektAndroidRelease"

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
    // #2471: "TYPE-RESOLVED" IS A NAME, NOT A GUARANTEE — the tier is currently PARTIAL, and
    // the part it is missing is most of the classpath. detekt 1.23.8 pins
    // `kotlin-compiler-embeddable:{strictly 2.0.21}` and refuses to start against any other
    // ("detekt was compiled with Kotlin 2.0.21 but is currently running with 2.4.10. This is
    // not supported."). A 2.0.21 frontend reads Kotlin binary metadata up to `mv=[1,9,0]`, and
    // every Kotlin binary on the analysis classpath is past that — measured with `javap -v`,
    // `[2,4,0]` for kotlin-stdlib-2.4.10, `kuilt-core-jvm.jar` and a module's own
    // `build/classes` output, `[2,2,0]` for kotlinx-coroutines-1.11.0. (Not one number: a
    // dependency's metadata version tracks the compiler that BUILT it, so the bound to check
    // is `> [1,9,0]`, not equality with the repo's own.) Metadata a frontend cannot
    // deserialize is not an error, it is SILENCE: the declaration simply does not exist for
    // resolution.
    //
    // So the tier resolves exactly three things — Kotlin BUILT-INS (`String`, `MutableMap`,
    // `Throwable` and their members, loaded from the compiler's own jar, not the classpath),
    // JAVA/JDK classes (no Kotlin metadata to read), and declarations in the SOURCE FILES
    // BEING ANALYSED. Every stdlib top-level function, every typealias, kotlinx-coroutines,
    // and every sibling kuilt module is invisible. A `!!` whose base expression's type comes
    // off the classpath resolves to an error type, `isNullable()` is false, and all four
    // rules skip it without a word. The receipt, in `kuilt-gossip/src/commonMain`:
    //
    //     val m: MutableMap<Long, String> = mutableMapOf()   // type DECLARED -> built-in
    //     m.remove(k)!!                                      // -> REPORTED
    //     val m = mutableMapOf<Long, String>()               // type INFERRED from stdlib
    //     m.remove(k)!!                                      // -> SILENT
    //
    // Two lines apart, same file, same task. There is no config that fixes this: detekt
    // 1.23.8 is the newest release on Maven Central and 2.x (K2 Analysis API) is unreleased.
    // Because of that, `forbidNotNullAssertionInUnresolvedSource` in the root build no longer
    // subtracts this tier — the lexical `!!` ban is the floor EVERYWHERE, and detekt is a
    // bonus on top of it rather than the mechanism. Re-read that guard's scope, and this
    // comment, the day detekt can run a frontend that matches `libs.versions.toml`'s Kotlin;
    // `forbidDetektFrontendSkew` in the root build reds when that day arrives.
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
    // reachable at all is the improvement. Real coverage here would need a rule set that
    // does not depend on type resolution; #2039 weighed that and did NOT choose it. What
    // shipped instead is `forbidNotNullAssertionInUnresolvedSource` in the root build — a
    // lexical `check`-wired ban on `!!` in every source set no type-resolved detekt task
    // covers, this tier and `:spike` (#1863) included. It buys the one rule that matters
    // most and nothing else, so everything above about what a green here means still
    // holds for the other three rules.
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
    val detektAllTaskNames =
        setOf("detektMetadataCommonMain", "detektJvmMain", androidMainTaskName) + testSourceSetTaskNames
    tasks.register("detektAll") {
        group = "verification"
        description = if (typeResolved) {
            "Runs detekt with type resolution on main sources (commonMain + jvmMain, incl. any jvmAndAndroidMain intermediate folded into the jvm task, plus androidMain via the release variant — #2334) and test sources (jvmTest, androidUnitTest). Does NOT reach commonTest (#1960) or any apple/native/wasm source set (#2039). Not wired into check — CI runs it as a separate job to avoid OOM."
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
