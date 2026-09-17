import json,re,datetime
d=json.load(open('issues.json')); d.sort(key=lambda x:x['number'])
def wk(x): return datetime.datetime.fromisoformat(x['createdAt'].replace('Z','+00:00')).isocalendar()[1]
def show(name,pat,ex=None,limit=None):
    print(f"\n### {name}")
    h=[x for x in d if re.search(pat,x['title'],re.I) and not(ex and re.search(ex,x['title'],re.I))]
    for x in (h[-limit:] if limit else h):
        print(f"  W{wk(x)} #{x['number']} {'O' if x['state']=='OPEN' else 'C'} {x['title'][:110]}")
    print(f"  -- {len(h)} total, {sum(1 for y in h if y['state']=='OPEN')} open")
show("e SeamState gate / unguarded flow / check-then-act", r'seamstate|mutablestateflow|check-then|unguarded|outside the lock|outside any lock|read-modify-write|no write mutex')
show("e roster collapse / peers on tear", r'collapse|roster|peers\b.*(tear|torn|close)|selfid')
show("a conformance suite vacuity", r'conformance|tck\b')
show("h doc citation enforcement", r'citation|verbatim|@sample|uncited|verifydoc|module\.md|cookbook|claude\.md')
show("i guards", r'forbid[A-Z]|lexical guard|build guard|guard:')
show("c flake/load", r'flake|flaky|under load|load-sensitive|uncompletedcoroutines|hang')
