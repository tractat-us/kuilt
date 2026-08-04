plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API exposes Loom/PeerId — expose the contract transitively
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.atomicfu)
        }
        // Real Google Nearby Connections binding lives Android-only; the pure
        // adapter logic in commonMain stays GMS-free and JVM-testable.
        androidMain.dependencies {
            implementation(libs.play.services.nearby)
            implementation(libs.kotlinx.coroutines.playServices)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(project(":kuilt-test")) // TEST_WEDGE_BACKSTOP
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.logback)
        }
        // kotlin-logging needs an SLF4J backend on the Android unit-test variant too. ChunkCodec
        // now logs every refused/evicted chunk, so the first logger USE is on the reassembly happy
        // path rather than an error-only path; without a backend that throws NoClassDefFoundError:
        // org/slf4j/LoggerFactory out of `feed` and poisons every ChunkCodec and NearbySeam test.
        // Mirrors :kuilt-nw and :kuilt-session, which hit the same thing for the same reason.
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
