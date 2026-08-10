plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The op-log contract (OpLogCrdt / LogOp / Dot) an archive is fed through.
            api(project(":kuilt-crdt"))
            // Frame bytes are built and parsed with kotlinx-io Buffers, so the same codec
            // serves an in-memory segment and (Task 3/#2214) a memory-mapped one.
            implementation(libs.kotlinx.io.core)
            // Ops are encoded with the CANONICAL op serializer — the one with golden vectors
            // behind it — in CBOR, matching the wire format Quilter already uses.
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.coroutines.core)
            // Explicit mutual exclusion for the append path; confinement-as-a-lock is banned.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
