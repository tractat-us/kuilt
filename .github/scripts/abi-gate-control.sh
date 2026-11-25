#!/usr/bin/env bash
# The ABI gate's positive control (#2684): prove that `checkKotlinAbi`, as `kuilt.kmp-library` wires
# it, still goes red when a module's public API drifts from its committed dump, and that the red
# names the declarations that drifted.
#
#   .github/scripts/abi-gate-control.sh [extra Gradle arguments…]
#
# Why a script and not a `check`-wired task: the property is "KGP's check FAILS on this input", and a
# Gradle task cannot run another task and observe its failure. Only a second invocation can. This is
# the stamp-invariance shape (.github/scripts/stamp-invariance.sh): after the build, mutate an input,
# re-run the real tasks in the warm daemon, judge the outcome. ci.yml runs it as build-jvm's last
# Gradle step, so it never sits between stamp invariance's two passes. A docs-only PR skips it, and cannot change the
# convention plugin or the KGP pin either.
#
# The subject is a REAL module through the REAL convention plugin, never a hand-configured KGP: a
# fixture that enabled validation itself would stay green if the plugin stopped enabling it, which is
# the regression this exists to catch. `:kuilt-math` because it applies `kuilt.kmp-library` and
# nothing else, depends on nothing, has no cinterop (so Linux checks it rather than skipping it), and
# has no `jvmMain` of its own, so the fixture can own that source set outright. The fixture is
# planted in `jvmMain` on purpose: only the JVM compile reruns, never the Apple or wasm klibs.
#
# Four arms, in order, each asserting its rig fired:
#   1. baseline  — plant two public functions, `updateKotlinAbi`; the JVM dump must gain exactly
#                  their block and the Android and klib dumps must not change.
#   2. green     — `checkKotlinAbi` must EXECUTE (not SKIPPED, not UP-TO-DATE) and pass.
#   3. red       — change one function's return type, remove the other, add a third. The check must
#                  fail, on that task alone, against the committed dump, with a diff whose changed
#                  lines are exactly those three changes. Any other line in the diff is a red for some
#                  other reason and fails the control.
#   4. wiring    — arms 1-3 prove `checkKotlinAbi` reds; this one proves the gate RUNS it. It reads
#                  the `./gradlew build …` command out of build-jvm in ci.yml (so the two cannot drift
#                  apart), dry-runs it with the same tasks and `-x` exclusions, and requires
#                  `:kuilt-math:checkKotlinAbi` in the plan. The build reaches the check only through
#                  KGP's `check` dependency, so an `-x checkKotlinAbi` there, or a KGP that stops
#                  wiring `check`, would leave arms 1-3 green with the gate off.
# The 2026-10-11 ruling named "removing a public member or changing its return type"; arm 3 does
# both, plus the addition, in one run.
#
# Receipts, taken on macOS against KGP 2.4.21 by editing kuilt.kmp-library and running this script:
#   - unmutated: OK, all three arms.
#   - the `abiValidation()` call (kuilt.kmp-library.gradle.kts:33) deleted: still OK, and correctly
#     so. The plugin has two switches. KGP also activates validation on any READ of
#     `kotlin.abiValidation` ("call abiValidation(), abiValidation { ... } or read abiValidation
#     property", its own deprecation text), and the off-macOS skip reads it (`val abi =
#     kotlin.abiValidation`, :265). Deleting the call alone does not turn the gate off.
#   - both switches removed, the call and the read: arm 1 red, `updateKotlinAbi` not found.
#   - `checkKotlinAbi` skipped for every module (an over-matching skip): arm 2 red, SKIPPED.
#   - `checkKotlinAbi`'s actions replaced by a no-op (a checker that compares nothing): arm 3 red.
#   - `-x checkKotlinAbi` added to build-jvm's build step in ci.yml: arms 1-3 OK, arm 4 red.
#   - TERM sent while arm 3's Gradle ran: exit 130, the Gradle client killed and reaped, the
#     fixture and all three dumps restored.
#
# Everything is restored on exit, by copy from a backup rather than `git checkout`. The module's JVM
# classes are left compiled from the fixture; the next build recompiles them.
set -euo pipefail

module="kuilt-math"
plant_dir="${module}/src/jvmMain"
plant_file="${plant_dir}/kotlin/us/tractat/kuilt/math/AbiGateControl.kt"
jvm_dump="${module}/api/jvm/${module}.api"
other_dumps=("${module}/api/android/${module}.api" "${module}/api/${module}.klib.api")
logs="build/abi-gate-control"
check_task=":${module}:checkKotlinAbi"
update_task=":${module}:updateKotlinAbi"
tab=$'\t'

fail() {
  echo "abi-gate-control: FAILED — $1" >&2
  if [ -n "${2:-}" ] && [ -f "$2" ]; then
    echo "----- $2: task outcomes for ${module} -----" >&2
    grep -E "^> Task :${module}:(compileKotlinJvm|internalDumpKotlinAbi|checkKotlinAbi|updateKotlinAbi)( |\$)" "$2" >&2 || true
    if grep -q '^\* What went wrong:' "$2"; then
      echo "----- $2: what went wrong -----" >&2
      sed -n '/^\* What went wrong:/,/^\* Try:/p' "$2" >&2
    else
      echo "----- $2 (last 40 lines) -----" >&2
      tail -40 "$2" >&2
    fi
  fi
  exit 1
}

# ── Preconditions ─────────────────────────────────────────────────────────────────────────────────
grep -q 'id("kuilt.kmp-library")' "${module}/build.gradle.kts" ||
  fail "${module} no longer applies kuilt.kmp-library, so it cannot stand for the convention plugin."
if grep -q 'abiValidation' "${module}/build.gradle.kts"; then
  fail "${module}/build.gradle.kts configures abiValidation itself, so this control would stay green if kuilt.kmp-library stopped enabling it."
fi
[ ! -e "$plant_dir" ] ||
  fail "${plant_dir} exists. The fixture needs that source set to itself; pick another module, deliberately."
for dump in "$jvm_dump" "${other_dumps[@]}"; do
  [ -f "$dump" ] || fail "no committed dump at ${dump}."
done
git diff --quiet HEAD -- "${module}/api" ||
  fail "${module}/api differs from HEAD. The control compares against the committed dumps; commit or discard first."

backup="$(mktemp -d)"
cp "$jvm_dump" "$backup/jvm.api"
cp "${other_dumps[0]}" "$backup/android.api"
cp "${other_dumps[1]}" "$backup/klib.api"
restore() {
  rm -rf "$plant_dir"
  cp "$backup/jvm.api" "$jvm_dump"
  cp "$backup/android.api" "${other_dumps[0]}"
  cp "$backup/klib.api" "${other_dumps[1]}"
  rm -rf "$backup"
}
trap restore EXIT
# Gradle runs in the background and is waited on, so a signal reaches this script while Gradle runs.
# On INT or TERM the client is killed and waited for BEFORE the EXIT trap restores, or an orphaned
# `./gradlew` could rewrite a dump after the restore.
gpid=""
on_signal() {
  if [ -n "$gpid" ]; then
    kill "$gpid" 2>/dev/null || true
    wait "$gpid" 2>/dev/null || true
  fi
  exit 130
}
trap on_signal INT TERM

mkdir -p "$logs" "$(dirname "$plant_file")"

# Every invocation: plain console so task outcomes are printed; no file-system watching, because the
# fixture is edited and rebuilt within seconds and a watching daemon can miss the edit; no build
# cache, so a fixture compile never comes from, or goes to, a cache shared with real builds.
gradle() {
  local log="$1"
  shift
  local status=0
  ./gradlew "$@" --console=plain --no-watch-fs --no-build-cache ${extra[@]+"${extra[@]}"} >"$log" 2>&1 &
  gpid=$!
  wait "$gpid" || status=$?
  gpid=""
  return "$status"
}
extra=("$@")
start="$(date +%s)"

# ── Arm 1: baseline ───────────────────────────────────────────────────────────────────────────────
cat >"$plant_file" <<'KT'
package us.tractat.kuilt.math

// Planted by .github/scripts/abi-gate-control.sh, and removed when it exits. Never commit this.
public fun abiGateControlReturnType(): String = ""

public fun abiGateControlRemoved(): String = ""
KT
gradle "$logs/1-update.log" "$update_task" ||
  fail "arm 1: ${update_task} did not succeed. If the task does not exist, ABI validation is off." "$logs/1-update.log"
grep -qx "> Task ${update_task}" "$logs/1-update.log" ||
  fail "arm 1: ${update_task} did not execute." "$logs/1-update.log"

expected_baseline="public final class us/tractat/kuilt/math/AbiGateControlKt {
${tab}public static final fun abiGateControlRemoved ()Ljava/lang/String;
${tab}public static final fun abiGateControlReturnType ()Ljava/lang/String;
}"
# Lines the update added (`>`) or removed (`<`), blank lines dropped: only additions, and exactly these.
actual_baseline="$(diff "$backup/jvm.api" "$jvm_dump" | grep -E '^[<>]' | grep -vE '^> *$' || true)"
[ "$actual_baseline" = "$(printf '%s\n' "$expected_baseline" | sed 's/^/> /')" ] ||
  fail "arm 1: the JVM dump did not gain exactly the fixture's block. diff against the committed dump:
${actual_baseline:-<no change>}"
cmp -s "$backup/android.api" "${other_dumps[0]}" || fail "arm 1: the Android dump changed; the fixture is JVM-only."
cmp -s "$backup/klib.api" "${other_dumps[1]}" || fail "arm 1: the klib dump changed; the fixture is JVM-only."
echo "abi-gate-control: arm 1 ok — ${update_task} recorded the fixture's two functions in ${jvm_dump}"

# ── Arm 2: green ──────────────────────────────────────────────────────────────────────────────────
gradle "$logs/2-green.log" "$check_task" ||
  fail "arm 2: ${check_task} is red on a dump that matches the source." "$logs/2-green.log"
grep -qx "> Task ${check_task}" "$logs/2-green.log" ||
  fail "arm 2: ${check_task} did not execute (SKIPPED, UP-TO-DATE or absent), so its green proves nothing." "$logs/2-green.log"
echo "abi-gate-control: arm 2 ok — ${check_task} executed and passed"

# ── Arm 3: red ────────────────────────────────────────────────────────────────────────────────────
cat >"$plant_file" <<'KT'
package us.tractat.kuilt.math

// Planted by .github/scripts/abi-gate-control.sh, and removed when it exits. Never commit this.
public fun abiGateControlReturnType(): Int = 0

public fun abiGateControlAdded(): String = ""
KT
if gradle "$logs/3-red.log" "$check_task"; then
  fail "arm 3: ${check_task} PASSED although the source no longer matches its dump. The ABI gate is a no-op." "$logs/3-red.log"
fi
red="$logs/3-red.log"
grep -qx "> Task ${check_task} FAILED" "$red" ||
  fail "arm 3: the build failed, but not in ${check_task}." "$red"
failed_tasks="$(grep -c '^Execution failed for task' "$red" || true)"
[ "$failed_tasks" = "1" ] && grep -qx "Execution failed for task '${check_task}'." "$red" ||
  fail "arm 3: expected ${check_task} to be the only failure, found ${failed_tasks}." "$red"
grep -qF '<<<ABI has changed>>>' "$red" ||
  fail "arm 3: ${check_task} failed without reporting an ABI change." "$red"
grep -qE "^ *--- .*/${jvm_dump}\$" "$red" ||
  fail "arm 3: the diff is not against the committed ${jvm_dump}." "$red"

expected_red="${tab}-public static final fun abiGateControlRemoved ()Ljava/lang/String;
${tab}-public static final fun abiGateControlReturnType ()Ljava/lang/String;
${tab}+public static final fun abiGateControlAdded ()Ljava/lang/String;
${tab}+public static final fun abiGateControlReturnType ()I"
# Each changed line of the reported diff, as "<tab><sign><declaration>", sorted for comparison.
actual_red="$(grep -E "^ *[-+]${tab}" "$red" | sed -E "s/^ *([-+])${tab}/${tab}\\1/" | LC_ALL=C sort || true)"
[ "$actual_red" = "$(printf '%s\n' "$expected_red" | LC_ALL=C sort)" ] ||
  fail "arm 3: the reported diff is not exactly the fixture's three changes. Changed lines reported:
${actual_red:-<none>}" "$red"
echo "abi-gate-control: arm 3 ok — ${check_task} failed naming the changed return type, the removal and the addition"

# ── Arm 4: the gate runs the check ────────────────────────────────────────────────────────────────
# Read build-jvm's build command out of ci.yml rather than copying it, so an edit to its task list or
# its `-x` exclusions reaches this arm. Exactly one build-jvm step may run `./gradlew build`.
ci_tokens="$logs/4-ci-command.txt"
ruby -ryaml -rshellwords -e '
  steps = YAML.load_file(".github/workflows/ci.yml").fetch("jobs").fetch("build-jvm").fetch("steps")
  runs = steps.map { |s| s["run"].to_s.strip }.select { |r| r =~ /\A\.\/gradlew\s+build\b/ }
  abort "abi-gate-control: expected one build-jvm step running ./gradlew build, found #{runs.size}" unless runs.size == 1
  puts Shellwords.split(runs.first)
' >"$ci_tokens" || fail "arm 4: could not read build-jvm's ./gradlew build command from .github/workflows/ci.yml."
# Its tasks and flags, minus the daemon -D arguments and --max-workers, which this script's own
# arguments supply so the warm daemon is reused.
build_args=()
while IFS= read -r token; do
  case "$token" in
    ./gradlew | -D* | --max-workers*) ;;
    *) build_args+=("$token") ;;
  esac
done <"$ci_tokens"
[ "${#build_args[@]}" -gt 0 ] || fail "arm 4: build-jvm's build command has no tasks."
gradle "$logs/4-wiring.log" "${build_args[@]}" --dry-run ||
  fail "arm 4: a dry run of build-jvm's build (${build_args[*]}) failed." "$logs/4-wiring.log"
grep -qx "${check_task} SKIPPED" "$logs/4-wiring.log" ||
  fail "arm 4: build-jvm's build (${build_args[*]}) does not run ${check_task}, so the PR gate checks no ABI." "$logs/4-wiring.log"
echo "abi-gate-control: arm 4 ok — build-jvm's build (${build_args[*]}) runs ${check_task}"

echo "abi-gate-control: OK in $(($(date +%s) - start))s; restoring ${module}"
