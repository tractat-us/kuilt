import re,sys,os,collections
files=[l.strip() for l in open(sys.argv[1])]
rows=[]
def mod(p): return p.split('/')[0]
def classify(ctx, kind, caught):
    c=ctx
    low=c
    # order matters
    if re.search(r'\b(decode|deserialize|decodeFrom|decodeToString|readInt|readHeaderOf|bytesOf|readFile|dictionaryFromTXTRecordData|Parser\.parse|parseModule|SignalingMessageCodec\.decode|toUtf8OrThrow|utf8ToString|Rational\.of|Weight\.of|encodeToByteArray\(throwOnInvalid)', low): return 'iv-decode'
    if re.search(r'\.(collect|onEach)\s*[({]|incoming\.collect', low): return 'vi-pump'
    if re.search(r'\b(sendTo|broadcast)\s*\(|\.send\(|deliver\(', low): return 'i-send'
    if re.search(r'\b(close|disconnect|stopListening|stopBrowsing|leave|unregisterService|destroy|unpin|dispose|removeSpoke|stop)\s*\(', low): return 'ii-close'
    if re.search(r'\b(Files\.|Native\.load|NSFileManager|nsdManager|m3_|Instance\.builder|instance\.export|wasmCompile|wifi\.|adapter\.|manager\.|future\.get|submit\(|api\.(start|connect)|startResolving|preallocate|RandomAccessFile|runWasmOpt|force\(|store\.(read|write|delete)|export\()', low): return 'v-platform'
    if re.search(r'\b(register|require\(|checkNotNull|error\()', low): return 'vii-invariant'
    return 'viii-other'
for f in files:
    try: txt=open(f).read()
    except: continue
    lines=txt.split('\n')
    for i,l in enumerate(lines):
        s=l.strip()
        if s.startswith('*') or s.startswith('//'): continue
        m=re.search(r'catch\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*([A-Za-z0-9_.]+)\s*\)',l)
        if m:
            caught=m.group(1).split('.')[-1]
            ctx='\n'.join(lines[max(0,i-8):i])
            ctx='\n'.join(x for x in ctx.split('\n') if not x.strip().startswith(('*','//')))
            rows.append((mod(f),'catch',classify(ctx,'catch',caught),caught,f,i+1))
        if re.search(r'runCatchingCancellable\s*[{(]',l) and 'RunCatchingCancellable.kt' not in f:
            ctx='\n'.join(lines[i:i+4])
            rows.append((mod(f),'rCC',classify(ctx,'rcc',''),'',f,i+1))
tab=collections.defaultdict(lambda: collections.Counter())
for m,k,c,ct,f,ln in rows: tab[m][c]+=1
order=['i-send','ii-close','iv-decode','v-platform','vi-pump','vii-invariant','viii-other']
print(f"{'MODULE':<22}"+''.join(f"{o.split('-')[0]:>6}" for o in order)+f"{'TOT':>6}")
tot=collections.Counter()
for m in sorted(tab):
    r=tab[m]; s=sum(r.values())
    if s==0: continue
    print(f"{m:<22}"+''.join(f"{r[o] or '':>6}" for o in order)+f"{s:>6}")
    for o in order: tot[o]+=r[o]
print(f"{'TOTAL':<22}"+''.join(f"{tot[o]:>6}" for o in order)+f"{sum(tot.values()):>6}")
