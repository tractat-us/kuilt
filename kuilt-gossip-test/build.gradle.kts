plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-gossip"))
            api(project(":kuilt-test"))              // InMemoryConnectionSource/connectionPair are its public API surface
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // StarHarness drives the gossip star (GossipSeam/HostedOverlay), whose file-level
        // KotlinLogging loggers bind to SLF4J on JVM — supply a backend so class-init doesn't
        // NoClassDefFoundError on org.slf4j.LoggerFactory. Mirrors kuilt-gossip/kuilt-quilter.
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
