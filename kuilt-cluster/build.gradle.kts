plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-raft"))
            api(project(":kuilt-session"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
        }

        // jvmAndAndroidMain: ServerCluster uses KtorRoomHost which is JVM/Android-only.
        // Mirrors the pattern in :kuilt-websocket. Adding a manual intermediate disables
        // the plugin's hierarchy auto-wiring — no ios/macos/wasm intermediates are needed
        // here since cluster has no platform-specific deps on those targets.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":kuilt-websocket"))
                implementation(libs.kotlin.logging)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonTest.dependencies {
            implementation(project(":kuilt-raft-test"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

// ── Harness-discipline guard (mirrors :kuilt-raft) ─────────────────────────
// Consensus tests run real RaftNodes under a test dispatcher: a non-converging
// cluster keeps virtual time advancing forever. Two rules prevent unbounded hangs:
//   1. Every runTest(...) must carry an explicit timeout = .
//   2. Cluster-state awaits must use the sim's bounded helpers — not raw .first/.filter.
val verifyRaftHarnessDiscipline by tasks.registering {
    group = "verification"
    description = "Fail if cluster tests use unbounded runTest/awaits."
    val testDir = layout.projectDirectory.dir("src")
    inputs.dir(testDir)
    doLast {
        val violations = mutableListOf<String>()
        testDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("Test") }
            .forEach { file ->
                file.readLines().forEachIndexed { i, raw ->
                    val line = raw.substringBefore("//")
                    val loc = "${file.name}:${i + 1}"
                    val callsRunTest = Regex("""(^|[^A-Za-z])runTest\(""").containsMatchIn(line)
                    if (callsRunTest && !line.contains("timeout")) {
                        violations += "$loc: runTest(...) without an explicit `timeout =` — pass timeout=."
                    }
                    if (Regex("""\b(commitIndex|role)\.(first|filter)\b""").containsMatchIn(line)) {
                        violations += "$loc: raw commitIndex/role .first/.filter await — use sim bounded helpers."
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Raft harness discipline violated:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyRaftHarnessDiscipline) }
