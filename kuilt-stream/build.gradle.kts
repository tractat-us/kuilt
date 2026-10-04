plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            // Parses the embedded TCP wire golden vectors (TcpWireV1Vectors.kt).
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// TcpWireVectorsFixtureTest compares the checked-in wire fixture with its embedded copy. Declaring the
// file as an input means an edit to the JSON alone re-runs jvmTest instead of serving a cached pass.
tasks.named<Test>("jvmTest") {
    inputs.file(layout.projectDirectory.file("wire/tcp-wire-v1.vectors.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("tcpWireVectors")
}
