#!/bin/bash
# kuilt-nw connectivity-suite harness (#1467).
#
# The suite is designed to run WITHOUT a Mac: two people tap Host / Join and text the report back.
# This harness is the Mac-tethered convenience path — install on two USB/network devices, launch each
# with its role, and extract the verbatim report each device prints to stdout.
#
# Because the suite includes a ~2-min soak, allow generous timeouts. `devicectl --console` rides Wi-Fi
# for network-attached devices, so keep at least one device on USB if you want live console during a
# Wi-Fi-off scenario (see PAINPOINTS.md). For a true no-Mac field run, ignore this script — tap the
# buttons and use the in-app Share/Copy.
#
# Usage: harness.sh <HOST_DEVICE_ID> <JOIN_DEVICE_ID> <APP_PATH>
set -uo pipefail
HOST_DEV="${1:?host device id}"; JOIN_DEV="${2:?join device id}"; APP="${3:?app path}"
BID=us.tractat.spike.nw
# Per-run log paths, for the same reason teardown is keyed on our own PIDs: attribution by
# something this run owns. A `devicectl … --console` orphan from an earlier run (one the EXIT trap
# below could not reap — a SIGKILL, a panic) holds its log open in O_APPEND, so truncating a
# reused path would NOT stop it appending; and an attached console keeps the stale app running and
# streaming, so it can emit a whole ===REPORT-BEGIN===…===REPORT-END=== block that wait_for matches
# and report() then prints as THIS run's result — a false PASS, which `--terminate-existing` cannot
# prevent because it fires at launch, after any truncation. A path no orphan has ever heard of makes
# that structurally impossible, and stops two concurrent runs colliding on one file besides.
# Left behind on exit on purpose: they are the post-mortem when a suite does not complete.
H=$(mktemp -t suite-host) || { echo "❌ cannot create host log"; exit 1; }
J=$(mktemp -t suite-join) || { echo "❌ cannot create join log"; exit 1; }
echo "logs: $H (host) · $J (join)"
SUITE_TIMEOUT=200   # seconds — covers weave + election + teardown + ~120s soak + slack

report() { # $1=logfile $2=label
  echo "=== $2 report ==="
  awk '/===REPORT-BEGIN===/{f=1;next} /===REPORT-END===/{f=0} f' "$1" 2>/dev/null | sed 's/^\[suite\] //'
}
wait_for() { local f="$1" pat="$2" secs="$3" desc="$4"; for i in $(seq 1 "$secs"); do grep -q "$pat" "$f" 2>/dev/null && { echo "  ✓ $desc"; return 0; }; sleep 1; done; return 1; }

# Teardown is scoped to the PIDs THIS script spawned. The EXIT trap covers normal completion, the
# early `exit 1`s and Ctrl-C (on main, a Ctrl-C left both consoles and both apps running). It does
# NOT cover a SIGKILL or a panic, and the TERM it sends is not waited on — hence the per-run log
# paths above, which make the orphan that survives those cases harmless rather than merely rarer.
#
# Never reap by command-line pattern here (`pkill -f "devicectl device process launch"`, which is
# what this script used to do): `-f` matches every process on the machine whose command line
# contains that string, so on a box running more than one device harness it kills a CONCURRENT
# run's launches too. That damage lands on someone else and arrives disguised — the victim sees a
# truncated log and a missing report block, which reads as "my change broke the device path" or
# "the harness is flaky". Both readings are wrong, and it attacks verification specifically (#2537).
#
# `xcrun` execs the tool in place, so $! is the devicectl process itself and a plain kill reaches it.
HOST_PID=""; JOIN_PID=""
# shellcheck disable=SC2329  # invoked indirectly, by the EXIT trap below
cleanup() {
  local pid
  for pid in "$HOST_PID" "$JOIN_PID"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
  return 0
}
trap cleanup EXIT

echo "### install"
xcrun devicectl device install app --device "$HOST_DEV" "$APP" >/dev/null 2>&1 || { echo "❌ install host"; exit 1; }
xcrun devicectl device install app --device "$JOIN_DEV" "$APP" >/dev/null 2>&1 || { echo "❌ install join"; exit 1; }

echo "### launch host (role=host)"
xcrun devicectl device process launch --terminate-existing --console --device "$HOST_DEV" "$BID" host >> "$H" 2>&1 &
HOST_PID=$!
wait_for "$H" "suite start role=host" 15 "host suite started" || { echo "❌ host did not start"; exit 1; }

echo "### launch join (role=join)"
xcrun devicectl device process launch --terminate-existing --console --device "$JOIN_DEV" "$BID" join >> "$J" 2>&1 &
JOIN_PID=$!
wait_for "$J" "suite start role=join" 15 "join suite started" || { echo "❌ join did not start"; exit 1; }

echo "### awaiting completion (up to ${SUITE_TIMEOUT}s — includes the soak)"
HOST_OK=1; JOIN_OK=1
wait_for "$H" "===REPORT-END===" "$SUITE_TIMEOUT" "host suite complete" || HOST_OK=0
wait_for "$J" "===REPORT-END===" 30 "join suite complete" || JOIN_OK=0

echo
report "$H" "HOST"
echo
report "$J" "JOIN"
echo
# (the EXIT trap reaps this run's own launches — see cleanup() above)
[ "$HOST_OK" = 1 ] && [ "$JOIN_OK" = 1 ] && { echo "✅ both suites completed"; exit 0; }
echo "⚠ a suite did not complete — see the tails above"; exit 1
