import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * Writes the `META-INF/MANIFEST.MF` that `kuilt.publish` adds to the published Android AAR, so the
 * AAR names the commit that built it the way every published JVM jar does (#3584, after #3576).
 *
 * AGP builds the AAR's `classes.jar` without a Gradle `Jar` task, so there is no manifest to
 * configure there. The AAR itself is assembled by `bundleReleaseAar`, a `Zip` task, and this task's
 * output directory is added to it. The manifest carries the same two attributes, under the same
 * names, as a jar's: `Implementation-Version` and [SourceRevision.ATTRIBUTE]. A consumer reads them
 * with `unzip -p <module>-android-<version>.aar META-INF/MANIFEST.MF`, or with `java.util.jar.JarFile`,
 * which opens any zip.
 *
 * **Both inputs change more often than the code (#3464), and this task is what keeps them off every
 * compile and test task.** It reads nothing but [libraryVersion] and [revision], and only
 * `bundleReleaseAar` reads its output. No in-build task reads the release AAR: a dependent module
 * compiles and tests against AGP's intermediate jars, and its lint reads the separate
 * `bundle<Variant>LocalLintAar`, which this is not added to.
 *
 * Written through [Manifest] rather than as text so that a 64-character SHA-256 revision is wrapped
 * at the 72-byte line limit the manifest format sets.
 */
abstract class WriteAarProvenance : DefaultTask() {

    @get:Input
    abstract val libraryVersion: Property<String>

    @get:Input
    abstract val revision: Property<String>

    /** Holds `META-INF/MANIFEST.MF`; add the directory itself to the AAR, at its root. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val file = root.resolve("META-INF/MANIFEST.MF")
        file.parentFile.mkdirs()
        val manifest = Manifest()
        manifest.mainAttributes.apply {
            put(Attributes.Name.MANIFEST_VERSION, "1.0")
            put(Attributes.Name.IMPLEMENTATION_VERSION, libraryVersion.get())
            put(Attributes.Name(SourceRevision.ATTRIBUTE), revision.get())
        }
        file.outputStream().use { manifest.write(it) }
    }
}
