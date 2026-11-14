def edit(p, pairs):
    s=open(p).read()
    for a,b in pairs:
        assert s.count(a)==1, (p,a)
        s=s.replace(a,b)
    open(p,'w').write(s)

edit('docs/agent-cookbook/heddle.md',[
("""a lane, or at an attached child of a group with a template: a group that happens to have nothing attached,
such as a group with a template before its first attached child, is never leased, and `admit` and `explain`""",
"""a lane, or at an attached child of a group with a template: a group that happens to have nothing
attached, such as a group with a template before its first child is attached, is never leased, and
`admit` and `explain`"""),
("""in your own loop beside the engine: set `maxConcurrent` on that group, lane or template (`0`
pauses it).""","""in your own loop beside the engine: set `maxConcurrent` on that group or lane, or inside a group's
`template` to cap each attached child on its own (`0` pauses it)."""),
])
edit('Writerside/topics/heddle-local.md',[
("""A group marked with `template()` is one whose children your program names
while it runs: one per customer, say, all at the same weight.""",
"""A group with a `template()` takes children your program attaches while it
runs: one per customer, say, all at the same weight."""),
("""on attached children it caps each child on its own.""","""in a group's template it caps each attached child on its own."""),
("""Forks open and close while the farm runs, so the tree cannot list them. A group
marked with `template()` takes children your program names as it goes:""",
"""Forks open and close while the farm runs, so the tree cannot list them. A group
with a `template()` takes children your program attaches as it goes:"""),
])
edit('kuilt-heddle-core/module.md',[
("""- **No more than N of these at once.** A `maxConcurrent` on a group, a lane or a
  template is a ceiling:""","""- **No more than N of these at once.** A `maxConcurrent` on a group, a lane or a
  group's template is a ceiling:"""),
])
