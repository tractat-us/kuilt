# Warp

You have a thousand photos to resize and a roomful of devices that could help.
Instead of making one laptop do it all, you want each device to take some
photos and send back the results. That is what Warp is for.

> **Playground.** Warp works today, but its API can change. Try it out before
> you build something that depends on it staying the same.

## Share the pile

Each photo becomes a job on a shared list. Every device sees that list and
works out which jobs belong to it. There is no central server handing out
each job.

When a device finishes a photo, it puts the answer on a shared board. The
other devices pick up the result as their copies catch up. Your app can
read the answers from its own copy.

![A shared photo list feeds two devices, which send their results to one shared board.](warp-work.svg)

The parts may sound familiar. kuilt already has ways to connect devices,
share a list, and merge answers. Warp puts those parts together so your app
can add jobs and read results.

## When a device goes quiet

Suppose a phone loses its connection halfway through a photo. Another
device can pick up its work. But the phone may still be running: losing
contact does not mean it stopped.

That means **a job can run more than once**. The answer board keeps one
result per job, but it cannot undo work outside Warp. Resizing the same
photo twice can be harmless. Sending the same email twice is not.

Choose jobs that are safe to repeat. For work that needs an agreed order,
Warp also has a path where the group agrees before a device acts.
[Following a job](warp-jobs.md) explains both paths and their limits.

## When another team joins

Now a second team adds its photos. The devices can share the work, but who
should get how much time? If one team has twice the share, it should get
roughly twice the service while both have work waiting.

That is [Heddle's part of the story](heddle.md). Warp chooses where a job
runs. Heddle controls how much work each team may start.

## Take a closer look

- [Follow a job](warp-jobs.md) from the shared list to its answer, through a
  lost connection and a retry.
- [Plan the work](warp-planning.md) to do less of it before the devices need
  to agree on a result.
- [Make each job faster](warp-compiler.md) by letting one device improve a
  program and share that copy with the others.

All three have working code. The wider
[vision](https://github.com/tractat-us/kuilt/blob/main/docs/warp-vision.md)
asks how close this could feel to running code on one computer.
That larger promise remains an idea to explore.
