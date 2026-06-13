plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // jqwik property-based / stateful testing (JVM-only; JUnit Platform)
            implementation(libs.jqwik)
            runtimeOnly(libs.junit.vintage.engine)
            runtimeOnly(libs.junit.platform.launcher)
            // SLF4J backend for kotlin-logging on JVM
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            // SLF4J backend for kotlin-logging on the Android unit-test variants
            // (testDebugUnitTest / testReleaseUnitTest). Without it, kotlin-logging's
            // Slf4jLoggerFactory init throws NoClassDefFoundError and contaminates the
            // whole raft suite once any logger.* call fires (issue #222).
            runtimeOnly(libs.logback)
        }
    }
}

// Switch jvmTest to the JUnit Platform so jqwik properties are discovered.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// ── Harness-discipline guard (issue #192) ──────────────────────────────────
// Consensus tests run a real RaftNode under UnconfinedTestDispatcher: a non-converging
// cluster keeps virtual time advancing (heartbeat loop), so runTest never auto-idles and a
// hang is only caught by the (long) default timeout — surfacing as an opaque, state-less
// failure. Two rules make that architecturally impossible:
//
//   1. Every runTest(...) in a test source must carry an explicit `timeout =`, OR go through
//      the raftRunTest { } wrapper (UnconfinedTestDispatcher + tight 5s default).
//   2. Cluster-state awaits must use RaftSimulation's bounded, dump-on-timeout helpers —
//      raw commitIndex.first / commitIndex.filter / role.first in a test body can hang.
//
// Wired into `check`, so the existing `./gradlew build` (and thus the ci-required gate) runs it.
val verifyRaftHarnessDiscipline by tasks.registering {
    group = "verification"
    description = "Fail if raft tests use unbounded runTest/awaits (issue #192)."
    val testDir = layout.projectDirectory.dir("src")
    inputs.dir(testDir)
    doLast {
        val violations = mutableListOf<String>()
        val sanctionedHelpers = setOf("RaftSimulation.kt", "RaftTestFixtures.kt")
        testDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("Test") }
            .forEach { file ->
                file.readLines().forEachIndexed { i, raw ->
                    val line = raw.substringBefore("//")
                    val loc = "${file.name}:${i + 1}"
                    // Rule 1: bare runTest( without an explicit timeout, not the raftRunTest wrapper.
                    val callsRunTest = Regex("""(^|[^A-Za-z])runTest\(""").containsMatchIn(line)
                    if (callsRunTest && !line.contains("raftRunTest") && !line.contains("timeout")) {
                        violations += "$loc: runTest(...) without an explicit `timeout =` — " +
                            "use raftRunTest { } or pass timeout=."
                    }
                    // Rule 2: unbounded cluster-state awaits outside the sanctioned helpers.
                    if (file.name !in sanctionedHelpers) {
                        if (Regex("""\b(commitIndex|role)\.(first|filter)\b""").containsMatchIn(line)) {
                            violations += "$loc: raw commitIndex/role .first/.filter await — " +
                                "use sim.awaitCommit / awaitLeader / awaitRole (bounded + dump-on-timeout)."
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Raft harness discipline (issue #192) violated:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyRaftHarnessDiscipline) }
