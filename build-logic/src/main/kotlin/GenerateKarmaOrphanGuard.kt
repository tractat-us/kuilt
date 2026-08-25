import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the Karma **orphan guard** into each module's `karma.config.d` directory, so a
 * headless browser cannot outlive the `wasmJsBrowserTest` that launched it (#2461).
 *
 * ## The leak
 *
 * Karma's `ProcessLauncher` spawns the browser with `require('child_process').spawn`, making
 * the browser a direct child of the Karma **node** process, which is in turn a child of the
 * long-lived **Gradle daemon**:
 *
 * ```
 * java (Gradle daemon)  ppid=1
 *  └─ node (karma)
 *      └─ Google Chrome --user-data-dir=…/T/karma-…  --headless
 *          └─ 7-8 Chrome Helper children
 * ```
 *
 * The browser is torn down *only* by Karma's JS-level launcher cleanup, running inside that
 * node process. macOS has no `PR_SET_PDEATHSIG`, so any termination that skips node's JS
 * handlers — `SIGKILL` from an OOM, a `kill -9`, a stray pattern kill, a hard daemon kill —
 * orphans the browser to `launchd` (`ppid=1`). It then holds ~230 MB indefinitely; observed
 * survivors were up to six days old and outlived the git worktree they were testing.
 *
 * ## What the guard does
 *
 * Three layers, in increasing order of how violently the build has to die:
 *
 * 1. **Record.** `child_process.spawn` is wrapped so every PID it returns is recorded. Those
 *    PIDs were returned by spawn calls made *by this process*, so the recorded set is exactly
 *    the build's own spawned tree.
 * 2. **Reap on orderly exit** via `process.on('exit')` — covers a Karma crash or a signal that
 *    Karma itself handles and turns into a normal exit.
 * 3. **Reap after an unsurvivable kill** via a detached **watchdog** child holding an open
 *    stdin pipe to the Karma process. When Karma dies by *any* means, including `SIGKILL`,
 *    the kernel closes that pipe, the watchdog wakes and kills the recorded PIDs. Parent-death
 *    detection with no polling and no timer.
 *
 * Killing the browser's root PID reaps its helper children, which was verified against a
 * reproduced orphan.
 *
 * ## Why this is not a pattern kill
 *
 * **Nothing here is ever matched by process name, command line, or user-data-dir.** A
 * `pkill -f chrome`-style fix would be actively harmful: this repo is built by several agent
 * workers concurrently, each with its own `wasmJsBrowserTest` and its own headless browser, so
 * a name-matched kill during one build's teardown would reap *other* builds' browsers — landing
 * as a truncated test XML or an unreproducible crash in an unrelated change. The guard only ever
 * kills an integer PID that this very process received back from its own `spawn` call, and it
 * *un*-records a PID as soon as that child exits, so a completed run leaves the watchdog holding
 * an empty set.
 *
 * Residual risk: PID reuse in the window between the Karma process dying and the watchdog
 * waking. The watchdog is woken by pipe closure rather than a poll, so that window is
 * sub-millisecond, and only PIDs of children still running at that instant are held at all.
 *
 * ## The diagnostic trail is load-bearing
 *
 * Every step and every error is appended to `karma-orphan-guard.log` beside the Karma config.
 * This is not debug scaffolding to remove later. **A guard that silently fails to arm is
 * indistinguishable from a guard that worked** — the build is green either way, and the leak
 * only shows up days later on someone's process table. The first version of this guard shipped
 * with each failure path behind a silent `catch` and was completely unarmed; the trail is what
 * turned "no watchdog, no explanation" into a one-line diagnosis:
 *
 * ```
 * … spawn intercepted pid=43308 argv0=…/Google Chrome
 * … watchdog spawned pid=43309
 * … [wd 43309] watchdog up
 * … [wd 43309] reap(end) killed=43308
 * ```
 *
 * A run whose trail lacks `watchdog up` is a disarmed guard, whatever the build says.
 */
abstract class GenerateKarmaOrphanGuard : DefaultTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        verifyWatchdogSourceHasNoEscape()
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(CONTENT)
    }

    /**
     * Fail generation if a backslash has crept back into the watchdog source.
     *
     * The watchdog is JS *inside* a JS string literal, so a `\` there is consumed by the OUTER
     * parser: `'\n'` becomes a real newline inside a single-quoted literal, the watchdog becomes
     * a `SyntaxError` that exits 1 milliseconds after spawn, and the guard is silently disarmed
     * while `wasmJsBrowserTest` stays green. That is not hypothetical — it shipped once in this
     * very change (commit a1f1cedc) and was found only by hand-instrumenting and reading a log
     * CI does not upload. The invariant was then written down in a comment, which is exactly the
     * kind of prose rule this repo converts into something that reds.
     */
    private fun verifyWatchdogSourceHasNoEscape() {
        val open = "var watchdogSource = ["
        val close = "].join("
        check(CONTENT.contains(open) && CONTENT.contains(close)) {
            "Cannot locate the watchdog source block: this check keys on \"$open\" … \"$close\" " +
                "and one of them moved. Re-point the check — do not delete it."
        }
        val watchdogSource = CONTENT.substringAfter(open).substringBefore(close)
        check(!watchdogSource.contains('\\')) {
            "The watchdog source is JS-inside-JS: a backslash is consumed by the OUTER parser " +
                "and silently turns the watchdog into a SyntaxError, disarming the orphan guard " +
                "while the build stays green (#2461, commit a1f1cedc). Re-express without an " +
                "escape — ';' already separates PID records and os.EOL already ends a log line."
        }
    }

    companion object {
        /**
         * Content of the generated `orphan-guard.js`.
         *
         * Appended by Kotlin's Karma integration into the body of `karma.conf.js`, so it runs
         * inside the Karma node process at config-load time — before any browser is launched,
         * with `require`, `process` and `module` in scope.
         *
         * Deliberately installs **no** `SIGINT`/`SIGTERM` handler. Karma registers its own for
         * graceful shutdown; a competing handler here would preempt it and could change the
         * build's exit code. Orderly signals already reach `process.on('exit')` through Karma's
         * handler, and everything else is the watchdog's job.
         */
        val CONTENT: String = """
            |// Generated by kuilt.kmp-library convention plugin — do not edit by hand.
            |//
            |// Orphan guard (#2461): a headless browser must not outlive the Karma process that
            |// spawned it. Only PIDs returned by THIS process's own spawn calls are ever killed —
            |// never a name, command line or user-data-dir match, because sibling builds on the
            |// same machine run their own browsers and a pattern kill would reap theirs too.
            |(function () {
            |    var childProcess = require('child_process');
            |    var originalSpawn = childProcess.spawn;
            |    var spawned = new Set();
            |
            |    // Diagnostic trail. A guard that fails silently is indistinguishable from a
            |    // guard that worked, so every step and every error is recorded next to the
            |    // Karma config. Cheap, bounded, and the only way a future failure is legible.
            |    var trail = require('path').join(process.cwd(), 'karma-orphan-guard.log');
            |    function note(message) {
            |        try {
            |            require('fs').appendFileSync(trail, Date.now() + ' [' + process.pid + '] ' + message + '\n');
            |        } catch (ignored) {
            |            // Never let diagnostics fail the build.
            |        }
            |    }
            |    function describe(error) {
            |        return error && error.stack ? error.stack : String(error);
            |    }
            |    note('guard loaded; execPath=' + process.execPath);
            |
            |    // Runs as `node -e`. Reads "+pid;" / "-pid;" records from stdin; when that pipe
            |    // closes — which the kernel guarantees the moment the Karma process dies, by
            |    // any signal including SIGKILL — it kills whatever PIDs are still recorded.
            |    //
            |    // These are JS strings holding JS source, so every backslash here has to survive
            |    // two parsers. It did not: a '\n' record separator was eaten by the OUTER parser
            |    // and emitted as a real newline inside a single-quoted literal, making the
            |    // watchdog a SyntaxError that died 51 ms after spawn — silently disarming the
            |    // guard while the build stayed green. So the watchdog source below contains NO
            |    // backslash escape at all: ';' separates records and os.EOL ends a log line.
            |    // Keep it that way; a nested escape here fails invisibly.
            |    var watchdogSource = [
            |        "var pids = new Set(), buf = '';",
            |        "var trail = process.argv[1];",
            |        "var eol = require('os').EOL;",
            |        "function note(message) {",
            |        "  try {",
            |        "    require('fs').appendFileSync(trail, Date.now() + ' [wd ' + process.pid + '] ' + message + eol);",
            |        "  } catch (ignored) {}",
            |        "}",
            |        "note('watchdog up');",
            |        "process.stdin.on('data', function (chunk) {",
            |        "  buf += chunk;",
            |        "  var i;",
            |        "  while ((i = buf.indexOf(';')) >= 0) {",
            |        "    var record = buf.slice(0, i).trim();",
            |        "    buf = buf.slice(i + 1);",
            |        "    if (record.charAt(0) === '+') { pids.add(Number(record.slice(1))); }",
            |        "    else if (record.charAt(0) === '-') { pids.delete(Number(record.slice(1))); }",
            |        "  }",
            |        "});",
            |        "function reap(why) {",
            |        "  var reaped = [];",
            |        "  pids.forEach(function (pid) {",
            |        "    try { process.kill(pid, 'SIGKILL'); reaped.push(pid); } catch (ignored) {}",
            |        "  });",
            |        "  note('reap(' + why + ') killed=' + reaped.join(','));",
            |        "  process.exit(0);",
            |        "}",
            |        "process.stdin.on('end', function () { reap('end'); });",
            |        "process.stdin.on('close', function () { reap('close'); });",
            |        "process.stdin.on('error', function (e) { reap('error:' + e.code); });",
            |    ].join('\n');
            |
            |    var watchdog = null;
            |    function watchdogInput() {
            |        if (watchdog === null) {
            |            // originalSpawn, so the watchdog never records itself.
            |            watchdog = originalSpawn(process.execPath, ['-e', watchdogSource, trail], {
            |                detached: true,
            |                stdio: ['pipe', 'ignore', 'ignore'],
            |            });
            |            note('watchdog spawned pid=' + watchdog.pid);
            |            watchdog.on('exit', function (code, signal) {
            |                note('watchdog exited code=' + code + ' signal=' + signal);
            |            });
            |            watchdog.on('error', function (error) {
            |                note('watchdog error: ' + describe(error));
            |            });
            |            // Neither the child nor its stdin may hold this process's event loop
            |            // open, or a passing build would never exit.
            |            watchdog.unref();
            |            if (watchdog.stdin) { watchdog.stdin.unref(); }
            |        }
            |        return watchdog.stdin;
            |    }
            |
            |    function tell(message) {
            |        try {
            |            var input = watchdogInput();
            |            if (input && input.writable) {
            |                input.write(message);
            |            } else {
            |                note('watchdog stdin not writable for ' + message.trim());
            |            }
            |        } catch (error) {
            |            // A guard that cannot start its watchdog must not fail the build — but
            |            // it must not hide why either.
            |            note('tell(' + message.trim() + ') failed: ' + describe(error));
            |        }
            |    }
            |
            |    childProcess.spawn = function () {
            |        var child = originalSpawn.apply(childProcess, arguments);
            |        if (child && typeof child.pid === 'number') {
            |            var pid = child.pid;
            |            spawned.add(pid);
            |            note('spawn intercepted pid=' + pid + ' argv0=' + arguments[0]);
            |            tell('+' + pid + ';');
            |            child.once('exit', function () {
            |                spawned.delete(pid);
            |                // Un-record promptly, so a run that ends cleanly leaves the
            |                // watchdog holding nothing and PID reuse has no target.
            |                note('child exited pid=' + pid);
            |                tell('-' + pid + ';');
            |            });
            |        }
            |        return child;
            |    };
            |    note('spawn patched; patch installed=' + (childProcess.spawn !== originalSpawn));
            |
            |    process.on('exit', function () {
            |        note('process exit; still recorded=' + Array.from(spawned).join(','));
            |        spawned.forEach(function (pid) {
            |            try { process.kill(pid, 'SIGKILL'); } catch (ignored) {}
            |        });
            |    });
            |})();
        """.trimMargin()
    }
}
