import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kuilt.publish")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
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

apply(plugin = "io.gitlab.arturbosch.detekt")

configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
    allRules = false
}

// In KMP projects detekt generates per-sourceset tasks (detektMetadataCommonMain,
// detektJvmMain, …) but the lifecycle `detekt` task gets NO-SOURCE because it
// has no default JVM source dirs wired. Wire it to the two most useful targets:
// commonMain (all platforms, no compiler needed) and jvmMain (type resolution).
afterEvaluate {
    val detektLifecycle = tasks.findByName("detekt") ?: return@afterEvaluate
    listOf("detektMetadataCommonMain", "detektJvmMain").forEach { name ->
        tasks.findByName(name)?.let { detektLifecycle.dependsOn(it) }
    }
    // Enforce on every `check`/`build` so CI's `./gradlew build` gates on it.
    tasks.findByName("check")?.dependsOn(detektLifecycle)
    val detektBaselineLifecycle = tasks.findByName("detektBaseline") ?: return@afterEvaluate
    listOf("detektBaselineMetadataCommonMain", "detektBaselineJvmMain").forEach { name ->
        tasks.findByName(name)?.let { detektBaselineLifecycle.dependsOn(it) }
    }
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
