package us.tractat.kuilt.heddle

/** Marks the [draft] scopes, so a block inside a group cannot reach an enclosing group's members. */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class TopologyDraftDsl

public fun draft(root: String, block: TopologyDraft.() -> Unit): Topology = TODO("draft $root $block")

@TopologyDraftDsl
public sealed class TopologyDraft {

    public fun group(name: String, weight: Weight = Weight.ONE, block: Group.() -> Unit): Unit = TODO("$name $weight $block")

    public fun lane(name: String, weight: Weight = Weight.ONE): Unit = TODO("$name $weight")

    public class Group internal constructor() : TopologyDraft() {
        public fun runtimeChildren(weight: Weight = Weight.ONE): Unit = TODO("$weight")
    }
}
