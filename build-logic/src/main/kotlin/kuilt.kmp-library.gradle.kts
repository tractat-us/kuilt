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
// detektJvmMain, …); the plain `detekt` lifecycle task is NO-SOURCE (no default
// JVM source dirs). The detekt plugin wires `check -> detekt`, so we must NOT
// hang the heavy type-resolution sourceset tasks off `detekt` — that would drag
// them into `./gradlew build`, where running them concurrently with the wasmJs-
// browser + test tasks OOMs the CI runner (same constraint behind --max-workers=4
// in ci.yml). Instead expose them via a dedicated `detektAll` task that CI runs
// as its own parallel job, isolated from the build's memory footprint.
afterEvaluate {
    val perSourceSet = listOf("detektMetadataCommonMain", "detektJvmMain")
        .mapNotNull { tasks.findByName(it) }
    tasks.register("detektAll") {
        group = "verification"
        description = "Runs detekt (commonMain + jvmMain, with type resolution). Not wired into check — CI runs it as a separate job to avoid OOM."
        dependsOn(perSourceSet)
    }
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
