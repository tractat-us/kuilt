import json, re, datetime, collections
d=json.load(open('bodies.json'))
byn={x['number']:x for x in d}
cut=datetime.datetime.fromisoformat('2026-07-27T00:00:00+00:00')
R=[x for x in d if datetime.datetime.fromisoformat(x['createdAt'].replace('Z','+00:00'))>=cut]
R.sort(key=lambda x:x['number'])

# ordered rules: (class, regex on title, regex on title+body) - first match wins
def has(s, *pats): return any(re.search(p,s) for p in pats)

def cls(x):
    t=x['title'].lower(); b=(x['title']+' '+x['body']).lower()
    lab=set(x['labels'])

    # (k) process / CI / build mechanics
    if has(t, r'^build:', r'^ci:', r'^release:', r'^test-harness', r'gradle', r'\bcache\b', r'runner', r'workflow',
              r'agp\b', r'detekt', r'lint', r'ci lanes?', r'wall time', r'\bicE\b'.lower(), r'renovate'):
        if not has(t, r'vacuo|pinned by nothing|un-?pinned|asserts nothing'):
            return 'k'
    if has(t, r'reaping|worktree|devicectl|headless chrome|leaks a headless|apple-nightly|main is red|exit-143|action_required|github_token|wedges$'):
        return 'k'

    # (i) lexical-guard maintenance / guard blind spot
    if has(b, r'forbid[A-Z]', r'verifydoccitations|verifysamplelinks|verifymoduletable|verifyskilldescriptionbudget|verifytestresultparity|verifyseamharnesscoverage') \
       or has(t, r'build guard|lexical guard|guard\b.*(blind|false green|baseline|allow marker)'):
        if has(t, r'guard|forbid|verify|ratchet|allowlist|baseline|scanner|marker'):
            return 'i'

    # (h) stale doc / citation / claim invalidated
    if has(t, r'^docs?[:(]|^docs\b|kdoc claims|docstring claims|comment.*(claims|state|assert)|cookbook|module\.md|readme|claude\.md|citations?|guide|stale|still frames|has (?:remove|.*) backwards|module tables?'):
        return 'h'
    if has(b, r'claims .* but|documented as if|the doc(?:ument)? .* is (?:wrong|stale)') and has(t,r'claim|doc|comment|kdoc'):
        return 'h'

    # (a) test vacuity
    if has(t, r'vacuo|pinned by nothing|un-?pinned|unpinned|asserts neither|asserts nothing|never (?:executes?|runs?|exercises?|reaches|produce)|'
              r'cannot fail|passes (?:vacuously|with|proving nothing)|proving nothing|deletable|no (?:test|property|coverage|conformance)|'
              r'is unasserted|are unasserted|untested|uncovered|no .*coverage|mutation|does not (?:assert|require|pin|test|exercise)|'
              r'tck|conformance suite|conformance:|satisfies .* for free|blind spot|self-test|is enforced by (?:nothing|one)|'
              r'enforced by nothing|caught by nothing|verified by nothing|tested by nothing|executed by no test|zero ci lanes|'
              r'silent skip|reads the host only|host only|structurally vacuous|tautology|generator can never|never reaches?|'
              r'unmeasured|unmeasurable|cannot (?:catch|see|express|detect)|floor is unenforced|exercises? (?:none|a different)'):
        return 'a'
    if 'conformance' in t or 'tck' in t: return 'a'
    if has(t, r'^test[:(]|^crdt-test|^kuilt-test|^warp-test|test\(|@sample|assertall|test flake|flaky|load-sensitive'):
        if has(t, r'flake|flaky|under load|load-sensitive|dies with|hangs?\b|timeout'):
            return 'c'
        return 'a'

    # (c) hang / flake / timeout under load
    if has(t, r'flake|flaky|under load|load-sensitive|uncompletedcoroutineserror|timeout.*(ceiling|budget)|hang|stall|wedge.*load|'
              r'contended|saturat|headroom|margin on macos|self-skips? as pass|spins? the'):
        return 'c'

    # (d) cancellation discipline
    if has(b, r'cancellationexception|minted cancellation|callee-minted|withtimeout|ensureactive|noncancellable|runcatching'):
        if has(t, r'cancel|withtimeout|runcatching|unshielded|best-effort|swallow|teardown|shielded'):
            return 'd'

    # (f) unvalidated peer-supplied wire input / fixed width
    if has(t, r'unvalidated|unclamped|un-?authenticated|forge|forgeable|malformed|any length|nonce|attacker|peer-chosen|peer-supplied|'
              r'peer-controllable|no cap on|uncapped|no sanity bound|overflow|oom|spoof|hijack|dispossession|displaces it|'
              r'no provenance|unauthoriz|admits p-1|fixed widths? (?:are|is)|decode a nonce|self-asserted'):
        return 'f'
    if has(t, r'undecodable|decode') and has(b, r'kills the node|permanently deaf|no signal'):
        return 'f'

    # (e) seam lifecycle / roster / state race
    if has(t, r'\bseam\b|roster|peers\b|torn|woven|collapse|close\(\)|sendto|_state|statef?low|check-then-|outside (?:the|any) lock|'
              r'unguarded|race|double-dial|link\b|weav|lifecycle|dedup.*link|scopedcloseable|onclose'):
        return 'e'
    if has(t, r'partition|reconnect|window|liveness|presence|heartbeat|departure|farewell|rejoin|resume'):
        return 'e'

    # (j) spec-conformance / algorithm defect
    if has(t, r'raft|§5|§6|§7|§8|§13|§14|leader|election|term|snapshot|installsnapshot|appendentries|membership|quorum|readindex|'
              r'crdt|lattice|associat|commutat|converge|merge|delta|orset|ormap|lwwmap|mvregister|rga|fugue|dot|counter|'
              r'heddle|ledger|conservation|entitlement|relocation|mint'):
        return 'j'

    # (b) cross-target determinism / canonical encoding
    if has(t, r'canonical|cross-target|golden vector|byte-identical|hashset|non-?canonical|wasm.*results|native'):
        return 'b'

    # (m) API design smell
    if has(t, r'no way to|cannot (?:decline|express|state|observe)|has no (?:teardown|health surface|failure channel|logger|hook)|'
              r'defaults to the value|nullable|knob|drops? (?:deliverypolicy|the payload budget)|silently drops|substitute the confident default'):
        return 'm'

    # (l) feature
    if 'epic' in lab or has(t, r'^epic|^spike|adopt|add (?:a|the)|introduce|support|extract|migrate|promote|new '):
        return 'l'
    return 'n'

cnt=collections.Counter(); byclass=collections.defaultdict(list)
for x in R:
    c=cls(x); x['cls']=c; cnt[c]+=1; byclass[c].append(x)
for c,n in cnt.most_common(): print(c,n)
json.dump({x['number']:x['cls'] for x in R}, open('cls.json','w'))
with open('byclass.txt','w') as f:
    for c in sorted(byclass):
        f.write(f"\n===== {c}  ({len(byclass[c])}) =====\n")
        for x in byclass[c]:
            f.write(f"{x['number']} {'O' if x['state']=='OPEN' else 'C'} {x['title'][:130]}\n")
