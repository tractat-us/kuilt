# Where the ideas come from

Fair sharing is an old problem with good answers. Network routers solved it
for packets, operating systems for processor time, and databases for
budgets that many writers spend at once. Heddle borrows from all three, and
this page says what it took from each, and what it changed.

Two kinds of source appear below. Some are **named in Heddle's own design
documents** as the source of a rule. Others are **background**: the family
the rule belongs to, cited so a reader can follow the idea back. Each entry
says which it is.

## Virtual time: weighted fair queueing

*Background.* The core trick is older than Heddle by three decades. Give
each claimant a clock that runs slower the more weight it has, and always
serve the one whose clock is furthest behind. Over time, each gets service
in proportion to its weight.

- A. Demers, S. Keshav, S. Shenker. *Analysis and Simulation of a Fair
  Queueing Algorithm.* ACM SIGCOMM 1989.
  [doi:10.1145/75246.75248](https://doi.org/10.1145/75246.75248)
- A. K. Parekh, R. G. Gallager. *A Generalized Processor Sharing Approach
  to Flow Control in Integrated Services Networks: The Single-Node Case.*
  IEEE/ACM Transactions on Networking 1(3), 1993.
  [doi:10.1109/90.234856](https://doi.org/10.1109/90.234856)

Heddle's **virtual time** is this clock: service issued to a child, divided
by its weight
([design §7.1](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#71-virtual-service)).

## Shares, tickets and strides

*Background.* Waldspurger and Weihl brought proportional share to processor
scheduling. Lottery scheduling draws a random ticket per turn; stride
scheduling makes the same promise deterministically, and nests it, so a
group's share can be split again among its members.

- C. A. Waldspurger, W. E. Weihl. *Lottery Scheduling: Flexible
  Proportional-Share Resource Management.* OSDI 1994.
  [usenix.org](https://www.usenix.org/conference/osdi-94/lottery-scheduling-flexible-proportional-share-resource-management)
- C. A. Waldspurger, W. E. Weihl. *Stride Scheduling: Deterministic
  Proportional-Share Resource Management.* MIT/LCS/TM-528, 1995.
  [PDF](https://www.waldspurger.org/carl/papers/stride-mit-tm528.pdf)
- C. A. Waldspurger. *Lottery and Stride Scheduling: Flexible
  Proportional-Share Resource Management.* PhD thesis, MIT/LCS/TR-667, 1995.
  [PDF](https://www.waldspurger.org/carl/papers/phd-mit-tr667.pdf)

Heddle is on the stride side of that line. Its picks use no randomness at
all, which is what lets every peer that holds the same books make the same
choice, and lets a test replay a run exactly. Its tree is a nested share in
the same spirit: each group is both a claimant and a scheduler for its own
children.

## Eligibility and deadlines: EEVDF

*Named in the design.* Heddle's selection rule is "EEVDF-inspired", in the
design's words: Stoica and Abdel-Wahab's split between **lag**,
**eligibility** and **virtual deadline**, and the insight that a group is
both schedulable and a scheduler
([design §7](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#7-the-policy-eevdf-over-edge-summaries)).
A child may be picked only when it is not ahead of its parent's weighted
average, and among those the earliest virtual deadline wins.

- I. Stoica, H. Abdel-Wahab. *Earliest Eligible Virtual Deadline First: A
  Flexible and Accurate Mechanism for Proportional Share Resource
  Allocation.* Technical Report TR-95-22, Old Dominion University, 1995.
  [PDF](https://people.eecs.berkeley.edu/~istoica/papers/eevdf-tr-95.pdf)

The design says plainly that this is not a reproduction of any kernel's
internals. What changed: positions are exact 128-bit counts on a per-child
grid rather than fractions, and the rule runs per fork of the tree.
[How the next turn is chosen](heddle-local-turns.md) gives the rule as
built.

## Sleepers and the front: the Linux schedulers

*Named in the design.* Linux's Completely Fair Scheduler, and its EEVDF
successor, face the question of a task that wakes after a long sleep. If
its clock stayed where it was, it would take the processor until it caught
up. CFS answers with `min_vruntime`, a front that only moves forward, and
seats a waking task near it. Heddle's seat for a newborn or returning child
started from that sketch, and its design records where Heddle had to
differ. CFS raises a task's clock to the front once, atomically, on a value
that already includes all its service. A peer writing a replicated seat may
not have seen all of a child's service yet, and an early design that took
the maximum on the seat alone counted that service twice
([seat design §5](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-seat-design.md#5-candidate-b--a-replicated-per-edge-seat-register)).
The shipped seat pairs each position with the issuance its writer had seen
([design §7.1](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#71-virtual-service)).
The no-idle-credit rule is in
[design §7.2](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#72-neutral-creation-and-no-idle-credit).

- *CFS Scheduler.* The Linux kernel documentation.
  [docs.kernel.org](https://docs.kernel.org/scheduler/sched-design-CFS.html)
- *EEVDF Scheduler.* The Linux kernel documentation.
  [docs.kernel.org](https://docs.kernel.org/scheduler/sched-eevdf.html)

## A budget many writers spend: replicated data types

*Named in the design.* The distributed half is built from kuilt's
replicated data types, which merge in any order and never count an update
twice. The ledger is a path-indexed generalisation of kuilt's
`BoundedCounter`, a counter whose total cannot be overspent even though
every replica spends locally
([design §4](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#4-the-entitlement-ledger--boundedcounter-grown-a-path-dimension)).

- M. Shapiro, N. Preguiça, C. Baquero, M. Zawirski. *Conflict-Free
  Replicated Data Types.* SSS 2011.
  [doi:10.1007/978-3-642-24550-3_29](https://doi.org/10.1007/978-3-642-24550-3_29)
- P. S. Almeida, A. Shoker, C. Baquero. *Delta State Replicated Data
  Types.* Journal of Parallel and Distributed Computing, 2018.
  [arXiv:1603.01529](https://arxiv.org/abs/1603.01529)
- V. Balegas, D. Serra, S. Duarte, C. Ferreira, M. Shapiro, R. Rodrigues,
  N. Preguiça. *Extending Eventually Consistent Cloud Databases for
  Enforcing Numeric Invariants.* SRDS 2015.
  [doi:10.1109/SRDS.2015.32](https://doi.org/10.1109/SRDS.2015.32)

The last is the bounded counter's own lineage. What Heddle added is the
path: a unit is minted at the root, delegated down a tree, and spent at a
leaf, and the same unit can never be in two places.

## What needs agreement: Raft

*Named in the design.* Spending needs no agreement. Creating supply,
changing the tree, and enrolling or departing a peer do, and those go
through kuilt's Raft
([design §9](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#9-the-consensus-seam--the-embroidery)).

- D. Ongaro, J. Ousterhout. *In Search of an Understandable Consensus
  Algorithm.* USENIX ATC 2014.
  [usenix.org](https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro)

## Considered, and set aside

*Named in the design.* Kleppmann and colleagues' move operation for
replicated trees ships in kuilt as `MovableTree`. Heddle's design rejects
it for the first version: it resolves concurrent moves silently, and a
scheduler has to surface those conflicts and drain the old edge instead
([design §5.4](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#54-movabletree-shipped-and-deliberately-not-used-in-v1)).
It is kept as the likely path for a later version.

- M. Kleppmann, D. P. Mulligan, V. B. F. Gomes, A. R. Beresford. *A Highly
  Available Move Operation for Replicated Trees.* IEEE Transactions on
  Parallel and Distributed Systems, 2021.
  [doi:10.1109/TPDS.2021.3118603](https://doi.org/10.1109/TPDS.2021.3118603)

## What is new

The design names three things as genuinely new
([design, *Recognition*](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md#recognition-most-of-this-is-already-on-the-shelf)):
the entitlement ledger, a small lifecycle register for the tree's edges in
which closing beats activating under merge, and the selection policy
itself. [How Heddle is verified](heddle-verification.md) shows how each is
checked.
