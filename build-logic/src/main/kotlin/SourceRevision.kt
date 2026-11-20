import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * The commit this build was made from, for the `SCM-Revision` attribute `kuilt.publish` writes into
 * every published class and sources jar (#3576) and into the published Android AAR's manifest
 * (#3584), so a consumer can read which commit built an artifact out of the artifact itself.
 *
 * In order: `git rev-parse HEAD` in [Parameters.rootDirectory], which works on a shallow or
 * detached CI checkout and in a linked worktree; then `GITHUB_SHA`, for a CI job that builds
 * without a `.git`; then [UNKNOWN]. A build outside git, such as one from a source tarball, gets
 * [UNKNOWN] rather than a failure. Git is asked only when the root itself holds a `.git` (a
 * directory, or the file a linked worktree has), so a tarball unpacked inside some other repository
 * does not report that repository's commit.
 *
 * A `ValueSource` because the configuration cache forbids running a process while configuring. It
 * is wired only into task inputs, never read while configuring: the archive tasks' manifests and
 * the `@Input` of `writeAarProvenance`, the task that writes the AAR's manifest for
 * `bundleReleaseAar`. So it is obtained when one of them runs, and the revision is never a
 * configuration-cache input: a new commit invalidates the jars, that manifest and the AAR, and
 * nothing else. `-Pkuilt.sourceRevision=<sha>` overrides it (see `kuilt.publish`).
 *
 * **The revision must reach archive tasks and `writeAarProvenance` only, never a compile or test
 * task (#3464).** It changes on every commit, so a compile or test task that read it would lose its
 * build cache on every commit. A test task reads every jar on its runtime classpath, manifest included, which is why the
 * root build tells runtime-classpath normalization to ignore this attribute.
 */
abstract class SourceRevision : ValueSource<String, SourceRevision.Parameters> {

    interface Parameters : ValueSourceParameters {
        /** The repository root: the directory whose `.git` names the commit. */
        val rootDirectory: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String =
        fromGit() ?: System.getenv("GITHUB_SHA")?.takeIf { COMMIT_SHAPE.matches(it) } ?: UNKNOWN

    private fun fromGit(): String? {
        val root = parameters.rootDirectory.get().asFile
        if (!root.resolve(".git").exists()) return null
        val out = ByteArrayOutputStream()
        val result = try {
            exec.exec {
                commandLine("git", "-C", root.absolutePath, "rev-parse", "--verify", "HEAD^{commit}")
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        } catch (_: GradleException) {
            // No `git` on the PATH. Nothing here is a coroutine, so there is no cancellation to keep.
            return null
        }
        if (result.exitValue != 0) return null
        return out.toString(Charsets.UTF_8).trim().takeIf { COMMIT_SHAPE.matches(it) }
    }

    companion object {
        /** What a build that cannot name its commit writes, rather than failing. */
        const val UNKNOWN: String = "unknown"

        /** The manifest attribute the revision is written under. */
        const val ATTRIBUTE: String = "SCM-Revision"

        /** A full SHA-1 or SHA-256 object name, lower-case hex: what `rev-parse` and `GITHUB_SHA` give. */
        val COMMIT_SHAPE: Regex = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}
