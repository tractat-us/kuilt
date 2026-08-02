# kuilt-nw connectivity suite — field guide (#1467)

Two phones, no Mac. One person taps **Host**, the other taps **Join**. The app runs a five-scenario
battery against the real `kuilt-nw` fabric, shows a pass/fail matrix, and produces a plaintext report
you **Share** or **Copy** and text back. The point: reproduce the adverse-network failures (coffee-shop
captive Wi-Fi, airplane, Wi-Fi-off/cellular-on, two SSIDs) *where they actually happen*.

Two more scenarios, **6 and 7, run on their own**, and in both you deliberately switch one phone's radio
off and back on. They are the only ones that need you to do something mid-run.
[Scenario 6](#scenario-6-the-airplane-mode-run-1712) checks that a phone which loses its own network says
*"I went offline"* rather than blaming the other phone.
[Scenario 7](#scenario-7-the-blip-the-other-phone-never-notices-1637) checks something narrower and
nastier: a drop so brief the other phone never even notices it — which used to kill the connection stone
dead. A fix for that has since landed, but **no hardware run has yet caught the fix actually working**,
and until one does the bug is not closed. Scenario 7 is how that gets settled either way, so its verdict
is unusually strict: it says PASS only when it *watched the fix run*, and says so in plain words when a
run proved nothing.

It works. Its first real two-phone run found a bug that made roughly **one room in eight permanently
unable to connect** — silently, with no error — that every automated test had passed straight over.
[What it found](#what-it-found) tells that story; it is the best argument for running this in odd places.

## Running it in the field

1. Both phones have the app installed (TestFlight or a cabled install beforehand).
2. Stand in the weird place. Put the phones in the network state you want to test (toggle Wi-Fi,
   airplane mode, join different SSIDs, etc.).
3. On one phone tap **Host**; on the other tap **Join**. Order doesn't matter for scenarios 2–5.
4. Watch the matrix fill in. The soak (scenario 5) takes ~2 minutes, so the whole run is ~3–4 min.
5. When it finishes, tap **Share report** (or **Copy**) and send the text back.

That's it. There is no Mac in the loop. Then, if you have another few minutes and a free hand,
run [scenario 6](#scenario-6-the-airplane-mode-run-1712) — it is a separate pair of buttons, about three
minutes — and [scenario 7](#scenario-7-the-blip-the-other-phone-never-notices-1637), which is another
pair below it and takes about four (most of it spent waiting, deliberately).

## What each scenario proves

| # | Scenario | What a PASS means |
|---|----------|-------------------|
| 1 | Raw NW round-trip | Raw Network.framework P2P connects + round-trips (the transport control). Join reports RTT; host confirms it echoed an inbound frame. |
| 2 | Fabric Seam weave | The real `appleNwLoom(...).weave` reaches `Woven` with `peers == 2` — the fabric layer, not just raw sockets. |
| 3 | Election establish | `SeamRoomFactory.electLobby` → the elected host (`min(peerId)`) runs `start()`, the other runs `awaitRoom()`, both adopt a `Room`. This is the `#1466` lobby path. |
| 4 | Teardown + reconnect | The host drops the link. The **host** sees its own `close()` latch `Torn`; the **joiner** sees the recoverable re-form — `Woven → Weaving` *and* `peers` collapsing to just itself ([why they differ](#scenario-4-the-two-sides-expect-different-things)). Both then re-weave on a second service type. |
| 5 | Soak (~2 min) | Continuous round-trip stays healthy: RTT distribution (min/p50/p95/max) with few/no stalls. This is where an AWDL data-path stall (the MC failure mode) would show up as a FAIL. |
| 6 | Local-fabric outage *(separate buttons — you toggle Airplane Mode)* | **The same outage read two opposite ways.** The phone you switched off says *my* network died: `localFabric` → `Unavailable`, a `LocalFabricLost`, and every `Partitioned`/`HostLost` it emits tagged `Unavailable`. The phone you left alone says *they* went away: its own `localFabric` stays `Available` and the `Partitioned` it emits for the vanished peer carries that `Available` tag. A short outage keeps the seat; a long one expires it, and the switched-off phone *still* blames itself. |
| 7 | Sub-timeout blip *(separate buttons — one Airplane Mode toggle)* | **A drop too brief for the other phone to notice must still recover.** The outage has to land between 3 and 15 seconds: long enough that the offline phone's own connection dies, short enough that the other phone never sees a gap. You flick the toggle on and straight back off — the radio supplies the rest. PASS = the **recovery machinery visibly did the thing under test**, not merely that the room looked fine ([why that distinction is the whole scenario](#what-the-results-mean)); a FAIL is the connection dying about a minute later, which is [#1637](https://github.com/tractat-us/kuilt/issues/1637) itself. After the flick, **leave both phones alone for about another minute and a half** — the phone watches its entire reconnect budget before it will judge anything. The bottom of the band is a [deliberate test setting](#how-the-band-was-widened) (the shipping value is 10s). |

Every report is prefixed with the **environment** captured from `nw_path_monitor`
(`path=satisfied ifaces=[wifi,cell,…] expensive=… constrained=…`) so a failing report is
self-describing — you can read "join FAILed with Wi-Fi off, path unsatisfied" without a Mac.

### Scenario 4: the two sides expect different things

**If you change scenario 4, read the `NwSeam` contract first.** This trips people up, so it is worth
stating plainly: one drop, two peers, and they are contractually *required* to observe different things
(`NwSeam`, "Peer loss is recoverable — re-form, don't tear", #1513).

- **Host** — it is the one that called `close()`, and a close *decision* is terminal by definition. Its
  seam latches `SeamState.Torn` and stays there. Nothing is waited for; the signal is its own.
- **Joiner** — losing its last remote is **not** terminal, because nothing has decided anything: the peer
  may simply be about to come back. The seam goes `Woven → Weaving`, resets `peers` to `{selfId}`, keeps
  `incoming` open, and waits for `NwLoom` to redial. `Torn` latches on *only* an explicit consumer
  `close()` or the initial `weave` timeout; it "is never a consequence of peer loss".

So the joiner has no terminal signal to wait for, and a scenario that waits for one on that side cannot
pass — it can only spend the whole timeout and report the absence. What the joiner asserts instead is the
**pair** `Weaving` *and* `peers == {selfId}`, checked together:

- `Weaving` alone does not exclude a seam that never wove at all, nor the brief moment during a peer
  *gain* where the roster has grown but the state has not yet flipped.
- `peers == {selfId}` alone is the seam's own value before it ever wove.

The two together say *this seam saw its last remote go, and re-formed rather than died* — but **only
given that it had provably wound up `Woven` first**. That precondition is not decoration: `Weaving` +
`peers == {selfId}` is *also* precisely the seam's initial state, so the pair on its own is not
self-sufficient. The suite therefore captures leg 1's `Woven` confirmation into `wovenA` and ANDs it
into both roles' verdicts. Drop it and a leg-1 weave that stopped blocking would turn this scenario
into a silent guaranteed PASS — a worse failure than the guaranteed FAIL it replaced.

The pair is matched on a combined view of both flows rather than "wait for the state, then read the
roster", because the eviction writes the roster and the state under one lock and a later read could
catch a roster a redial had already re-grown.

The two roles therefore also PASS with different words — `close latched Torn` on the host,
`peer-loss re-form seen` on the joiner. Two reports that read identically would hide the asymmetry.

## Scenario 6: the airplane-mode run (#1712)

### The idea, in one paragraph

Two phones are talking. You switch one phone's radio off. Both phones notice the silence — but they
should describe it *differently*, because from where each one is standing, different things happened.
The phone you switched off should say **"I lost my network."** The phone you left alone should say
**"the other one went away."** Same silence, two correct-but-opposite readings. Until recently a phone
could only ever say the second thing, so a phone that lost its *own* Wi-Fi told its user the other
person had left. This scenario is how we check that it now tells the truth.

You have to do the switching yourself. iOS gives an app no way to touch Airplane Mode, and this is the
one test where the radio really has to go away — so a person has to flip it. The phone tells you exactly
when.

### What to tap, and when to flip

You need two phones and about three minutes. Decide up front which phone is going offline.

1. On the phone you want to **keep online**, tap **Host · S6 stay up**.
2. On the phone that will **go offline**, tap **Join · S6 go offline**. (These two buttons are the
   bottom row — the `S4 only` row above them is a different diagnostic.)
   *Both phones must use the matching button.* If you both tap Host, nothing connects and the report
   says so in those words.
3. Wait a few seconds. Both phones show an orange banner. Follow it.
4. The offline phone will say **"AIRPLANE MODE ON now"**. Swipe into Control Centre and turn it on.
5. It will then say **"AIRPLANE MODE OFF now"** after about eight seconds. Turn it back off.
   *Being slow is fine* — the phone measures how long the outage actually lasted and tells you. Anything
   up to about thirty seconds still counts as the "short" outage. Past that the seat is genuinely
   allowed to expire, and the phone says so in the trace rather than blaming the library for it.
6. It will say **"AIRPLANE MODE ON again — and LEAVE IT ON until this phone tells you otherwise."**
   Turn it on and **wait**. It will take about a minute. Don't turn it back off early; the phone is
   waiting for its own seat in the room to expire, and it will tell you the moment it has.
7. When it says **"AIRPLANE MODE OFF now — that's the last toggle"**, turn it off. Done.
8. **Share or Copy the report from BOTH phones.** They say different things on purpose — one report
   alone cannot show the asymmetry.

Meanwhile the online phone asks you to do nothing at all, twice. Don't touch its network; that is the
whole point of it.

### What a PASS means on each phone

Both must pass, and they pass for opposite reasons:

**The phone you switched off** (`role=join S6-ONLY`):

```
[6] Local-fabric outage    PASS    2.4m  MY outage both times: Lost + Unavailable tag, short recovered, long self-attributed
```

It saw its own `localFabric` go `Unavailable`, got a `LocalFabricLost`, and tagged every
`Partitioned`/`HostLost` it emitted `Unavailable`. The short outage recovered inside the reconnect
window with its seat intact; the long one outlasted the window, the seat expired — and it *still* said
"my network died" rather than "the host is gone". That last part is the whole feature.

**The phone you left alone** (`role=host S6-ONLY`):

```
[6] Local-fabric outage    PASS    2.4m  THEIR outage both times: Partitioned tagged Available, mine stayed Available
```

Its own `localFabric` never left `Available`, and both `Partitioned` events it emitted for the vanished
peer carried that `Available` tag — "they went away", not "you are offline". After the short outage it
held the seat and welcomed the peer back; after the long one it let the seat expire.

### Reading a FAIL

Everything is in the report; you never need a Mac. A FAIL line names the observed value, not just the
disappointment:

```
[6] Local-fabric outage    FAIL   96.4s  long: HostLost tagged localFabric=Available — expected Unavailable. MY radio was off (72.1s outage), so this phone is blaming the host for its own outage
```

That is exactly the bug #1712 fixed, caught on hardware. The mirror-image failure on the other phone
reads:

```
[6] Local-fabric outage    FAIL   84.0s  short: Partitioned tagged localFabric=Unknown(no path observer) — expected Available. MY radio was never touched, so this event must read as 'they went away'
```

Under the matrix, the hop trace carries the whole timeline — every event with its tag, every
`localFabric` transition, and the roster with each member's liveness by peer id:

```
· [6] Local-fabric outage
    role=join svc=_ksuite6._tcp side=DROPPED detect=5s window=1m
    admitted peer=a91f2c04 role=Joiner t=1840ms
    baseline mine=Available side=DROPPED t=1851ms
    SAY t=1852ms | AIRPLANE MODE **ON** now, on THIS phone. Hold it ~8s; …
    short: armed mine=Available discarded=[] t=1853ms
    short: mine→Unavailable(path unsatisfied) t=6420ms
    short: mine→Available after 14.7s (window 1m)
    short: DONE outage=14.7s events=[LocalFabricLost(path unsatisfied,at=…),Partitioned(a91f2c04,LinkTimeout,mine=Unavailable(path unsatisfied),at=…),…]
      t=6420ms mine=Unavailable(path unsatisfied)
      t=21150ms roster=[a91f2c04:Connected]
```

Three verdicts, not two:

- **PASS** — the reading was right on this phone.
- **FAIL** — an outage happened and this phone read it wrong. A real defect.
- **SKIP** — nothing was observed, so nothing was tested. Almost always "Airplane Mode never actually
  went on". The SKIP text says which wait ran out, how long it waited, and what the value was instead.
  The other SKIP you can cause yourself: turning the radio off *before* the phone asks. Each phase
  starts by throwing away everything that happened before the prompt — otherwise an earlier Wi-Fi
  hiccup could be mistaken for the outage under test — so a phone that is already offline when asked
  says "my localFabric read … at the start of this phase" and measures nothing. Wait for the prompt.
  One SKIP is worth knowing about: on the **online** phone, "no Partitioned … cross-check the DROPPED
  phone's report". That phone has no information about the other one, so it genuinely cannot tell "they
  never went offline" from "I failed to notice" — which is why you send back both reports. If the
  offline phone shows a measured outage and this one says SKIP, the online phone *did* fail to notice,
  and that is a real failure.

### Why the timings are what they are

The scenario runs with a tighter heartbeat than the default (`interval` 1.5 s, `timeout` 5 s,
`reconnectWindow` 60 s):

- **5 s to notice** instead of the default 15 s. A connection whose path drops is given 10 s to get it
  back before the link is torn down, so an ~8 s outage never gets far enough to look like a lost peer —
  the only way either phone notices is the missed heartbeats. At the default 15 s it would not notice
  even those (15 > 10 > 8), so *neither* half of the asymmetry would be tested and the scenario would
  skip forever. 5 s is still three missed pings, and the scenario-5 soak measures a healthy link at
  p95 ≈ 30 ms, so it will not trip on a good day.
- **60 s of grace** (the default, kept deliberately) is what makes ~8 s "short" and the second outage
  "long". It also sets how long the long outage lasts: the offline phone waits for its own window to
  expire — about 65 s — before asking for the radio back, so **the one interval that has to overrun is
  never on your stopwatch**.

Everything else is generous on purpose. You get two minutes to find the toggle after each prompt. A
scenario that FAILs because someone took twelve seconds instead of eight is worse than no scenario.

### Why there is no automatic version

Tempting idea: instead of killing the radio, just tear the socket — scenario 4 already does that, and no
human is needed. It would not test the same thing, and it would quietly test the *wrong* thing.

`localFabric` moves for exactly one reason: the fabric's `nw_path_monitor` says the device's network path
went away. Closing a connection doesn't touch the path, so after a socket tear `localFabric` still reads
`Available` on **both** phones — no `LocalFabricLost` fires, and both sides' `Partitioned` carries the
`Available` tag. That is the correct answer for a socket tear (nobody's radio died) and the *opposite* of
what this scenario exists to prove. An "automatic scenario 6" would therefore be a green light for a
lane #1712 never touched, sitting in the matrix next to the one that matters. Scenario 4 already covers
the socket-tear lane, honestly labelled. So: the radio, or nothing.

`nw` is also the *only* fabric where any of this is observable. Every fabric without a live OS path
observer reports `FabricAvailability.Unknown` — "cannot tell" — which is a first-class third answer, not
a gap. That is precisely why this check has to happen on two real phones.

## Scenario 7: the blip the other phone never notices (#1637)

### The idea, in one paragraph

Two phones are talking. You switch one phone's radio off for a moment and switch it straight back on.
The whole gap lasts about ten seconds — long enough that the offline phone's own connection gives up
and has to be rebuilt, but *too short for the other phone to notice anything at all*. It was never
waiting long enough to get worried. So when the first phone comes back and says "I'm back, let me in",
the second phone answers, quite reasonably, "back from what? You never left." The first phone asks
again. And again. It keeps asking for a whole minute, and then gives up and declares the session dead —
over a ten-second hiccup that neither phone had any real trouble with. That is the bug. **This
scenario is how we catch it on real phones, and today it is expected to FAIL.**

### Why scenario 6 can't show it

Scenario 6 is tuned to notice an outage after five seconds, which is *faster* than the time a
connection is given to recover on its own. So in scenario 6 the other phone always notices first, and
everything then works the ordinary way. There is simply no gap for this bug to live in. Scenario 7
gives the other phone fifteen seconds before it worries, and shortens the recover-on-its-own window at
the same time — which opens a twelve-second gap. Anything the network is really down for between three
and fifteen seconds lands in it.

That is also why the two scenarios can't be merged: they need opposite settings.

### How the band was widened

The bottom edge of that band — three seconds — is **not** what the library does in a real app. Shipping
behaviour gives a connection whose network vanished a full **ten seconds** to come back before tearing
it down, and that is what every other scenario, and every app built on this fabric, uses.

Scenario 7 alone turns it down, by passing `wovenPathGrace = 3s` when it builds its fabric. The reason
is purely ergonomic: the *top* of the band can't be raised (see below), so the bottom is the only edge
that moves, and at the shipping value the target is five seconds wide and has to be aimed through about
nine seconds of radio lag you don't control. Almost every attempt missed.

Turning it down changes *when* the offline phone starts trying to get back in — not what happens once
it does. The other phone is untouched, the offline phone still can't do anything until its radio
returns, and the behaviour under test is unchanged. So a shorter fuse buys a wider target without
softening the test.

### What to tap, and how long to hold

You need two phones and about four minutes. Decide up front which phone is going offline.

1. On the phone you want to **keep online**, tap **Host · S7 stay up**. Then leave it completely alone —
   don't touch it, and **don't close the app**. It has to stay reachable the entire time, because the
   other phone's whole test is coming *back* to it. It ends by itself as soon as the other phone
   finishes (usually about two minutes), and gives up after five minutes at the outside; both
   are normal, not a hang.
2. On the phone that will **go offline**, tap **Join · S7 go offline**. (Bottom row of buttons.)
   *Both phones must use the matching button* — one Host, one Join.
3. Wait a few seconds for them to find each other, then follow the orange banner on the offline phone.
4. It will say **"AIRPLANE MODE ON now"** — and to keep your thumb on the toggle. Turn it on and leave
   your thumb there.
5. **About a second later** it says **"AIRPLANE MODE OFF — NOW"**. Turn it straight back off. It really
   is that quick: on, then immediately off, one flick. That's your only job.
6. Wait — **about another minute and a half**, and it will look like nothing is happening. That is
   deliberate. The connection usually comes back within seconds, and the bug this scenario is about
   kills it *a minute after that*, so a phone that stopped watching early would call every run a
   success. The banner tells you how long is left. Leave both phones alone until the matrix row
   appears on each, and **don't close either app** — closing one mid-wait destroys the run.
7. **Share or Copy the report from BOTH phones.**

**Why such a short hold?** The outage is much longer than your thumb is. Turning Airplane Mode off
doesn't put you back on Wi-Fi — the radio has to find the network again first, and on the one run we
have measured that took **8.7 seconds** on its own (a 15.0s hold produced a 23.7s outage). The radio,
in other words, supplies nearly the whole outage by itself: a one-second flick measures about ten
seconds, which sits in the 3–15s target with about seven seconds of room below it and five above.

The phone times the actual outage itself, from the moment the network really went away to the moment it
really came back, and judges on that, not on the prompt. If it lands outside the band the phone says
**SKIP** and tells you which way you missed:

- *too short* → the offline phone's connection never actually died, so nothing was tested. Hold longer.
- *too long* → the other phone noticed, and then everything works the ordinary way. Off faster.

Neither is a failure of anything. Just run it again — and expect to.

### Why the band's top edge can't move

Only the bottom edge was ever adjustable, and it has now been adjusted. The top has not, and can't be:

- The **top** is not the fifteen-second "notice" setting at all — it is the point where the *other*
  phone's TCP connection to a vanished peer dies of its own timeout. On the first hardware run that
  happened at **~18.6 seconds** (one observation, not a constant — it will move with the network), and
  it fired *regardless* of the fact that phone was configured to wait 30 seconds before worrying. That
  is why the setting was pulled back to 15s: a band whose top half is physically unreachable just
  invites out-of-band runs. **Raising the "notice" setting cannot buy more room.**
- The **bottom** (3s) is how long the fabric gives a connection whose network vanished before tearing it
  down. It used to be a hard 10s inside `kuilt-nw`; it is now a parameter on the fabric, and scenario 7
  is the only thing that passes anything other than the shipping 10s. See
  [how the band was widened](#how-the-band-was-widened).

So the target went from five seconds wide to twelve, aimed through the same ~9s of radio lag — the flick
lands around ten seconds, which is roughly in the middle. Misses are still possible (the lag varies, and
nobody has characterised how much), but a re-run should now be the exception rather than the norm.

If it still misses too often, in rough order of cost:

1. **Retry the blip inside one run** — flick, measure, and if it misses the band, prompt for another
   toggle rather than ending the scenario. Turns three runs into one, without widening anything.
2. **Calibrate first**: one throwaway toggle to measure *this* phone's restore lag, then aim the real
   hold at the band using that number instead of the 8.7s we measured once.
3. **Lower the bottom edge again** — there is room between 3s and zero, though below a couple of seconds
   the fabric starts tearing links over ordinary Wi-Fi hiccups and the scenario stops resembling the
   real world.

### What the results mean

**On the phone you switched off** (`role=join S7-ONLY`) — this is the one that matters:

```
[7] Sub-timeout blip       FAIL   88.1s  blip: HostLost 63.2s after the radio died, on a 10.2s outage that sat INSIDE the (3s, 15s) repro interval — reason=Refused(code=resume-window-not-yet-open,retryable=true), mine=Available. This IS #1637, discriminated on the reject code: …
```

**That FAIL is the expected result on a build without the #1637 fix, and it is the point of the
scenario.** It is not a broken test and not a bad run — it is the bug, caught on real hardware, with
the measured outage and the exact failure reason attached so the report can be pasted straight into
the issue. On a build *with* the fix, a PASS looks like this — and note what it quotes:

```
[7] Sub-timeout blip       PASS  104.3s  #1637 FIX OBSERVED — the resume lane resolved on the dwell after a 10.2s blip inside (3s, 15s): 'resume.no-op host=a91f2c04 roomId=… reason=host-never-partitioned dwellMs=15000' 27.4s after the radio died, no HostLost through the whole 1m 28s observation, host back Connected …
```

#### The PASS quotes the machinery, not the mood

That is not decoration. A verdict here has to survive one specific trap, and the suite fell into it on
2026-07-28: it reported a clean `PASS 14.8s … Recovered(…) 8.5s after the radio died, no HostLost, host
Connected` on a build that *contained* the fix — and the fix had never run. The connection had simply
healed on its own before the recovery machinery got anywhere, and the phone stopped watching the moment
things looked fine.

Two things were wrong, and both are fixed:

- **It stopped watching too early.** The failure this scenario hunts does not arrive promptly; it
  arrives when the reconnect budget runs out, about a minute after the radio died. Judging at fifteen
  seconds meant most of the dangerous minute went unwatched. The phone now watches the **whole** budget
  (about a minute and a half) before saying anything — which is why step 6 asks you to wait, and why a
  PASS row now takes ~100 s instead of ~15 s. A genuine FAIL still arrives as fast as it ever did.
- **"Nothing went wrong" is not evidence that anything went right.** The event a healthy room emits
  when the *fix* completes and the event it emits when the connection merely came back on its own are
  **the same event** — there is no way to tell them apart from the outside. So the scenario now watches
  the recovery machinery's own trace instead, and a PASS is granted only when it saw the fix conclude.

It also now asks two separate questions where it used to ask one: **did the recovery machinery start**,
and **did it finish**. Those are not the same, and running them together is what let a run that never
started look like one that finished. A run where the room survived but the machinery never concluded is
reported as **SKIP — NOT EXERCISED**, not as a PASS:

```
[7] Sub-timeout blip       SKIP  104.1s  NOT EXERCISED — the room survived a 6.2s blip inside (3s, 15s) but the resume lane never resolved in 1m 28s: no resume.no-op, no resume.ok, no HostLost. This run says NOTHING about #1637. The resume lane was NEVER entered … Hold LONGER and re-run. Any Recovered(441485b2) here is NOT evidence of the fix — the detector emits the identical event on an ordinary recovery …
```

and the two shapes of that ask for opposite things, so the report says which:

- **never entered** — the blip was mis-aimed (too short, or the link healed before the connection was
  torn down). Hold longer and re-run.
- **entered but never finished** — the machinery genuinely ran and did not conclude inside its whole
  budget. Do *not* just re-run: that is a bug worth filing on its own.

Either way it is honest, it is actionable, and it is **not** a validation of the fix. #1637 stays open
until a run produces the PASS above.

**Only one shape of failure is #1637**, and the scenario checks for it precisely: a `HostLost` whose
reason is `Refused` carrying the code `resume-window-not-yet-open`. That code means the other phone was
*answering* — over and over, "not yet, you never left" — which is the entire bug. Any other `HostLost`
is a different problem and reports as its own **SKIP**, not as a FAIL:

```
[7] Sub-timeout blip       SKIP   72.0s  blip: HostLost 70.0s after the radio died with reason=WindowExpired … That is **not** the #1637 signature … This run's resume was never answered at all. seam=Weaving … the seam never got back to Woven, so no Resume was ever sent. Likely causes, in order: this phone never re-wove (the STAY-UP phone stopped advertising or was closed, …)
```

That one means nothing ever got through — usually because the other phone wasn't there any more. Check
how long the stay-up phone says it stayed up, and re-run. (This exact case is what the first hardware
run produced, and the old code reported it as "#1637 confirmed" while asserting three things that had
not happened.)

**On the phone you left alone** (`role=host S7-ONLY`):

```
[7] Sub-timeout blip       PASS   96.2s  never partitioned a91f2c04 while staying up 91.0s (ended on the other phone leaving) — my link to it never closed, so no window ever opened. That is the #1637 precondition holding, AND the joiner had a live peer to return to for the whole episode. …
```

This phone is a **witness, and a fixture**. It is supposed to observe nothing whatsoever, and it reads
the same before and after the fix — so don't read its PASS as good news about the bug. It has two jobs:
prove the *setup* was right (it genuinely never noticed the outage), and **still be there** when the
other phone comes back. Its verdict now quotes how long it stayed up, so you can tell the two apart. If
it says SKIP ("I DID notice…"), the outage was too long and the whole run is out of band no matter what
the other phone said.

One result to watch for on the offline phone, because it looks like success and isn't:

```
[7] Sub-timeout blip       SKIP  104.2s  blip: the host ACKed a REAL resume (resume.ok host=… after 34.8s), which means it HAD a window open — so it noticed the outage and this was the ordinary resume lane, not the sub-timeout one…
```

The connection *did* recover — but the ordinary way, because the other phone noticed after all. That
says nothing about #1637, so it is honestly a SKIP rather than a PASS. Hold shorter and re-run.

**So a surviving room has three different endings and the report never blurs them**: the machinery
concluded the way the fix intends (PASS), it recovered the ordinary way because the other phone did
notice (SKIP), or nothing conclusive happened at all (SKIP — NOT EXERCISED). Only the first says
anything about #1637.

### Why the timings are what they are

Five numbers, and every one of them is doing something:

- **3 seconds** is how long the fabric gives a connection whose network vanished to come back before
  tearing it down. Below that, nothing happens and there is nothing to test. It is the one number here
  that scenario 7 deliberately sets away from shipping behaviour, which gives a connection **10
  seconds** — see [how the band was widened](#how-the-band-was-widened).
- **15 seconds to notice** is the shipping default, and the band's nominal top. It used to be set to 30
  to make the target wider; the first hardware run showed that doesn't work — see the next bullet.
- **~18.6 seconds** is the real ceiling, and nobody chose it: it is when the stay-up phone's own TCP
  connection to the vanished peer died of ETIMEDOUT on the one run we have measured, firing a
  `TransportClosed` no "notice" setting has any say over. One observation, not a constant. Everything
  above it is unreachable, which is why the notice setting was brought back under it.
- **60 seconds of window** is the budget the bug burns through. It is why a FAIL takes about a minute
  to arrive after the radio dies, and why you are asked to leave both phones alone at step 6.
- **~1 minute 28 seconds of watching** is the previous number plus the 3-second fuse plus 25 seconds of
  slack — i.e. the *entire* budget, measured from the moment the radio actually died, and it is how
  long the offline phone now waits before it will render any verdict at all. Anything shorter can
  declare victory during the stretch where the bug hasn't struck yet, which is precisely what happened
  on 2026-07-28. A `HostLost` cuts the wait short, so a real failure is never slowed down by it.

And one more that isn't a timing but is just as load-bearing: **the stay-up phone must outlive the
whole episode.** It stops advertising the moment its scenario ends, and a phone that comes back to find
nothing to dial can't resume no matter how the library behaves — so its dwell is sized to cover the
other phone's entire reconnect (up to five minutes, raised from four when the watching window above was
added) and it ends early only when the other phone leaves.
The first hardware run got this wrong: the stay-up side decided its verdict on the first `Partitioned`
and exited at t=25.4s, **4.1 seconds before** the other phone's radio came back. That phone then found
only itself in its browse, got `ECONNREFUSED`, and sent zero Resumes. Both reports looked plausible;
neither said anything about #1637.

## Reading the report

```
kuilt-nw connectivity suite
role=join  passed=4/5
env: path=satisfied ifaces=[wifi,lo] expensive=false constrained=false
device: Version 18.5 (Build 22F76)
----------------------------------
[1] Raw NW round-trip      PASS    412ms  RTT=28ms
[2] Fabric Seam weave      PASS     1.2s  peers=2 Woven
[3] Election establish     PASS     0.9s  adopted as member
[4] Teardown+reconnect     PASS     4.1s  peer-loss re-form seen; re-wove in 1.3s
[5] Soak 120.0s            FAIL   121.0s  n=90 p50=31ms p95=410ms stalls=7
----------------------------------
· [5] Soak 120.0s
    weave svc=_ksuite5._tcp; soaking 120.0s
    wove peers=2
    n=90/480 rtt min=22 p50=31 p95=410 max=980 stalls=7
```

The matrix is the headline; the per-scenario **hop traces** below it name the failing hop.

Scenarios 6 and 7 each report on their own (`role=join S6-ONLY (airplane-mode gate)` /
`role=join S7-ONLY (sub-timeout blip #1637)`, one row each), because neither runs inside the battery —
see [scenario 6](#scenario-6-the-airplane-mode-run-1712) and
[scenario 7](#scenario-7-the-blip-the-other-phone-never-notices-1637).

## Diagnostic mode — when a scenario fails and you need to know *why*

Two extra buttons run **scenario 4 alone**: **Host · S4 only** / **Join · S4 only**. Use them when the
pass/fail matrix isn't enough. They do two things a normal run doesn't:

1. **Isolate.** Scenario 4 runs in a pristine process, with no earlier scenario having left a Bonjour
   listener or browser alive. That separates "this scenario is broken" from "something earlier
   contaminated it" in a single run — a distinction otherwise very hard to make in the field.
2. **Turn on the fabric's own logging.** kuilt-nw already logs its whole dial/connection path
   (`nw.loom.weave`, `nw.api.connect.dial`, `nw.api.state`, `nw.api.state.error` with the decoded
   `nw_error` domain/code, `nw.api.close`). Diagnostic mode routes it to **stdout** at DEBUG so a
   cabled console run captures it.

   The default Darwin logger writes to os_log, which `devicectl --console` does **not** capture — which
   is why an ordinary run shows zero `nw.*` lines. Diagnostic mode switches to `DirectLoggerFactory`.

### The discovery watcher — read this line first on any weave failure

Every weave in scenarios 2 and 4 is wrapped so the **discovery boundary** stays visible even when the
weave times out and throws:

```
  leg1 disco t=826ms  n=1 ids=[kuilt-suite]
  leg1 disco t=3737ms n=2 ids=[kuilt-suite,kuilt-suite (2)]
leg1 discovery: events=3 finalSeen=2 ids=[…] elapsed=45011ms
leg1 weave THREW after 45011ms: NwUnreachableException: no peer reached …
```

That summary line splits the search space in half:

- **`events=0 finalSeen=0`** → the peers never saw each other. Browse/advertise never met — Bonjour,
  AWDL, Local Network permission, or a lingering listener.
- **`finalSeen ≥ 1` but the weave still timed out** → discovery is fine; the failure is **connection
  establishment** (TLS-PSK handshake / transport). Read the `nw.api.state.error` codes next.

Why it's needed: `weave()` waits for `seam.peers.size > 1` — a *fully connected* peer, not a discovered
one — so "no peer reached" covers both classes and cannot distinguish them on its own. The traces are
drained into the report on **both** the success and the throw path; before that existed, a timed-out
weave discarded every clue and the report showed two useless lines.

Seeing two endpoints (e.g. `kuilt-suite` and `kuilt-suite (2)`) is normal, not a fault: both phones
advertise the same Bonjour instance name, so mDNS conflict-renames one, and each phone discovers its own
record alongside the peer's.

### `nw_error` codes you will actually meet

`nw.api.state.error` prints the decoded domain and OSStatus:

| Code | Name | Means |
|------|------|-------|
| `-9864` | `errSSLUnknownPSKIdentity` | The acceptor didn't recognise the presented PSK identity. |
| `-9858` | `errSSLHandshakeFail` | Identity accepted; the handshake collapsed later. |
| `-9848` / `-9854` | `errSSLBadConfiguration` / `errSSLConfigurationFailed` | NW rejected the TLS options before any handshake. |
| `-9816` | `errSSLClosedNoNotify` | Peer vanished without a close_notify — often the *other* side of a failure. |

## Collecting the logs afterwards — one command

Every run now writes itself to a file **on the phone**, as it goes. You don't have to do anything to
make that happen, and you don't need a Mac anywhere near you at the time. The phone keeps it.

That matters most during scenario 6. When you switch a phone into Airplane Mode, a Mac plugged into it
stops being able to see anything — which is precisely the minute you most want to see. And if you
relaunch the app for any reason, whatever was on screen is gone. The file survives both.

Later, get both phones back to your Mac — a cable, or just the same Wi-Fi, whichever is easier — and
run:

```bash
./spike/collect-logs.sh
```

It finds every iPhone the Mac can see, copies each one's logs off, and writes **one merged file** with
both phones' lines interleaved in the order things actually happened, each line labelled with which
phone said it. It prints where it put it. Run it as many times as you like — it never changes or
deletes anything on the phones, and each run gets its own folder.

The merged file is the thing to read, and for scenario 6 it is *the* thing to read: that scenario
passes only if the two phones say **opposite** things about the same silence, so the evidence is the
pair, side by side. It looks like this:

```
2026-07-28T00:25:16.000Z  iPhone-iPhone11-2        [6] role=join side=DROPPED detect=5s window=1m
2026-07-28T00:25:17.250Z  Iains-Phone-iPhone18-1   [6] role=host side=ONLINE detect=5s window=1m
2026-07-28T00:25:18.500Z  iPhone-iPhone11-2        [6] short: mine→Unavailable(path unsatisfied)
2026-07-28T00:25:19.900Z  Iains-Phone-iPhone18-1   [6] short: Partitioned tagged Available
```

Two phones, one column of times, and you can see the asymmetry rather than having to reconstruct it.

If it can't find a phone it says so and stops — it never writes a half-empty timeline and calls it a
result. `./spike/collect-logs.sh -h` lists the few options (choose the output folder, a different app,
or one specific phone).

### The details, if you need them

- Files land in the app's `Documents`, one per run, named
  `suite-<UTC timestamp>-<hardware model>-<role>.log` — timestamp first so they sort into order, model
  in the middle so two phones can never overwrite each other. Scenario 1's raw `nw.log` sits alongside
  and is merged in too.
- The file holds every line the on-screen log shows, plus the final report, plus whatever `kuilt-nw`
  and `kuilt-session` log through `kotlin-logging` at the level the run is using — so a `nw_error` code
  is in there without a console attached. It's a superset of **Share report**.
- Capture can never fail a scenario. If the file can't be opened the run says so once, in the log, and
  carries on.
- Enabling capture also routes `kotlin-logging` through `DirectLoggerFactory`, exactly as the S4
  diagnostic mode already did — so fabric lines now reach stdout (and `devicectl --console`) instead of
  `os_log`. The *level* is untouched: nothing new is emitted, so the soak measures what it always did.
  Use the `S4 only` buttons when you want the full DEBUG trace; it now lands in the file too.
- This is step 1 of #1837. Steps 2 and 3 — the phones draining to a Mac-side `LogTapHost` over Bonjour
  by themselves — are deliberately not built yet; they hinge on an unsettled decision about the join
  token's lifetime. Until then, the cable is the drain, and the merged file is the same artifact.

## Mac-tethered runs (optional)

The field path needs no Mac. For bench work, the launch arguments auto-start the battery headlessly and
the app prints its report to stdout:

```bash
BID=us.tractat.spike.nw
xcrun devicectl list devices                       # get the device identifiers
xcrun devicectl device process launch --terminate-existing --console \
  --device <HOST_DEVICE_ID> $BID host              # host | join | -s4 | -s6 | -s7 suffixed variants
```

Launch both within a few seconds of each other so they overlap. `harness.sh <HOST_ID> <JOIN_ID> <APP>`
wraps install + launch + report extraction for the full battery.

`devicectl --console` rides Wi-Fi for network-attached devices, so a Wi-Fi-off scenario goes dark on
that device — keep at least one on USB if you want live console (see `PAINPOINTS.md`). This bites the
`-s6` and `-s7` variants hardest: the launch arg only *starts* them, a human still has to flip Airplane
Mode, and the phone doing the flipping goes dark on a network-attached console for the whole outage. Put
the offline phone on USB, or just read the report off the phone.

## What's real vs. control

- **Scenarios 2–7 drive the shipping API** — `appleNwLoom`, `SeamRoomFactory.electLobby`,
  `ElectionLobby.start`/`awaitRoom`, `SeamRoomFactory.adopt`, `Room.localFabric`, the adopt-path
  resume machine, `Seam`/`Room` lifecycle. A FAIL here is a real `kuilt-nw`/`kuilt-session` field
  failure — **except scenario 7, whose FAIL is #1637 itself**, the defect it exists to capture.
- **Scenario 1 is the raw transport control** (`spike.nw.SpikeNw`) — if it passes but scenario 2 fails,
  the fabric layer is at fault, not the radio.

## Building

Kotlin (the device-free gate — needs a Mac, but no iPhone):

```bash
./gradlew -PincludeSpike :spike:compileKotlinIosArm64
```

Build and install on devices, from a worktree of this repo:

```bash
./gradlew -PincludeSpike :spike:linkDebugFrameworkIosArm64   # produces SpikeKit.framework
cd spike/app && xcodegen generate                            # regenerate SpikeNw.xcodeproj
xcodebuild -project SpikeNw.xcodeproj -scheme SpikeNw -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath /tmp/spike-dd -allowProvisioningUpdates build
xcrun devicectl device install app --device <DEVICE_ID> \
  /tmp/spike-dd/Build/Products/Debug-iphoneos/SpikeNw.app
```

The SwiftUI target links the `SpikeKit` framework Gradle produces, via `FRAMEWORK_SEARCH_PATHS`. An IDE
"No such module 'SpikeKit'" warning before the first Gradle link is expected — `xcodebuild` resolves it.

**Every Bonjour service type the app browses or advertises must be declared in `NSBonjourServices`** in
`spike/app/project.yml` — xcodegen writes `Info.plist` from it, so edit the yml, not the plist. iOS
silently blocks discovery for undeclared types: no error, just nothing found. Verify the built bundle
with `plutil -extract NSBonjourServices xml1 -o - <path>/SpikeNw.app/Info.plist`.

## What it found

The first real two-phone run failed scenario 4 with a 45-second timeout on `_ksuite4a._tcp`, while
scenarios 2, 3 and 5 wove in under a second on their own service types.

The discovery watcher showed both phones finding each other in ~1s, so discovery was fine. The fabric
log then showed every inbound connection failing TLS with `-9864 errSSLUnknownPSKIdentity`. That traced
to `NwPsk.derive` handing Apple's external-PSK path a **raw 32-byte HMAC output** as the PSK *identity*.
That path is C-string-based, so an embedded `0x00` truncated the identity and no handshake could match.

A random 32-byte identity contains a NUL about **11.8%** of the time, so ~1 room in 8 could never
connect — and `weave()` reported only "no peer reached". Every `(roomKey, serviceType)` pair committed to
the repo happened to derive a NUL-free identity, so CI stayed green throughout. Fixed in #1577 by
hex-encoding the identity (RFC 4279 §5.1 requires the identity be a UTF-8 character string), with a
loopback regression test that reproduces the whole thing with no hardware.

Three things worth keeping from that hunt:

- **A suite whose fixtures are all lucky proves very little.** The regression test pins a *property*
  across many derived inputs rather than one golden vector.
- **Instrument the boundary before hypothesising.** The first two explanations were both wrong (the
  wrong leg, then cross-talk from a leaked listener); the discovery line and the isolation mode
  falsified each in one run.
- **Fixing one bug can expose the next.** With leg 1 finally establishing, scenario 4's stale `Torn`
  expectation surfaced immediately — it had never been reachable before, so the joiner branch had never
  once run. Assertion corrected in #1838 (tracked by #1836, still open) —
  [the section above](#scenario-4-the-two-sides-expect-different-things) is what it now asserts, and
  per [Status](#status) that assertion has **not yet been re-run on hardware**.
- **A doc can be fixed while the code it describes is not.** The guide was corrected to the #1513
  contract in a *docs-only* commit that never touched `ConnectivitySuite.kt`, so for eight days the
  section above described the right behaviour while the code twenty lines away still waited for `Torn`.
  Reading the prose was actively misleading. When a doc change is the *fix* for a wrong expectation,
  check whether the expectation lives in code too.

## Status

**Scenarios 1, 2, 3 and 5: validated on-device** on two phones (iPhone XS / iOS 18.7.9 and iPhone 17 Pro
/ iOS 26.5.1) on infrastructure Wi-Fi, including the 2-minute soak with zero stalls.

**Scenario 4: the joiner side has never passed on hardware.** A "5/5 on both phones" claim stood here
from 2026-07-19 and does not survive #1836: that run's `Join · S4 only` shows `[4] Teardown+reconnect
FAIL 21.1s torn=false`, and with the pre-#1836 assertion it could not have shown anything else. The
host side and both legs' weaves are validated (leg 1 in 746 ms, leg 2 in 435 ms with both peers) — but
note the host branch previously PASSed by *fiat*: it set its own drop signal unconditionally, so no run
to date has actually asserted that `close()` latched `Torn`. It now reads the state, so that half is
newly-asserted too. What is owed is one paired `Host · S4 only` / `Join · S4 only` run confirming the
joiner reports `peer-loss re-form seen` and the host's latch read comes back true.

The adverse-network matrix the suite was built for — captive-portal Wi-Fi, airplane mode,
Wi-Fi-off/cellular-on, two SSIDs — is still worth walking through; that is what it is for.

**Scenario 6: PASSED on hardware on 2026-07-27**, on both phones, and again the same evening. The
switched-off phone reported its own outage (`localFabric` → `Unavailable`, `LocalFabricLost`, its
`Partitioned`/`HostLost` tagged `Unavailable`) and the phone left alone reported the peer's — the
asymmetry #1712 exists for, read two correct-but-opposite ways. One detail from those runs is worth
keeping, because it is what motivated scenario 7: with `detect=5s window=1m`, a 23.7 s real outage
produced `Partitioned(LinkTimeout)` and `WindowOpened` at 9.3 s and a clean `Recovered` at 30.4 s.
The other phone always notices first at that setting, so the resume machine's failing lane is
unreachable from scenario 6 — see [scenario 7](#scenario-7-the-blip-the-other-phone-never-notices-1637).

**Scenario 7: run once on hardware (2026-07-28, 06:50 ET) — inconclusive about #1637, and the run
exposed three defects in the scenario itself.** iPhone XS as the dropped joiner, iPhone 17 Pro as the
stay-up host, `detect=30s window=1m grace=10s repro=(10s,30s)`. What happened:

1. **The stay-up phone exited before the other one's episode could finish.** It decided its verdict on
   its first `Partitioned` and finished at t=25.4 s, which stopped its advertisement — 4.1 s *before*
   the joiner's radio returned. The joiner's browse then found only itself (`nw.loom.self-skip`), its
   dial was refused (`posix 61`), and it sent **zero** Resumes. Every run was invalidated this way; the
   resume machine under test was never entered.
2. **The FAIL prose asserted #1637 without checking for it.** The joiner reported `HostLost` with
   `reason=WindowExpired` and printed "This is #1637: the host never partitioned me (its link never
   closed), so every Resume was answered WindowNotYetOpen" — every clause of which was false for that
   run. #1637's signature is `Refused` carrying `RejectCode.ResumeWindowNotYetOpen`, and nothing else.
3. **The band's upper half was physically unreachable.** Configured `detect=30s`, the host still
   noticed at t≈18.6 s: its TCP connection to the vanished peer died of ETIMEDOUT (`posix 60`) and
   fired `TransportClosed`. `HeartbeatConfig.timeout` never governed the ceiling.

All three are fixed: the stay-up side now dwells for the joiner's whole episode and reports how long it
stayed up; the FAIL is gated on the reject code and any other `HostLost` reports as its own SKIP; and
the band's top is `15s` — under the observed transport death — with the hold derived from the band and
the restore lag stated.

A fourth problem was left standing by that pass and has since been fixed too: the band was still only
five seconds wide, aimed through ~9 s of radio lag, so most attempts would have SKIPped before saying
anything. `appleNwLoom` now exposes the fabric's path grace, and scenario 7 — and only scenario 7 —
drops its own floor to 3 s, making the band `(3s, 15s)` and the hold a single flick. See
[how the band was widened](#how-the-band-was-widened).

**Scenario 7: run again on hardware (2026-07-28) against a build containing the #1637 fix — reported
PASS, and the fix had not run.** `PASS 14.8s survived a 7.1s blip inside (3s, 15s): Recovered(441485b2)
8.5s after the radio died, no HostLost, host Connected`. The trace says otherwise: the resume machine
emitted **no log line of any kind** for the whole run — not `resume.ok`, not `resume.no-op`, not
`resume.terminal` — and no `WindowNotYetOpen` appeared anywhere. The link healed on its own at 25.9 s,
`Recovered` landed at 27.3 s, and the scenario declared victory and tore the room down while the
machinery it exists to test had concluded nothing. (Not a logging gap: the run file's tee forwards every
event unfiltered and five other kuilt loggers were captured.) Two more defects in the scenario, both now
fixed:

5. **It stopped watching at the first encouraging event.** #1637 does not kill the room promptly — it
   kills it when the reconnect window expires, ≈63 s after the radio dies. Returning on the first
   `Recovered` produced a verdict at ~15 s with 48 s of the window the bug lives in never observed. The
   wait is now a **deadline** — the whole budget plus slack, ≈1 m 28 s from the measured radio death —
   and only a `HostLost`, which is terminal, ends it early.
6. **It inferred health instead of observing the mechanism.** "No `HostLost` + host `Connected`" is
   satisfied by a room that merely healed on its own. Worse, the two obvious membership signals cannot
   discriminate at all: `Recovered(hostId)` is emitted *identically* by the fix's no-op path
   (`onNoOpResume` → `markRecovered`) and by an ordinary detector-observed recovery (`PeerRecovered` →
   the same `markRecovered`), and `Partitioned`/`WindowOpened` are emitted by `markPartitioned` on a
   plain heartbeat `Timeout` with the resume machine never entered. `Room` exposes nothing else. The
   verdict is therefore keyed to the machine's own `resume.*` evidence lines, watched through a
   structural `(loggerName, message)` observer on the run-file tee, and it now separates **entered**
   (`resume.refused` once #1969 lands; `membership.unresponsive … branch=resume` until then) from
   **resolved** (`resume.no-op` / `resume.ok`). A surviving room reports three distinct endings —
   `resume.no-op` (PASS, the fix observed), `resume.ok` (SKIP, the ordinary resume lane), or neither
   (SKIP, **NOT EXERCISED**, sub-split into never-entered vs entered-and-stalled).

**The scenario has therefore still never produced a verdict about #1637** — one run was inconclusive and
one was a false green. When it next runs, check four things before believing either report: the offline
phone's measured outage is inside 3–15 s, the online phone says it never partitioned *and* names how
long it stayed up, a FAIL quotes `code=resume-window-not-yet-open`, and a **PASS quotes a
`resume.no-op` line**. Without all four the run says nothing. Neither a `Recovered` nor the absence of a
`HostLost` counts as validation — that is exactly the ambiguity the false green rode in on.
