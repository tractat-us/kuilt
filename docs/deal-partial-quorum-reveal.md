# Cards visible to some players: partial-quorum reveal in kuilt-deal

Some games deal a card that exactly two (or three, …) of the players may look
at — a shared hand, a team secret — while everyone else must stay in the dark.
kuilt-deal supports this with the same no-trusted-dealer card deal it already
uses for private and community cards: the players who may look cooperate to
unlock the card *for each other*, and nobody else ever gets enough of the
puzzle to read it.

This note records how that works, the alternatives considered, and the
security argument. The set of players allowed to see a card is its
**visibility quorum**; this note is about *partial* quorums —
`1 < |quorum| < N`.

## Background: how a deal already works

Every player wraps every card in their own encryption layer (the SRA
commutative scheme — layers can be removed in any order). A card dealt to one
reader is revealed by everyone *else* removing ("stripping") their layer
publicly: the public ciphertext ends up carrying only the reader's layer,
`m^(e_reader)`, which only the reader can remove — and they do so locally, in
`decrypt`. A community card is revealed by *everyone* stripping.

A partial quorum breaks this: after all non-members strip, the card still
carries **every member's** layer. No member can finish alone, and any member
stripping the shared ciphertext further would walk it toward plaintext *in
public* — leaking it to non-members if the remaining members stripped too.

## The chosen mechanism: per-member reveal tracks

Once every non-member has stripped (public ciphertext = `m^(∏ members' e)`),
each quorum member `r` gets a **reveal track** — a private-copy chain that the
*other* members strip, one at a time, in canonical order (ascending
`PlayerId`):

```
track r:  m^(e_r · e_x · e_y)  --x strips-->  m^(e_r · e_y)  --y strips-->  m^(e_r)
```

Every track op (`CardOp.QuorumStrip`) is **broadcast**, like every other deal
op. The terminal value of `r`'s track, `m^(e_r)`, is exactly as protected as a
single-reader card's revealed state — a state the protocol already makes
public today. `r` removes the final layer locally in `decrypt`; **a member's
own layer never comes off publicly**. The card reports `REVEALED` only when
the main chain *and* every member's track are complete.

Track state (`QuorumTrack`) lives in `CardState` and converges like the rest
of the deal: each op carries the track base it was computed against, receivers
reconstruct the sender's resulting track and fold it in with a commutative,
idempotent join (strip-count-ordered ciphertext, GSet of strippers) — so
cross-sender reorder converges, mirroring `Encrypt`/`Strip`.

Cost: `|Q| · (|Q|−1)` strips per partial-quorum card, sequential per track
(`|Q|−1` rounds of `strip()` per member). Partial quorums in games are small
(2–3 members), so this stays negligible next to the N-layer deal itself.

## The fork: alternatives considered

- **Unicast/private strip shares** — members strip and send intermediates
  member-to-member over `Seam.sendTo`. Rejected: kuilt has no
  end-to-end-confidential unicast, so on a relay topology (`KtorRoomHost`) the
  relay reads every "private" frame — the privacy advantage evaporates against
  exactly the untrusted-infrastructure threat model this module exists for.
  It also forks the state model (per-member private side state that cannot be
  recovered from the broadcast transcript after a crash or late join).
- **Per-card keys + key reveal** (classic mental poker) — each member unicasts
  a per-card strip key to co-members; one round, minimal exposure. Rejected
  for machinery: keys today are per-player-per-deal, so revealing one reveals
  every card that player touched; per-card keys mean `N × deck` key
  management and a scheme-surface refactor. Worth revisiting if large quorums
  ever matter (it is `O(|Q|)` messages instead of `O(|Q|²)`).

The chosen design is the smallest delta that keeps the op-based CRDT model,
the transcript-recoverable state, and the byte-parity discipline (no new
modPow path — `QuorumStrip` uses `scheme.strip`, canonicalized identically).

## Security argument

- **Non-members learn nothing new in kind.** Every value made public is
  `m^S` for some non-empty subset `S` of *member* keys. The base protocol
  already publishes exactly this shape: every strip of any card publishes
  `m^S` for a shrinking `S`, down to a singleton (single-reader cards) or to
  `m` itself (community cards). Recovering `m` from `{m^S}` without holding
  any member's strip key is the commutative-encryption (SRA) hardness
  assumption the whole deal already rests on. The known 1-bit
  quadratic-residuosity leak of SRA is unchanged (odd exponents preserve the
  Legendre symbol; the modulus was already a safe prime).
- **All members can decrypt.** Track `r` terminates at `m^(e_r)`; `r` holds
  `d_r`. Completion needs every other member's cooperation — i.e. exactly the
  quorum, jointly.
- **No proper subset of members can leak the card *cryptographically*.** No
  public op ever removes the last member layer from any published value
  (`canApply` rejects a member stripping their own track, and non-members'
  ops never touch tracks). A colluding subset can of course photograph the
  card and tell — no scheme prevents members leaking plaintext out-of-band.
- **A malicious member can deny or corrupt, not expose.** Refusing to strip a
  track blocks reveal (liveness — identical to refusing a main-chain strip
  today); stripping with garbage corrupts a track detectably-in-principle
  (`verifyStrip` is still the stubbed ZK hook, same status as the base
  protocol). Neither reveals anything to non-members.
- **Single-key compromise caveat** (the honest seam): once tracks are public,
  a later leak of *one* member's key `d_r` exposes the card via `m^(e_r)` —
  versus needing all `|Q|` keys if intermediates had stayed private. This is
  not a new property: every single-reader card already has it, and post-game
  key disclosure (the standard mental-poker audit) reveals all cards anyway.
  If this margin ever matters, the per-card-key design above is the upgrade
  path.

## Protocol sequencing

`assignQuorums` must run on **every** peer before **any** peer strips: quorum
membership gates which strip ops a receiver accepts, and a `QuorumStrip`
arriving before the local assignment is dropped, not retried. (The base
protocol already required this for `phase()` to be meaningful.) Members call
`strip()` again as ops arrive — each pass performs every strip that is
currently theirs to make — until the card is `REVEALED`.
