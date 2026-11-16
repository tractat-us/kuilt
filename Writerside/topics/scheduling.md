# Sharing a busy machine

Ten teams share eight build machines. One team paid for twice the share of
another. Every time a machine frees up, someone has to say whose job runs
next. Heddle makes that call, fairly, inside your own program.

It keeps the promise over hours, not job by job. A team that sat idle does
not come back owed a pile of turns. A job that ran long pays for every
minute. And when a team has nothing to run, its share goes to whoever does.

> **Beta.** Heddle is built, documented and heavily tested, and its API can
> still change between releases. Its saved form is frozen at format 1: a
> later release that changes it will still read the one before.

The rest of this page walks the same path the section does:

1. **[Who goes next](#next)**, in one program with no network.
2. **[Wiring it in](#wire)** to a real service: a pool of CI runners.
3. **[Many machines, no boss](#peers)**: the same promise across a network.
4. **[Why you can trust it](#trust)**: the tests, and the research it rests on.

## 1. Who goes next {id="next"}

Draw a tree of who shares the machines and in what proportions. Each time a
machine is free, ask Heddle for a **lease**. It picks the group that is
furthest behind its share, sets aside an estimate of the time the job will
take, and hands back the lease. When the job ends, tell it how long the job
really took. It charges that, and puts any unused estimate back.

That is the whole loop: `acquire`, run, `complete`. Heddle never reads a
clock and never draws a random number. Every time is a number you pass in.
So the same calls in the same order make the same picks, on every platform
kuilt runs on.

[Fair share in one program](heddle-local.md) follows one build farm through
that loop, a change to its tree, a crash and a restart. Its companion pages
show [who goes next](heddle-local-preview.md),
[explain a wait](heddle-local-explain.md) in words a person can read, and
give [the exact rule](heddle-local-turns.md) with its measured fairness.

## 2. Wiring it in {id="wire"}

A CI service already has a queue, a pool of runners and a database. Heddle
does not replace any of them. It answers one question at one moment, "this
runner is free: whose job?", and keeps the books that answer depends on.

[Embedding Heddle](heddle-embedding.md) builds that service end to end:
teams signing up, Mac runners that only some teams can use, a ceiling per
team, a job that runs long, and a restart that loses a runner.

## 3. Many machines, no boss {id="peers"}

Now take away the one program. The runners are phones, laptops and servers,
each making its own calls, and the network sometimes splits them in half.

Heddle's distributed form keeps a shared record of who was given what, who
lent unused share onward, and who spent it. Each machine spends only the
share it already holds. A machine that loses touch keeps working with what
it has, and cannot spend anyone else's. When they meet again, the records
merge and nothing is counted twice.

[Fair share with Heddle](heddle.md) tells that story, and
[Inside Heddle](heddle-accounting.md) opens the books: the ledger, the
agreed decisions that add supply or change the tree, and what a lost
machine can and cannot delay.

## 4. Why you can trust it {id="trust"}

A scheduler earns trust the slow way. Heddle's picks use exact whole-number
arithmetic, with no floating point anywhere in the choice. A second,
independent implementation checks the picks. A pinned record of picks must
come out the same on every platform. Its fairness is measured over
thousands of seeded steps, and each bar it must stay under is written into
a test.

[How Heddle is verified](heddle-verification.md) lists those checks, each
next to the test that makes it. [Where the ideas come from](heddle-lineage.md)
names the research: weighted fair queueing, stride scheduling, EEVDF and the
Linux schedulers, and the replicated data types the shared record is made of.

## Where it runs, and the honest limits

Heddle runs on every kuilt target: JVM, Android, iOS, macOS and the browser.
The single-program engine, `LocalHeddle`, lives in `:kuilt-heddle-core`,
which needs no network and no coroutines. The distributed node lives in
`:kuilt-heddle`.

- **Fair over time, per fork.** Each fork in the tree keeps its own children
  near their weights. A job's place in line can differ from a strict
  round-robin, and a lane deep in the tree inherits the small gaps of every
  fork above it.
- **Estimates steer order.** A lease counts at its estimate until it
  completes. Good estimates give fairer turns.
- **One caller at a time.** `LocalHeddle` takes no locks of its own. Call it
  from one place, under your scheduler's lock.
- **A crashed peer's share waits.** In the distributed form, nothing takes
  back a silent machine's share automatically, since it might still be
  spending it.

Each limit has its arithmetic and its tracking issue on the page that
covers it.
