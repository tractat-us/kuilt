def edit(p, pairs):
    s=open(p).read()
    for a,b in pairs:
        assert s.count(a)==1, (p,a)
        s=s.replace(a,b)
    open(p,'w').write(s)

edit('docs/heddle-design.md',[
("""Children the consumer creates while running hang under a group marked with
`template`, in their own id namespace (`pool/tenants/!c-7f3a`), so a runtime
name never collides with a declared one.""","""Children the consumer attaches while running hang under a group with a
`template`, in their own id namespace (`pool/tenants/!c-7f3a`), so an attached
name never collides with a declared one."""),
("""is met today by declared groups, which nest freely and reshape on a reload, and not
yet by runtime ones. A tree found at runtime two levels deep (say, a repository and""",
"""is met today by declared groups, which nest freely and reshape on a reload, and not
yet by attached ones. A tree found while running two levels deep (say, a repository and"""),
("""- On `ChildTemplate` it caps **each** attached child, not all of them together.
- **0 means pause:** picks stop under that node. It is not a problem.
- A negative value is the one new `TopologyProblem.Kind`.""",
"""- On a group's `template` (`ChildTemplate`) it caps **each** attached child, not
  all of them together; the group's own `maxConcurrent` caps them all together.
  A document writes the per-child ceiling inside the template, so it cannot be
  written on a group that has none (#3368):

  ```yaml
  - type: group
    name: repos
    maxConcurrent: 8                # all repos together
    template: { maxConcurrent: 2 }  # each attached repo
  ```

- **0 means pause:** picks stop under that node. It is not a problem.
- A negative value is the one new `TopologyProblem.Kind`. A negative
  `template.maxConcurrent` is reported at the group's address."""),
("""- `AT_CEILING` before `SUPPLY_EXHAUSTED`, and `POSITION_CEILING` after it
  (#3324, #3313).

#### Open

- A document's `runtimeChildrenMaxConcurrent` on a group that does not mark
  attached children is dropped without a word. Reporting it needs a second new
  `TopologyProblem.Kind`, and this section allows only one. Tracked by #3368.
""","""- `AT_CEILING` before `SUPPLY_EXHAUSTED`, and `POSITION_CEILING` after it
  (#3324, #3313).
- A per-child ceiling written beside the template rather than inside it: a
  document once had both a marker and a separate ceiling field, and dropped the
  ceiling without a word when the marker was missing. Nesting it in `template`
  makes that document impossible to write, so no second problem kind is needed
  (#3368).
"""),
])
