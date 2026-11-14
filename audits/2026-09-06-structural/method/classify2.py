import json, re, datetime, collections
d=json.load(open('bodies.json'))
byn={x['number']:x for x in d}
cut=datetime.datetime.fromisoformat('2026-07-27T00:00:00+00:00')
R=[x for x in d if datetime.datetime.fromisoformat(x['createdAt'].replace('Z','+00:00'))>=cut]
R.sort(key=lambda x:x['number'])
def has(s,*p): return any(re.search(q,s) for q in p)

def cls(x):
    t=x['title'].lower(); b=(x['title']+' '+x['body']).lower(); lab=set(x['labels'])
    # k: build/CI/process mechanics
    if has(t, r'^build[:(]|^ci[:(]|^release[:(]|gradle|agp |detekt|android lint|\blint\b|renovate|runner|workflow|wall time|'
              r'build cache|ci lanes|apple-nightly|main is red|exit-143|action_required|github_token|worktree|devicectl|'
              r'headless chrome|:spike|jdk|karma|dokka does not|migrate off writerside|reaping'):
        return 'k'
    # i: lexical guard maintenance
    if has(b,r'forbid[A-Z][a-z]') or has(t,r'verifydoccitation|verifysamplelink|verifymoduletable|verifyskilldescription|verifytestresultparity|verifyseamharness'):
        if has(t,r'guard|forbid|verify|ratchet|allowlist|baseline|scanner|marker|enforce|build failure|catalogscanner|elision|citations?|uncited'):
            return 'i'
    if has(t,r'^guard |build guard|lexical guard|shape a: lexical'): return 'i'
    # s: agent-skill / routing surface
    if has(t,r'kuilt-primitives|skill'): return 's'
    # h: stale doc / claim / citation
    if has(t,r'^docs?[:(]|^docs\b|kdoc (?:claims|link|says|has)|docstring claims|cookbook|module\.md|readme|claude\.md|'
             r'guide|comments? (?:still )?(?:state|claim|assert)|still frames|module tables?|has .* backwards|'
             r'@sample|documents two meanings|no tracking issue|assert work is outstanding|is closed'):
        return 'h'
    # b: cross-target determinism / canonical encoding
    if has(t,r'canonical|golden vector|cross-target|byte-identical|hashset|not reproducible across targets|'
             r'wasm.*(no|zero) (?:results|tests)|silently produce no wasm|encodes? non-canonic|merge-order dependent'):
        return 'b'
    # a: test vacuity / conformance gap
    if has(t,r'vacuo|pinned by (?:nothing|neither|only)|un-?pinned|unpinned|asserts? (?:neither|nothing)|'
             r'never (?:executes?|runs?|exercises?|reaches?|produces?|fires?|called)|cannot fail|passes (?:vacuously|with|proving)|'
             r'proving nothing|deletable|is unasserted|are unasserted|untested|uncovered|has no (?:test|property|coverage|conformance|harness)|'
             r'no (?:test|property|conformance|coverage|.*harness)\b|mutation|tck|conformance|suite|'
             r'satisfies .* for free|blind spot|enforced by (?:nothing|one|no)|caught by nothing|verified by nothing|'
             r'tested by nothing|executed by no test|zero ci lanes|silent skip|(?:reads|asserted on) the host only|'
             r'tautology|can never produce|unmeasured|unmeasurable|cannot (?:catch|see|express|detect|attribute)|'
             r'exercises? (?:none|a different)|does not (?:assert|require|pin|test|exercise|round-trip)|'
             r'fires 1 run in|too thin|frequency floor|self-skips? as pass|is not (?:pinned|probed)|only the leaf half|'
             r'not (?:probed|pinned)|floor is unenforced|is a flag nothing reads|blind to it'):
        return 'a'
    if has(t,r'^test[:(]|^crdt-test|^kuilt-test|^warp-test|^conformance|test\(|assertall|coverage'):
        if has(t,r'flake|flaky|under load|load-sensitive|dies with|hangs?\b'): return 'c'
        return 'a'
    # c: hang/flake/load
    if has(t,r'flake|flaky|under load|load-sensitive|uncompletedcoroutineserror|hang|stall|contended|saturat|headroom|'
             r'2× margin|spins? the|virtual-time|only under load'):
        return 'c'
    # d: cancellation discipline
    if has(b,r'cancellationexception|minted cancellation|callee-minted|ensureactive|noncancellable|runcatching|withtimeout'):
        if has(t,r'cancel|withtimeout|runcatching|unshielded|best-effort (?:close|teardown)|swallow|shielded|teardown'):
            return 'd'
    # f: unvalidated peer input / wire invariant
    if has(t,r'unvalidated|unclamped|un-?authenticated|forge|forgeable|malformed|any length|nonce|attacker|peer-chosen|'
             r'peer-supplied|peer-controllable|no cap on|uncapped|no sanity bound|oom |spoof|hijack|dispossession|'
             r'displaces it|no provenance|unauthoriz|admits p-1|fixed widths?|decode a nonce|self-asserted|'
             r'bounds a joiner-asserted|assert any composite peerid|undecodable|reserve is unenforced|never bounded|'
             r'validates .* per-chunk|no gate to refuse|exceed the batch bound|is unenforced against'):
        return 'f'
    # o: silent failure / observability gap
    if has(t,r'silent|silently|is invisible|unobservable|no signal|no diagnostic|no attribution|attribution lost|'
             r'no health surface|reads zero|misleading|no logger|unreadable|logs a (?:warn|stack trace) per|'
             r'fails silently|nothing reds|no way to (?:observe|alarm)|reports success while|is a lower bound'):
        return 'o'
    # e: seam lifecycle / roster / concurrency race
    if has(t,r'\bseam\b|roster|\bpeers\b|torn|woven|collapse|close\(\)|sendto|_state|stateflow|check-then-|'
             r'outside (?:the|any) lock|unguarded|\brace\b|double-dial|dedup|weav|lifecycle|scopedcloseable|onclose|'
             r'mutates outside|read-modify-write|no write mutex|encodes under lock|reassembl|watchdog|'
             r'partition|reconnect|window|liveness|presence|heartbeat|departure|farewell|rejoin|resume|admission|admit|'
             r'link\b|pumps?\b|collector|teardown|leaks? (?:its|the)'):
        return 'e'
    # p: performance / cost
    if has(t,r'o\(|θ\(|per-record|per-entry|wire cost|halve|whole-buffer|materialises|blocks up to|serialised|'
             r'ships? (?:the )?(?:whole|o\()|broadcast the entire|batch the write|bound the total size|'
             r'unbounded channel|tax|re-derives|recomputation|double their opaque payload|reduce build'):
        return 'p'
    # q: durability / storage
    if has(t,r'durablestore|storekey|renaming|power loss|persisted|flush|msync|sent-set|indexeddb|store:'):
        return 'q'
    # j: algorithm / spec defect
    if has(t,r'raft|§|leader|election|term |snapshot|installsnapshot|appendentries|membership|quorum|readindex|'
             r'crdt|lattice|associat|commutat|converge|merge|delta|orset|ormap|lwwmap|mvregister|rga|fugue|dot |counter|'
             r'heddle|ledger|conservation|entitlement|relocation|mint|anti-entropy|quilter|version.?vector|gauge'):
        return 'j'
    # m: API design smell
    if has(t,r'no way to|cannot (?:decline|express|state|observe|receive)|has no (?:teardown|failure channel|hook|reopen)|'
             r'defaults to the value|nullable|drops? deliverypolicy|drop deliverypolicy|substitute the confident default|'
             r'value classes|erase to any|two visibilities|splitting the enum|knob'):
        return 'm'
    if 'epic' in lab or has(t,r'^epic|^spike|adopt|introduce|support for|extract |promote |^kuilt-bolt \d|backend —'):
        return 'l'
    return 'n'

# duplication flag
DUP = [r'same (?:class|shape|defect|bug) as', r'the other half', r'one of (?:three|four|two|five)', r'\bboth\b .*(backend|fabric|path|site)',
       r'(?:second|third|fourth|fifth) (?:site|instance|strand|module)', r'still live after', r'remaining .*sites',
       r'\bsweep\b', r'independently', r're-derive', r'reintroduc', r'the #\d+ shape', r'#\d+\'s shape', r'shares #\d+',
       r'\bport \w+\'s\b', r'twin of', r'sibling of', r'\bN sites\b', r'\d+ (?:more|other|residual) ', r'three more',
       r'the same (?:concept|defect|shape)', r'each of the (?:three|four)', r'all (?:three|four|five)',
       r'fails the #\d+ .*obligation', r'#\d+ (?:closed|fixed) .* in one of', r'the class', r'same defect']
def dup(x):
    b=(x['title']+' '+x['body']).lower()
    return any(re.search(p,b) for p in DUP)

cnt=collections.Counter(); byc=collections.defaultdict(list); ndup=0
for x in R:
    x['cls']=cls(x); x['dup']=dup(x); cnt[x['cls']]+=1; byc[x['cls']].append(x); ndup+=x['dup']
NAMES={'a':'test vacuity / conformance gap','b':'cross-target determinism & canonical encoding','c':'hang/flake/load-sensitivity',
'd':'cancellation-exception discipline','e':'seam lifecycle / roster / concurrency race','f':'unvalidated peer input / wire invariant',
'h':'stale doc/claim/citation','i':'lexical-guard maintenance','j':'algorithm/spec defect (Raft/CRDT/heddle)','k':'build/CI/process mechanics',
'l':'feature/enhancement','m':'API design smell','n':'other','o':'silent failure / observability gap','p':'performance & wire cost',
'q':'durability/storage correctness','s':'agent-skill routing surface'}
tot=len(R)
print(f"total since W31: {tot}   duplication-flagged: {ndup} ({100*ndup/tot:.0f}%)")
print(f"{'cls':4}{'name':46}{'n':>5}{'open':>6}{'dup':>5}  examples")
for c,n in cnt.most_common():
    xs=byc[c]; op=sum(1 for y in xs if y['state']=='OPEN'); dp=sum(1 for y in xs if y['dup'])
    ex=' '.join('#'+str(y['number']) for y in xs[:3])
    print(f"{c:4}{NAMES[c][:45]:46}{n:>5}{op:>6}{dp:>5}  {ex}")
json.dump({str(x['number']):[x['cls'],x['dup']] for x in R}, open('cls2.json','w'))
with open('byclass2.txt','w') as f:
    for c in sorted(byc):
        f.write(f"\n===== {c} {NAMES[c]} ({len(byc[c])}) =====\n")
        for x in byc[c]:
            f.write(f"{x['number']} {'O' if x['state']=='OPEN' else 'C'} {'DUP' if x['dup'] else '   '} {x['title'][:125]}\n")
