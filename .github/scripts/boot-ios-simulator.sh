#!/usr/bin/env bash
# Creates a fresh iPhone simulator on the newest installed iOS runtime, boots it, waits until it
# has finished booting, and prints its UDID on stdout. Everything else goes to stderr.
#
#   UDID=$(.github/scripts/boot-ios-simulator.sh kuilt-keychain)
#   ...
#   xcrun simctl shutdown "$UDID"; xcrun simctl delete "$UDID"
#
# A fresh device rather than one the host already has: the run then starts with an empty Keychain,
# and deleting the device afterwards takes every item the run added with it.
#
# Used by apple-nightly.yml's real-Keychain run of :kuilt-identity (#3310), whose test task needs a
# booted device (`-Pidentity.keychain.device=<udid>`).
set -euo pipefail

name=${1:-kuilt-sim}

# The newest available iOS runtime and the first iPhone it supports. A runtime made for one device
# (its identifier carries the device after the version) loses a tie to the general one.
read -r runtime devtype < <(xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"]
            if r.get("platform") == "iOS" and r.get("isAvailable")]
if not runtimes:
    sys.exit("no available iOS simulator runtime")
key = lambda r: (tuple(int(p) for p in r["version"].split(".")), -len(r["identifier"]))
best = max(runtimes, key=key)
phones = [d["identifier"] for d in best.get("supportedDeviceTypes", []) if d.get("productFamily") == "iPhone"]
if not phones:
    sys.exit("runtime " + best["identifier"] + " supports no iPhone")
print(best["identifier"], phones[0])
')

echo "boot-ios-simulator: creating $name ($devtype on $runtime)" >&2
udid=$(xcrun simctl create "$name" "$devtype" "$runtime")
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b >/dev/null
echo "$udid"
