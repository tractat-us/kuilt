import re,sys,collections
files=[l.strip() for l in open(sys.argv[1])]
CE={'CancellationException','TimeoutCancellationException'}
def mod(p): return p.split('/')[0]
def cls(ctx):
    low=ctx
    if re.search(r'\b(decode|deserialize|decodeFrom|decodeToString|readInt|readHeaderOf|bytesOf|readFile|dictionaryFromTXTRecordData|Parser\.parse|parseModule|toUtf8OrThrow|utf8ToString|Rational\.of|Weight\.of)\b', low): return 'iv'
    if re.search(r'\.(collect|onEach)\s*[({]', low): return 'vi'
    if re.search(r'\b(sendTo|broadcast)\s*\(|\.send\(|deliver\(', low): return 'i'
    if re.search(r'\b(close|closeNow|disconnect|stopListening|stopBrowsing|leave|unregisterService|destroy|dispose|detachPly|discard)\s*\(', low): return 'ii'
    if re.search(r'\b(Files\.|Native\.load|NSFileManager|nsdManager|m3_|Instance\.builder|instance\.export|wasmCompile|wifi\.|adapter\.|manager\.|future\.get|\.submit\(|api\.|startResolving|preallocate|RandomAccessFile|runWasmOpt|\.force\(|store\.(read|write|delete)|exporter\.export|capture\.capt|handle\.stop|rollSegment|adoptSegments|reopen|allocate)', low): return 'v'
    if re.search(r'\b(register|checkNotNull)\s*\(', low): return 'vii'
    return 'viii'
rows=[]
for f in files:
    try: txt=open(f).read()
    except: continue
    L=txt.split('\n'); i=0
    prev_chain_end=-99
    for i,l in enumerate(L):
        s=l.strip()
        if s.startswith('*') or s.startswith('//'): continue
        m=re.search(r'catch\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*([A-Za-z0-9_.]+)\s*\)',l)
        if m:
            t=m.group(1).split('.')[-1]
            # a chain arm: the previous non-comment code line before this catch ends with '}' AND
            # we saw a catch within 12 lines -> continuation
            cont = (i-prev_chain_end)<=14 and prev_chain_end>0
            prev_chain_end=i
            if t in CE:
                rows.append((mod(f),'CE-plumbing',t,f,i+1)); continue
            if cont:
                rows.append((mod(f),'chain-arm',t,f,i+1)); continue
            ctx='\n'.join(x for x in L[max(0,i-9):i] if not x.strip().startswith(('*','//')))
            rows.append((mod(f),cls(ctx),t,f,i+1))
        if re.search(r'runCatchingCancellable\s*[{(]',l) and 'RunCatchingCancellable.kt' not in f:
            rows.append((mod(f),cls('\n'.join(L[i:i+4])),'rCC',f,i+1))
tab=collections.defaultdict(collections.Counter)
for m,c,t,f,ln in rows: tab[m][c]+=1
order=['i','ii','iv','v','vi','vii','viii','chain-arm','CE-plumbing']
hdr=['i-send','ii-close','iv-dec','v-plat','vi-pump','vii-inv','viii-oth','chain','CE-plumb']
print(f"{'MODULE':<21}"+''.join(f"{h:>10}" for h in hdr)+f"{'TOT':>6}")
tot=collections.Counter()
for m in sorted(tab):
    r=tab[m];s=sum(r.values())
    print(f"{m:<21}"+''.join(f"{r[o] or '.':>10}" for o in order)+f"{s:>6}")
    for o in order: tot[o]+=r[o]
print(f"{'TOTAL':<21}"+''.join(f"{tot[o]:>10}" for o in order)+f"{sum(tot.values()):>6}")
import json
json.dump([list(r) for r in rows], open(sys.argv[2],'w'), indent=0)
