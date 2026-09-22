# Fair share with Heddle

A roomful of devices shares a pile of work. Now add a wrinkle: Team A paid
for twice Team B's share. Heddle honours those shares and lends spare
capacity to whoever needs it.

> **Playground.** Heddle works today; its API can change. Use it on its own
> or to give [Warp](warp.md) jobs a fair share.

## Give each team a budget

Both teams want to resize photos. Heddle gives each an allowance to spend
on work. A team can split its share between quick previews and full-size
exports, so a background job need not starve someone's next click.

Your app chooses the unit: a small job, or a measure of its cost. Twice the
share does not promise twice the photos per second. Some photos take more work.

## Follow one photo

A device reserves ten units before it starts a photo. The job costs seven.
Heddle charges seven and frees the other three for more work.

![Ten units are reserved for one job; seven are spent and three become free again.](heddle-budget.svg)

Report that finish twice to the same node, and Heddle charges once.
Without enough allowance to start, the job waits.
Each device spends only the share it holds.

## Give each team its turn

Heddle tracks work as it hands out allowance. An eligible team that is
behind on its share gets the next turn. An idle team sits out.

Asking fastest should not mean taking every turn. Shares settle over time;
each small batch need not have the exact ratio.

## When the network splits

A device can keep spending the allowance it already holds. It cannot spend
another device's share just because that device has gone quiet.
When its share runs out, work waits.

This is the trade: keep the budget safe, even if part of it sits unused.
When the devices can talk again, they share their records and catch up.
Creating more allowance or changing the team structure needs an agreed decision.

## Open the books

[Inside Heddle](heddle-accounting.md) follows those ten units through the
budget, the queue, and a change of team. It explains what a lost connection
can delay and why a missing device's share cannot just be taken back.

[Follow a Warp job](warp-jobs.md) to see how a team label connects a photo
to its allowance. Automatic recovery of a crashed
device's budget is still future work.
