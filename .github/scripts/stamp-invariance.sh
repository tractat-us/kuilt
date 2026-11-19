#!/usr/bin/env bash
# The second pass of the stamp-invariance check (#3464): re-run `stampProbeTargets` at a version
# and a commit SHA that differ from the first pass's, record every task outcome, and fail on any
# task outside the allowlist that re-ran. The SHA is the `SCM-Revision` that `kuilt.publish` writes
# into jar manifests (#3576); the first pass reads it from git, this pass overrides it (#3583). See the "Stamp invariance" section of the root build.gradle.kts.
#
#   .github/scripts/stamp-invariance.sh <linux|apple> [extra Gradle arguments…]
#
# Run it AFTER a build at the default version (CI: the job's own build step). Locally, a first pass
# of the same selection is enough:  ./gradlew stampProbeTargets -Pkuilt.stampProbe.family=linux
# STAMP_PROBE_TASKS overrides the task list, e.g. ":kuilt-store:stampProbeTargets" for one module.
set -euo pipefail

family="${1:?usage: stamp-invariance.sh <linux|apple> [gradle args…]}"
shift

line="$(sed -n 's/^kuiltVersionLine=//p' gradle.properties)"
report="build/stamp-probe/report-${family}.tsv"
selected="build/stamp-probe/selected"
rm -rf "$report" "$selected"

start="$(date +%s)"
# The checker reads the manifests of the jars this pass writes, and only those (#3583).
export STAMP_PROBE_START="$start" STAMP_PROBE_FAMILY="$family"
status=0
# shellcheck disable=SC2086 # STAMP_PROBE_TASKS is a word list by design
./gradlew ${STAMP_PROBE_TASKS:-stampProbeTargets} \
  "-Pversion=${line}.0-dev.stamp-probe" \
  "-Pkuilt.sourceRevision=stamp-probe" \
  "-Pkuilt.stampProbe.family=${family}" \
  "-Pkuilt.stampProbe.report=${report}" \
  --no-build-cache --continue "$@" || status=$?
echo "stamp-invariance: probe pass took $(( $(date +%s) - start ))s (gradle exit ${status})"

if [ ! -s "$report" ]; then
  echo "stamp-invariance: no report at ${report}; the probe pass recorded nothing." >&2
  exit 1
fi
python3 .github/scripts/stamp-invariance.py "$report" "$selected" || status=1
exit "$status"
