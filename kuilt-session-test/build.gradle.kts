plugins { id("kuilt.kmp-library") }

kotlin {
    // `admissionRequest` builds an AdmissionRequest, whose constructor :kuilt-session gates behind
    // @SessionInternalApi because only the room builds one in production and `internal` cannot cross
    // the module boundary. Consumers opt in to this module's own @AdmissionTestApi instead.
    compilerOptions { optIn.add("us.tractat.kuilt.session.SessionInternalApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-session"))
            implementation(libs.kotlinx.coroutines.core)
            // FakeRoomFactory's process-wide room counter — a fake that handed two rooms one id
            // would reproduce #1594 inside the double used to test around it.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
