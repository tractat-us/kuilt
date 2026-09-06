#!/usr/bin/env bash
#
# Find in-tree comments that assert work is OUTSTANDING under an issue that is CLOSED.
#
# WHY THIS EXISTS
#   A comment naming `#N` asserts a world-state. When the issue closes, the comment
#   silently inverts: it now tells a reader that work is pending which is in fact
#   done, and it does so confidently. Two instances (#2419, #2634) were each found
#   by accident; the survey that followed (#2647) found four more, and two of those
#   were worse than dangling pointers — the surrounding *sentence* had become false.
#
# WHY THIS IS NOT A BUILD GUARD
#   The verdict needs GitHub's issue state, which no `check`-wired Gradle task can
#   resolve offline. And a stale citation is not a reason to block someone else's
#   merge — a required check that reddens whenever an unrelated issue closes pushes
#   the cost onto whoever opens the next PR, and gets muted within a week. So this
#   runs out of band on a schedule and opens a tracking issue: the
#   `skill-staleness.yml` shape. It is deliberately NOT part of `ci-required`.
#
# WHY THE VERB SUBSET, AND NOT THE OBVIOUS GREP
#   The obvious formulation — an openness word (`open`/`still`/`remains`) near a
#   `#N` — was MEASURED at ~57% precision on this tree (#2647): 3 of 7 live hits
#   were false positives, every one of them `open` attached to a *seam* or a *gap*
#   rather than to the issue ("for as long as the seam is open (#2449)"). Those are
#   correct PROVENANCE citations, which is the legitimate use, and a detector that
#   cries wolf on them gets ignored.
#
#   So the trigger is the VERBS instead. `tracked by`, `blocked on`, `deferred to`,
#   `stays open under` take an issue as their grammatical object and cannot be said
#   of a seam. All four of the survey's true positives are in this subset; none of
#   its three false positives is.
#
# WHY A TWO-LINE WINDOW
#   KDoc wraps at ~100 columns, so the verb and its number routinely land on
#   different lines — `kuilt-core/.../Seam.kt` carries "stays open under" / "#2642"
#   exactly that way. A single-line regex misses those silently, which is the worst
#   failure a detector can have. Each line is joined with its successor (comment
#   continuation markers stripped) before matching, and the reported line number is
#   the verb's.
#
# WHAT THIS CANNOT SEE — stated rather than implying completeness
#   - An outstanding-work claim phrased with any OTHER verb: "see #N for the rest",
#     "pending #N", "TODO(#N)", "follow-up in #N", "left to #N". Precision was
#     bought with recall, and this is the price.
#   - A citation split across more than two lines.
#   - A claim that is false while its issue is still OPEN — the #2644 shape, where
#     `Seam.kt` described an obligation the decorators had just invalidated. That is
#     the larger and more dangerous class, and nothing here touches it.
#   - Whether a citation to an OPEN issue is still accurate at all.
#   - A claim that names no issue number, or names one by title.
#   It reports a lexical signal filtered by issue state. It is a prompt to go read
#   the sentence, never a verdict on it.
#
# USAGE
#   .github/scripts/stale-citations.sh [<tree-ish>]
#     <tree-ish>  scan that tree instead of the working tree. This is what makes the
#                 POSITIVE CONTROL possible: run it against the commit before the
#                 fixes landed and confirm it names the known sites (#2647 item 4).
#   Env:
#     STALE_CITATIONS_NO_RESOLVE=1     stop after the lexical stage; print every
#                                      candidate, making no network calls.
#     STALE_CITATIONS_REPO=owner/name  override the repo used for issue resolution.
#
# EXIT
#   0  clean (or lexical-only mode)
#   1  at least one citation resolves to a CLOSED issue
#   2  usage / environment error

set -euo pipefail

TREEISH="${1:-}"

# Verbs that take an ISSUE as their object. Adding one here widens recall and costs
# precision; measure both before you do (see the header).
VERBS='(tracked (by|under)|blocked (on|by)|deferred to|stays open (under|in))'
# Up to 40 non-`#` characters may sit between the verb and its number. The #2647
# survey used the same window; widening it starts bridging unrelated sentences.
PATTERN="${VERBS}[^#]{0,40}#[0-9]+"

# An escape hatch for the genuine case: a citation that names a CLOSED issue on
# purpose, as provenance for where something was established or fixed. Same shape as
# the repo's other markers (`// ALLOW-realDispatcher:`, `// ALLOW-ise:`) — line-tight,
# and the reason is mandatory, because a blank reason is a claim that nobody checked.
# Matched on the verb's line or on the line joined to it.
#
# The reason must OPEN WITH A WORD, and that is not fussiness: the obvious spelling
# of "non-empty" — one non-whitespace character — accepts `<!-- ALLOW-staleCitation: -->`,
# because the comment terminator itself is that character. A probe caught exactly
# that here, which is the whole argument for testing a guard's own escape hatch
# rather than assuming it holds.
MARKER='ALLOW-staleCitation:[[:space:]]*[A-Za-z][A-Za-z0-9]{2,}'

# Scanned: live source and living docs.
#
# EXCLUDED, deliberately — and this records #2647 acceptance item 2 in the one place
# that cannot drift from the mechanism it governs. `docs/superpowers/**` and
# `docs/plans/**` are ARCHIVED design records: each states what was true when a
# decision was taken. Its citations are part of that record, so "correcting" one
# would falsify the document rather than repair it. An archived plan saying work was
# blocked on #779 is right about the day it was written, whatever #779 does after.
# They carry ~6 such citations today and are meant to keep them.
PATHSPECS=(
  '*.kt' '*.kts' '*.md' '*.yml' '*.yaml'
  ':(exclude)docs/superpowers/'
  ':(exclude)docs/plans/'
)

# The matching runs in awk, and `[^#]{0,40}` is an INTERVAL EXPRESSION, which not
# every awk implements — mawk, still the default `awk` on some Debian/Ubuntu images,
# historically treated `{0,40}` as literal characters. That failure is silent and it
# is the worst shape a detector can have: the regex simply never matches and the run
# reports a clean tree. So pick an awk and then PROVE it on a string that must match
# and a string that must not, refusing to run rather than reporting a false all-clear.
AWK="${STALE_CITATIONS_AWK:-$(command -v gawk || command -v awk || true)}"
[ -n "$AWK" ] || { echo "error: no awk on PATH" >&2; exit 2; }
awk_selftest() {
  local yes no
  yes=$(printf 'tracked by #1849\n' | "$AWK" -v pat="$PATTERN" '$0 ~ pat { print "hit" }')
  # 60 characters of filler puts the number outside the 40-char window.
  no=$(printf 'tracked by %s #1849\n' "$(printf 'x%.0s' $(seq 60))" \
        | "$AWK" -v pat="$PATTERN" '$0 ~ pat { print "hit" }')
  if [ "$yes" != "hit" ] || [ -n "$no" ]; then
    echo "error: ${AWK} does not honour interval expressions — every scan would be a false all-clear." >&2
    echo "       positive control='${yes:-<no match>}' negative control='${no:-<no match>}'" >&2
    exit 2
  fi
}
awk_selftest

repo() {
  if [ -n "${STALE_CITATIONS_REPO:-}" ]; then
    printf '%s\n' "$STALE_CITATIONS_REPO"
  else
    gh repo view --json nameWithOwner --jq .nameWithOwner
  fi
}

# --- Stage 1: lexical ------------------------------------------------------
# Emit `file<TAB>line<TAB>issue` for every candidate. `git grep -l` runs first so the
# awk join only touches files that already contain a verb somewhere.
candidates() {
  local files
  if [ -n "$TREEISH" ]; then
    files=$(git grep -l -I -E "$VERBS" "$TREEISH" -- "${PATHSPECS[@]}" 2>/dev/null | sed "s|^${TREEISH}:||" || true)
  else
    files=$(git grep -l -I -E "$VERBS" -- "${PATHSPECS[@]}" 2>/dev/null || true)
  fi
  [ -n "$files" ] || return 0

  while IFS= read -r f; do
    [ -n "$f" ] || continue
    {
      if [ -n "$TREEISH" ]; then
        git show "${TREEISH}:${f}" 2>/dev/null || true
      else
        cat "$f" 2>/dev/null || true
      fi
    } | awk -v file="$f" -v pat="$PATTERN" -v marker="$MARKER" '
      # `text` is line n joined to line n+1; `own` is how much of it line n
      # contributed. A match is attributed to the line its VERB starts on, so a
      # match living wholly in the joined successor is left to that line`s own
      # window instead of being reported twice.
      function flush(n, text, raw, own,   tmp, num, at, base) {
        if (raw ~ marker) return
        tmp = text; base = 0
        while (match(tmp, pat)) {
          at = base + RSTART
          num = substr(tmp, RSTART, RLENGTH)
          sub(/^.*#/, "", num)
          if (at <= own) print file "\t" n "\t" num
          base = base + RSTART + RLENGTH - 1
          tmp = substr(tmp, RSTART + RLENGTH)
        }
      }
      {
        cur = $0
        # Strip a comment-continuation prefix so a wrapped KDoc reads as one sentence.
        nxt = cur
        sub(/^[[:space:]]*(\*|\/\/|#|>)+[[:space:]]*/, "", nxt)
        if (NR > 1) flush(NR - 1, prev " " nxt, prevraw " " cur, length(prev))
        prev = cur; prevraw = cur
      }
      END { if (NR > 0) flush(NR, prev, prevraw, length(prev)) }
    '
  done <<< "$files" | LC_ALL=C sort -u
}

CANDIDATES=$(candidates)

if [ -z "$CANDIDATES" ]; then
  echo "No outstanding-work citations found."
  exit 0
fi

if [ "${STALE_CITATIONS_NO_RESOLVE:-}" = "1" ]; then
  echo "Candidates (lexical stage only, no issue state resolved):"
  printf '%s\n' "$CANDIDATES" | awk -F'\t' '{printf "  %s:%s  ->  #%s\n", $1, $2, $3}'
  exit 0
fi

# --- Stage 2: resolve issue state -----------------------------------------
REPO=$(repo)
declare -A STATE=()
STALE_ROWS=()
UNRESOLVED=()

while IFS=$'\t' read -r file line num; do
  [ -n "${num:-}" ] || continue
  if [ -z "${STATE[$num]:-}" ]; then
    # The issues endpoint answers for pull requests too, so a `#N` that is really a
    # PR resolves rather than 404ing.
    STATE[$num]=$(gh api "repos/${REPO}/issues/${num}" --jq '.state' 2>/dev/null || echo "unresolved")
  fi
  case "${STATE[$num]}" in
    closed) STALE_ROWS+=("${file}:${line}|${num}") ;;
    open)   ;;
    *)      UNRESOLVED+=("${file}:${line}|${num}") ;;
  esac
done <<< "$CANDIDATES"

TOTAL=$(printf '%s\n' "$CANDIDATES" | grep -c . || true)
echo "Scanned ${TOTAL} outstanding-work citation(s) in ${REPO}."

if [ "${#UNRESOLVED[@]}" -gt 0 ]; then
  echo "Could not resolve (not an issue or PR in ${REPO}):"
  for r in "${UNRESOLVED[@]}"; do echo "  ${r%|*} -> #${r#*|}"; done
fi

if [ "${#STALE_ROWS[@]}" -eq 0 ]; then
  echo "All cited issues are OPEN — no stale citation."
  exit 0
fi

echo "STALE — these assert outstanding work under a CLOSED issue:"
for r in "${STALE_ROWS[@]}"; do echo "- \`${r%|*}\` -> #${r#*|}"; done
exit 1
