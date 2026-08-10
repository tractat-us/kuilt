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
            // serves an in-memory segment and (#2213/#2214) a memory-mapped one.
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

        // ── The disk-backed backends' source sets, wired AHEAD of the backends themselves ──
        //
        // #2213 (mmap on JVM/Android) and #2214 (mmap on Apple) are independent pieces of work
        // that would otherwise have to make ONE edit each to this block — and the edits are not
        // separable. Creating a manual `jvmAndAndroidMain` intermediate turns off the Kotlin
        // plugin's default hierarchy template for the whole module, so the Apple intermediates
        // stop being wired for free the moment #2213 lands. Whichever backend merged first would
        // silently take the other's source sets out of the build.
        //
        // So the wiring lands once, here, empty, and each backend then adds only its own files.
        // The shape is copied from `:kuilt-otel`, which already runs a `DurableStore` split
        // exactly this way — a common interface with one named implementation per source set and
        // no `expect`/`actual`, which is what lets the two backends proceed in parallel at all.

        // jvmAndAndroidMain: `FileChannel.map()` lives in java.nio.channels, present on both the
        // JVM and Android and on neither Apple nor wasmJs.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // appleMain: `platform.posix` mmap is in the K/N distribution, so no dependency is needed.
        // Created by hand rather than left to the hierarchy template for a second reason beyond
        // the one above: a module whose FIRST real appleMain source arrives under the template
        // fails the required `dokkaGenerateModuleHtml` check with "no source module for appleMain"
        // — Dokka never registers a template-created intermediate as a source set of its own, and
        // that is invisible until the directory stops being empty (#1074).
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosMain by creating { dependsOn(appleMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        // appleTest: where #2214's `BoltConformanceSuite` subclass goes. The JVM/Android backend
        // needs no equivalent — `jvmTest` is a leaf source set and depends on `commonTest`, where
        // the suite lives, without any wiring.
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
    }
}
