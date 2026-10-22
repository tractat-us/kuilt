# F — Exception-catching population in kuilt production source

Scope: every `*Main` source set under `kuilt-*/src` (602 files). Read-only audit, no builds.
Method scripted (`F_classify2.py`), spot-corrected by reading all 173 catch arms and all 195
`runCatchingCancellable` call sites.

## 0. Two corrections to the brief, before the numbers

**(a) The brief's per-module counts are raw token counts, not call sites.** They include KDoc prose,
imports, and the declaration itself. Call-site counts are ~40 % lower:

| module | brief `rCC` | actual call sites | brief `catch` | actual arms |
|---|---|---|---|---|
| kuilt-core | 53 | **23** | 33 | **25** |
| kuilt-nw | 48 | **29** | 14 | **12** |
| kuilt-otel | 43 | **28** | — | 0 |
| kuilt-cluster | 38 | **23** | 12 | **8** |
| kuilt-session | 30 | **19** | 15 | **14** |
| kuilt-multipeer | 24 | **17** | — | 2 |
| kuilt-quilter | 18 | **14** | — | 0 |
| kuilt-bolt | — | 0 | 18 | **14** |

Tree totals: **173 catch arms + 195 `runCatchingCancellable` calls + 16 `NonCancellable`
shields + 19 `ensureActive()` = 368 sites**, over 186 `try` blocks. The population is real but it is
368, not the ~500 the token counts suggest.

**(b) `WireRejectionMode` is not a `decode(): T?` contract.** It is a *declaration field* on
`WireCodecConformanceSuite` (`kuilt-conformance/.../WireCodecConformanceSuite.kt:96`) recording which
of two shapes a codec already uses, and both shapes are live in-tree. It pins fixed-**width** fields;
it says nothing about who catches what. Five subclasses exist tree-wide. So the answer to the brief's
parenthetical is: **no, and the two shapes coexisting is itself the finding.**

---

## 1. Sites by intent × module

`chain` = a second/third arm of one `try` (type translation). `CE-plumb` = an arm whose entire body
is `throw e` on `CancellationException`/`TimeoutCancellationException`.

| MODULE | i send | ii close | iv decode | v platform | vi pump | vii invariant | viii other | chain | CE-plumb | TOT |
|---|---|---|---|---|---|---|---|---|---|---|
| kuilt-bolt | | | 3 | 5 | | | 3 | 2 | 1 | 14 |
| kuilt-cluster | 8 | 6 | 5 | | 2 | | 8 | 1 | 1 | 31 |
| kuilt-conformance | | 2 | 1 | | | | 2 | 2 | 3 | 10 |
| kuilt-core | 14 | 12 | 1 | | 1 | | 9 | 6 | 4 | 47 |
| kuilt-deal | | | | | | | | 2 | 2 | 4 |
| kuilt-game | | | | | | | 2 | | | 2 |
| kuilt-gossip | 2 | | | | | | | | | 2 |
| kuilt-heddle | 1 | | 2 | | | | 5 | 1 | | 9 |
| kuilt-liveness | 2 | | | | | | | | | 2 |
| kuilt-mdns | | 2 | 1 | 5 | | | 1 | | | 9 |
| kuilt-multipeer | 2 | 8 | | 1 | | | 6 | | 2 | 19 |
| kuilt-nearby | | | 1 | 2 | | | 1 | | | 4 |
| kuilt-nw | 6 | 17 | | 4 | | | 7 | 2 | 5 | 41 |
| kuilt-otel | | | | 14 | | | 14 | | | 28 |
| kuilt-otel-{log4j2,logback,logging,otlp,sdk} | | | | 3 | | | 6 | | | 9 |
| kuilt-otel-tap(+test) | 4 | | | | | | 1 | 2 | 3 | 10 |
| kuilt-quilter | 11 | | 2 | | | | 1 | | | 14 |
| kuilt-raft(+test) | 5 | | | | 1 | | 4 | 3 | 3 | 16 |
| kuilt-session | 18 | 2 | | | | | 8 | 3 | 2 | 33 |
| kuilt-store | | | 1 | 1 | | | | 1 | | 3 |
| kuilt-stream | | | 1 | | | | | | | 1 |
| kuilt-test | | | | | | | 2 | 3 | 5 | 10 |
| kuilt-warp(+compiler,runtime,test) | 3 | | 3 | 4 | | 3 | 10 | 16 | 4 | 43 |
| kuilt-webrtc | | | 1 | | | | | | | 1 |
| kuilt-websocket | | 1 | | 1 | | | | 2 | 2 | 6 |
| **TOTAL** | **76** | **50** | **22** | **41** | **4** | **3** | **89** | **46** | **37** | **368** |

Two readings the table forces:

- **83 of 368 (23 %) are pure cancellation bookkeeping** — `chain` + `CE-plumb`. They carry no
  domain logic; they exist to stop the *next* arm swallowing a cancel.
- **126 of 368 (34 %) — buckets (i)+(ii) — are calls on kuilt's own contract surfaces**
  (`Seam.sendTo`/`broadcast`/`close`, `Connection.send`/`close`, `Loom.weave`, `Room.leave`). That set
  is exactly the seven the `Seam.close` KDoc enumerates, and it is the entire subject matter of the
  discipline. Buckets (iv)–(viii), 155 sites, catch platform or domain exceptions where cancellation
  is not in play at all.

---

## 2. Prescribed idiom vs. what is written

| intent | CLAUDE.md's one idiom | uses it | different-but-equivalent | **wrong by CLAUDE.md's own rules** |
|---|---|---|---|---|
| (i) send/broadcast | `runCatchingCancellable{…}.onFailure{log}` | 66 rCC | 10 catch-based (`SeamRaftTransport`, `SeamRoom.sendTo`) | 0 |
| (ii) single close | rCC (contract forbids minting) **or** plain catch under `NonCancellable` | 28 rCC + 16 shields | 6 with `try/catch + ensureActive` | **2** (see below) |
| (iii) multi-item cleanup loop | `try/catch(Throwable){ensureActive()}` unshielded, or plain catch inside a shield | 9 (`MeshSeam:1389`, `VoterMesh:121`, `VoterMeshAssembly:281/312`, `CompositeLoom:120`, …) | — | **2** |
| (iv) decode untrusted bytes | decoder returns `T?`; call site has no guard | 4 decoders (`AdmitMessage`, `LobbyMessage`, `RelayEnvelope`, `TapAdmitMessage`) | 18 call-site guards | 0 |
| (v) platform API | no prescription | — | 41 | 0 |
| (vi) pump body | `Flow.pumpIn` | 12 | 4 hand-rolled | 23 bare `.launchIn` (the #1803 ratchet) |
| (vii) invariant | `// ALLOW-ise:` marker | 1 | 2 | 0 |

### The wrong sites, cited

**1. `kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt:2244-2246`** — multi-item cleanup
loop using `runCatchingCancellable`, **unshielded, with work following**:

```kotlin
for (connId in targets) { runCatchingCancellable { api.disconnect(connId) } }
… stageMutex.withMutex { orderingHolds.clear() }
runCatchingCancellable { api.stopListening() }   // #1419: the AWDL/NWListener leak this prevents
runCatchingCancellable { api.stopBrowsing() }
```

CLAUDE.md's elision (1) permits rCC only where "the contract forbids [the callee] to mint at all",
and names the covered set: `Loom.weave`, `broadcast`, `sendTo`, `Seam.close`,
`Connection.send`/`close`, `Room.leave`. **`NwApi` is not in it.** `NwApi` is a `public interface`
(`NwApi.kt:72`) with two in-tree implementations and no no-mint obligation in its KDoc; a
`withTimeout` in any `disconnect` aborts the loop at the first stuck connection and skips
`stopListening`/`stopBrowsing` — precisely the leak the comment three lines below says this code
exists to prevent.

**2. `kuilt-nw/src/macosMain/kotlin/us/tractat/kuilt/nw/bridge/NwBridgeRuntime.kt:263-265`** — same
shape (`for (id in targets) { runCatchingCancellable { api.disconnect(…) } }`), with `scope.cancel()`
following outside the `runBlocking`.

**3. `kuilt-nw/src/macosMain/kotlin/us/tractat/kuilt/nw/bridge/NwBridgeExports.kt:76`** —
`runCatchingCancellable { ref.get().destroy() }` then `ref.dispose()`. A rethrown
`CancellationException` skips `dispose()` *and* escapes a `@CName` cdecl entry point on
Kotlin/Native, where an unhandled exception aborts the process (#1788's mechanism).

**4. `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt:3819`** — the
hand-written `if (e is CancellationException) throw e` inside `catch (e: Throwable)`, unshielded,
with `runCatchingCancellable { send(from, ForwardResponse(…)) }` following. Shape-wrong (should be
`currentCoroutineContext().ensureActive()`), **outcome currently right**: I traced every
`failPending`/`completeExceptionally` path on this deferred (`RaftEngine.kt:938, 1373, 1948, 3869`) —
all complete with `LeadershipLostException`/`NotLeaderException`, never a cancellation. So it is
un-pinned, not broken. It is also the single best illustration of the guard problem: **it is
invisible to all three lexical guards simultaneously** (no shield → guard 1 blind; no `withTimeout` →
guard 2 blind; `Throwable` not `IllegalStateException` → guard 3 blind).

**Population note, not a site list:** ~22 rCC calls sit on surfaces with *no* no-mint obligation
(`NwApi.disconnect/stopListening/stopBrowsing` ×14, `NsdManager.unregisterService`,
`handle.stop()` ×4, `lib.mc_runtime_close`, `BridgeRuntime.destroy` ×3). The four above are the ones
where work follows. The other ~18 are last-statement or inside `awaitClose`, so harmless today —
which is a property of their position, not of the contract.

---

## 3. Structural collapse

| intent | proposed primitive | sites removed | residual |
|---|---|---|---|
| (i) 76 | `Seam.trySendTo(peer,bytes): SendOutcome` / `tryBroadcast` (non-throwing) | **76** | 0 catches; 5 keep a `when` on the outcome |
| (ii)+(iii) 50+9 | `closeQuietly()` / `closeAll(items)` — the loop discipline written once | **55** | ~4 that must report the failure |
| (iv) 22 (34 on a wider count) | `decode(bytes): T?` as the *uniform* wire convention | **18 call-site guards** | 12 (one inside each decoder) |
| (vi) 4 + 23 `launchIn` | `pumpIn` (already exists, 12 adopted) | **27** | 0 |
| (v) 41 | none — platform APIs throw; that is their contract | 0 | 41 |
| (vii)+(viii) 92 | domain control flow; ~14 otel sites would fall to `DurableStore.readOrNull` | ~14 | ~78 |
| chain+CE-plumb 83 | falls out with the sites they attach to | ~45 | ~38 |

**Residual estimate: ~175 of 368 (a 52 % reduction)** — and the composition matters more than the
count. After the collapse, **zero** hand-written catches remain in buckets (i), (ii), (iii) and (vi),
which is where all five documented shapes and all four issues (#1803, #1824, #1834, #2292) live. What
survives is bucket (v)+(viii): platform I/O and domain control flow, where no cancellation ambiguity
exists because the callee is not a consumer-authored suspend surface.

Evidence that the collapse works: **`:kuilt-bolt` already did it one layer down.** `Bolt.append`
returns `AppendResult.Failed(reason, …)` and `replay` returns a `Truncated` verdict rather than
throwing, so its 14 arms are all bucket (v) — real `IOException`/`PosixFailure` — and it has **zero**
cancellation-discipline sites despite being one of the largest catch populations in the tree.

**What the send guards actually do** (the number that decides the recommendation): of the 66 rCC send
sites, **28 discard the `Result` entirely**, 33 do nothing but `.onFailure { log.debug { … } }`, and
**5** branch on it (`MeshSeam:1038/1050/1161`, `LinkSeam:163`, `CompositeSeam:1268` → `removePeer` /
`tearDown` / `endDrain`). 61 of 66 exist solely to convert a throw into silence or a debug line.

---

## 4. Is the Seam contract the core problem?

**The contract does not declare them to throw cancellation — it forbids it,** in ~70 lines of KDoc
(`Seam.kt:271-291` for `sendTo`, `318-350` for `close`), asserted by
`SeamConformanceSuite.closeDoesNotReportFailureAsCancellation` and `assertNoMintedCancellation`
(`SeamConformanceSuite.kt:1184`, `RoomConformanceSuite.kt:1219`).

So why do callers guard? **Because `sendTo` is declared to throw three *ordinary* exceptions** —
`IllegalArgumentException` (self-send), `PeerNotConnected`, `PayloadTooLarge` (`Seam.kt:293-311`) —
and `close`/`weave` throw ordinary exceptions on failure. The guard exists for the ordinary throw.
The cancellation discipline is **downstream of that choice**: the idiom reached for to handle the
ordinary throw (`runCatchingCancellable`) is a `Result`-returning wrapper, and `Result` is
type-based, and type cannot separate the two cancellations. Hence 83 sites of pure cancellation
bookkeeping and 132 lines of CLAUDE.md (lines 460-591, **14 % of the file**).

Three observations that sharpen this:

1. **`broadcast` already made the other choice and it worked.** Its KDoc: "A payload over
   `maxPayloadBytes` is **dropped**, not reported — this call is best-effort, and its most common
   caller is a timer-driven replication loop that a throw would kill." That is the argument for
   `trySendTo`, written in the codebase, applied to one method only.
2. **The obligation is unenforceable where it matters most.** It binds *consumer-implemented*
   surfaces; the TCK holds only fabrics that subclass it. And the enumerated seven do not include
   `NwApi`, which is `public` and carries 22 rCC calls on it (§2).
3. **The contract's own escape hatch concedes the point:** "A caller that cannot afford to trust this
   should guard with `try`/`catch` plus `ensureActive()` rather than `runCatchingCancellable`"
   (`Seam.kt:288-291`). A contract that tells callers to defend anyway is a contract paying the cost
   of both designs.

**Verdict: yes.** Not because the contract permits cancellation, but because it chose `throws` as the
failure channel for operations whose callers overwhelmingly (61/66) do not want a failure channel at
all.

---

## 5. Actual guard coverage

| guard | scope | population it can see | of 368 | blind to |
|---|---|---|---|---|
| `forbidRunCatchingCancellableUnderNonCancellable` | prod `*Main` | 16 `NonCancellable` regions; **0 offenders today** (verified with the guard's own brace walk) | **4.3 %** | the hand-written `if (e is CancellationException) throw e` inside a shield; anything through one helper hop |
| `forbidCancellationRethrowAroundBound` | prod `*Main` | 10 arms with a `withTimeout(` in the guarded body | **2.7 %** | a `withTimeout` one helper hop away (its own header cites `:spike`'s two-hop miss); `catch(Throwable)` + hand-written `is CancellationException` rethrow (header: "the token is greppable; this paragraph is the guard on it") |
| `forbidCancellationSwallowingCatch` | whole tree | `catch (…: IllegalStateException)` arms = **1** (`WarpNode.kt:1394`, already `// ALLOW-ise`) | **0.3 %** | see below |

**Union: 27 of 368 sites, ~7 %. 93 % of the population is unseen by every guard.**

Guard 3's blind spot deserves naming because it is the *same* defect one supertype up. Its receipt is
`CancellationException` ⊂ `IllegalStateException` — but the chain continues:
`IllegalStateException` ⊂ `RuntimeException` ⊂ `Exception`. There are **8 `catch (…: Exception)` arms
in production `*Main`**, each swallowing cancellation exactly as an ISE arm does, and the guard does
not look at them. Five are cleared by an earlier CE arm in the same chain; three
(`NwSeam.kt:793`, `ChicoryWasmRuntime.kt:310`, `GuestWorker.kt:148`) rest on a chain arm >6 lines
back or on "nothing here suspends" — which is precisely the claim the `// ALLOW-ise:` marker exists
to record, and none of them records it. Extending the scanner's type set from `{IllegalStateException}`
to `{IllegalStateException, RuntimeException, Exception}` and reusing the existing clearing rules
is a one-line change to `CancellationSwallowingCatchScanner.sites` (`build.gradle.kts:3888`) that
takes guard 3's population from 1 to 9.

(Conversely: `catch (…: PeerNotConnected)` ×2 and `catch (…: ClosedSendChannelException)` ×7 are
*sibling* subclasses of ISE, so they do **not** catch cancellation. They are safe, and a scanner
keyed on subtype rather than supertype would false-accuse all nine.)

---

## 6. One prioritised recommendation

> **Add `Seam.trySendTo(peer, payload): SendOutcome` and `Seam.tryBroadcast(payload): SendOutcome`
> as non-throwing defaults on the interface, delegating to `sendTo`/`broadcast`.**

Smallest possible API change — two default methods and one sealed result type in `:kuilt-core`,
no fabric changes, no migration required to compile.

What it removes:

- **76 catch/rCC sites** (21 % of the whole population) — the single largest bucket, and the only one
  spread across every module (11 modules touch it).
- The `chain`/`CE-plumb` arms attached to them.
- **~35 lines of `Seam.kt` KDoc** (`Seam.kt:271-291` becomes "this is the throwing form; prefer
  `trySendTo`"), and the largest single justification for CLAUDE.md's §"Exception discipline",
  because elision (1) — "`runCatchingCancellable` where a wrong rethrow costs nothing… this stays
  the default for ordinary best-effort sends" — is *exactly* bucket (i) and stops needing to exist.
- The obligation `SeamConformanceSuite.sendDoesNotReportFailureAsCancellation` polices, for every
  caller that migrates: a non-throwing method cannot mint anything.

Why this one rather than `closeAll` (55 sites, second-largest): the send bucket is where the
*majority of callers do not want the failure at all* (61/66 log-or-discard), so a typed outcome is
strictly less code at the call site, not a lateral move. `closeQuietly`/`closeAll` is the natural
follow-up and would fix all four cited defects in §2 by making the wrong shape unwritable — but it
must land *after* the loop discipline is settled, or it just relocates the same argument into one
helper.

**What it does not fix, stated so it is not assumed:** buckets (v) and (viii), 130 sites, are
genuine platform and domain exception handling and are unaffected. The three lexical guards would
still see ~7 % of what remains — the coverage gap in §5 is independent of this change, and the
cheapest separate win there is the three-line type-set extension to guard 3.
