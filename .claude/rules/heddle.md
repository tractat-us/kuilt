---
paths:
  - "kuilt-heddle/**"
  - "kuilt-warp-heddle/**"
---
# Heddle

When several groups share one limited resource — time on a device, a turn in a
queue — somebody has to decide who gets how much, without a referee standing
over them and without freezing up the moment the network splits into pieces.
These modules are that fair-share scheduler: a record of who was promised what,
who lent their unused share to someone busier, and who actually spent it — plus
the same idea applied to spreading work across a group of devices.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/heddle.md`. This file holds only the
rules for changing the code here.

## Rules

The cross-cutting rules in the root `CLAUDE.md` apply. Three more are this family's own; each
states a property the code relies on that a test cannot see until someone writes the arm for it.

- **The control log's bytes are a wire format, and a reader halts on what it cannot decode.**
  A governed peer stops for good at an entry it cannot read (#1738), so renaming a `ControlCommand`
  subclass, reordering a field or deleting one strands every durable log written before it. The
  checked-in vectors in `HeddleControlLogCodecTest` red on any such change; regenerate them only for
  a deliberate format change, and bump `ControlLogCodec.CURRENT_VERSION` when you do, never the
  magic. A decoding default is not a repair: it turns an unreadable act into a readable, wrong one.
- **Every unordered field of `EntitlementLedger` goes through a canonical serializer.** Peers that
  merged in different orders must encode the same bytes, or their anti-entropy roots never match
  and every round ships the whole ledger (#3062). A new field needs `CanonicalMapSerializer` /
  `CanonicalSetSerializer` at every level *and* its own arm in
  `EntitlementLedgerCanonicalEncodingTest`: an unannotated field leaves that suite green.
- **The write gate moves in the apply loop, never in a caller's continuation.** It moves when the
  apply loop applies this run's own `Enroll(self)` or `Depart(self)`, recognised by the in-memory
  set of keys this instance issued, not by the key's text (#3047). A caller that timed out or was
  cancelled never resumes, so a gate it moved would stay wrong. See `HeddleControlPlane`'s
  *The write gate moves here* section before touching either.
