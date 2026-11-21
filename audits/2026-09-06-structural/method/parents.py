import json, re, collections, datetime
d=json.load(open('bodies.json'))
d={x['number']:x for x in d}
cut=datetime.datetime.fromisoformat('2026-07-27T00:00:00+00:00')
r=[x for x in d.values() if datetime.datetime.fromisoformat(x['createdAt'].replace('Z','+00:00'))>=cut]
r.sort(key=lambda x:x['number'])
print("since W31 with bodies:", len(r))

PAT = [
 (r'found while ([^.\n]{0,80}?)#(\d+)', 'found-while'),
 (r'(?:follow-?up|follow on) (?:to|from|of)\s*#(\d+)', 'followup'),
 (r'surfaced (?:by|while|during)[^.\n]{0,60}?#(\d+)', 'surfaced'),
 (r'the fix for #(\d+)', 'fix-for'),
 (r'spun out of #(\d+)', 'spun-out'),
 (r'split (?:out )?(?:from|of) #(\d+)', 'split'),
 (r'(?:discovered|noticed|uncovered|exposed) (?:while|by|during)[^.\n]{0,60}?#(\d+)', 'discovered'),
 (r'#(\d+)\s+(?:landed|shipped|added|introduced|left|created|made)', 'prior-change'),
 (r'(?:review of|reviewing) #(\d+)', 'review-of'),
 (r'part of #(\d+)', 'part-of'),
 (r'deferred (?:from|by|in) #(\d+)', 'deferred-from'),
 (r'(?:while|during) (?:landing|implementing|writing|fixing)[^.\n]{0,60}?#(\d+)', 'while-doing'),
 (r'(?:sibling|companion) (?:of|to) #(\d+)', 'sibling'),
 (r'raised (?:in|on|during)[^.\n]{0,40}?#(\d+)', 'raised-in'),
 (r'(?:PR )?#(\d+)(?:\'s)? (?:review|fix|guard|change)', 'refs-change'),
]
res={}
for x in r:
    txt = (x['title']+"\n"+x['body'])
    low=txt.lower()
    hits=[]
    for pat,kind in PAT:
        for m in re.finditer(pat, low):
            g=[g for g in m.groups() if g and g.isdigit()]
            if g: hits.append((kind,int(g[-1])))
    res[x['number']]=hits
spawned=[n for n,h in res.items() if h]
print("spawned-by-another:", len(spawned), "/", len(r), "=", round(100*len(spawned)/len(r),1),"%")
par=collections.Counter()
for n,h in res.items():
    for k,p in set(h):
        if p!=n: par[p]+=1
print("TOP PARENTS:")
for p,c in par.most_common(25):
    t=d.get(p,{}).get('title','(PR or older)')
    print(f"  #{p}  {c}  {t[:90]}")
json.dump({str(k):v for k,v in res.items()}, open('parents.json','w'))
