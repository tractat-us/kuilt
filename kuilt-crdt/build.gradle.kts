plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
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
            // whole crdt suite once any logger.* call fires (raft issue #222).
            runtimeOnly(libs.logback)
        }
    }
}

// Switch jvmTest to the JUnit Platform so jqwik properties are discovered.
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// koverVerify is NOT bound to the check lifecycle — coverage verification is
// opt-in via: ./gradlew koverVerify koverHtmlReport
// onCheck = false keeps the threshold rules available for explicit invocation
// without paying the kover instrumentation cost on every CI build.
kover {
    reports {
        total {
            verify {
                onCheck = false
                rule("Minimum 90% line coverage in commonMain") {
                    // Initial threshold: actual was 90.7% at landing. Raise via follow-up issues.
                    minBound(90)
                }
            }
        }
    }
}
