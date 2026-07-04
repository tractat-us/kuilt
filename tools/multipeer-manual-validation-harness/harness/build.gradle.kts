plugins {
    kotlin("multiplatform") version "2.3.21"
}

kotlin {
    jvm()
    iosArm64 {
        binaries.framework { baseName = "Harness" }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("us.tractat.kuilt:kuilt-core:0.7.0-dev")
                implementation("us.tractat.kuilt:kuilt-crdt:0.7.0-dev")
                implementation("us.tractat.kuilt:kuilt-quilter:0.7.0-dev")
                implementation("us.tractat.kuilt:kuilt-otel:0.7.0-dev")
                implementation("us.tractat.kuilt:kuilt-otel-tap:0.7.0-dev")
                implementation("us.tractat.kuilt:kuilt-multipeer:0.7.0-dev")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("net.java.dev.jna:jna:5.18.1")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
            }
        }
        val iosArm64Main by getting { }
    }
}

tasks.register<JavaExec>("runMac") {
    group = "application"
    mainClass.set("harness.MacMainKt")
    classpath = files(
        kotlin.jvm().compilations.getByName("main").output.allOutputs,
        kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles,
    )
    args = (project.findProperty("harnessArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
