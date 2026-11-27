import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// The wasmJs tests again, in Playwright's WebKit (#3246). Safari and WebKit refuse a compressed
// P-256 `importKey("raw")` (#3232), and every other target accepts it — the JDK, CryptoKit and
// ChromeHeadless — so if `decodeOnCurve` went back to importing the compressed form, only a WebKit
// run would notice. The task exists only under -Pidentity.webkit.tests=true: no WebKit build is
// installed on the Linux runners, and an absent task cannot report a green it never earned.
// Without the flag `:kuilt-identity:wasmJsBrowserWebkitTest` is "task not found", not a skip.
// apple-nightly.yml runs it on macOS after installing the WebKit build the pinned playwright-core
// names.
val runWebkitTests = providers.gradleProperty("identity.webkit.tests").orNull == "true"

// The convention plugin's timeout, orphan-guard and line-reporter files, plus the one that swaps
// the browser. Without the line reporter this run writes no result count, and the plugin's count
// check, which covers every Karma task, fails it (#3605).
val assembleKarmaWebkitConfig = tasks.register<Sync>("assembleKarmaWebkitConfig") {
    from(
        tasks.named("generateKarmaTimeouts"),
        tasks.named("generateKarmaOrphanGuard"),
        tasks.named("generateKarmaLineReporter"),
    )
    from(layout.projectDirectory.dir("karma-webkit.d"))
    into(layout.buildDirectory.dir("karma-webkit.d"))
}

// The iOS Keychain store's TCK against the REAL Keychain (#3310), where the PR build runs it over
// FakeKeychain. Two things the default `iosSimulatorArm64Test` cannot give it, both measured on
// #3225: KGP's default `simctl spawn --standalone` has no Keychain at all (errSecNotAvailable,
// -25291), and a booted simulator answers a binary without `keychain-access-groups`
// errSecMissingEntitlement (-34018). So the run is a second test binary, linked with the
// entitlements the way Xcode embeds them for a simulator build, spawned into a device the caller
// has already booted. The binary, its test run and the task exist only under
// -Pidentity.keychain.tests=true, with -Pidentity.keychain.device=<booted UDID>; without the flag
// `:kuilt-identity:iosSimulatorArm64RealKeychainTest` is "task not found", never a green skip.
// apple-nightly.yml boots the simulator and runs it.
val runRealKeychainTests = providers.gradleProperty("identity.keychain.tests").orNull == "true"
val realKeychainSuite = "us.tractat.kuilt.identity.RealKeychainDeviceKeyStoreConformanceTest"

kotlin {
    if (runRealKeychainTests) {
        iosSimulatorArm64 {
            binaries.test("realKeychain", listOf(NativeBuildType.DEBUG)) {
                linkerOpts(
                    "-sectcreate", "__TEXT", "__entitlements",
                    layout.projectDirectory.file("real-keychain.entitlements").asFile.absolutePath,
                )
            }
            testRuns.create("realKeychain") {
                setExecutionSourceFrom(binaries.getTest("realKeychain", NativeBuildType.DEBUG))
            }
        }
    }
    if (runWebkitTests) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs { browser { testRuns.create("webkit") } }
    }
    // The module that declares @IdentityInternalApi is the one place it is not a warning sign.
    compilerOptions { optIn.add("us.tractat.kuilt.identity.IdentityInternalApi") }
    sourceSets {
        // MANUAL appleMain, as in :kuilt-seal, :kuilt-otel-tap and :kuilt-store. The default
        // hierarchy template's appleMain compiles, but Dokka's source-set discovery never registers
        // it once it holds real files, and dokkaGenerateModuleHtml fails with "Cannot find source
        // set with id :kuilt-identity/appleMain". Creating it by hand disables the template, so
        // every Apple intermediate is wired here; jvm, android and wasmJs need none.
        val appleMain by creating { dependsOn(commonMain.get()) }
        // iOS keeps the device key in the Keychain and macOS in a file (Decided 28), so each has its
        // own intermediate for its `DeviceKeyStore.platform(appId)`; the store they share is appleMain's.
        val iosMain by creating { dependsOn(appleMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosTest by creating { dependsOn(appleTest) }
        val macosTest by creating { dependsOn(appleTest) }
        val iosArm64Test by getting { dependsOn(iosTest) }
        val iosSimulatorArm64Test by getting { dependsOn(iosTest) }
        val macosArm64Test by getting { dependsOn(macosTest) }

        commonMain.dependencies {
            // `api`: `RevocationList.open` takes a `DurableStore` and a `StoreKey`, so a caller names
            // both. Every platform key store also keeps its grants in one (FileDeviceKeyStore in a
            // FileChannelDurableStore). The one kuilt module this depends on; it brings only
            // coroutines and atomicfu, which this module already uses.
            api(project(":kuilt-store"))
            // The primitives are cryptography-kotlin's; this module composes them and owns no
            // cryptographic math beyond decompressing a point (ionspin, below).
            // `implementation`, not `api`: no cryptography-kotlin type appears in the public surface.
            implementation(libs.cryptography.core)
            // `api`: the device's `DidKey` name is `@Serializable` in the public surface.
            api(libs.kotlinx.serialization.core)
            // For `currentCoroutineContext().ensureActive()`, telling this coroutine's own cancellation
            // from one a provider minted (providerCall), per kuilt's exception rule; and for the
            // `Mutex` InMemoryDeviceKeyStore holds across first-key generation.
            implementation(libs.kotlinx.coroutines.core)
            // For `RevocationList`'s `reentrantLock`: a revocation is a read-modify-write that never
            // suspends, so a plain lock guards it and a `Mutex` would buy nothing.
            implementation(libs.kotlinx.atomicfu)
            // Only for `decompress`: checking that a did:key's x has a point on P-256 and computing
            // its y, so every provider imports the 65-byte uncompressed point. Safari's WebCrypto
            // refuses compressed import (#3232) and JDK's compressed decoder never checks the curve
            // (#3201), so it runs here, in common code, and every provider gets the same answer.
            // Pure Kotlin on every target, as in :kuilt-deal.
            implementation(libs.ionspin.bignum)
            // Reading a stored JWK's own x and y (SoftwareDeviceKey.readBack): the providers disagree
            // about a private JWK's public half, so the name is read here. Already on every runtime
            // classpath through cryptography-kotlin's JWK codec.
            implementation(libs.kotlinx.serialization.json)
        }
        // One provider per platform, each a thin wrapper over the platform's own implementation.
        // Chosen explicitly in `platformProvider()` rather than via `CryptographyProvider.Default`,
        // which a consumer can replace process-wide with `CryptographySystem.setDefaultProvider`.
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            // FileDeviceKeyStore's one warning: a key it could not read back, replaced.
            implementation(libs.kotlin.logging)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            // The Keystore signs in DER; DeviceKey.sign returns raw r ‖ s. The library's DER codec
            // reads the ECDSA-Sig-Value (derSignatureToRaw), so the conversion is encoding work only.
            implementation(libs.cryptography.serialization.asn1)
            implementation(libs.cryptography.serialization.asn1.modules)
            // AndroidKeystoreDeviceKeyStore's one warning: a Keystore key it could not use, replaced.
            implementation(libs.kotlin.logging)
            // @RequiresApi(26) on DeviceKeyStore.platform(appId, context), which lint reads.
            implementation(libs.androidx.annotation)
        }
        appleMain.dependencies {
            implementation(libs.cryptography.provider.cryptokit)
            // The Apple stores' one warning: a stored key they could not read back, replaced.
            implementation(libs.kotlin.logging)
        }
        wasmJsMain.dependencies {
            implementation(libs.cryptography.provider.webcrypto)
            // WebCryptoDeviceKeyStore's one warning: a stored key pair it could not use, replaced.
            implementation(libs.kotlin.logging)
        }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            // DeviceKeyStoreConformanceSuite. `:kuilt-conformance` api's THIS module in its main
            // source set; this is a test-only edge back, so the graph stays acyclic, as in :kuilt-store.
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.cbor)
        }
        androidUnitTest.dependencies {
            // AndroidKeystoreDeviceKeyStore's tests build a real KeyGenParameterSpec and throw a real
            // KeyPermanentlyInvalidatedException; android.jar's stubs throw "Stub!" for both.
            implementation(libs.robolectric)
            // Compile scope: kotlin-logging needs an SLF4J backend on the Android unit-test variant, as in
            // :kuilt-session, and AndroidKeystoreDeviceKeyStoreTest reads the replacement warning off a
            // Logback ListAppender, as FileDeviceKeyStoreTest does.
            implementation(libs.logback)
        }
        // The Android Keystore store against a REAL `AndroidKeyStore` provider, on a device or an emulator
        // (#3226): the androidUnitTest suites above run over FakeKeystore, which reads AOSP but measures
        // nothing. Its own tree, not commonTest's, so a device run is these classes alone. Run it with
        // `:kuilt-identity:connectedDebugAndroidTest`; nothing in `build` or `check` does, since a PR
        // runner has no device. `check` compiles it, through the wiring further down.
        getByName("androidInstrumentedTest").dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
        }
        jvmTest.dependencies {
            // Compile scope: FileDeviceKeyStoreTest reads the replacement warning off a Logback
            // ListAppender, the line an operator would actually see.
            implementation(libs.logback)
        }
        // For the WebKit run above, declared whatever the flag so the shared yarn.lock does not
        // change with it. playwright-core downloads no browser on install; its version fixes which
        // WebKit build `playwright-core install webkit` fetches and karma-webkit.d/webkit.js runs.
        wasmJsTest.dependencies {
            implementation(devNpm("playwright-core", "1.65.0"))
        }
    }
}

dependencies {
    constraints {
        // cryptography-core 0.6.0 asks for kotlinx-io 0.9.0, whose wasmJs Node interop contains
        // `import.meta`; a browser bundle (Karma's webpack here, and a consumer's) then fails with
        // "Cannot use 'import.meta' outside a module" before any test runs. The version the rest of
        // kuilt pins has none. A constraint rather than a dependency: this module never touches
        // kotlinx-io itself, and the constraint is published, so consumers get the fixed one too.
        add("commonMainImplementation", libs.kotlinx.io.core) {
            because("kotlinx-io 0.9.0's wasmJs import.meta breaks browser bundles")
        }
    }
}

if (runWebkitTests) {
    tasks.named<KotlinJsTest>("wasmJsBrowserWebkitTest") {
        dependsOn(assembleKarmaWebkitConfig)
        // The build-wide browser-test mutex the convention plugin gives wasmJsBrowserTest. Beyond
        // load, both tasks write the same karma.conf.js into the module's npm package directory,
        // so run side by side one reads the other's: measured, ChromeHeadless's task ran in WebKit.
        usesService(gradle.sharedServices.registrations.getByName("browserTestSerializer").service)
        useKarma {
            // A placeholder Karma insists on; karma-webkit.d/webkit.js replaces the browser list.
            useChromeHeadless()
            useConfigDirectory(layout.buildDirectory.dir("karma-webkit.d").get().asFile)
        }
        filter.apply {
            // These arms ask the PROVIDER to import a compressed point directly, to record which
            // engines can. WebKit cannot (#3232): that is the finding the module was rebuilt around,
            // not a regression. This run guards the module's own import path, which the other tests
            // here go through.
            excludeTestsMatching("*CompressedPointProbeTest.compressedImportRoundTripsToTheSamePoint")
            excludeTestsMatching("*CompressedPointProbeTest.importedCompressedKeyVerifies")
            excludeTestsMatching("*CompressedPointProbeTest.knownCompressedPointDecodesOnEveryTarget")
        }
    }
}

// The real-Keychain suite needs a booted device and an entitled binary, so the default run, which
// has neither, leaves it out. It still COMPILES there, in every PR build.
tasks.named<KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
    filter.excludeTestsMatching(realKeychainSuite)
}

if (runRealKeychainTests) {
    val bootedDevice = providers.gradleProperty("identity.keychain.device").orNull?.takeIf { it.isNotBlank() }
        ?: error("-Pidentity.keychain.tests=true needs -Pidentity.keychain.device=<UDID of a booted iOS simulator>")
    tasks.named<KotlinNativeSimulatorTest>("iosSimulatorArm64RealKeychainTest") {
        description = "Runs the iOS Keychain store's TCK against the real Keychain of a booted simulator (#3310)."
        device.set(bootedDevice)
        // `simctl spawn <udid>` into the booted device, not KGP's default `--standalone`, which has
        // no Keychain.
        standalone.set(false)
        filter.includeTestsMatching(realKeychainSuite)
        // The Keychain is state outside the build: the same inputs are no evidence that it still
        // answers as it did, so the task never counts as up to date.
        outputs.upToDateWhen { false }
        // The receipt is the XML, not the green: a filter matching nothing, or a suite whose rows
        // all came back skipped, exits 0. Count the suite's rows in this run's results, after
        // clearing the last run's so a stale file cannot answer for this one.
        val results = reports.junitXml.outputLocation.get().asFile
        val suite = realKeychainSuite
        doFirst { results.deleteRecursively() }
        doLast {
            val text = results.listFiles { file -> file.extension == "xml" }.orEmpty().joinToString("") { it.readText() }
            // KGP prefixes the classname with the test run's task name
            // (`iosSimulatorArm64RealKeychainTest.us.tractat…`), so match the suite as its suffix.
            val rows = Regex(
                """<testcase\b[^>]*\bclassname="(?:[^"]*\.)?${Regex.escape(suite)}"[^>]*?(?:/>|>.*?</testcase>)""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(text).map { it.value }.toList()
            val skipped = rows.count { "<skipped" in it }
            if (rows.isEmpty() || skipped > 0) {
                throw GradleException(
                    "real-Keychain run: $suite reported ${rows.size} rows, $skipped of them skipped, in $results. " +
                        "The suite did not run against the Keychain.",
                )
            }
            logger.lifecycle("real-Keychain run: $suite executed ${rows.size} rows against the Keychain")
        }
    }
}

// The device run (#3226): `./gradlew :kuilt-identity:connectedDebugAndroidTest` with a device or an
// emulator attached. AndroidJUnitRunner runs the androidInstrumentedTest classes against the real
// AndroidKeyStore provider.
android {
    defaultConfig { testInstrumentationRunner = "us.tractat.kuilt.identity.IdentityDeviceTestRunner" }
}

// The device classes run nowhere a PR builds, so `check` at least compiles them: lint reads their
// source and compiles none of it, so without this they could stop compiling and nothing would say so.
tasks.named("check") { dependsOn("compileDebugAndroidTestKotlinAndroid") }

// How many rows each device's result file must hold. AGP reports a connected run that executed
// nothing as a success, so without this an un-run class reads as green. Adding or removing a device
// test means changing this number in the same commit. A row an assumption skipped still counts, and
// the verify names every skipped row, so a device that could not reach a rig says so.
val expectedDeviceTestRows = 24

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    val results = layout.buildDirectory.dir("outputs/androidTest-results/connected/debug")
    val expected = expectedDeviceTestRows
    var startedAt = 0L
    doFirst { startedAt = System.currentTimeMillis() }
    doLast {
        val dir = results.get().asFile
        val files = dir.walkTopDown().filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }.toList()
        check(files.isNotEmpty()) { "device run: no result file under $dir, so nothing ran" }
        files.forEach { file ->
            check(file.lastModified() >= startedAt) { "device run: $file predates this run, so it is stale" }
            val xml = file.readText()
            val rows = Regex("<testcase\\b").findAll(xml).count()
            val skipped = Regex("""<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<skipped""").findAll(xml)
                .map { "${it.groupValues[2].substringAfterLast('.')}.${it.groupValues[1]}" }
                .toList()
            check(rows == expected) { "device run: ${file.name} holds $rows rows, expected $expected" }
            logger.lifecycle(
                "device run: ${file.name}: $rows of $expected rows, ${skipped.size} skipped" +
                    if (skipped.isEmpty()) "" else ": " + skipped.joinToString(),
            )
        }
    }
}
