import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

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

// The convention plugin's timeout and orphan-guard files, plus the one that swaps the browser.
val assembleKarmaWebkitConfig = tasks.register<Sync>("assembleKarmaWebkitConfig") {
    from(tasks.named("generateKarmaTimeouts"), tasks.named("generateKarmaOrphanGuard"))
    from(layout.projectDirectory.dir("karma-webkit.d"))
    into(layout.buildDirectory.dir("karma-webkit.d"))
}

kotlin {
    if (runWebkitTests) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs { browser { testRuns.create("webkit") } }
    }
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
        androidMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        appleMain.dependencies {
            implementation(libs.cryptography.provider.cryptokit)
            // The Apple stores' one warning: a stored key they could not read back, replaced.
            implementation(libs.kotlin.logging)
        }
        wasmJsMain.dependencies { implementation(libs.cryptography.provider.webcrypto) }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            // DeviceKeyStoreConformanceSuite. `:kuilt-conformance` api's THIS module in its main
            // source set; this is a test-only edge back, so the graph stays acyclic, as in :kuilt-store.
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.cbor)
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
