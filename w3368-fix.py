base='kuilt-heddle-core/src/commonMain/kotlin/us/tractat/kuilt/heddle/'
def edit(name, pairs):
    p=base+name
    s=open(p).read()
    for a,b in pairs:
        assert s.count(a)>=1, (name,a)
        s=s.replace(a,b)
    open(p,'w').write(s)

edit('TopologyLowering.kt',[
("//   attached child           \"<parent>/!<escaped name>\"", "//   attached child          \"<parent>/!<escaped name>\""),
(""" * declares, or a child the consumer created at runtime beneath a group with a template.""",
 """ * declares, or a child the consumer attached while running beneath a group with a template."""),
(""" * [GroupPath.Declared] it does not declare, or a [GroupPath.Attached] whose group is not marked
 * for attached children. Never throws.""",""" * [GroupPath.Declared] it does not declare, or a [GroupPath.Attached] whose group has no
 * template. Never throws."""),
("""    // `pathOf` puts a runtime segment only at depth two or deeper, so `below` holds its group.""",
 """    // `pathOf` puts an attached segment only at depth two or deeper, so `below` holds its group."""),
("""    // Rebuilding the id checks the root, and that the path is declared or marked here.""",
 """    // Rebuilding the id checks the root, and that the path is declared, or under a template, here."""),
(""" * @throws IllegalArgumentException if [group] does not name a group marked with
 *   [template][Topology.Group.template], or [name] is empty.""",""" * @throws IllegalArgumentException if [group] does not name a group with a
 *   [template][Topology.Group.template], or [name] is empty."""),
("""/** An attached child's [id], its [parent] group's id, and the [marker] that group carries. */""",
 """/** An attached child's [id], its [parent] group's id, and the [template] that group carries. */"""),
(""" * The attached child [name] of the group at [group], checked once: [group] names a marked declared
 * group and [name] is non-empty.""",""" * The attached child [name] of the group at [group], checked once: [group] names a declared group
 * with a template and [name] is non-empty."""),
(""" * Attached children are not lowered; the consumer creates them while running.""",
 """ * Attached children are not lowered; the consumer attaches them while running."""),
(""" * A group id read back into names: [names] runs from the root down, and [runtime] says the last""",
 """ * A group id read back into names: [names] runs from the root down, and [attached] says the last"""),
(""" * The template of the declared group at [path], read from the root down, or `null`
 * when [path] names no declared group or the group is not marked. Never throws, so the diff can ask""",
 """ * The template of the declared group at [path], read from the root down, or `null`
 * when [path] names no declared group or the group has none. Never throws, so the diff can ask"""),
])
