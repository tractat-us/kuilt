#!/usr/bin/env bash
#
# pull-device-telemetry.sh — pull the on-device telemetry / durable-log store off one or
# more tethered iPhones, decode it, and report WHICH container actually covers the window
# you care about. For post-hoc hardware debugging: nothing needs to be armed in advance and
# the app need not be running — this is a plain filesystem copy over USB.
#
# Usage:
#   .claude/scripts/pull-device-telemetry.sh [options]
#
#   --list                 Just run `xcrun devicectl list devices` and exit.
#   --device <UDID>        Restrict to this device. Repeatable. Default: every reachable iPhone.
#   --bundle-id <id>       Pull this bundle only, skipping discovery. Repeatable.
#   --match <substring>    Discovery filter over installed bundle ids (default: tractat).
#   --source <path>        Path inside the app data container (default: Documents).
#   --out <dir>            Output directory (default: /tmp/kuilt-device-telemetry-<UTC stamp>).
#   -h, --help             This text.
#
# Requires Xcode 15+ (`xcrun devicectl`), the phone paired with Developer Mode on, and a
# development-signed build (a distribution/TestFlight build's data container is not readable).
#
# ── Three things that cost real time. Read them before trusting the output. ──────────────
#
# 1. BUNDLE-ID DISCOVERY IS THE HARD PART — the obvious bundle id is usually WRONG.
#    A phone that has been run from Xcode typically carries the app under BOTH the plain id
#    and an Xcode-generated one, e.g.
#        us.tractat.fireworks.compose
#        us.tractat.fireworks.compose.fireworks-compose.fireworks-composeF4S2NUR9VL
#    They are separate installs with separate data containers, and the session you are
#    looking for is often in the *generated* one. A wrong or undebuggable bundle fails with
#    an opaque `ContainerLookupErrorDomain error -1` that says nothing about why. So this
#    script ENUMERATES every installed bundle matching --match and tries each, reporting
#    which ones yielded a container. Use --bundle-id only when you already know.
#
# 2. PICK THE BUFFER BY TIME COVERAGE, NOT BY NAME. Several containers will hand you a
#    plausible-looking store. The one you want is the one whose records span the incident.
#    Every candidate below is reported with its record count and its first/last record time
#    (UTC) for exactly this reason: a container whose NEWEST record predates the incident is
#    the wrong container, no matter how right its name looks.
#
# 3. RECORD TIMESTAMPS ARE WRITE-TIMES, NOT EVENT-TIMES. The store flushes on a fixed,
#    device-dependent cadence — the two phones this was written against show median
#    inter-record gaps of ~130 ms and ~360 ms — so consecutive records come out
#    near-uniformly spaced regardless of when the events they describe actually happened.
#    Record order and record timestamps therefore CANNOT support a claim of the form
#    "A happened before B". Any ordering conclusion must come from the `at=` / `expiresAt=`
#    fields embedded in the event bodies. Getting this backwards produced a wrong
#    root-cause diagnosis on #1637 — the script re-prints this after every decode. (Plain
#    `*.log` files are not affected: those lines are stamped when the event is logged.)

set -euo pipefail

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="/tmp/kuilt-device-telemetry-${STAMP}"
MATCH="tractat"
SOURCE="Documents"
DEVICES=()
BUNDLES=()
LIST_ONLY=0

# The decoder lives in a sibling checkout (`tools/log-puller`), presence-gated the same way
# the build's `includeBuild` is: if it is there we decode, if it is not we still copy the raw
# store and say so. Override with KUILT_LOG_PULLER_REPO.
DECODER_REPO="${KUILT_LOG_PULLER_REPO:-/Users/keddie/tractatus/fireworks-compose}"

usage() { awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list) LIST_ONLY=1; shift;;
    --device) DEVICES+=("$2"); shift 2;;
    --bundle-id) BUNDLES+=("$2"); shift 2;;
    --match) MATCH="$2"; shift 2;;
    --source) SOURCE="$2"; shift 2;;
    --out) OUT_DIR="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown arg: $1 (try --help)" >&2; exit 2;;
  esac
done

command -v xcrun >/dev/null 2>&1 || { echo "ERROR: xcrun not found — install Xcode." >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 not found." >&2; exit 2; }

if (( LIST_ONLY )); then
  xcrun devicectl list devices
  exit 0
fi

mkdir -p "$OUT_DIR"
echo "[pull] output: $OUT_DIR"

# ── device discovery ─────────────────────────────────────────────────────────────────────
# devicectl's JSON is its only supported machine interface; the human table's columns move.
DEV_JSON="$OUT_DIR/devices.json"
if ! xcrun devicectl list devices --quiet --timeout 60 --json-output "$DEV_JSON" >/dev/null 2>&1; then
  echo "[pull] ERROR: 'xcrun devicectl list devices' failed — is a device paired?" >&2
  exit 1
fi

# udid<TAB>tag for every reachable iOS device. A device whose tunnel is 'unavailable' is
# paired but unreachable (asleep, or has never trusted this Mac) — listing it would only
# produce a confusing copy failure later.
DEVICE_ROWS="$(python3 - "$DEV_JSON" <<'PY'
import json, re, sys
with open(sys.argv[1]) as fh:
    doc = json.load(fh)
for dev in doc.get("result", {}).get("devices", []):
    hw = dev.get("hardwareProperties", {})
    if hw.get("platform") != "iOS":
        continue
    if dev.get("connectionProperties", {}).get("tunnelState") == "unavailable":
        continue
    name = dev.get("deviceProperties", {}).get("name", "iPhone")
    tag = re.sub(r"[^A-Za-z0-9]+", "-", f"{name}_{hw.get('productType', 'unknown')}").strip("-")
    print(f"{dev['identifier']}\t{tag}")
PY
)"

if [[ -z "$DEVICE_ROWS" ]]; then
  echo "[pull] ERROR: no reachable iPhone. Plug one in, unlock it, and trust this Mac." >&2
  echo "[pull]        '$0 --list' shows what this Mac can currently see." >&2
  exit 1
fi

SELECTED=()
while IFS=$'\t' read -r udid tag; do
  [[ -n "$udid" ]] || continue
  if (( ${#DEVICES[@]} > 0 )); then
    keep=0
    for want in "${DEVICES[@]}"; do if [[ "$want" == "$udid" ]]; then keep=1; fi; done
    (( keep )) || continue
  fi
  SELECTED+=("$udid|$tag")
done <<< "$DEVICE_ROWS"

if (( ${#SELECTED[@]} == 0 )); then
  echo "[pull] ERROR: no --device matched. Reachable ids:" >&2
  cut -f1 <<< "$DEVICE_ROWS" | sed 's/^/[pull]        /' >&2
  exit 1
fi
echo "[pull] devices: ${#SELECTED[@]}"

# ── decoder availability ─────────────────────────────────────────────────────────────────
DECODE=0
if [[ -x "$DECODER_REPO/gradlew" && -d "$DECODER_REPO/tools/log-puller" ]]; then
  DECODE=1
  echo "[pull] store decoder: $DECODER_REPO/tools/log-puller"
else
  echo "[pull] store decoder: NOT PRESENT at $DECODER_REPO — durable stores will be copied but"
  echo "[pull]                NOT decoded, so no record counts or time ranges for them. kuilt has"
  echo "[pull]                no decoder of its own; set KUILT_LOG_PULLER_REPO to a checkout that"
  echo "[pull]                has tools/log-puller, or read the raw copy by hand."
fi

# ── summarisers ──────────────────────────────────────────────────────────────────────────
# Both print one line: "<records> <first-utc> <last-utc> <median-gap-ms>", or "0 - - -".

summarize_ndjson() {
  python3 - "$1" <<'PY'
import json, sys, datetime, statistics
ts = []
n = 0
with open(sys.argv[1]) as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        n += 1
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        t = rec.get("timestampEpochNanos") or rec.get("observedEpochNanos")
        if t:
            ts.append(t / 1e9)
if not ts:
    print(f"{n} - - -")
else:
    ts.sort()
    iso = lambda s: datetime.datetime.fromtimestamp(s, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    gaps = [(b - a) * 1000 for a, b in zip(ts, ts[1:])]
    gap = f"{statistics.median(gaps):.0f}" if gaps else "-"
    print(f"{n} {iso(ts[0])} {iso(ts[-1])} {gap}")
PY
}

summarize_plainlog() {
  python3 - "$1" <<'PY'
import sys, re, datetime, statistics
# Two on-device line formats: an ISO-8601 UTC prefix, and a bare epoch-seconds Double
# (Kotlin renders it in exponent form). Everything else is a continuation line.
ISO = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?)Z\s")
EPOCH = re.compile(r"^([0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)\s")
ts = []
n = 0
with open(sys.argv[1], errors="replace") as fh:
    for line in fh:
        if not line.strip():
            continue
        n += 1
        m = ISO.match(line)
        if m:
            ts.append(datetime.datetime.fromisoformat(m.group(1)).replace(tzinfo=datetime.timezone.utc).timestamp())
            continue
        m = EPOCH.match(line)
        if m:
            try:
                ts.append(float(m.group(1)))
            except ValueError:
                pass
if not ts:
    print(f"{n} - - -")
else:
    ts.sort()
    iso = lambda s: datetime.datetime.fromtimestamp(s, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    gaps = [(b - a) * 1000 for a, b in zip(ts, ts[1:])]
    gap = f"{statistics.median(gaps):.0f}" if gaps else "-"
    print(f"{n} {iso(ts[0])} {iso(ts[-1])} {gap}")
PY
}

# ── pull ─────────────────────────────────────────────────────────────────────────────────
CANDIDATES=()   # "<tag>|<bundle>|<artifact>|<records>|<first>|<last>|<gap-ms>"
DECODED_ANY=0
COPIED_ANY=0

for entry in "${SELECTED[@]}"; do
  udid="${entry%%|*}"
  tag="${entry##*|}"
  echo "[pull] ── $tag ($udid)"

  # candidate bundles: explicit, or every installed id matching --match
  if (( ${#BUNDLES[@]} > 0 )); then
    candidates=("${BUNDLES[@]}")
    echo "[pull]   bundles (explicit): ${#candidates[@]}"
  else
    apps_json="$OUT_DIR/apps-$tag.json"
    if ! xcrun devicectl device info apps --device "$udid" --timeout 90 \
           --json-output "$apps_json" >/dev/null 2>&1; then
      echo "[pull]   WARN: could not enumerate apps on $tag — skipping." >&2
      continue
    fi
    candidates=()
    while IFS= read -r bid; do
      if [[ -n "$bid" ]]; then candidates+=("$bid"); fi
    done < <(python3 - "$apps_json" "$MATCH" <<'PY'
import json, sys
with open(sys.argv[1]) as fh:
    doc = json.load(fh)
needle = sys.argv[2]
for app in doc.get("result", {}).get("apps", []):
    bid = app.get("bundleIdentifier", "")
    if needle in bid:
        print(bid)
PY
)
    if (( ${#candidates[@]} == 0 )); then
      echo "[pull]   no installed bundle matches --match '$MATCH' — try a shorter substring." >&2
      continue
    fi
    echo "[pull]   bundles matching '$MATCH': ${#candidates[@]} (trying each — the obvious one is often not the one)"
    for b in "${candidates[@]}"; do echo "[pull]     · $b"; done
  fi

  for bundle in "${candidates[@]}"; do
    dest="$OUT_DIR/raw/$tag/$bundle"
    mkdir -p "$dest"
    echo "[pull]   copying $SOURCE from $bundle"
    if ! copy_err="$(xcrun devicectl device copy from --quiet --timeout 120 --device "$udid" \
          --domain-type appDataContainer --domain-identifier "$bundle" \
          --source "$SOURCE" --destination "$dest" 2>&1)"; then
      rmdir "$dest" 2>/dev/null || true
      if grep -q "ContainerLookupErrorDomain" <<< "$copy_err"; then
        echo "[pull]     ✗ no readable data container (ContainerLookupErrorDomain) — the bundle is"
        echo "[pull]       not installed, or its container is not readable (a distribution/TestFlight"
        echo "[pull]       build, or never launched). Expected for some candidates; keep reading."
      else
        echo "[pull]     ✗ copy failed: $(tail -1 <<< "$copy_err")"
      fi
      continue
    fi
    COPIED_ANY=1

    # A kuilt-otel durable store is a directory holding otel_* key files; anything else
    # useful in Documents is a plain text log. Summarise both — the operator picks by time.
    found=0
    while IFS= read -r store; do
      [[ -n "$store" ]] || continue
      found=1
      rel="${store#"$dest"/}"
      if [[ "$rel" == "$store" ]]; then rel="."; fi
      if (( DECODE )); then
        label="${tag}__${bundle}__$(tr '/' '_' <<< "$rel")"
        echo "[pull]     decoding store $rel"
        if "$DECODER_REPO/gradlew" -q -p "$DECODER_REPO" :tools-log-puller:run \
             --args="--mode dump-store --in $store --label $label --out $OUT_DIR/decoded" >/dev/null 2>&1; then
          DECODED_ANY=1
          read -r n first last gap <<< "$(summarize_ndjson "$OUT_DIR/decoded/$label.ndjson")"
          CANDIDATES+=("$tag|$bundle|store:$rel|$n|$first|$last|$gap")
        else
          echo "[pull]     ✗ decode failed for $rel (raw copy kept at $store)"
          CANDIDATES+=("$tag|$bundle|store:$rel|decode-failed|-|-|-")
        fi
      else
        CANDIDATES+=("$tag|$bundle|store:$rel|not-decoded|-|-|-")
      fi
    done < <(find "$dest" -type f -name 'otel*' -exec dirname {} \; | sort -u)

    while IFS= read -r logfile; do
      [[ -n "$logfile" ]] || continue
      found=1
      read -r n first last gap <<< "$(summarize_plainlog "$logfile")"
      CANDIDATES+=("$tag|$bundle|log:${logfile#"$dest"/}|$n|$first|$last|$gap")
    done < <(find "$dest" -type f -name '*.log' | sort)

    if (( ! found )); then
      echo "[pull]     · container copied, but it held no telemetry store and no *.log"
    fi
  done
done

# ── report ───────────────────────────────────────────────────────────────────────────────
echo
if (( ${#CANDIDATES[@]} == 0 )); then
  if (( COPIED_ANY )); then
    echo "[pull] copied container(s), but found nothing that looks like a telemetry store or log."
    echo "[pull] Raw copies are under $OUT_DIR/raw — check --source (currently '$SOURCE')."
  else
    echo "[pull] nothing was pulled. No candidate bundle had a readable data container."
    echo "[pull] Widen --match, or check the build on the phone is development-signed."
  fi
  exit 1
fi

echo "[pull] candidates — PICK BY TIME COVERAGE, not by name:"
echo
{
  printf 'DEVICE\tBUNDLE\tARTIFACT\tRECORDS\tFIRST (UTC)\tLAST (UTC)\tMEDIAN GAP\n'
  for row in "${CANDIDATES[@]}"; do
    IFS='|' read -r tag bundle artifact n first last gap <<< "$row"
    if [[ "$gap" != "-" ]]; then gap="${gap}ms"; fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$tag" "$bundle" "$artifact" "$n" "$first" "$last" "$gap"
  done
} | column -t -s$'\t'
echo
echo "[pull] a candidate whose LAST record predates your incident is the wrong container."
echo

if (( DECODED_ANY )); then
  echo "================================================================================"
  echo " RECORD TIMESTAMPS ARE WRITE-TIMES, NOT EVENT-TIMES."
  echo
  echo " The store flushes on a fixed, device-dependent cadence — see the MEDIAN GAP"
  echo " column above — so consecutive records are near-uniformly spaced no matter when"
  echo " the events they describe actually happened."
  echo
  echo " Do NOT conclude 'A happened before B' from record order or record timestamps."
  echo " Read the at= / expiresAt= fields inside the event bodies instead. This exact"
  echo " mistake produced a wrong root-cause diagnosis on #1637."
  echo "================================================================================"
  echo
fi

echo "[pull] raw containers: $OUT_DIR/raw"
if (( DECODED_ANY )); then echo "[pull] decoded NDJSON:  $OUT_DIR/decoded"; fi
echo "[pull] done."
