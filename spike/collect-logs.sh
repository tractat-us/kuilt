#!/bin/bash
# kuilt-nw connectivity-suite log collector (#1837 step 1).
#
# Pull every connected iPhone's spike trace off the device and merge them into ONE causally-ordered
# cross-device timeline. Run it after a two-phone run; it needs nothing running on the phones.
#
#   ./spike/collect-logs.sh
#
# Why the merge is the artifact, not a convenience: scenario 6's PASS is defined by an *asymmetry
# between the two phones* — one must say "MY outage", the other "THEIR outage". Two separate texts
# compared by eye is how that was judged before; one interleaved file makes it mechanical.
#
# Usage: collect-logs.sh [-o <output-dir>] [-b <bundle-id>] [-d <device-id>]...
#   -o  where to write (default: ./spike-logs/<UTC timestamp>)
#   -b  app bundle id (default: us.tractat.spike.nw)
#   -d  restrict to this device identifier; repeatable. Default: every connected iPhone.
#
# Safe to re-run: each run writes its own timestamped directory and pulls fresh copies. Nothing on the
# device is modified or deleted, so collecting twice is harmless — and collecting BEFORE you are sure
# you are done is the right instinct.
set -uo pipefail

BUNDLE_ID="us.tractat.spike.nw"
OUT_ROOT=""
WANTED=()

while getopts ":o:b:d:h" opt; do
  case "$opt" in
    o) OUT_ROOT="$OPTARG" ;;
    b) BUNDLE_ID="$OPTARG" ;;
    d) WANTED+=("$OPTARG") ;;
    h) sed -n '2,20p' "$0"; exit 0 ;;
    \?) echo "unknown option -$OPTARG (try -h)" >&2; exit 2 ;;
    :) echo "option -$OPTARG needs a value" >&2; exit 2 ;;
  esac
done

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT_ROOT:-$(pwd)/spike-logs/$STAMP}"
mkdir -p "$OUT" || { echo "❌ cannot create $OUT" >&2; exit 1; }
MERGED="$OUT/merged-timeline.log"

command -v xcrun >/dev/null 2>&1 || { echo "❌ xcrun not found — install Xcode command line tools" >&2; exit 1; }

# ── 1. discover ───────────────────────────────────────────────────────────────
# Never hardcode device ids; devicectl's JSON is the only supported machine interface (its own help
# says so — the table is for humans and its columns move).
echo "### discovering devices"
DEVJSON="$OUT/devices.json"
if ! xcrun devicectl list devices --quiet --timeout 60 --json-output "$DEVJSON" >/dev/null 2>&1; then
  echo "❌ 'xcrun devicectl list devices' failed — is Xcode installed and a device paired?" >&2
  exit 1
fi

# id<TAB>tag, one per line. An iPhone whose tunnel is 'unavailable' is paired-but-unreachable (a
# powered-off phone, or one that has never trusted this Mac) — listing it would only produce a
# confusing copy failure later, so it is filtered here and reported as skipped.
DEVICES="$(python3 - "$DEVJSON" <<'PY'
import json, re, sys
with open(sys.argv[1]) as fh:
    doc = json.load(fh)
for dev in doc.get("result", {}).get("devices", []):
    hw, conn, props = dev.get("hardwareProperties", {}), dev.get("connectionProperties", {}), dev.get("deviceProperties", {})
    if hw.get("platform") != "iOS":
        continue
    if conn.get("tunnelState") == "unavailable":
        continue
    tag = f"{props.get('name', 'iPhone')}_{hw.get('productType', 'unknown')}"
    tag = re.sub(r"[^A-Za-z0-9]+", "-", tag).strip("-")
    print(f"{dev['identifier']}\t{tag}")
PY
)" || { echo "❌ could not parse the devicectl device list" >&2; exit 1; }

if [ -z "$DEVICES" ]; then
  echo "❌ no reachable iPhone found." >&2
  echo "   Plug a phone in (or put it on the same network), unlock it, and trust this Mac." >&2
  echo "   'xcrun devicectl list devices' shows what this Mac can currently see." >&2
  exit 1
fi

# ── 2. pull ───────────────────────────────────────────────────────────────────
PULLED=0
FAILED=0
while IFS=$'\t' read -r DEV_ID TAG; do
  [ -n "$DEV_ID" ] || continue
  if [ ${#WANTED[@]} -gt 0 ]; then
    KEEP=0
    for w in "${WANTED[@]}"; do [ "$w" = "$DEV_ID" ] && KEEP=1; done
    [ "$KEEP" = 1 ] || continue
  fi
  DEST="$OUT/$TAG"
  mkdir -p "$DEST"
  echo "### pulling $TAG ($DEV_ID)"
  # The whole Documents directory in one call: that is both the suite's per-run `suite-*.log` files
  # AND SpikeNw's raw scenario-1 `nw.log`. Both are merged below.
  # --timeout so a phone that is paired but asleep on the network cannot hang the whole collection.
  if xcrun devicectl device copy from --quiet --timeout 120 --device "$DEV_ID" \
        --domain-type appDataContainer --domain-identifier "$BUNDLE_ID" \
        --source Documents --destination "$DEST" >/dev/null 2>&1; then
    N=$(find "$DEST" -type f -name '*.log' | wc -l | tr -d ' ')
    echo "  ✓ $N log file(s)"
    PULLED=$((PULLED + 1))
  else
    echo "  ⚠ copy failed — is the app installed and has it been run at least once?"
    FAILED=$((FAILED + 1))
  fi
done <<< "$DEVICES"

if [ "$PULLED" = 0 ]; then
  if [ "$FAILED" = 0 ]; then
    echo "❌ no device matched -d. Reachable ids:" >&2
    cut -f1 <<< "$DEVICES" | sed 's/^/   /' >&2
  else
    echo "❌ nothing pulled from any device ($FAILED failure(s))." >&2
    echo "   Is '$BUNDLE_ID' installed, and has the suite been run on it at least once?" >&2
  fi
  echo "   Merged timeline not written." >&2
  exit 1
fi

# ── 3. merge ──────────────────────────────────────────────────────────────────
# Two on-device line formats have to interleave:
#   * `2026-07-28T09:15:03.412Z <text>`  — SuiteLogCapture (the suite trace + the fabric log tee)
#   * `1.785198320532506E9 <text>`       — SpikeNw's nw.log (a Kotlin Double, hence the exponent form)
# Both are normalised to epoch-with-millis for sorting and re-rendered as ISO-8601 UTC for reading, so
# one column of timestamps means one thing on both phones. A line with no leading timestamp (a stack
# trace continuation) inherits the previous line's — it belongs to it.
echo "### merging"
ANNOTATED="$OUT/.annotated"
: > "$ANNOTATED"
WIDTH=0
while IFS=$'\t' read -r _ TAG; do
  [ ${#TAG} -gt "$WIDTH" ] && WIDTH=${#TAG}
done <<< "$DEVICES"

while IFS=$'\t' read -r DEV_ID TAG; do
  [ -n "$DEV_ID" ] || continue
  DEST="$OUT/$TAG"
  [ -d "$DEST" ] || continue
  find "$DEST" -type f -name '*.log' -print0 | while IFS= read -r -d '' f; do
    perl -e '
      use strict; use warnings; use POSIX qw(floor);
      use Time::Local qw(timegm);
      my ($tag, $width, $file) = @ARGV;
      open(my $fh, "<", $file) or exit 0;
      my $prev = 0; my $seq = 0;
      while (my $line = <$fh>) {
        chomp $line;
        my $epoch; my $rest = $line;
        if ($line =~ /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z\s(.*)$/s) {
          $epoch = timegm($6, $5, $4, $3, $2 - 1, $1) + ($7 ? "0.$7" : 0);
          $rest  = $8;
        } elsif ($line =~ /^([0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)\s(.*)$/s) {
          $epoch = 0 + $1;
          $rest  = $2;
        } else {
          $epoch = $prev;   # continuation: belongs to the line above it
        }
        $prev = $epoch;
        my $ms  = sprintf("%.3f", $epoch - floor($epoch)); $ms =~ s/^0//;
        my $iso = POSIX::strftime("%Y-%m-%dT%H:%M:%S", gmtime(floor($epoch))) . $ms . "Z";
        printf("%.6f\t%06d\t%s  %-*s  %s\n", $epoch, $seq++, $iso, $width, $tag, $rest);
      }
    ' "$TAG" "$WIDTH" "$f" >> "$ANNOTATED"
  done
done <<< "$DEVICES"

if [ ! -s "$ANNOTATED" ]; then
  echo "⚠ pulled files contained no log lines — nothing to merge." >&2
  rm -f "$ANNOTATED"
  echo "   raw pull is in: $OUT"
  exit 1
fi

{
  echo "# kuilt-nw connectivity suite — merged cross-device timeline"
  echo "# collected $(date -u +%Y-%m-%dT%H:%M:%SZ) · bundle $BUNDLE_ID · $PULLED device(s)"
  echo "# columns: <UTC timestamp>  <device>  <line>"
  echo "#"
  # -s (stable) + -k1,1n on the epoch: ties keep each device's own file order, which is the only
  # ordering that is actually known. -k2 would interleave two devices' sequence numbers, which mean
  # nothing across devices.
  sort -s -k1,1n "$ANNOTATED" | cut -f3-
} > "$MERGED"
rm -f "$ANNOTATED"

# ── 4. report ─────────────────────────────────────────────────────────────────
LINES=$(grep -vc '^#' "$MERGED" 2>/dev/null || echo 0)
echo
echo "### newest run per device"
while IFS=$'\t' read -r DEV_ID TAG; do
  [ -n "$DEV_ID" ] || continue
  NEWEST=$(find "$OUT/$TAG" -type f -name 'suite-*.log' 2>/dev/null | sort | tail -1)
  [ -n "$NEWEST" ] && echo "  $TAG → ${NEWEST#"$OUT"/}" || echo "  $TAG → (no suite-*.log — was the suite run on this phone?)"
done <<< "$DEVICES"
echo
[ "$FAILED" -gt 0 ] && echo "⚠ $FAILED device(s) failed to copy — see above"
echo "✅ $LINES lines from $PULLED device(s)"
echo
echo "MERGED TIMELINE:  $MERGED"
echo "raw per-device:   $OUT"
exit 0
