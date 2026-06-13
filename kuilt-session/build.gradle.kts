plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // SLF4J backend for kotlin-logging on the JVM + Android unit-test variants.
        // SeamReplicator (consumed here via RoomReplicator / Room.channel) initialises a
        // file-level kotlin-logging logger; without a backend, SeamReplicatorKt class-init
        // throws NoClassDefFoundError: org/slf4j/LoggerFactory and poisons every test that
        // merely constructs a replicator. Mirrors :kuilt-crdt (raft issue #222).
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
