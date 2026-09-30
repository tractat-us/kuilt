// Agent-workspace M1 shared-state semantics probe (#2869).
//
// Deliberately a PLAIN KMP module, not `kuilt.kmp-library`: no explicitApi, no
// publishing, JVM only. It is a characterization probe — two dot-carrying
// "dinner" models run against the same merge questions — kept apart from
// :demo-workspace so the two tracks share no files. Listed in kuilt-bom's
// `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-crdt"))
            implementation(libs.kotlinx.serialization.core)
            // encodedSize measures a delta with the codec Quilter defaults to (plain Cbor).
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test")) // assertAll
            implementation(libs.kotlin.test)
        }
    }
}
