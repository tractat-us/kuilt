import json,re,datetime,sys
d=json.load(open('issues.json')); d.sort(key=lambda x:x['number'])
def wk(x):
    dt=datetime.datetime.fromisoformat(x['createdAt'].replace('Z','+00:00')); return dt.isocalendar()[1]
def show(name, pat, exclude=None):
    print(f"\n### {name}")
    hits=[x for x in d if re.search(pat,x['title'],re.I) and not (exclude and re.search(exclude,x['title'],re.I))]
    for x in hits:
        print(f"  W{wk(x)} #{x['number']} {'O' if x['state']=='OPEN' else 'C'} {x['title'][:112]}")
    print(f"  -- {len(hits)} total, {sum(1 for h in hits if h['state']=='OPEN')} open")

show("d cancellation", r'cancellation|runcatching|withtimeout|unshielded|noncancellable|best-effort close|minted')
show("b canonical/cross-target", r'canonic|golden vector|cross-target|byte-identical|hashset|reproducible across')
show("f wire/fixed-width/unvalidated", r'unvalidated|fixed width|fixed-width|any length|nonce|unclamped|uncapped|forge')
