import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma

plugins {
    id("kuilt.publish")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
}

val libs = the<LibrariesForLibs>()

kotlin {
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonTest.dependencies { implementation(libs.kotlin.test) }
        // Compile any src/commonSamples/kotlin sources as part of commonTest.
        // This means @sample functions referenced in KDoc are compiled and
        // verified on every test run — they cannot silently rot.
        val samplesDir = project.file("src/commonSamples/kotlin")
        if (samplesDir.exists()) {
            commonTest { kotlin.srcDir(samplesDir) }
        }
    }
}

android {
    namespace = "us.tractat.kuilt." + project.name.replace('-', '.')
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

// Kover (applied above) measures the JVM variant of each module — KMP common
// code plus JVM-specific code. Android's generated BuildConfig carries no
// meaningful logic, so it's filtered out of the report.
kover {
    reports {
        filters {
            excludes { classes("*.BuildConfig") }
        }
    }
}

// Dokka per-module source set wiring.
//
// Dokka's KotlinAdapter auto-discovers KMP source sets by looking up
// KotlinBasePlugin via Class.forName in the *project* classloader. When KMP is
// applied through a precompiled convention plugin (build-logic), the Kotlin
// plugin classes live in the build-logic classloader — the project classloader
// cannot find them, so KotlinAdapter silently produces sourceSets=[]. This is a
// known limitation of Gradle precompiled script plugins + Dokka 2.x.
//
// Workaround: capture the KMP extension reference inside the convention plugin
// body (where `kotlin` is the typed accessor from the same classloader) and
// register Dokka source sets in afterEvaluate once all source sets exist.
val kmpExtension = kotlin
afterEvaluate {
    configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        val samplesDir = project.file("src/commonSamples/kotlin")
        val moduleMd = project.file("module.md")
        kmpExtension.sourceSets
            .filter { it.name.endsWith("Main") }
            .forEach { kmpSs ->
                // Use maybeCreate so this is idempotent: when the root build script
                // declares kotlin plugins with `apply false`, Dokka's KotlinAdapter
                // can now find KotlinBasePlugin in the shared classloader and
                // auto-registers the source sets — a second register() call would
                // throw "already exists". Named().configure() handles both cases.
                val existing = dokkaSourceSets.findByName(kmpSs.name)
                val sourceSetProvider = if (existing != null) {
                    dokkaSourceSets.named(kmpSs.name)
                } else {
                    dokkaSourceSets.register(kmpSs.name)
                }
                sourceSetProvider.configure {
                    sourceRoots.from(kmpSs.kotlin.srcDirs)
                    if (samplesDir.exists()) samples.from(samplesDir)
                    if (moduleMd.exists()) includes.from(moduleMd)
                    suppress.set(!kmpSs.name.startsWith("common"))
                }
            }
    }
}

apply(plugin = "io.gitlab.arturbosch.detekt")

configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
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
        (task as io.gitlab.arturbosch.detekt.Detekt).config.setFrom(testDetektConfig)
    }

    val mainSourceSetTasks = listOf("detektMetadataCommonMain", "detektJvmMain")
        .mapNotNull { tasks.findByName(it) }
    val testSourceSetTasks = testSourceSetTaskNames
        .mapNotNull { tasks.findByName(it) }
    tasks.register("detektAll") {
        group = "verification"
        description = "Runs detekt on main sources (commonMain + jvmMain) and test sources (jvmTest, androidUnitTest) with type resolution. Not wired into check — CI runs it as a separate job to avoid OOM."
        dependsOn(mainSourceSetTasks + testSourceSetTasks)
    }
    val detektBaselineLifecycle = tasks.findByName("detektBaseline") ?: return@afterEvaluate
    listOf("detektBaselineMetadataCommonMain", "detektBaselineJvmMain").forEach { name ->
        tasks.findByName(name)?.let { detektBaselineLifecycle.dependsOn(it) }
    }
}

// Generate the shared wasmJs Mocha/Karma timeout configuration into the build
// directory so that every module gets an adequate per-test and socket budget by
// default. The Gradle `useMocha { timeout }` DSL does NOT reach the wasmJs
// *browser* task (it configures the node task instead) — hence the karma.config.d
// approach. Rather than a per-module source file (which caused three identical
// copies to accumulate), the convention plugin materialises one canonical copy in
// each module's build directory and redirects KotlinKarma.configDirectory there.
//
// Why afterEvaluate: KGP calls `test.useKarma { … }` (which sets testFramework)
// inside its own `project.whenEvaluated` block, registered when `browser()` is
// called above. Gradle processes afterEvaluate/whenEvaluated in FIFO order, so KGP's
// callback fires before this one — testFramework is already a KotlinKarma instance
// by the time we reach this block.
val generateKarmaTimeouts = tasks.register<GenerateKarmaTimeouts>("generateKarmaTimeouts") {
    group = "build setup"
    description = "Writes the shared wasmJs Mocha/Karma timeout config into build/karma-config.d/."
    outputFile.set(layout.buildDirectory.file("karma-config.d/timeouts.js"))
}

afterEvaluate {
    val wasmBrowserTest = tasks.findByName("wasmJsBrowserTest") as? KotlinJsTest ?: return@afterEvaluate
    val karma = wasmBrowserTest.testFramework as? KotlinKarma ?: return@afterEvaluate
    val configDir = layout.buildDirectory.dir("karma-config.d").get().asFile
    karma.useConfigDirectory(configDir)
    wasmBrowserTest.dependsOn(generateKarmaTimeouts)
}

// Serialize wasmJsBrowserTest across the whole build. `registerIfAbsent` makes
// the shared service idempotent across modules — every module registers, but
// Gradle keeps one instance, so its `maxParallelUsages = 1` becomes a build-wide
// mutex. Required because `workers.max=16` lets too many Karma+Chrome instances
// race their startup otherwise (see BrowserTestSerializer.kt).
val browserTestSerializer =
    gradle.sharedServices.registerIfAbsent(
        "browserTestSerializer",
        BrowserTestSerializer::class.java,
    ) { maxParallelUsages.set(1) }

tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    usesService(browserTestSerializer)
}
