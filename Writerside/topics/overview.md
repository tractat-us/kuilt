# kuilt

kuilt stitches peers together and keeps their shared data in sync—across
the room or across the web.

One Kotlin library spans phones, browsers, and servers. Start with a link
between two devices; add shared data and decisions as your app grows.

## First, connect

A chat needs a path between people. Through a server or straight to a nearby
phone, kuilt gives your app the same way to send and receive. Each kind of
link is a **fabric**.

[Connect two devices](getting-started.md), or
[try it on one computer](quick-start.md) with no network setup.

## Then, share what changes

Now both people send at once. The chat needs both messages, even if one
arrives late. Shared lists, counts, and notes have the same need.

[Replicated Data](crdt-overview.md) gives each device a copy that merges
changes when they can talk again. Choose the merge rules that fit your data.

## Agree when order matters

A game adds a wrinkle: two players cannot both take the next turn.
They need one agreed sequence of moves.

[Consensus](raft.md) keeps that sequence. Use it where the group must agree
before anyone acts.

## Find out what happened

When an app goes wrong on a phone with no signal, you still need the story.
kuilt can save notes on the device and send them when the network comes back.

[Observability](observability.md) follows those notes from the device to
your dashboard. [Testing](testing.md) shows how to try failures on your own
computer.

## Keep going

Use the parts you need on their own. See
[how connections work](contract.md), [choose a connection](fabrics.md), or
[browse the modules](modules.md) when you are ready to build.

In the **Playground**, we explore what else these parts can do.
[Warp](warp.md) shares a pile of work across devices.
[Heddle](heddle.md) gives each team its fair share of that work.
These parts work today, but their APIs can still change.
