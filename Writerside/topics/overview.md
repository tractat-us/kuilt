# kuilt

Your app runs on a phone and a laptop. Both people should see the same chat,
even if one loses the network for a while. In a game, both should agree on
whose turn comes next. kuilt gives your app the parts it needs to do this.

It is a Kotlin library for apps that span phones, browsers, and servers.
Start with a link between two devices. Add shared data or shared decisions
when your app needs them.

## First, connect

A chat starts with one device sending a message to another. kuilt gives your
app one way to send and receive, whether the link goes through a server or
straight to a nearby phone. We call each kind of link a **fabric**.

[Connect two devices](getting-started.md), or
[try it on one computer](quick-start.md) with no network setup.

## Then, share what changes

Now both people send a message at once. Each device needs to keep both edits
and show the same chat once they have caught up. The same need appears in
shared lists, counts, and notes.

[Replicated Data](crdt-overview.md) gives each device its own copy.
Those copies merge changes when they can talk again. You choose how each
kind of data handles edits made at the same time.

## Agree when order matters

A game adds a different need. Two players cannot both take the next turn.
They need one agreed sequence of moves, so each sees the same game.

[Consensus](raft.md) keeps that shared sequence. Use it for the parts of
your app where a choice must be agreed before you act on it.

## Find out what happened

When an app goes wrong on a phone with no signal, you still need the story.
kuilt can save notes on the device and send them when the network comes back.

[Observability](observability.md) follows those notes from the device to
your dashboard. [Testing](testing.md) shows how to try failures on your own
computer.

## Keep going

You can use the parts you need on their own. See
[how connections work](contract.md), [choose a connection](fabrics.md), or
[browse the modules](modules.md) when you are ready to build.

In the **Playground**, we explore what else these parts can do.
[Warp](warp.md) shares a pile of work across devices.
[Heddle](heddle.md) gives each team its fair share of that work.
These parts work today, but their APIs can still change.
