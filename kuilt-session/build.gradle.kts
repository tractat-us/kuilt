plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract, not an
// enumeration — the same convention as `:kuilt-core` and `:kuilt-cluster`). They run on real threads
// rather than virtual time, so a box saturated by sibling test JVMs can delay them past any budget
// and red them for a reason unrelated to the code under test (#1135 / #1158). So they are EXCLUDED
// from the normal run and only execute under -Pconcurrency.stress.tests=true, in the
// `concurrency-probes` CI job, on a runner with no co-scheduled test JVMs.
//
// The cost, stated rather than discovered later: that job is deliberately NON-BLOCKING and is not
// aggregated into `ci-required`, so the merge gate does NOT pin `SeamRoom`'s guard that removes an
// admission and its roster entries in one critical section (`AdmissionRosterConcurrencyTest`).
// Nothing cheaper is available — a single-threaded test cannot distinguish the guarded removal from
// the unguarded one it replaced, because the unguarded code had no suspension point between the
// map removal and the roster write: a re-admission can only land between them when a second thread
// runs there, which is precisely what a test dispatcher never does.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    // Apply the exclusion only when the flag is OFF. With the flag ON the exclusion is absent, so a
    // command-line `--tests "*ConcurrencyTest"` include filter runs them (a build-defined exclude
    // would otherwise win over the include and match nothing — the CI job would be green by vacuity).
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
        // The probe harness installs DebugProbes to dump *coroutine* stacks on a hang (#1784), which
        // attaches a java agent at runtime. JDK 21+ warns on stderr when that happens (JEP 451), and
        // stderr cleanliness is itself evidence on these hangs. Scoped to the stress runs.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        // Installing DebugProbes also loads JNA (a coroutines-debug dependency), whose `System.load`
        // is a restricted method: JDK 24+ prints a native-access warning on stderr for it (#2842).
        // Same argument, same scope.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

kotlin {
    // The module that declares @SessionInternalApi is the one place it is not a warning sign.
    compilerOptions { optIn.add("us.tractat.kuilt.session.SessionInternalApi") }
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            // `api`: `:kuilt-identity` types are in this module's public surface — `AdmissionRequest.key`
            // is a `DidKey`, `HostAdmission.revocations` a `RevocationList`, and `DeviceKeyStore` sits in
            // `SeamRoomFactory`'s public constructor — so a consumer writing an `AdmissionPolicy` or
            // building a factory needs them on its compile classpath (#3208).
            api(project(":kuilt-identity"))
            implementation(project(":kuilt-liveness"))
            implementation(project(":kuilt-crdt"))
            implementation(project(":kuilt-quilter"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            // The platform CSPRNG behind `ResumeSecret.generate()` (#3165) — the same source
            // `:kuilt-otel-tap` mints its join codes from.
            implementation(libs.kotlincrypto.random.crypto.rand)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            // `fakeKeyStore(n)`: rooms in tests sign and check with a scheme that never waits on
            // WebCrypto, so wasmJs room tests stay on virtual time (#3280).
            implementation(project(":kuilt-identity-test"))
            // The WireCodecConformanceSuite TCK, for the Hello's fixed-width joiner nonce (#3210).
            // `:kuilt-conformance` api's THIS module in its main source set, so the edge is
            // :kuilt-session main -> :kuilt-conformance main -> :kuilt-session test, with no cycle:
            // the same shape as `:kuilt-core`'s test dependency on it.
            implementation(project(":kuilt-conformance"))
            implementation(project(":kuilt-crdt"))
            implementation(project(":kuilt-quilter"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // SLF4J backend for kotlin-logging on the JVM + Android unit-test variants.
        // SeamReplicator (consumed here via RoomReplicator / Room.channel) initialises a
        // file-level kotlin-logging logger; without a backend, SeamReplicatorKt class-init
        // throws NoClassDefFoundError: org/slf4j/LoggerFactory and poisons every test that
        // merely constructs a replicator. Mirrors :kuilt-crdt (raft issue #222).
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
            // `DebugProbes`, which `runConcurrencyStress` installs to dump *coroutine* stacks on a
            // hang (#1784) — a thread dump shows only threads, and a suspended coroutine has none.
            implementation(libs.kotlinx.coroutines.debug)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
