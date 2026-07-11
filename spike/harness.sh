#!/bin/bash
# kuilt-nw Phase-0 connectivity harness.
# Installs the app on two devices, launches each with a fresh run-id, and
# VALIDATES every stage against the app's streamed log before declaring the
# connectivity test passed. No "launch and pray" — each stage fails loudly.
#
# Usage: harness.sh <HOST_DEVICE_ID> <JOIN_DEVICE_ID> <APP_PATH>
set -uo pipefail
HOST_DEV="${1:?host device id}"; JOIN_DEV="${2:?join device id}"; APP="${3:?app path}"
BID=us.tractat.spike.nw
RUN=$(date +%s)
H=/tmp/h.log; J=/tmp/j.log

fail() { echo "❌ FAIL @ $1"; echo "--- host tail ---"; tail -8 "$H" 2>/dev/null | sed 's/^1\.7[0-9]*E9 //'; echo "--- join tail ---"; tail -12 "$J" 2>/dev/null | sed 's/^1\.7[0-9]*E9 //'; pkill -f "devicectl device process launch" 2>/dev/null; exit 1; }
wait_for() { local f="$1" pat="$2" secs="$3" desc="$4"; for i in $(seq 1 "$secs"); do grep -q "$pat" "$f" 2>/dev/null && { echo "  ✓ $desc"; return 0; }; sleep 1; done; return 1; }

echo "### run=$RUN  host=$HOST_DEV  join=$JOIN_DEV"
echo "### install"
xcrun devicectl device install app --device "$HOST_DEV" "$APP" >/dev/null 2>&1 || fail "install host"
xcrun devicectl device install app --device "$JOIN_DEV" "$APP" >/dev/null 2>&1 || fail "install join"
pkill -f "devicectl device process launch" 2>/dev/null; sleep 1; : > "$H"; : > "$J"

echo "### host"
xcrun devicectl device process launch --terminate-existing --console --device "$HOST_DEV" "$BID" host "run=$RUN" >> "$H" 2>&1 &
wait_for "$H" "run=$RUN" 12 "host STARTED (run=$RUN)" || fail "host did not start (flaky launch?)"
wait_for "$H" "advertising" 6 "host advertising" || fail "host not advertising"
sleep 2

echo "### join"
xcrun devicectl device process launch --terminate-existing --console --device "$JOIN_DEV" "$BID" join "run=$RUN" >> "$J" 2>&1 &
wait_for "$J" "run=$RUN" 12 "join STARTED (run=$RUN)" || fail "join did not start (flaky launch?)"
wait_for "$J" "browsing" 6 "join browsing (AWDL activated)" || fail "join not browsing"
wait_for "$J" "READY" 20 "join READY (connection established)" || fail "join never reached READY"
wait_for "$J" "RTT=" 15 "join RTT (DATA round-trip)" || fail "READY but NO data round-trip — see boundary logs above"

echo "✅ PASS — connect + data round-trip"
grep -o "RTT=[0-9]*ms" "$J" | head -5 | tr '\n' ' '; echo
pkill -f "devicectl device process launch" 2>/dev/null; true
