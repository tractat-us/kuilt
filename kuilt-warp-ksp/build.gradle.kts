// :kuilt-warp-ksp — the @WarpOp symbol processor (build-time only, pure JVM).
//
// Published (kuilt.publish) so consumers can wire it into their own KSP setup:
//   dependencies { add("kspCommonMainMetadata", "us.tractat.kuilt:kuilt-warp-ksp:<v>") }
// In-repo modules apply the `kuilt.warp-ops` convention plugin instead, which does
// that wiring (plus the generated-source plumbing) in one line.
//
// Deliberately NOT a KMP module: a KSP processor always executes on the JVM inside
// the build, regardless of which Kotlin target it is generating code for. The
// generated code is plain commonMain Kotlin, so one metadata-compilation run covers
// every target.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    id("kuilt.publish")
    alias(libs.plugins.detekt)
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// kuilt.publish only auto-configures KMP publications; declare the JVM shape
// explicitly (same pattern as :kuilt-bom's JavaPlatform()).
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

dependencies {
    implementation(libs.ksp.api)
    testImplementation(libs.kotlin.test)
}

detekt {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
    allRules = false
}

// Match the KMP modules' `detektAll` entry point so `./gradlew detektAll` (CI's
// lint job) covers this module too. `detektMain`/`detektTest` are the
// type-resolution variants, mirroring what the kmp-library convention wires.
afterEvaluate {
    tasks.register("detektAll") {
        group = "verification"
        description = "Runs detekt with type resolution on main and test sources."
        dependsOn(listOfNotNull(tasks.findByName("detektMain"), tasks.findByName("detektTest")))
    }
}
