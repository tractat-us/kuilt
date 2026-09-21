# Fair share with Heddle

Two teams share a roomful of computers. One has paid for twice as much time.
While both teams have work waiting, it should get roughly twice the service.
When either team goes quiet, the other should be able to use the spare capacity.
Heddle keeps track of those shares.

> **Playground.** Heddle works today, but its API can change. It can manage
> a budget on its own or give [Warp](warp.md) jobs a fair share.

## Give each team a budget

Back in our photo room, both teams want to resize pictures. Heddle gives
each team an allowance to spend on work. A team can split its share among
smaller groups, such as quick previews and full-size exports.

Your app chooses what one unit means. It might be one small job or a measure
of its cost. A share of those units is not a promise about how many photos
finish each second. Some photos take more work than others.

## Follow one photo

Before a device starts, it sets aside enough allowance to pay for the job.
Suppose it reserves ten units. The photo costs seven. When the job ends,
Heddle charges seven and frees the other three for more work.

![Ten units are reserved for one job; seven are spent and three become free again.](heddle-budget.svg)

If the app reports that same finish twice to the same node, Heddle charges
it once. If there is not enough allowance to start, the job waits.
Each device spends only the share it holds.

## Give the next turn to the team that is behind

As work gets handed out, Heddle tracks how much each team has received.
It gives the next turn to an eligible team that is due more service.
A team with no work waiting does not compete for a turn.

This keeps a busy team from taking every turn just because it can ask fastest.
It also lets spare capacity flow toward work that can use it.
The shares settle over time; each small batch need not have the exact ratio.

## When the network splits

A device can keep spending the allowance it already holds. It cannot spend
another device's share just because that device has gone quiet.
If its own share runs out, some work has to wait.

This is the trade: keep the budget safe, even if part of it sits unused.
When the devices can talk again, they share their records and catch up.
Creating more allowance or changing the team structure needs an agreed decision.

## Open the books

[Inside Heddle](heddle-accounting.md) follows those ten units through the
budget, the queue, and a change of team. It explains what a lost connection
can delay and why a missing device's share cannot just be taken back.

[Follow a Warp job](warp-jobs.md) to see how a team label connects a photo
to its allowance. This link works today. Automatic recovery of a crashed
device's budget is still future work.
